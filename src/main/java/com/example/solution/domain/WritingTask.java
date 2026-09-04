package com.example.solution.domain;

import java.time.LocalDateTime;

public record WritingTask(
        Long id,
        String goal,
        String templateMarkdown,
        String templateAnalysis,
        WritingTaskStatus status,
        int planVersion,
        long stateVersion,
        String lastDecision,
        String lastError,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
