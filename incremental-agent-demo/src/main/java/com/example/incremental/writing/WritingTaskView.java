package com.example.incremental.writing;

import java.time.Instant;
import java.util.List;

public record WritingTaskView(
        WritingTask task,
        List<String> sources,
        List<ContentEntry> contents,
        TaskStatus status,
        String activeRunId,
        Instant updatedAt) {

    public WritingTaskView(WritingTask task, List<String> sources, List<ContentEntry> contents) {
        this(task, sources, contents, null, null, task.createdAt());
    }
}

