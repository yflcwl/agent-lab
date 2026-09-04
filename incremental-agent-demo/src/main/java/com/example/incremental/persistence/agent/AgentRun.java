package com.example.incremental.persistence.agent;

import java.time.Instant;

public record AgentRun(
        String id,
        String conversationId,
        String agentId,
        String triggerMessageId,
        AgentRunStatus status,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt) {
}
