package com.example.solution.domain;

import java.util.List;

public record PlanningDecision(
        PlanningDecisionType decisionType,
        String templateAnalysis,
        List<PlannedChapter> tasks,
        String selectedTaskKey,
        List<String> impactedTaskKeys,
        String reason) {

    public PlanningDecision {
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
        impactedTaskKeys = impactedTaskKeys == null ? List.of() : List.copyOf(impactedTaskKeys);
    }
}
