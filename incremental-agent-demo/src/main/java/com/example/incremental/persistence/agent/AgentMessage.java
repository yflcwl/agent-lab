package com.example.incremental.persistence.agent;

import java.time.Instant;

public record AgentMessage(
        String id,
        String conversationId,
        String runId,
        AgentMessageRole role,
        String content,
        String contentJson,
        AgentMessageStatus status,
        long sequenceNo,
        Instant createdAt) {
}
