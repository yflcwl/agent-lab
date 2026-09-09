package com.example.incremental.persistence.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.incremental.persistence.agent.mapper.AgentConversationMapper;
import com.example.incremental.runtime.TaskControl;
import com.example.incremental.runtime.TaskControlRepository;
import com.example.incremental.writing.TaskStatus;
import com.example.incremental.writing.WritingTask;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Repository
@ConditionalOnProperty(name = "demo.rag-store", havingValue = "pgvector")
public class PostgresTaskControlRepository implements TaskControlRepository {
    private final AgentConversationMapper mapper;
    private final AgentHistoryService history;

    public PostgresTaskControlRepository(AgentConversationMapper mapper, AgentHistoryService history) {
        this.mapper = mapper;
        this.history = history;
    }

    @Override
    public TaskControl find(String taskId) {
        AgentConversation conversation = mapper.selectById(taskId);
        return conversation == null ? null : record(conversation);
    }

    @Override
    @Transactional
    public TaskControl create(WritingTask task, String agentId, String title) {
        AgentConversation conversation = history.getOrCreateConversation(
                task.id(), null, task.userId(), agentId, title);
        if (conversation.deletedAt() != null) {
            throw new IllegalStateException("WritingTask 已删除: " + task.id());
        }
        return record(conversation);
    }

    @Override
    @Transactional
    public TaskControl transition(TaskControl expected, TaskStatus status, String activeRunId) {
        Instant now = Instant.now();
        var update = Wrappers.<AgentConversation>lambdaUpdate()
                .eq(AgentConversation::getId, expected.taskId())
                .eq(AgentConversation::getStatus, expected.status())
                .eq(AgentConversation::getLockVersion, expected.lockVersion())
                .isNull(AgentConversation::getDeletedAt)
                .set(AgentConversation::getStatus, status)
                .set(AgentConversation::getActiveRunId, activeRunId)
                .set(AgentConversation::getUpdatedAt, now)
                .set(AgentConversation::getLockVersion, expected.lockVersion() + 1);
        if (expected.activeRunId() == null) {
            update.isNull(AgentConversation::getActiveRunId);
        } else {
            update.eq(AgentConversation::getActiveRunId, expected.activeRunId());
        }
        if (mapper.update(null, update) != 1) {
            throw new IllegalStateException("WritingTask 状态已被其他请求修改，请重新读取");
        }
        return new TaskControl(expected.taskId(), status, activeRunId, null,
                expected.createdAt(), now, expected.lockVersion() + 1);
    }

    private TaskControl record(AgentConversation conversation) {
        return new TaskControl(conversation.id(), conversation.status(), conversation.activeRunId(),
                conversation.deletedAt(),
                conversation.createdAt(), conversation.updatedAt(), conversation.lockVersion());
    }
}
