package com.example.incremental.writing;

import java.util.List;

public record WritingTaskView(WritingTask task, List<String> sources, List<ContentEntry> contents) {
}

