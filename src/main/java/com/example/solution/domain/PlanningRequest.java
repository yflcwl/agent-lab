package com.example.solution.domain;

import java.util.List;

public record PlanningRequest(
        Long writingTaskId,
        String goal,
        String templateMarkdown,
        String templateAnalysis,
        List<TemplateSection> templateSections,
        List<ChapterTask> taskList,
        int planVersion,
        List<ChapterFeedback> pendingFeedback,
        List<SourceDocument> sourceDocuments,
        List<TaskMessage> planInstructions) {
}
