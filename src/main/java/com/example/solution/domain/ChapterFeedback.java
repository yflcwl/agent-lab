package com.example.solution.domain;

import java.time.LocalDateTime;

public record ChapterFeedback(
        Long id,
        Long writingTaskId,
        Long chapterTaskId,
        ReviewDecision decision,
        String feedbackText,
        boolean processed,
        LocalDateTime createdAt) {
}
