package com.example.incremental.runtime;

import com.example.incremental.persistence.agent.AgentConversationHistory;
import com.example.incremental.persistence.agent.AgentMessage;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;

@Service
public class AgentRunRuntime {

    private final AgentRunRepository runs;
    private final AgentRunEventRecorder recorder;
    private final Set<String> activeCorrelations = ConcurrentHashMap.newKeySet();
    private final Map<String, RunControl> controls = new ConcurrentHashMap<>();

    public AgentRunRuntime(AgentRunRepository runs, AgentRunEventRecorder recorder) {
        this.runs = runs;
        this.recorder = recorder;
    }

    public boolean acquire(String correlationId) {
        return activeCorrelations.add(correlationId);
    }

    public void release(String correlationId) {
        activeCorrelations.remove(correlationId);
    }

    public AgentRunContext createRun(String correlationId, String threadId) {
        return new AgentRunContext(correlationId, threadId, "run-" + UUID.randomUUID());
    }

    public Flux<AguiEvent> start(AgentRunContext run, AgentRunEventRecorder.RunHistory history, Flux<AguiEvent> events) {
        transition(runs.find(run.runId()), AgentRunStatus.RUNNING, List.of(), null, null, null);
        return continueRun(run, history, events);
    }

    public Flux<AguiEvent> continueRun(
            AgentRunContext run,
            AgentRunEventRecorder.RunHistory history,
            Flux<AguiEvent> events) {
        return recorder.recordEvents(history, events.doOnNext(event -> transitionForEvent(run.runId(), event)))
                .doOnError(error -> transitionForEvent(run.runId(),
                        new AguiEvent.RunError(run.threadId(), run.runId(), messageOf(error), "RUN_FAILED")))
                .doFinally(signal -> controls.remove(run.runId()));
    }

    public AgentRunEventRecorder.RunHistory beginHistory(
            AgentRunContext run,
            String userId,
            String agentId,
            String title,
            String userMessage) {
        runs.create(run, userId, agentId, title, userMessage);
        controls.put(run.runId(), new RunControl());
        return recorder.findHistory(run.runId());
    }

    public AgentRunEventRecorder.RunHistory beginRetryHistory(
            String failedRunId,
            AgentRunContext run,
            String agentId) {
        runs.createRetry(failedRunId, run, agentId);
        controls.put(run.runId(), new RunControl());
        return recorder.findHistory(run.runId());
    }

    public AgentRunEventRecorder.RunHistory findHistory(String runId) {
        return recorder.findHistory(runId);
    }

    public AgentConversationHistory findConversationHistory(String conversationId) {
        return recorder.findConversationHistory(conversationId);
    }

    public List<AgentMessage> findMessages(String conversationId, long offset, int limit) {
        return recorder.findMessages(conversationId, offset, limit);
    }

    public Flux<AguiEvent> failure(String threadId, String runId, String message, String code) {
        return Flux.just(new AguiEvent.RunError(threadId, runId, message, code));
    }

    public AguiEvent.Custom retryEvent(AgentRunContext retryRun, String failedRunId, Throwable error) {
        return new AguiEvent.Custom(retryRun.threadId(), retryRun.runId(), "run.retry", Map.of(
                "failedRunId", failedRunId,
                "reason", messageOf(error)));
    }

    public AgentRunRecord find(String runId) {
        return runs.find(runId);
    }

    public AgentRunRecord findAwaitingConfirmation(String correlationId) {
        return runs.findAwaitingConfirmation(correlationId);
    }

    public void requireAwaitingConfirmation(String correlationId, String runId) {
        AgentRunRecord run = runs.find(runId);
        if (!correlationId.equals(run.correlationId())) {
            throw new IllegalArgumentException("Agent Run 不属于当前任务");
        }
        if (run.status() != AgentRunStatus.AWAITING_CONFIRM) {
            throw new IllegalStateException("Agent Run 当前不在等待确认状态");
        }
    }

