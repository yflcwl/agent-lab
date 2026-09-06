package com.example.incremental.persistence.agent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.incremental.runtime.AgentRunStatus;

import java.time.Instant;

@TableName(value = "agent_run", autoResultMap = true)
public record AgentRun(
        @TableId(value = "id", type = IdType.INPUT) String id,
        String conversationId,
        String agentId,
        String triggerMessageId,
        AgentRunStatus status,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt,
        String correlationId,
        String threadId,
        @TableField(value = "pending_interrupts", typeHandler = JsonbStringTypeHandler.class) String pendingInterruptsJson,
        long lockVersion) {
}
