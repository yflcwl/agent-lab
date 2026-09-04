package com.example.solution.domain;

import java.time.LocalDateTime;

public record TaskMessage(
        Long id,
        Long writingTaskId,
        Long chapterTaskId,
        TaskMessageRole role,
        TaskMessageType messageType,
        String content,
        TaskMessageStatus status,
        Long relatedAgentRunId,
        LocalDateTime createdAt) {
}
