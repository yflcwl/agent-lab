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

@Service
public class RoundRunner {

    private static final Logger log = LoggerFactory.getLogger(RoundRunner.class);
    private static final String WRITING_AGENT_ID = "incremental-writing-agent";

    private final WritingWorkflow writingWorkflow;
    private final AgentRunRuntime agentRunRuntime;
    private final AgentExecutor agentExecutor;

    public RoundRunner(
            WritingWorkflow writingWorkflow,
            AgentRunRuntime agentRunRuntime,
            AgentExecutor agentExecutor) {
        this.writingWorkflow = writingWorkflow;
        this.agentRunRuntime = agentRunRuntime;
        this.agentExecutor = agentExecutor;
    }

    public Flux<AguiEvent> run(String taskId) {
        return run(taskId, "");
    }

    public Flux<AguiEvent> run(String taskId, String userMessage) {
        return Flux.defer(() -> {
            acquire(taskId);
            try {
                WritingWorkflow.PreparedRun prepared = writingWorkflow.prepareRun(taskId, userMessage);
                AgentRunContext run = agentRunRuntime.createRun(taskId, prepared.chapterSessionId());
                AgentRunEventRecorder.RunHistory history = agentRunRuntime.beginHistory(run, prepared.task().userId(),
                        WRITING_AGENT_ID, "写作任务 " + taskId, userMessage);
                Flux<AguiEvent> events = prepared.recoveredCommit() == null
                        ? writingWorkflow.completeAgentEvents(taskId, prepared.toolContext(), run,
                                Flux.defer(() -> agentExecutor.execute(run, prepared.task(), prepared.command(),
                                        prepared.toolContext())))
                        : writingWorkflow.recoveredEvents(prepared, run);
                return agentRunRuntime.start(run, history, events)
                        .onErrorResume(error -> retryCommit(prepared, run, error))
                        .doOnTerminate(() -> finish(taskId, "finished"))
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
                Flux<AguiEvent> events = writingWorkflow.completeAgentEvents(taskId, input.toolContext(), resume.run(),
                        Flux.defer(() -> agentExecutor.resume(resume.run(), input.task(), decisions,
                                resume.interrupts(), input.toolContext(), input.message())));
                if (input.rejectedStageId() != null) {
                    events = Flux.concat(Flux.just(writingWorkflow.revisionRequestedEvent(
                            resume.run(), input.rejectedStageId())), events);
                }
                return agentRunRuntime.continueRun(resume.run(), history, events)
                        .doOnTerminate(() -> finish(taskId, "resume finished"))
                        .doOnCancel(() -> cancel(taskId, resume.run(), "resume cancelled"));
            } catch (RuntimeException error) {
                agentRunRuntime.release(taskId);
                log.warn("Writing resume rejected: taskId={}, reason={}", taskId, error.getMessage());
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
        AgentRunEventRecorder.RunHistory retryHistory = agentRunRuntime.beginRetryHistory(failedRun.runId(), retryRun, WRITING_AGENT_ID);
        Flux<AguiEvent> retryEvents = writingWorkflow.completeAgentEvents(failed.task().id(), retry.toolContext(), retryRun,
                Flux.defer(() -> agentExecutor.execute(retryRun, retry.task(), retry.command(), retry.toolContext())));
        return Flux.concat(Flux.just(agentRunRuntime.retryEvent(retryRun, failedRun.runId(), error)),
                        agentRunRuntime.start(retryRun, retryHistory, retryEvents)
                                .onErrorResume(retryError -> agentRunRuntime.failure(retryRun.threadId(),
                                        retryRun.runId(), "自动重试失败：" + messageOf(retryError), "RETRY_FAILED")));
    }

    private void acquire(String taskId) {
        if (!agentRunRuntime.acquire(taskId)) {
            throw new IllegalStateException("该写作任务已有一轮正在运行");
        }
    }

    private void finish(String taskId, String state) {
        agentRunRuntime.release(taskId);
        log.info("Writing round {}: taskId={}", state, taskId);
    }

    private void cancel(String taskId, AgentRunContext run, String state) {
        try {
            agentRunRuntime.cancel(run.runId());
        } finally {
            finish(taskId, state);
        }
    }

    private String messageOf(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank() ? "未知错误" : error.getMessage();
    }
}
