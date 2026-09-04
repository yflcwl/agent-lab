package com.example.solution.domain;

import java.time.LocalDateTime;
import java.util.List;

public record ChapterTask(
        Long id,
        Long writingTaskId,
        Long outlineNodeId,
        String chapterKey,
        String title,
        String requirement,
        List<String> dependencies,
        int priority,
        ChapterTaskStatus status,
        ChapterTaskStatus previousStatus,
        String content,
        String summary,
        int contentVersion,
        LocalDateTime leaseUntil,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
