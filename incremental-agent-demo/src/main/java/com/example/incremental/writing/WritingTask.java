package com.example.incremental.writing;

import java.time.Instant;

public record WritingTask(String id, String userId, String sessionId, Instant createdAt) {
}

