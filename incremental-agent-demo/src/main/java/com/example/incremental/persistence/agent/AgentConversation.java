package com.example.incremental.persistence.agent;

import java.time.Instant;

public record AgentConversation(
        String id,
        String tenantId,
        String userId,
        String agentId,
        String title,
        ConversationStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
