package com.example.incremental.runtime;

import java.time.Instant;
import java.util.List;

public record AgentRunRecord(
        String runId,
        String correlationId,
        String threadId,
        AgentRunStatus status,
        List<AgentRunInterrupt> pendingInterrupts,
        Instant createdAt,
        Instant updatedAt) {
}

