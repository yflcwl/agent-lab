package com.example.incremental.persistence.agent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName(value = "agent_message", autoResultMap = true)
public class AgentMessage {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    @TableField("conversation_id")
    private String conversationId;
    @TableField("run_id")
    private String runId;
    private AgentMessageRole role;
    private String content;
    @TableField(value = "content_json", typeHandler = JsonbStringTypeHandler.class)
    private String contentJson;
    private AgentMessageStatus status;
    @TableField("sequence_no")
    private long sequenceNo;
    @TableField("created_at")
    private Instant createdAt;

    public AgentMessage() {
    }

    public AgentMessage(String id, String conversationId, String runId, AgentMessageRole role, String content,
                        String contentJson, AgentMessageStatus status, long sequenceNo, Instant createdAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.runId = runId;
        this.role = role;
        this.content = content;
        this.contentJson = contentJson;
        this.status = status;
        this.sequenceNo = sequenceNo;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public AgentMessageRole getRole() { return role; }
    public void setRole(AgentMessageRole role) { this.role = role; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContentJson() { return contentJson; }
    public void setContentJson(String contentJson) { this.contentJson = contentJson; }
    public AgentMessageStatus getStatus() { return status; }
    public void setStatus(AgentMessageStatus status) { this.status = status; }
    public long getSequenceNo() { return sequenceNo; }
    public void setSequenceNo(long sequenceNo) { this.sequenceNo = sequenceNo; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String id() { return id; }
    public String conversationId() { return conversationId; }
    public String runId() { return runId; }
    public AgentMessageRole role() { return role; }
    public String content() { return content; }
    public String contentJson() { return contentJson; }
    public AgentMessageStatus status() { return status; }
    public long sequenceNo() { return sequenceNo; }
    public Instant createdAt() { return createdAt; }
}