    public void registerPauseHandler(String runId, Runnable pauseHandler) {
        controls.computeIfAbsent(runId, ignored -> new RunControl()).onPauseRequested(pauseHandler);
    }

    public PauseRequestResult requestPause(String runId) {
        AgentRunRecord current = runs.find(runId);
        if (current.status() == AgentRunStatus.PAUSED || current.status() == AgentRunStatus.ERROR
                || current.status().terminal()) {
            return PauseRequestResult.ALREADY_STOPPED;
        }
        if (current.status() == AgentRunStatus.AWAITING_CONFIRM) {
            RunCheckpoint checkpoint = new RunCheckpoint(
                    RunSafePoint.WAITING_FOR_CONFIRMATION, AgentRunStatus.AWAITING_CONFIRM, Instant.now());
            transition(current, AgentRunStatus.PAUSED, current.pendingInterrupts(), checkpoint, null, null);
            controls.remove(runId);
            return PauseRequestResult.PAUSED;
        }
        controls.computeIfAbsent(runId, ignored -> new RunControl()).requestPause();
        return PauseRequestResult.REQUESTED;
    }

    public Flux<AguiEvent> pauseAtSafePoints(
            AgentRunContext run,
            Flux<AguiEvent> events,
            Runnable onPaused) {
        return events.concatMap(event -> {
                    RunSafePoint before = safePointBefore(event);
                    if (before != null && pauseRequested(run.runId())) {
                        return pausedEvent(run, before, onPaused);
                    }
                    RunSafePoint after = safePointAfter(event);
                    if (after != null && pauseRequested(run.runId())) {
                        return Flux.concat(Flux.just(event), pausedEvent(run, after, onPaused));
                    }
                    return Flux.just(event);
                })
                .takeUntil(this::isPausedEvent);
    }

    public PausedResume preparePausedResume(String correlationId, String runId) {
        AgentRunRecord current = runs.find(runId);
        if (!correlationId.equals(current.correlationId())) {
            throw new IllegalArgumentException("Agent Run 不属于当前任务");
        }
        if (current.status() != AgentRunStatus.PAUSED || current.checkpoint() == null) {
            throw new IllegalStateException("Agent Run 当前没有可恢复的暂停 Checkpoint");
        }
        AgentRunContext run = new AgentRunContext(current.correlationId(), current.threadId(), current.runId());
        if (current.checkpoint().resumeStatus() == AgentRunStatus.AWAITING_CONFIRM) {
            transition(current, AgentRunStatus.AWAITING_CONFIRM, current.pendingInterrupts(), null, null, null);
            return new PausedResume(run, current.checkpoint(), false);
        }
        transition(current, AgentRunStatus.RESUMING, current.pendingInterrupts(), null, null, null);
        controls.put(runId, new RunControl());
        return new PausedResume(run, current.checkpoint(), true);
    }

    public PausedResume prepareErrorResume(String correlationId, String runId) {
        AgentRunRecord current = runs.find(runId);
        if (!correlationId.equals(current.correlationId())) {
            throw new IllegalArgumentException("Agent Run 不属于当前任务");
        }
        if (current.status() != AgentRunStatus.ERROR) {
            throw new IllegalStateException("Agent Run 当前不在可恢复的失败状态");
        }
        AgentRunContext run = new AgentRunContext(current.correlationId(), current.threadId(), current.runId());
        transition(current, AgentRunStatus.RESUMING, current.pendingInterrupts(), current.checkpoint(), null, null);
        controls.put(runId, new RunControl());
        return new PausedResume(run, current.checkpoint(), true);
    }

    public void markResumedRunning(String runId) {
        AgentRunRecord current = runs.find(runId);
        transition(current, AgentRunStatus.RUNNING, current.pendingInterrupts(), null, null, null);
    }

