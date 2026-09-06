package com.example.incremental.runtime;

import com.example.incremental.persistence.agent.AgentConversationHistory;
import com.example.incremental.persistence.agent.AgentMessage;
import io.agentscope.core.agui.event.AguiEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AgentRunRuntime {

    private final AgentRunStore runStore;
    private final Set<String> activeCorrelations = ConcurrentHashMap.newKeySet();

    public AgentRunRuntime(AgentRunStore runStore) {
        this.runStore = runStore;
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

    public Flux<AguiEvent> start(AgentRunContext run, AgentRunStore.RunHistory history, Flux<AguiEvent> events) {
        runStore.create(new AgentRunRecord(run.runId(), run.correlationId(), run.threadId(), AgentRunStatus.RUNNING,
                List.of(), Instant.now(), Instant.now()));
        return continueRun(run, history, events);
    }

    public Flux<AguiEvent> continueRun(
            AgentRunContext run,
            AgentRunStore.RunHistory history,
            Flux<AguiEvent> events) {
        return runStore.recordEvents(history, events)
                .doOnNext(event -> transitionForEvent(run.runId(), event))
                .doOnError(error -> runStore.update(run.runId(), AgentRunStatus.ERROR, List.of()));
    }

    public AgentRunStore.RunHistory beginHistory(
            AgentRunContext run,
            String userId,
            String agentId,
            String title,
            String userMessage) {
        return runStore.beginHistory(run, userId, agentId, title, userMessage);
    }

    public AgentRunStore.RunHistory beginRetryHistory(
            AgentRunStore.RunHistory history,
            AgentRunContext run,
            String agentId) {
        return runStore.beginRetryHistory(history, run, agentId);
    }

    public AgentRunStore.RunHistory findHistory(String runId) {
        return runStore.findHistory(runId);
    }

    public AgentConversationHistory findConversationHistory(String conversationId) {
        return runStore.findConversationHistory(conversationId);
    }

    public List<AgentMessage> findMessages(String conversationId, long offset, int limit) {
        return runStore.findMessages(conversationId, offset, limit);
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
        return runStore.find(runId);
    }

    public AgentRunRecord findAwaitingConfirmation(String correlationId) {
        return runStore.findAwaitingConfirmation(correlationId);
    }

    public ResumeRequest prepareResume(String correlationId, String runId, List<AgentRunDecision> decisions) {
        AgentRunRecord current = runStore.find(runId);
        if (!correlationId.equals(current.correlationId())) {
            throw new IllegalArgumentException("Agent Run 不属于当前任务");
        }
        if (current.status() != AgentRunStatus.AWAITING_CONFIRM
                && current.status() != AgentRunStatus.RESUMING) {
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
        runStore.update(runId, AgentRunStatus.RESUMING, current.pendingInterrupts());
        return new ResumeRequest(new AgentRunContext(current.correlationId(), current.threadId(), current.runId()),
                current.pendingInterrupts());
    }

    public void restoreAwaitingConfirmation(String runId) {
        AgentRunRecord current = runStore.find(runId);
        runStore.update(runId, AgentRunStatus.AWAITING_CONFIRM, current.pendingInterrupts());
    }

    private void transitionForEvent(String runId, AguiEvent event) {
        if (event instanceof AguiEvent.RunError) {
            runStore.update(runId, AgentRunStatus.ERROR, List.of());
            return;
        }
        if (!(event instanceof AguiEvent.RunFinished finished)
                || runStore.find(runId).status() == AgentRunStatus.ERROR) {
            return;
        }
        if (finished.outcome() instanceof AguiEvent.RunFinishedInterruptOutcome outcome) {
            List<AgentRunInterrupt> interrupts = outcome.interrupts().stream()
                    .map(interrupt -> new AgentRunInterrupt(
                            interrupt.id(), interrupt.toolCallId(),
                            interrupt.metadata() == null ? null : (String) interrupt.metadata().get("toolName"),
                            interrupt.metadata() == null ? Map.of() : toolInput(interrupt.metadata())))
                    .toList();
            runStore.update(runId, AgentRunStatus.AWAITING_CONFIRM, interrupts);
            return;
        }
        runStore.update(runId, AgentRunStatus.FINISHED, List.of());
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
