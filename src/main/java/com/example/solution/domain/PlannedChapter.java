package com.example.solution.domain;

import java.util.List;

public record PlannedChapter(
        String templateNodeId,
        List<String> dependencies,
        int priority) {

    public PlannedChapter {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
    }
}
