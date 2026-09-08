package com.example.incremental.persistence.agent;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName(value = "agent_run_event", autoResultMap = true)
public class AgentRunEvent {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;
    @TableField("run_id")
    private String runId;
    @TableField("conversation_id")
    private String conversationId;
    @TableField("sequence_no")
    private long sequenceNo;
    @TableField("event_type")
    private AgentRunEventType eventType;
    @TableField(typeHandler = JsonbStringTypeHandler.class)
    private String payload;
    @TableField("reply_id")
    private String replyId;
    @TableField("block_id")
    private String blockId;
    @TableField("tool_call_id")
    private String toolCallId;
    @TableField("created_at")
    private Instant createdAt;

    public AgentRunEvent() {
    }

    public AgentRunEvent(String id, String runId, String conversationId, long sequenceNo,
                         AgentRunEventType eventType, String payload, String replyId, String blockId,
                         String toolCallId, Instant createdAt) {
        this.id = id;
        this.runId = runId;
        this.conversationId = conversationId;
        this.sequenceNo = sequenceNo;
        this.eventType = eventType;
        this.payload = payload;
        this.replyId = replyId;
        this.blockId = blockId;
        this.toolCallId = toolCallId;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public long getSequenceNo() { return sequenceNo; }
    public void setSequenceNo(long sequenceNo) { this.sequenceNo = sequenceNo; }
    public AgentRunEventType getEventType() { return eventType; }
    public void setEventType(AgentRunEventType eventType) { this.eventType = eventType; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public String getReplyId() { return replyId; }
    public void setReplyId(String replyId) { this.replyId = replyId; }
    public String getBlockId() { return blockId; }
    public void setBlockId(String blockId) { this.blockId = blockId; }
    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String id() { return id; }
    public String runId() { return runId; }
    public String conversationId() { return conversationId; }
    public long sequenceNo() { return sequenceNo; }
    public AgentRunEventType eventType() { return eventType; }
    public String payload() { return payload; }
    public String replyId() { return replyId; }
    public String blockId() { return blockId; }
    public String toolCallId() { return toolCallId; }
    public Instant createdAt() { return createdAt; }
}
