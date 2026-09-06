package com.example.incremental.runtime;

import com.example.incremental.persistence.agent.AgentConversationHistory;
import com.example.incremental.persistence.agent.AgentMessage;
import io.agentscope.core.agui.event.AguiEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentRunRuntime {

    private final AgentRunRepository runs;
    private final AgentRunEventRecorder recorder;
    private final Set<String> activeCorrelations = ConcurrentHashMap.newKeySet();

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
        transition(runs.find(run.runId()), AgentRunStatus.RUNNING, List.of(), null, null);
        return continueRun(run, history, events);
    }

    public Flux<AguiEvent> continueRun(
            AgentRunContext run,
            AgentRunEventRecorder.RunHistory history,
            Flux<AguiEvent> events) {
        return recorder.recordEvents(history, events.doOnNext(event -> transitionForEvent(run.runId(), event)))
                .doOnError(error -> transitionForEvent(run.runId(),
                        new AguiEvent.RunError(run.threadId(), run.runId(), messageOf(error), "RUN_FAILED")));
    }

    public AgentRunEventRecorder.RunHistory beginHistory(
            AgentRunContext run,
            String userId,
            String agentId,
            String title,
            String userMessage) {
        runs.create(run, userId, agentId, title, userMessage);
        return recorder.findHistory(run.runId());
    }

    public AgentRunEventRecorder.RunHistory beginRetryHistory(
            String failedRunId,
            AgentRunContext run,
            String agentId) {
        runs.createRetry(failedRunId, run, agentId);
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
        transition(current, AgentRunStatus.RESUMING, current.pendingInterrupts(), null, null);
        return new ResumeRequest(new AgentRunContext(current.correlationId(), current.threadId(), current.runId()),
                current.pendingInterrupts());
    }

    public void restoreAwaitingConfirmation(String runId) {
        AgentRunRecord current = runs.find(runId);
        transition(current, AgentRunStatus.AWAITING_CONFIRM, current.pendingInterrupts(), null, null);
    }

    private void transitionForEvent(String runId, AguiEvent event) {
        if (!(event instanceof AguiEvent.RunStarted || event instanceof AguiEvent.RunError
                || event instanceof AguiEvent.RunFinished)) return;
        AgentRunRecord current = runs.find(runId);
        if (current.status().terminal()) return;
        if (event instanceof AguiEvent.RunStarted) {
            if (current.status() != AgentRunStatus.RUNNING) {
                transition(current, AgentRunStatus.RUNNING, current.pendingInterrupts(), null, null);
            }
            return;
        }
        if (event instanceof AguiEvent.RunError error) {
            transition(current, AgentRunStatus.ERROR, List.of(), error.code(), error.message());
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
            transition(current, AgentRunStatus.AWAITING_CONFIRM, interrupts, null, null);
            return;
        }
        transition(current, AgentRunStatus.FINISHED, List.of(), null, null);
    }

    private void transition(AgentRunRecord current, AgentRunStatus target, List<AgentRunInterrupt> interrupts,
                            String errorCode, String errorMessage) {
        boolean allowed = switch (current.status()) {
            case CREATED -> target == AgentRunStatus.RUNNING || target == AgentRunStatus.ERROR;
            case RUNNING -> target == AgentRunStatus.AWAITING_CONFIRM || target.terminal();
            case AWAITING_CONFIRM -> target == AgentRunStatus.RESUMING;
            case RESUMING -> target == AgentRunStatus.RUNNING || target == AgentRunStatus.AWAITING_CONFIRM
                    || target.terminal();
            case FINISHED, ERROR, CANCELLED -> false;
        };
        if (!allowed) throw new IllegalStateException("非法 Run 状态转换: " + current.status() + " → " + target);
        runs.transition(current, target, interrupts, errorCode, errorMessage);
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
}
