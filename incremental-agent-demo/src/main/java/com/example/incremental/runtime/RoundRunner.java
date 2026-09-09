package com.example.incremental.runtime;

import com.example.incremental.agent.AgentExecutor;
import com.example.incremental.writing.WritingWorkflow;
import io.agentscope.core.agui.event.AguiEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

@Service
public class RoundRunner {

    private static final Logger log = LoggerFactory.getLogger(RoundRunner.class);
    private static final String WRITING_AGENT_ID = "incremental-writing-agent";

    private final WritingWorkflow writingWorkflow;
    private final AgentRunRuntime agentRunRuntime;
    private final AgentExecutor agentExecutor;
    private final TaskRuntime taskRuntime;

    public RoundRunner(
            WritingWorkflow writingWorkflow,
            AgentRunRuntime agentRunRuntime,
            AgentExecutor agentExecutor,
            TaskRuntime taskRuntime) {
        this.writingWorkflow = writingWorkflow;
        this.agentRunRuntime = agentRunRuntime;
        this.agentExecutor = agentExecutor;
        this.taskRuntime = taskRuntime;
    }

    public Flux<AguiEvent> run(String taskId) {
        return run(taskId, "");
    }

    public Flux<AguiEvent> run(String taskId, String userMessage) {
        return Flux.defer(() -> {
            acquire(taskId);
            try {
                taskRuntime.requireCanCreateRun(taskId);
                WritingWorkflow.PreparedRun prepared = writingWorkflow.prepareRun(taskId, userMessage);
                AgentRunContext run = agentRunRuntime.createRun(taskId, prepared.chapterSessionId());
                AgentRunEventRecorder.RunHistory history = agentRunRuntime.beginHistory(run, prepared.task().userId(),
                        WRITING_AGENT_ID, "写作任务 " + taskId, userMessage);
                try {
                    taskRuntime.attachRun(taskId, run.runId());
                } catch (RuntimeException error) {
                    agentRunRuntime.cancel(run.runId());
                    throw error;
                }
                registerPauseHandler(run, prepared.task());
                Flux<AguiEvent> events;
                if (prepared.recoveredCommit() == null) {
                    Flux<AguiEvent> agentEvents = agentRunRuntime.pauseAtSafePoints(run,
                            Flux.defer(() -> agentExecutor.execute(run, prepared.task(), prepared.command(),
                                    prepared.toolContext())),
                            () -> taskRuntime.markRunPaused(taskId, run.runId()));
                    events = writingWorkflow.completeAgentEvents(
                            taskId, prepared.toolContext(), run, agentEvents);
                } else {
                    events = agentRunRuntime.pauseAtSafePoints(run,
                            writingWorkflow.recoveredEvents(prepared, run),
                            () -> taskRuntime.markRunPaused(taskId, run.runId()));
                }
                return agentRunRuntime.start(run, history, events)
                        .onErrorResume(error -> retryCommit(prepared, run, error))
                        .doOnTerminate(() -> finish(taskId, run, "finished"))
                        .doOnCancel(() -> cancel(taskId, run, "cancelled"));
            } catch (RuntimeException error) {
                agentRunRuntime.release(taskId);
                log.warn("Writing round rejected: taskId={}, reason={}", taskId, error.getMessage());
                return Flux.error(error);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Flux<AguiEvent> resume(String taskId, String interruptedRunId, List<AgentRunDecision> decisions) {
        return Flux.defer(() -> {
            agentRunRuntime.requireAwaitingConfirmation(taskId, interruptedRunId);
            taskRuntime.requireRunCanContinue(taskId, interruptedRunId);
            acquire(taskId);
            try {
                AgentRunRuntime.ResumeRequest resume = agentRunRuntime.prepareResume(taskId, interruptedRunId, decisions);
                AgentRunEventRecorder.RunHistory history = agentRunRuntime.findHistory(interruptedRunId);
                WritingWorkflow.AgentResumeInput input;
                try {
                    input = writingWorkflow.prepareAgentResume(taskId, resume.interrupts(), decisions);
                } catch (RuntimeException error) {
                    agentRunRuntime.restoreAwaitingConfirmation(interruptedRunId);
                    throw error;
                }
                registerPauseHandler(resume.run(), input.task());
                Flux<AguiEvent> agentEvents = agentRunRuntime.pauseAtSafePoints(resume.run(),
                        Flux.defer(() -> agentExecutor.resume(resume.run(), input.task(), decisions,
                                resume.interrupts(), input.toolContext(), input.message())),
                        () -> taskRuntime.markRunPaused(taskId, resume.run().runId()));
                Flux<AguiEvent> events = writingWorkflow.completeAgentEvents(
                        taskId, input.toolContext(), resume.run(), agentEvents);
                if (input.rejectedStageId() != null) {
                    events = Flux.concat(Flux.just(writingWorkflow.revisionRequestedEvent(
                            resume.run(), input.rejectedStageId())), events);
                }
                return agentRunRuntime.continueRun(resume.run(), history, events)
                        .doOnTerminate(() -> finish(taskId, resume.run(), "resume finished"))
                        .doOnCancel(() -> cancel(taskId, resume.run(), "resume cancelled"));
            } catch (RuntimeException error) {
                agentRunRuntime.release(taskId);
                log.warn("Writing resume rejected: taskId={}, reason={}", taskId, error.getMessage());
                return Flux.error(error);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Flux<AguiEvent> resumeTask(String taskId) {
        return Flux.defer(() -> {
            acquire(taskId);
            TaskRuntime.TaskResume resume;
            try {
                resume = taskRuntime.prepareResume(taskId);
            } catch (RuntimeException error) {
                agentRunRuntime.release(taskId);
                return Flux.error(error);
            }
            if (resume.run() == null) {
                agentRunRuntime.release(taskId);
                return Flux.empty();
            }
            if (!resume.run().execute()) {
                agentRunRuntime.release(taskId);
                AgentRunContext run = resume.run().run();
                return Flux.just(new AguiEvent.Custom(run.threadId(), run.runId(), "task.resumed",
                        Map.of("status", resume.task().status().name())));
            }

            AgentRunContext run = resume.run().run();
            try {
                WritingWorkflow.PreparedRun prepared = writingWorkflow.preparePausedRun(taskId, run);
                agentRunRuntime.markResumedRunning(run.runId());
                taskRuntime.markRunRunning(taskId, run.runId());
                registerPauseHandler(run, prepared.task());
                AgentRunEventRecorder.RunHistory history = agentRunRuntime.findHistory(run.runId());
                Flux<AguiEvent> agentEvents = agentRunRuntime.pauseAtSafePoints(run,
                        Flux.defer(() -> agentExecutor.resumePaused(
                                run, prepared.task(), prepared.command(), prepared.toolContext())),
                        () -> taskRuntime.markRunPaused(taskId, run.runId()));
                Flux<AguiEvent> events = writingWorkflow.completeAgentEvents(
                        taskId, prepared.toolContext(), run, agentEvents);
                return agentRunRuntime.continueRun(run, history, events)
                        .doOnTerminate(() -> finish(taskId, run, "pause resume finished"))
                        .doOnCancel(() -> cancel(taskId, run, "pause resume cancelled"));
            } catch (RuntimeException error) {
                try {
                    agentRunRuntime.cancel(run.runId());
                    taskRuntime.onRunStreamFinished(taskId, run.runId());
                } finally {
                    agentRunRuntime.release(taskId);
                }
                return Flux.error(error);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<AguiEvent> retryCommit(
            WritingWorkflow.PreparedRun failed,
            AgentRunContext failedRun,
            Throwable error) {
        WritingWorkflow.PreparedRun retry = writingWorkflow.prepareRetry(
                failed.task().id(), failedRun.threadId());
        if (retry == null) {
            return agentRunRuntime.failure(failedRun.threadId(), failedRun.runId(),
                    "本轮执行失败：" + messageOf(error), "RUN_FAILED");
        }
        AgentRunContext retryRun = agentRunRuntime.createRun(failed.task().id(), retry.chapterSessionId());
        AgentRunEventRecorder.RunHistory retryHistory = agentRunRuntime.beginRetryHistory(
                failedRun.runId(), retryRun, WRITING_AGENT_ID);
        try {
            taskRuntime.replaceRunForRetry(failed.task().id(), failedRun.runId(), retryRun.runId());
        } catch (RuntimeException retryRejected) {
            agentRunRuntime.cancel(retryRun.runId());
            return agentRunRuntime.failure(failedRun.threadId(), failedRun.runId(),
                    "本轮执行失败：" + messageOf(error), "RUN_FAILED");
        }
        registerPauseHandler(retryRun, retry.task());
        Flux<AguiEvent> controlled = agentRunRuntime.pauseAtSafePoints(retryRun,
                Flux.defer(() -> agentExecutor.execute(
                        retryRun, retry.task(), retry.command(), retry.toolContext())),
                () -> taskRuntime.markRunPaused(failed.task().id(), retryRun.runId()));
        Flux<AguiEvent> retryEvents = writingWorkflow.completeAgentEvents(
                failed.task().id(), retry.toolContext(), retryRun, controlled);
        return Flux.concat(Flux.just(agentRunRuntime.retryEvent(retryRun, failedRun.runId(), error)),
                        agentRunRuntime.start(retryRun, retryHistory, retryEvents)
                                .onErrorResume(retryError -> agentRunRuntime.failure(retryRun.threadId(),
                                        retryRun.runId(), "自动重试失败：" + messageOf(retryError), "RETRY_FAILED")))
                .doOnTerminate(() -> taskRuntime.onRunStreamFinished(
                        failed.task().id(), retryRun.runId()));
    }

    private void acquire(String taskId) {
        if (!agentRunRuntime.acquire(taskId)) {
            throw new IllegalStateException("该写作任务已有一轮正在运行");
        }
    }

    private void registerPauseHandler(AgentRunContext run, com.example.incremental.writing.WritingTask task) {
        agentRunRuntime.registerPauseHandler(run.runId(), () -> {
            try {
                agentExecutor.requestPause(run, task);
            } catch (RuntimeException error) {
                log.debug("Agent pause signal will be handled at the next runtime boundary: runId={}",
                        run.runId(), error);
            }
        });
    }

    private void finish(String taskId, AgentRunContext run, String state) {
        try {
            taskRuntime.onRunStreamFinished(taskId, run.runId());
        } finally {
            agentRunRuntime.release(taskId);
        }
        log.info("Writing round {}: taskId={}", state, taskId);
    }

    private void cancel(String taskId, AgentRunContext run, String state) {
        try {
            agentRunRuntime.cancel(run.runId());
        } finally {
            finish(taskId, run, state);
        }
    }

    private String messageOf(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank() ? "未知错误" : error.getMessage();
    }
}
