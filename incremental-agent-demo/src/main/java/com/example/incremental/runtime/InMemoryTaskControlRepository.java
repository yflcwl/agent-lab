package com.example.incremental.runtime;

import com.example.incremental.writing.TaskStatus;
import com.example.incremental.writing.WritingTask;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Non-durable counterpart of agent_conversation for demo/test mode. */
@Repository
@ConditionalOnProperty(name = "demo.rag-store", havingValue = "in-memory")
public class InMemoryTaskControlRepository implements TaskControlRepository {
    private final Object lock = new Object();
    private final Map<String, TaskControl> tasks = new HashMap<>();

    @Override
    public TaskControl find(String taskId) {
        synchronized (lock) {
            return tasks.get(taskId);
        }
    }

    @Override
    public TaskControl create(WritingTask task, String agentId, String title) {
        synchronized (lock) {
            Instant now = Instant.now();
            return tasks.computeIfAbsent(task.id(), ignored -> new TaskControl(
                    task.id(), TaskStatus.RUNNING, null, null, task.createdAt(), now, 0));
        }
    }

    @Override
    public TaskControl transition(TaskControl expected, TaskStatus status, String activeRunId) {
        synchronized (lock) {
            TaskControl current = tasks.get(expected.taskId());
            if (current == null || current.lockVersion() != expected.lockVersion()) {
                throw new IllegalStateException("WritingTask 状态已被其他请求修改，请重新读取");
            }
            TaskControl updated = new TaskControl(expected.taskId(), status, activeRunId, expected.deletedAt(),
                    expected.createdAt(), Instant.now(), expected.lockVersion() + 1);
            tasks.put(expected.taskId(), updated);
            return updated;
        }
    }
}
