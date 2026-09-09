package com.example.incremental.persistence.agent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.incremental.runtime.AgentRunStatus;

import java.time.Instant;

@TableName(value = "agent_run", autoResultMap = true)
public class AgentRun {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    @TableField("conversation_id")
    private String conversationId;
    @TableField("agent_id")
    private String agentId;
    @TableField("trigger_message_id")
    private String triggerMessageId;
    private AgentRunStatus status;
    @TableField("error_code")
    private String errorCode;
    @TableField("error_message")
    private String errorMessage;
    @TableField("started_at")
    private Instant startedAt;
    @TableField("finished_at")
    private Instant finishedAt;
    @TableField("created_at")
    private Instant createdAt;
    @TableField("updated_at")
    private Instant updatedAt;
    @TableField("correlation_id")
    private String correlationId;
    @TableField("thread_id")
    private String threadId;
    @TableField(value = "pending_interrupts", typeHandler = JsonbStringTypeHandler.class)
    private String pendingInterruptsJson;
    @TableField(value = "checkpoint", typeHandler = JsonbStringTypeHandler.class)
    private String checkpointJson;
    @TableField("lock_version")
    private long lockVersion;

    public AgentRun() {
    }

    public AgentRun(String id, String conversationId, String agentId, String triggerMessageId,
                    AgentRunStatus status, String errorCode, String errorMessage, Instant startedAt,
                    Instant finishedAt, Instant createdAt, Instant updatedAt, String correlationId,
                    String threadId, String pendingInterruptsJson, String checkpointJson, long lockVersion) {
        this.id = id;
        this.conversationId = conversationId;
        this.agentId = agentId;
        this.triggerMessageId = triggerMessageId;
        this.status = status;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.correlationId = correlationId;
        this.threadId = threadId;
        this.pendingInterruptsJson = pendingInterruptsJson;
        this.checkpointJson = checkpointJson;
        this.lockVersion = lockVersion;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getTriggerMessageId() { return triggerMessageId; }
    public void setTriggerMessageId(String triggerMessageId) { this.triggerMessageId = triggerMessageId; }
    public AgentRunStatus getStatus() { return status; }
    public void setStatus(AgentRunStatus status) { this.status = status; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public String getThreadId() { return threadId; }
    public void setThreadId(String threadId) { this.threadId = threadId; }
    public String getPendingInterruptsJson() { return pendingInterruptsJson; }
    public void setPendingInterruptsJson(String pendingInterruptsJson) { this.pendingInterruptsJson = pendingInterruptsJson; }
    public String getCheckpointJson() { return checkpointJson; }
    public void setCheckpointJson(String checkpointJson) { this.checkpointJson = checkpointJson; }
    public long getLockVersion() { return lockVersion; }
    public void setLockVersion(long lockVersion) { this.lockVersion = lockVersion; }

    public String id() { return id; }
    public String conversationId() { return conversationId; }
    public String agentId() { return agentId; }
    public String triggerMessageId() { return triggerMessageId; }
    public AgentRunStatus status() { return status; }
    public String errorCode() { return errorCode; }
    public String errorMessage() { return errorMessage; }
    public Instant startedAt() { return startedAt; }
    public Instant finishedAt() { return finishedAt; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public String correlationId() { return correlationId; }
    public String threadId() { return threadId; }
    public String pendingInterruptsJson() { return pendingInterruptsJson; }
    public String checkpointJson() { return checkpointJson; }
    public long lockVersion() { return lockVersion; }
}
