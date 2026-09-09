package com.example.incremental.runtime;

import com.example.incremental.writing.TaskStatus;
import com.example.incremental.writing.WritingTask;

public interface TaskControlRepository {

    TaskControl find(String taskId);

    TaskControl create(WritingTask task, String agentId, String title);

    TaskControl transition(TaskControl expected, TaskStatus status, String activeRunId);
}
