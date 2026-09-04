package com.example.incremental.persistence.agent;

import java.time.Instant;

public record AgentRunEvent(
        String id,
        String runId,
        String conversationId,
        long sequenceNo,
        AgentRunEventType eventType,
        String payload,
        String replyId,
        String blockId,
        String toolCallId,
        Instant createdAt) {
}
