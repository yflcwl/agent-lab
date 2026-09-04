package com.example.solution.domain;

import java.time.LocalDateTime;

public record AgentTrace(Long id, Long writingTaskId, String actor, String eventType, String detail,
                         LocalDateTime createdAt) {
}
