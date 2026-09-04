package com.example.solution.domain;

import java.util.List;

public record WritingTaskState(
        WritingTask task,
        List<TemplateSection> templateSections,
        List<DocumentOutlineNode> outlineNodes,
        List<ChapterTask> taskList,
        List<ChapterFeedback> pendingFeedback,
        List<SourceDocument> sourceDocuments,
        List<AgentTrace> traces,
        List<AgentRun> agentRuns,
        List<AgentRunEvent> agentRunEvents,
        List<TaskMessage> messages) {
}
