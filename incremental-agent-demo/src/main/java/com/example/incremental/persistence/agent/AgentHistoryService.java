package com.example.incremental.persistence.agent;

import com.example.incremental.persistence.agent.mapper.AgentConversationMapper;
import com.example.incremental.persistence.agent.mapper.AgentMessageMapper;
import com.example.incremental.persistence.agent.mapper.AgentRunEventMapper;
import com.example.incremental.persistence.agent.mapper.AgentRunMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "demo.rag-store", havingValue = "pgvector")
public class AgentHistoryService {

    private static final int MAX_PAGE_SIZE = 200;

    private final AgentConversationMapper conversationMapper;
    private final AgentMessageMapper messageMapper;
    private final AgentRunMapper runMapper;
    private final AgentRunEventMapper runEventMapper;
    private final ObjectMapper objectMapper;

    public AgentHistoryService(
            AgentConversationMapper conversationMapper,
            AgentMessageMapper messageMapper,
            AgentRunMapper runMapper,
            AgentRunEventMapper runEventMapper,
            ObjectMapper objectMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.runMapper = runMapper;
        this.runEventMapper = runEventMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AgentConversation createConversation(String tenantId, String userId, String agentId, String title) {
        return createConversation(UUID.randomUUID().toString(), tenantId, userId, agentId, title);
    }

    @Transactional
    public AgentConversation createConversation(String conversationId, String tenantId, String userId, String agentId, String title) {
        requireText(conversationId, "conversationId", 64);
        requireText(userId, "userId", 64);
        requireText(agentId, "agentId", 64);
        requireText(title, "title", 500);
        if (tenantId != null) {
            requireText(tenantId, "tenantId", 64);
        }
        Instant now = Instant.now();
        AgentConversation conversation = new AgentConversation(
                conversationId, tenantId, userId, agentId, title,
                ConversationStatus.ACTIVE, now, now);
        conversationMapper.insert(conversation);
        return conversation;
    }

    @Transactional
    public AgentConversation getOrCreateConversation(
            String conversationId, String tenantId, String userId, String agentId, String title) {
        AgentConversation existing = conversationMapper.findById(conversationId);
        return existing == null ? createConversation(conversationId, tenantId, userId, agentId, title) : existing;
    }

    @Transactional(readOnly = true)
    public AgentRun findRun(String runId) {
        requireText(runId, "runId", 128);
        return runMapper.findById(runId);
    }

    @Transactional
    public AgentMessage saveMessage(
            String conversationId,
            String runId,
            AgentMessageRole role,
            String content,
            String contentJson,
            AgentMessageStatus status) {
        requireText(conversationId, "conversationId", 64);
        requireText(content, "content", Integer.MAX_VALUE);
        if (role == null || status == null) {
            throw new IllegalArgumentException("role 和 status 不能为空");
        }
        validateJson(contentJson, "contentJson");
        AgentConversation conversation = conversationMapper.lockById(conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("Conversation 不存在: " + conversationId);
        }
        if (runId != null) {
            AgentRun run = runMapper.findById(runId);
            if (run == null || !conversationId.equals(run.conversationId())) {
                throw new IllegalArgumentException("Run 不属于当前 Conversation");
            }
        }
        Instant now = Instant.now();
        AgentMessage message = new AgentMessage(
                UUID.randomUUID().toString(), conversationId, runId, role, content, contentJson,
                status, messageMapper.nextSequenceNo(conversationId), now);
        messageMapper.insert(message);
        conversationMapper.touch(conversationId, now);
        return message;
    }

    @Transactional
    public AgentRun createRun(String conversationId, String agentId, String triggerMessageId) {
        return createRun(UUID.randomUUID().toString(), conversationId, agentId, triggerMessageId);
    }

    @Transactional
    public AgentRun createRun(String runId, String conversationId, String agentId, String triggerMessageId) {
        requireText(runId, "runId", 128);
        requireText(conversationId, "conversationId", 64);
        requireText(agentId, "agentId", 64);
        requireText(triggerMessageId, "triggerMessageId", 64);
        AgentConversation conversation = conversationMapper.lockById(conversationId);
        AgentMessage triggerMessage = messageMapper.findById(triggerMessageId);
        if (conversation == null || triggerMessage == null
                || !conversationId.equals(triggerMessage.conversationId())
                || triggerMessage.role() != AgentMessageRole.USER) {
            throw new IllegalArgumentException("triggerMessage 必须是当前 Conversation 的用户消息");
        }
        Instant now = Instant.now();
        AgentRun run = new AgentRun(runId, conversationId, agentId, triggerMessageId,
                AgentRunStatus.CREATED, null, null, null, null, now, now);
        runMapper.insert(run);
        return run;
    }

    @Transactional
    public AgentRun updateRunStatus(String runId, AgentRunStatus status, String errorCode, String errorMessage) {
        requireText(runId, "runId", 128);
        if (status == null) {
            throw new IllegalArgumentException("status 不能为空");
        }
        AgentRun current = runMapper.lockById(runId);
        if (current == null) {
            throw new IllegalArgumentException("Run 不存在: " + runId);
        }
        Instant now = Instant.now();
        Instant startedAt = status == AgentRunStatus.RUNNING && current.startedAt() == null ? now : null;
        Instant finishedAt = isTerminal(status) ? now : null;
        runMapper.updateStatus(runId, status, errorCode, errorMessage, startedAt, finishedAt, now);
        return runMapper.findById(runId);
    }

    @Transactional
    public AgentRunEvent saveRunEvent(
            String runId,
            AgentRunEventType eventType,
            String payload,
            String replyId,
            String blockId,
            String toolCallId) {
        requireText(runId, "runId", 128);
        if (eventType == null) {
            throw new IllegalArgumentException("eventType 不能为空");
        }
        requireText(payload, "payload", Integer.MAX_VALUE);
        validateJson(payload, "payload");
        AgentRun run = runMapper.lockById(runId);
        if (run == null) {
            throw new IllegalArgumentException("Run 不存在: " + runId);
        }
        AgentRunEvent event = new AgentRunEvent(
                UUID.randomUUID().toString(), runId, run.conversationId(), runEventMapper.nextSequenceNo(runId),
                eventType, payload, replyId, blockId, toolCallId, Instant.now());
        runEventMapper.insert(event);
        return event;
    }

    @Transactional(readOnly = true)
    public List<AgentMessage> findMessages(String conversationId, long offset, int limit) {
        requireText(conversationId, "conversationId", 64);
        validatePage(offset, limit);
        return messageMapper.findByConversationId(conversationId, offset, limit);
    }

    @Transactional(readOnly = true)
    public List<AgentConversation> findConversations(String tenantId, String userId, long offset, int limit) {
        if (tenantId != null) {
            requireText(tenantId, "tenantId", 64);
        }
        requireText(userId, "userId", 64);
        validatePage(offset, limit);
        return conversationMapper.findActiveByUser(tenantId, userId, offset, limit);
    }

    private void validateJson(String json, String fieldName) {
        if (json == null) {
            return;
        }
        try {
            objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(fieldName + " 必须是合法 JSON", e);
        }
    }

    private void requireText(String value, String fieldName, int maxLength) {
        if (!StringUtils.hasText(value) || value.length() > maxLength) {
            throw new IllegalArgumentException(fieldName + " 不能为空且不能超过 " + maxLength + " 个字符");
        }
    }

    private void validatePage(long offset, int limit) {
        if (offset < 0 || limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("分页参数不合法");
        }
    }

    private boolean isTerminal(AgentRunStatus status) {
        return status == AgentRunStatus.COMPLETED
                || status == AgentRunStatus.FAILED
                || status == AgentRunStatus.CANCELLED;
    }
}