    public ResumeRequest prepareResume(String correlationId, String runId, List<AgentRunDecision> decisions) {
        AgentRunRecord current = runs.find(runId);
        if (!correlationId.equals(current.correlationId())) {
            throw new IllegalArgumentException("Agent Run 不属于当前任务");
        }
        if (current.status() != AgentRunStatus.AWAITING_CONFIRM) {
            throw new IllegalStateException("Agent Run 当前不在等待确认状态");
        }
        if (decisions == null || decisions.isEmpty()) {
            throw new IllegalArgumentException("至少需要提供一个 toolCall 决策");
        }
        Map<String, AgentRunInterrupt> interruptsByToolCallId = new LinkedHashMap<>();
        current.pendingInterrupts().forEach(interrupt -> interruptsByToolCallId.put(interrupt.toolCallId(), interrupt));
        if (decisions.size() != interruptsByToolCallId.size()
                || decisions.stream().map(AgentRunDecision::toolCallId).anyMatch(id -> !interruptsByToolCallId.containsKey(id))
                || decisions.stream().map(AgentRunDecision::toolCallId).collect(java.util.stream.Collectors.toSet()).size()
                != decisions.size()) {
            throw new IllegalArgumentException("resume 必须为当前所有 pending tool call 各提供一次决策");
        }
        if (current.threadId() == null || current.threadId().isBlank()) {
            throw new IllegalStateException("Run 缺少 AgentScope session，请先导入旧 Run 状态");
        }
        transition(current, AgentRunStatus.RESUMING, current.pendingInterrupts(), null, null, null);
        controls.put(runId, new RunControl());
        return new ResumeRequest(new AgentRunContext(current.correlationId(), current.threadId(), current.runId()),
                current.pendingInterrupts());
    }

    public void restoreAwaitingConfirmation(String runId) {
        AgentRunRecord current = runs.find(runId);
        transition(current, AgentRunStatus.AWAITING_CONFIRM, current.pendingInterrupts(), null, null, null);
        controls.remove(runId);
    }

    public void cancel(String runId) {
        AgentRunRecord current = runs.find(runId);
        if (!current.status().terminal()) {
            transition(current, AgentRunStatus.CANCELLED, List.of(), null,
                    "CLIENT_CANCELLED", "客户端取消了本轮执行");
        }
    }

    private boolean pauseRequested(String runId) {
        RunControl control = controls.get(runId);
        return control != null && control.pauseRequested();
    }

    private Flux<AguiEvent> pausedEvent(AgentRunContext run, RunSafePoint safePoint, Runnable onPaused) {
        AgentRunRecord current = runs.find(run.runId());
        if (current.status() == AgentRunStatus.PAUSED) {
            return Flux.empty();
        }
        AgentRunStatus resumeStatus = current.status() == AgentRunStatus.RESUMING
                ? AgentRunStatus.RUNNING : current.status();
        RunCheckpoint checkpoint = new RunCheckpoint(safePoint, resumeStatus, Instant.now());
        transition(current, AgentRunStatus.PAUSED, current.pendingInterrupts(), checkpoint, null, null);
        onPaused.run();
        return Flux.just(new AguiEvent.Custom(run.threadId(), run.runId(), "run.paused", Map.of(
                "safePoint", safePoint.name(),
                "checkpointAt", checkpoint.capturedAt().toString())));
    }

    private RunSafePoint safePointBefore(AguiEvent event) {
        if (event instanceof AguiEvent.StepStarted) {
            return RunSafePoint.BEFORE_AGENT_STEP;
        }
        if (event instanceof AguiEvent.RunFinished) {
            return RunSafePoint.BEFORE_BUSINESS_COMMIT;
        }
        return null;
    }

    private RunSafePoint safePointAfter(AguiEvent event) {
        if (event instanceof AguiEvent.StepFinished) {
            return RunSafePoint.AFTER_AGENT_STEP;
        }
        if (event instanceof AguiEvent.Raw raw && raw.event() instanceof ModelCallEndEvent) {
            return RunSafePoint.AFTER_LLM_CALL;
        }
        if (event instanceof AguiEvent.ToolCallResult) {
            return RunSafePoint.AFTER_TOOL_CALL;
        }
        return null;
    }

