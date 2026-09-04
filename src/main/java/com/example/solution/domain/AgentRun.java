package com.example.solution.domain;

import java.time.LocalDateTime;

public record AgentRun(
        Long id,
        Long writingTaskId,
        Long chapterTaskId,
        String agentName,
        String runType,
        String status,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime endedAt) {
}
