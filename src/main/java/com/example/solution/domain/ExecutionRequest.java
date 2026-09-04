package com.example.solution.domain;

import java.util.List;

public record ExecutionRequest(
        Long writingTaskId,
        String goal,
        ChapterTask currentTask,
        List<ChapterContext> relatedChapters,
        String feedback,
        List<SourceDocument> sourceDocuments,
        String resumeContent,
        List<TaskMessage> chapterInstructions) {
}
