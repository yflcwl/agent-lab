package com.example.incremental.writing;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.util.StringUtils;

import java.time.Instant;

public record ChapterStage(
        String stageId,
        String taskId,
        ContentEntry content,
        String markdown,
        String chapterMemory,
        String documentState,
        String workingPlan,
        ChapterStageStatus status,
        Instant createdAt,
        Instant committedAt) {

    @JsonIgnore
    public boolean isComplete() {
        return content != null
                && StringUtils.hasText(markdown)
                && StringUtils.hasText(chapterMemory)
                && StringUtils.hasText(documentState)
                && StringUtils.hasText(workingPlan);
    }

    @JsonIgnore
    public boolean isReadyForReview() {
        return content != null && StringUtils.hasText(markdown);
    }

    public ChapterStage withChapterMemory(String value) {
        return new ChapterStage(stageId, taskId, content, markdown, value, documentState,
                workingPlan, status, createdAt, committedAt);
    }

    public ChapterStage withDocumentState(String value) {
        return new ChapterStage(stageId, taskId, content, markdown, chapterMemory, value,
                workingPlan, status, createdAt, committedAt);
    }

    public ChapterStage withWorkingPlan(String value) {
        return new ChapterStage(stageId, taskId, content, markdown, chapterMemory, documentState,
                value, status, createdAt, committedAt);
    }

    public ChapterStage withStatus(ChapterStageStatus value, Instant committedAt) {
        return new ChapterStage(stageId, taskId, content, markdown, chapterMemory, documentState,
                workingPlan, value, createdAt, committedAt);
    }
}

