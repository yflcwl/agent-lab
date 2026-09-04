package com.example.solution.domain;

import java.util.List;

public record ResolvedPlannedChapter(String templateNodeId, String title, String requirement,
                                     List<String> dependencies, int priority) {
}
