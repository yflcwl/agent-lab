package com.example.incremental.runtime;

import com.example.incremental.config.DemoProperties;
import com.example.incremental.writing.ChapterStage;
import com.example.incremental.writing.ChapterStageStatus;
import com.example.incremental.writing.ContentEntry;
import com.example.incremental.runtime.AgentRunDecision;
import com.example.incremental.writing.WritingTask;
import com.example.incremental.writing.WritingTaskView;
import com.example.incremental.workspace.TaskWorkspaceService;
import com.example.incremental.writing.ChapterStageCoordinator;
import com.example.incremental.writing.WritingAgent;
import com.example.incremental.writing.WritingRunCommand;
import com.example.incremental.writing.WritingToolContext;
import io.agentscope.core.agui.event.AguiEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Collectors;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class RoundRunner {

    private static final Logger log = LoggerFactory.getLogger(RoundRunner.class);

    private final TaskWorkspaceService workspaceService;
    private final ChapterStageCoordinator chapterStageCoordinator;
    private final AgentRunRuntime agentRunRuntime;
    private final WritingAgent writingAgent;
    private final Path stateRoot;
    private final Set<String> runningTasks = ConcurrentHashMap.newKeySet();

    public RoundRunner(
            TaskWorkspaceService workspaceService,
            ChapterStageCoordinator chapterStageCoordinator,
            AgentRunRuntime agentRunRuntime,
            WritingAgent writingAgent,
            DemoProperties properties) {
        this.workspaceService = workspaceService;
        this.chapterStageCoordinator = chapterStageCoordinator;
        this.agentRunRuntime = agentRunRuntime;
        this.writingAgent = writingAgent;
        this.stateRoot = properties.getStateRoot().toAbsolutePath().normalize();
    }

    public Flux<AguiEvent> run(String taskId) {
        return run(taskId, "");
    }

    public Flux<AguiEvent> run(String taskId, String userMessage) {
        return Flux.defer(() -> {
            if (!runningTasks.add(taskId)) {
                return Flux.error(new IllegalStateException("该写作任务已有一轮正在运行"));
            }
            try {
                WritingTaskView taskView = workspaceService.getTaskView(taskId);
                if (taskView.sources().isEmpty()) {
                    throw new IllegalStateException("该任务没有目标背景资料，不能运行下一轮");
                }
                WritingTask task = taskView.task();
                ChapterStage openStage = chapterStageCoordinator.findOpenStage(taskId);
                WritingToolContext toolContext = new WritingToolContext(taskId, openStage);
                String message = userMessage == null ? "" : userMessage.trim();
                if (message.length() > 4000) {
                    throw new IllegalArgumentException("单条消息不能超过 4000 个字符");
                }
                int nextSequence = openStage == null
                        ? taskView.contents().size() + 1
                        : openStage.content().sequence();
                String chapterSessionId = resolveChapterSessionId(task, nextSequence);
                String runId = "writing-" + taskId + "-" + UUID.randomUUID();
                if (openStage != null && openStage.status() == ChapterStageStatus.AWAITING_REVIEW) {
                    throw new IllegalStateException("当前 ChapterStage 正在等待用户审核，请通过 resume 完成本次确认");
                }
                WritingRunCommand command = openStage == null
                        ? WritingRunCommand.writeChapter(message)
                        : openStage.isComplete()
                                ? WritingRunCommand.requestChapterCommit(openStage.stageId())
                                : WritingRunCommand.recoverStage(openStage.stageId());
                log.info("Writing round starting: taskId={}, chapterSessionId={}, runId={}",
                        taskId, chapterSessionId, runId);

                ChapterStageCoordinator.ChapterCommit recoveredCommit = chapterStageCoordinator
                        .commitRecoveredStageIfReady(taskId, openStage);
                if (recoveredCommit != null) {
                    Flux<AguiEvent> recoveredEvents = Flux.just(
                                    new AguiEvent.RunStarted(chapterSessionId, runId),
                                    chapterSavedEvent(chapterSessionId, runId, taskId, recoveredCommit),
                                    new AguiEvent.RunFinished(chapterSessionId, runId));
                    return agentRunRuntime.track(taskId, chapterSessionId, runId, recoveredEvents)
                            .doFinally(signal -> {
                                runningTasks.remove(taskId);
                                log.info("Writing round finished: taskId={}, signal={}", taskId, signal);
                            });
                }

                Flux<AguiEvent> agentEvents = finishAgentEvents(taskId, chapterSessionId, runId, toolContext,
                        Flux.defer(() -> writingAgent.streamRound(task, chapterSessionId, runId, command, toolContext)));
                return agentRunRuntime.track(taskId, chapterSessionId, runId, agentEvents)
                        .onErrorResume(error -> retryCompleteStageCommit(
                                taskId, task, chapterSessionId, runId, error))
                        .doFinally(signal -> {
                            runningTasks.remove(taskId);
                            log.info("Writing round finished: taskId={}, signal={}", taskId, signal);
                        });
            } catch (RuntimeException e) {
                runningTasks.remove(taskId);
                log.warn("Writing round rejected: taskId={}, reason={}", taskId, e.getMessage());
                return Flux.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<AguiEvent> retryCompleteStageCommit(
            String taskId,
            WritingTask task,
            String chapterSessionId,
            String failedRunId,
            Throwable error) {
        ChapterStage stage = chapterStageCoordinator.findOpenStage(taskId);
        if (stage == null || !stage.isComplete()) {
            return Flux.just(new AguiEvent.RunError(chapterSessionId, failedRunId,
                    "本轮执行失败：" + safeMessage(error), "RUN_FAILED"));
        }
        String retryRunId = "writing-" + taskId + "-" + UUID.randomUUID();
        WritingToolContext retryContext = new WritingToolContext(taskId, stage);
        WritingRunCommand retryCommand = WritingRunCommand.requestChapterCommit(stage.stageId());
        log.warn("Writing round retrying commit request: taskId={}, failedRunId={}, retryRunId={}",
                taskId, failedRunId, retryRunId, error);
        Flux<AguiEvent> retryEvents = finishAgentEvents(taskId, chapterSessionId, retryRunId, retryContext,
                Flux.defer(() -> writingAgent.streamRound(task, chapterSessionId, retryRunId, retryCommand, retryContext)));
        return Flux.concat(
                        Flux.just(new AguiEvent.Custom(chapterSessionId, retryRunId, "run.retry", Map.of(
                                "failedRunId", failedRunId,
                                "reason", safeMessage(error)))),
                        agentRunRuntime.track(taskId, chapterSessionId, retryRunId, retryEvents)
                                .onErrorResume(retryError -> Flux.just(new AguiEvent.RunError(
                                        chapterSessionId, retryRunId,
                                        "自动重试失败：" + safeMessage(retryError), "RETRY_FAILED"))));
    }

    public Flux<AguiEvent> resume(String taskId, String interruptedRunId, List<AgentRunDecision> decisions) {
        return Flux.defer(() -> {
            if (!runningTasks.add(taskId)) {
                return Flux.error(new IllegalStateException("该写作任务已有一轮正在运行"));
            }
            try {
                ChapterStage stage = chapterStageCoordinator.findOpenStage(taskId);
                if (stage == null || stage.status() != ChapterStageStatus.AWAITING_REVIEW) {
                    throw new IllegalStateException("当前没有等待审核的 ChapterStage");
                }
                boolean revisionRequested = decisions != null
                        && decisions.stream().anyMatch(decision -> !decision.approved());
                String feedback;
                if (revisionRequested) {
                    feedback = decisions.stream()
                            .filter(decision -> !decision.approved())
                            .map(AgentRunDecision::feedback)
                            .filter(StringUtils::hasText)
                            .collect(Collectors.joining("\n"))
                            .trim();
                    if (!StringUtils.hasText(feedback)) {
                        throw new IllegalArgumentException("请填写审核意见后再请求重写");
                    }
                    if (feedback.length() > 4000) {
                        throw new IllegalArgumentException("审核意见不能超过 4000 个字符");
                    }
                } else {
                    feedback = "";
                }
                AgentRunRuntime.ResumeRequest resume = agentRunRuntime.prepareResume(taskId, interruptedRunId, decisions);
                if (revisionRequested) {
                    chapterStageCoordinator.reject(taskId, stage.stageId());
                    agentRunRuntime.completeResume(interruptedRunId);
                    WritingTask task = workspaceService.getTaskView(taskId).task();
                    String rewriteRunId = "writing-" + taskId + "-" + UUID.randomUUID();
                    String rewriteMessage = "用户未通过当前章节候选，请在同一章节内根据以下审核意见重写；"
                            + "此前候选未提交，不得将其视为已完成内容。\n\n审核意见：\n" + feedback;
                    WritingToolContext rewriteContext = new WritingToolContext(taskId);
                    Flux<AguiEvent> rewriteEvents = finishAgentEvents(taskId, resume.threadId(), rewriteRunId,
                            rewriteContext, Flux.defer(() -> writingAgent.streamRound(task, resume.threadId(),
                                    rewriteRunId, WritingRunCommand.writeChapter(rewriteMessage), rewriteContext)));
                    return Flux.concat(
                                    Flux.just(new AguiEvent.Custom(resume.threadId(), interruptedRunId,
                                            "chapter.revision_requested", Map.of("stageId", stage.stageId()))),
                                    agentRunRuntime.track(taskId, resume.threadId(), rewriteRunId, rewriteEvents))
                            .doFinally(signal -> {
                                runningTasks.remove(taskId);
                                log.info("Writing revision finished: taskId={}, runId={}, signal={}",
                                        taskId, rewriteRunId, signal);
                            });
                }
                try {
                    ChapterStageCoordinator.ChapterCommit commit = chapterStageCoordinator.commitIfComplete(taskId, stage);
                    agentRunRuntime.completeResume(interruptedRunId);
                    return Flux.<AguiEvent>just(
                                    new AguiEvent.RunStarted(resume.threadId(), interruptedRunId),
                                    chapterSavedEvent(resume.threadId(), interruptedRunId, taskId, commit),
                                    new AguiEvent.RunFinished(resume.threadId(), interruptedRunId))
                            .doFinally(signal -> {
                                runningTasks.remove(taskId);
                                log.info("Writing resume finished: taskId={}, runId={}, signal={}",
                                        taskId, interruptedRunId, signal);
                            });
                } catch (RuntimeException error) {
                    agentRunRuntime.restoreAwaitingConfirmation(interruptedRunId);
                    return Flux.<AguiEvent>just(new AguiEvent.RunError(resume.threadId(), interruptedRunId,
                            "恢复提交失败：" + safeMessage(error), "RESUME_FAILED"))
                            .doFinally(signal -> runningTasks.remove(taskId));
                }
            } catch (RuntimeException e) {
                runningTasks.remove(taskId);
                log.warn("Writing resume rejected: taskId={}, reason={}", taskId, e.getMessage());
                return Flux.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<AguiEvent> finishAgentEvents(
            String taskId,
            String chapterSessionId,
            String runId,
            WritingToolContext toolContext,
            Flux<AguiEvent> events) {
        AtomicBoolean runFailed = new AtomicBoolean();
        return events
                        .concatMap(event -> {
                            if (event instanceof AguiEvent.RunError) {
                                runFailed.set(true);
                            }
                            if (event instanceof AguiEvent.RunFinished finished) {
                                if (runFailed.get()) {
                                    return Flux.just(event);
                                }
                                if (finished.outcome() instanceof AguiEvent.RunFinishedInterruptOutcome outcome) {
                                    boolean commitInterrupt = outcome.interrupts().stream().anyMatch(interrupt ->
                                            interrupt.metadata() != null
                                                    && "commit_chapter".equals(interrupt.metadata().get("toolName")));
                                    if (commitInterrupt) {
                                        chapterStageCoordinator.markAwaitingReview(taskId, toolContext.stageId());
                                    }
                                    return Flux.just(event);
                                }
                                ContentEntry committed = toolContext.committedContent();
                                if (committed != null) {
                                    return Flux.just(chapterSavedEvent(chapterSessionId, runId, taskId,
                                            new ChapterStageCoordinator.ChapterCommit(toolContext.stageId(), committed)), event);
                                }
                                ChapterStage stage = toolContext.stage();
                                if (stage != null && stage.isComplete()) {
                                    return Flux.error(new IllegalStateException(
                                            "完整 ChapterStage 尚未请求 commit_chapter 审核"));
                                }
                            }
                            return Flux.just(event);
                        })
                        .doOnNext(event -> log.info("Writing round event: taskId={}, type={}, runId={}",
                                taskId, event.getType(), event.getRunId()))
                        .doOnError(error -> log.error("Writing round failed: taskId={}", taskId, error));
    }

    private String safeMessage(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank() ? "未知错误" : error.getMessage();
    }

    private AguiEvent.Custom chapterSavedEvent(
            String chapterSessionId,
            String runId,
            String taskId,
            ChapterStageCoordinator.ChapterCommit commit) {
        return new AguiEvent.Custom(chapterSessionId, runId, "chapter.saved", Map.of(
                "taskId", taskId,
                "stageId", commit.stageId(),
                "content", commit.content()));
    }

    private String resolveChapterSessionId(WritingTask task, int sequence) {
        String prefix = "%s-chapter-%03d".formatted(task.sessionId(), sequence);
        String stableSessionId = prefix + "-active";
        Path userStateRoot = stateRoot.resolve(task.userId()).normalize();
        if (!userStateRoot.startsWith(stateRoot) || !Files.isDirectory(userStateRoot)) {
            return stableSessionId;
        }
        try (var sessions = Files.list(userStateRoot)) {
            return sessions.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().startsWith(prefix))
                    .max(Comparator.comparingLong(this::lastModified))
                    .map(path -> path.getFileName().toString())
                    .orElse(stableSessionId);
        } catch (IOException e) {
            log.warn("读取章节 Session 失败，使用稳定 Session ID: {}", stableSessionId, e);
            return stableSessionId;
        }
    }

    private long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return Long.MIN_VALUE;
        }
    }
}

