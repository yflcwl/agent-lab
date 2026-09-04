package com.example.solution.infrastructure;

public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(long taskId) {
        super("方案任务不存在: " + taskId);
    }
}
