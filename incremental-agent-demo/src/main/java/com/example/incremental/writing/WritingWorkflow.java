package com.example.incremental.writing;

import com.example.incremental.config.DemoProperties;
import com.example.incremental.runtime.AgentRunContext;
import com.example.incremental.runtime.AgentRunDecision;
import com.example.incremental.runtime.AgentRunInterrupt;
import com.example.incremental.workspace.TaskWorkspaceService;
import io.agentscope.core.agui.event.AguiEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class WritingWorkflow {

    private static final Logger log = LoggerFactory.getLogger(WritingWorkflow.class);

    private final TaskWorkspaceService workspaceService;
    private final ChapterStageCoordinator chapterStageCoordinator;
    private final Path stateRoot;

    public WritingWorkflow(
            TaskWorkspaceService workspaceService,
            ChapterStageCoordinator chapterStageCoordinator,
            DemoProperties properties) {
        this.workspaceService = workspaceService;
        this.chapterStageCoordinator = chapterStageCoordinator;
        this.stateRoot = properties.getStateRoot().toAbsolutePath().normalize();
    }

    public PreparedRun prepareRun(String taskId, String userMessage) {
        WritingTaskView taskView = workspaceService.getTaskView(taskId);
        if (taskView.sources().isEmpty()) {
            throw new IllegalStateException("该任务没有目标背景资料，不能运行下一轮");
        }
        String message = userMessage == null ? "" : userMessage.trim();
        if (message.length() > 4000) {
            throw new IllegalArgumentException("单条消息不能超过 4000 个字符");
        }
        ChapterStage stage = findOpenStage(taskId);
        int sequence = stage == null ? taskView.contents().size() + 1 : stage.content().sequence();
        WritingRunCommand command = stage == null
                ? WritingRunCommand.writeChapter(message)
                : stage.isReadyForReview()
                        ? WritingRunCommand.requestChapterCommit(stage.stageId())
                        : WritingRunCommand.recoverStage(stage.stageId());
        return new PreparedRun(taskView.task(), resolveChapterSessionId(taskView.task(), sequence), command,
                new WritingToolContext(taskId, stage), commitRecoveredStageIfReady(taskId, stage));
    }

    public PreparedRun prepareRetry(String taskId, String chapterSessionId) {
        ChapterStage stage = findOpenStage(taskId);
        if (stage == null || !stage.isReadyForReview()) {
            return null;
        }
        WritingTask task = workspaceService.getTaskView(taskId).task();
        return new PreparedRun(task, chapterSessionId, WritingRunCommand.requestChapterCommit(stage.stageId()),
                new WritingToolContext(taskId, stage), null);
    }

    public PreparedRun preparePausedRun(String taskId, AgentRunContext run) {
        if (!taskId.equals(run.correlationId())) {
            throw new IllegalArgumentException("暂停 Run 不属于当前 WritingTask");
        }
        WritingTask task = workspaceService.getTaskView(taskId).task();
        ChapterStage stage = findOpenStage(taskId);
        WritingRunCommand command = stage == null
                ? WritingRunCommand.resumePaused()
                : stage.isComplete()
                        ? WritingRunCommand.requestChapterCommit(stage.stageId())
                        : WritingRunCommand.recoverStage(stage.stageId());
        return new PreparedRun(task, run.threadId(), command,
                new WritingToolContext(taskId, stage), null);
    }

    public ChapterStage findOpenStage(String taskId) {
        return chapterStageCoordinator.findOpenStage(taskId);
    }

    public AgentResumeInput prepareAgentResume(
            String taskId, List<AgentRunInterrupt> interrupts, List<AgentRunDecision> decisions) {
        WritingTask task = workspaceService.getTaskView(taskId).task();
        ChapterStage stage = findOpenStage(taskId);
        List<AgentRunInterrupt> reviews = interrupts.stream()
                .filter(interrupt -> "commit_chapter".equals(interrupt.toolName())).toList();
        if (reviews.isEmpty()) {
            return new AgentResumeInput(task, new WritingToolContext(taskId, stage), "", null);
        }
        requireAwaitingReview(stage);
        boolean mismatchedStage = reviews.stream().anyMatch(review -> {
            Object reference = review.toolInput() == null ? null : review.toolInput().get("stage_id");
            return reference != null && !stage.stageId().equals(reference);
        });
        if (mismatchedStage) {
            throw new IllegalStateException("待确认的 commit_chapter 与当前 ChapterStage 不一致");
        }
        List<AgentRunDecision> reviewDecisions = decisions.stream()
                .filter(decision -> reviews.stream().anyMatch(review -> review.toolCallId().equals(decision.toolCallId())))
                .toList();
        if (reviewDecisions.stream().allMatch(AgentRunDecision::approved)) {
            return new AgentResumeInput(task, new WritingToolContext(taskId, stage), "", null);
        }
        if (reviewDecisions.stream().anyMatch(AgentRunDecision::approved)) {
            throw new IllegalArgumentException("同一 ChapterStage 的审核决定必须一致");
        }
        String feedback = reviewDecisions.stream()
                .map(AgentRunDecision::feedback)
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.joining("\n"))
                .trim();
        if (!StringUtils.hasText(feedback)) {
            throw new IllegalArgumentException("请填写审核意见后再请求重写");
        }
        if (feedback.length() > 4000) {
            throw new IllegalArgumentException("审核意见不能超过 4000 个字符");
        }
        chapterStageCoordinator.reject(taskId, stage.stageId());
        return new AgentResumeInput(task, new WritingToolContext(taskId),
                "用户未通过第 %d 章「%s」的候选，请在当前章节和 Session 内根据审核意见重写；"
                        .formatted(stage.content().sequence(), stage.content().title())
                        + "旧 Stage " + stage.stageId() + " 已废弃，不得提交旧 Stage 或将其视为已完成内容。"
                        + "请重新暂存正文后调用 commit_chapter 请求新一轮审核。章节记忆、滚动状态和临时计划仅在用户通过审核后写入。"
                        + "\n\n审核意见：\n" + feedback, stage.stageId());
    }

    public Flux<AguiEvent> completeAgentEvents(
            String taskId,
            WritingToolContext toolContext,
            AgentRunContext run,
            Flux<AguiEvent> events) {
        AtomicBoolean runFailed = new AtomicBoolean();
        return events.concatMap(event -> {
            if (event instanceof AguiEvent.RunError) {
                runFailed.set(true);
            }
            if (event instanceof AguiEvent.RunFinished finished && !runFailed.get()) {
                AgentRunCompletion completion = completeAgentRun(taskId, toolContext, finished);
                if (completion.commit() != null) {
                    return Flux.just(chapterSavedEvent(run, completion.commit()), event);
                }
            }
            return Flux.just(event);
        }).doOnError(error -> log.error("Writing round failed: taskId={}", taskId, error));
    }

    public Flux<AguiEvent> recoveredEvents(PreparedRun prepared, AgentRunContext run) {
        return Flux.just(new AguiEvent.RunStarted(run.threadId(), run.runId()),
                chapterSavedEvent(run, prepared.recoveredCommit()),
                new AguiEvent.RunFinished(run.threadId(), run.runId()));
    }

    public AguiEvent.Custom revisionRequestedEvent(AgentRunContext interruptedRun, String stageId) {
        return new AguiEvent.Custom(interruptedRun.threadId(), interruptedRun.runId(), "chapter.revision_requested",
                Map.of("stageId", stageId));
    }

    private ChapterStageCoordinator.ChapterCommit commitRecoveredStageIfReady(String taskId, ChapterStage stage) {
        return chapterStageCoordinator.commitRecoveredStageIfReady(taskId, stage);
    }

    private AgentRunCompletion completeAgentRun(
            String taskId,
            WritingToolContext toolContext,
            AguiEvent.RunFinished finished) {
        if (finished.outcome() instanceof AguiEvent.RunFinishedInterruptOutcome outcome) {
            boolean commitInterrupt = outcome.interrupts().stream().anyMatch(interrupt ->
                    interrupt.metadata() != null
                            && "commit_chapter".equals(interrupt.metadata().get("toolName")));
            if (commitInterrupt) {
                chapterStageCoordinator.markAwaitingReview(taskId, toolContext.stageId());
            }
            return AgentRunCompletion.none();
        }
        ContentEntry committed = toolContext.committedContent();
        if (committed != null) {
            return new AgentRunCompletion(new ChapterStageCoordinator.ChapterCommit(toolContext.stageId(), committed));
        }
        ChapterStage stage = toolContext.stage();
        if (stage != null && stage.isReadyForReview()) {
            throw new IllegalStateException("候选正文尚未请求 commit_chapter 审核");
        }
        return AgentRunCompletion.none();
    }

    private void requireAwaitingReview(ChapterStage stage) {
        if (stage == null || stage.status() != ChapterStageStatus.AWAITING_REVIEW) {
            throw new IllegalStateException("当前没有等待审核的 ChapterStage");
        }
    }

    private AguiEvent.Custom chapterSavedEvent(AgentRunContext run, ChapterStageCoordinator.ChapterCommit commit) {
        return new AguiEvent.Custom(run.threadId(), run.runId(), "chapter.saved", Map.of(
                "taskId", run.correlationId(),
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

    public record PreparedRun(
            WritingTask task,
            String chapterSessionId,
            WritingRunCommand command,
            WritingToolContext toolContext,
            ChapterStageCoordinator.ChapterCommit recoveredCommit) {
    }

    public record AgentResumeInput(
            WritingTask task, WritingToolContext toolContext, String message, String rejectedStageId) {
    }

    private record AgentRunCompletion(ChapterStageCoordinator.ChapterCommit commit) {

        private static AgentRunCompletion none() {
            return new AgentRunCompletion(null);
        }
    }
}
