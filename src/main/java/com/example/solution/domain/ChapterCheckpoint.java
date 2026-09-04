package com.example.solution.domain;

public record ChapterCheckpoint(Long chapterTaskId, int contentVersion, String partialContent) {
}
