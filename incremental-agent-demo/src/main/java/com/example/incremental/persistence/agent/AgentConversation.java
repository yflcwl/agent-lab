package com.example.incremental.persistence.agent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.example.incremental.writing.TaskStatus;

import java.time.Instant;

@TableName("agent_conversation")
public class AgentConversation {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    @TableField("tenant_id")
    private String tenantId;
    @TableField("user_id")
    private String userId;
    @TableField("agent_id")
    private String agentId;
    private String title;
    private TaskStatus status;
    @TableField("active_run_id")
    private String activeRunId;
    @TableField("deleted_at")
    private Instant deletedAt;
    @TableField("lock_version")
    private long lockVersion;
    @TableField("created_at")
    private Instant createdAt;
    @TableField("updated_at")
    private Instant updatedAt;

    public AgentConversation() {
    }

    public AgentConversation(String id, String tenantId, String userId, String agentId, String title,
                             TaskStatus status, String activeRunId, Instant deletedAt,
                             Instant createdAt, Instant updatedAt, long lockVersion) {
        this.id = id;
        this.tenantId = tenantId;
        this.userId = userId;
        this.agentId = agentId;
        this.title = title;
        this.status = status;
        this.activeRunId = activeRunId;
        this.deletedAt = deletedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lockVersion = lockVersion;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public String getActiveRunId() { return activeRunId; }
    public void setActiveRunId(String activeRunId) { this.activeRunId = activeRunId; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getLockVersion() { return lockVersion; }
    public void setLockVersion(long lockVersion) { this.lockVersion = lockVersion; }

    public String id() { return id; }
    public String tenantId() { return tenantId; }
    public String userId() { return userId; }
    public String agentId() { return agentId; }
    public String title() { return title; }
    public TaskStatus status() { return status; }
    public String activeRunId() { return activeRunId; }
    public Instant deletedAt() { return deletedAt; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public long lockVersion() { return lockVersion; }
}