    private boolean isPausedEvent(AguiEvent event) {
        return event instanceof AguiEvent.Custom custom && "run.paused".equals(custom.name());
    }

    private void transitionForEvent(String runId, AguiEvent event) {
        if (!(event instanceof AguiEvent.RunStarted || event instanceof AguiEvent.RunError
                || event instanceof AguiEvent.RunFinished)) return;
        AgentRunRecord current = runs.find(runId);
        if (current.status() == AgentRunStatus.ERROR || current.status().terminal()) return;
        if (event instanceof AguiEvent.RunStarted) {
            if (current.status() != AgentRunStatus.RUNNING) {
                transition(current, AgentRunStatus.RUNNING, current.pendingInterrupts(), null, null, null);
            }
            return;
        }
        if (event instanceof AguiEvent.RunError error) {
            transition(current, AgentRunStatus.ERROR, List.of(), null, error.code(), error.message());
            return;
        }
        AguiEvent.RunFinished finished = (AguiEvent.RunFinished) event;
        if (finished.outcome() instanceof AguiEvent.RunFinishedInterruptOutcome outcome) {
            List<AgentRunInterrupt> interrupts = outcome.interrupts().stream()
                    .map(interrupt -> new AgentRunInterrupt(
                            interrupt.id(), interrupt.toolCallId(),
                            interrupt.metadata() == null ? null : (String) interrupt.metadata().get("toolName"),
                            interrupt.metadata() == null ? Map.of() : toolInput(interrupt.metadata())))
                    .toList();
            transition(current, AgentRunStatus.AWAITING_CONFIRM, interrupts, null, null, null);
            return;
        }
        transition(current, AgentRunStatus.FINISHED, List.of(), null, null, null);
    }

    private void transition(AgentRunRecord current, AgentRunStatus target, List<AgentRunInterrupt> interrupts,
                            RunCheckpoint checkpoint, String errorCode, String errorMessage) {
        boolean allowed = switch (current.status()) {
            case CREATED -> target == AgentRunStatus.RUNNING || target == AgentRunStatus.ERROR
                    || target == AgentRunStatus.CANCELLED;
            case RUNNING -> target == AgentRunStatus.PAUSED
                    || target == AgentRunStatus.AWAITING_CONFIRM || target == AgentRunStatus.ERROR
                    || target.terminal();
            case PAUSED -> target == AgentRunStatus.RESUMING || target == AgentRunStatus.AWAITING_CONFIRM
                    || target == AgentRunStatus.CANCELLED;
            case AWAITING_CONFIRM -> target == AgentRunStatus.RESUMING || target == AgentRunStatus.PAUSED;
            case RESUMING -> target == AgentRunStatus.RUNNING || target == AgentRunStatus.AWAITING_CONFIRM
                    || target == AgentRunStatus.PAUSED || target == AgentRunStatus.ERROR || target.terminal();
            case ERROR -> target == AgentRunStatus.RESUMING || target == AgentRunStatus.CANCELLED;
            case FINISHED, CANCELLED -> false;
        };
        if (!allowed) throw new IllegalStateException("非法 Run 状态转换: " + current.status() + " → " + target);
        runs.transition(current, target, interrupts, checkpoint, errorCode, errorMessage);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toolInput(Map<String, Object> metadata) {
        Object value = metadata.get("toolInput");
        return value instanceof Map<?, ?> input ? (Map<String, Object>) input : Map.of();
    }

    private String messageOf(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank() ? "未知错误" : error.getMessage();
    }

    public record ResumeRequest(AgentRunContext run, List<AgentRunInterrupt> interrupts) {
    }

    public record PausedResume(AgentRunContext run, RunCheckpoint checkpoint, boolean execute) {
    }

    public enum PauseRequestResult {
        REQUESTED,
        PAUSED,
        ALREADY_STOPPED
    }
}
