package com.example.incremental.runtime;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Explicit non-durable storage for the existing in-memory demo/test mode. */
@Repository
@ConditionalOnProperty(name = "demo.rag-store", havingValue = "in-memory")
public class InMemoryAgentRunRepository implements AgentRunRepository {
    private final Map<String, AgentRunRecord> runs = new HashMap<>();

    @Override
    public synchronized void create(AgentRunContext run, String userId, String agentId, String title, String message) {
        Instant now = Instant.now();
        if (runs.putIfAbsent(run.runId(), new AgentRunRecord(run.runId(), run.correlationId(), run.threadId(),
                AgentRunStatus.CREATED, List.of(), null, now, now, 0)) != null) {
            throw new IllegalStateException("Run 已存在: " + run.runId());
        }
    }

    @Override
    public synchronized void createRetry(String failedRunId, AgentRunContext run, String agentId) {
        if (!find(failedRunId).correlationId().equals(run.correlationId())) {
            throw new IllegalArgumentException("重试 Run 不属于原任务");
        }
        create(run, null, agentId, null, null);
    }

    @Override
    public synchronized AgentRunRecord find(String runId) {
        AgentRunRecord run = runs.get(runId);
        if (run == null) throw new IllegalArgumentException("Agent Run 不存在: " + runId);
        return run;
    }

    @Override
    public synchronized AgentRunRecord findAwaitingConfirmation(String correlationId) {
        return runs.values().stream().filter(run -> correlationId.equals(run.correlationId())
                        && run.status() == AgentRunStatus.AWAITING_CONFIRM && !run.pendingInterrupts().isEmpty())
                .max(Comparator.comparing(AgentRunRecord::updatedAt)).orElse(null);
    }

    @Override
    public synchronized void transition(AgentRunRecord expected, AgentRunStatus status,
                                        List<AgentRunInterrupt> interrupts, RunCheckpoint checkpoint,
                                        String errorCode, String errorMessage) {
        if (find(expected.runId()).lockVersion() != expected.lockVersion()) {
            throw new IllegalStateException("Run 状态已被其他请求修改，请重新读取");
        }
        runs.put(expected.runId(), new AgentRunRecord(expected.runId(), expected.correlationId(), expected.threadId(),
                status, List.copyOf(interrupts), checkpoint,
                expected.createdAt(), Instant.now(), expected.lockVersion() + 1));
    }
}
