package com.example.incremental.runtime;

import com.example.incremental.writing.TaskStatus;

import java.time.Instant;

/** Authoritative parent-task control state. */
public record TaskControl(
        String taskId,
        TaskStatus status,
        String activeRunId,
        Instant deletedAt,
        Instant createdAt,
        Instant updatedAt,
        long lockVersion) {
}
