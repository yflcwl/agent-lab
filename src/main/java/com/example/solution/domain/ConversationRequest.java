package com.example.solution.domain;

import java.util.List;

public record ConversationRequest(
        Long writingTaskId,
        String goal,
        List<ChapterTask> taskList,
        List<SourceDocument> sourceDocuments,
        String userMessage) {
}
