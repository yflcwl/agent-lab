package com.example.solution.domain;

import java.time.LocalDateTime;

public record AgentRunEvent(
        Long id,
        Long agentRunId,
        String eventType,
        String toolCallId,
        String toolName,
        String detail,
        LocalDateTime createdAt) {
}
