package com.example.incremental.persistence.agent;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
        AgentConversation existing = conversationMapper.selectById(conversationId);
        return existing == null ? createConversation(conversationId, tenantId, userId, agentId, title) : existing;
    }

    @Transactional(readOnly = true)
    public AgentRun findRun(String runId) {
        requireText(runId, "runId", 128);
        return runMapper.selectById(runId);
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
        AgentConversation conversation = conversationMapper.selectOne(
                Wrappers.<AgentConversation>lambdaQuery()
                        .eq(AgentConversation::getId, conversationId)
                        .last("FOR UPDATE"));
        if (conversation == null) {
            throw new IllegalArgumentException("Conversation 不存在: " + conversationId);
        }
        if (runId != null) {
            AgentRun run = runMapper.selectById(runId);
            if (run == null || !conversationId.equals(run.conversationId())) {
                throw new IllegalArgumentException("Run 不属于当前 Conversation");
            }
        }
        Instant now = Instant.now();
        AgentMessage lastMessage = messageMapper.selectOne(Wrappers.<AgentMessage>lambdaQuery()
                .select(AgentMessage::getSequenceNo)
                .eq(AgentMessage::getConversationId, conversationId)
                .orderByDesc(AgentMessage::getSequenceNo)
                .last("LIMIT 1"));
        AgentMessage message = new AgentMessage(
                UUID.randomUUID().toString(), conversationId, runId, role, content, contentJson,
                status, lastMessage == null ? 1 : lastMessage.sequenceNo() + 1, now);
        messageMapper.insert(message);
        conversation.setUpdatedAt(now);
        conversationMapper.updateById(conversation);
        return message;
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
        AgentRun run = runMapper.selectOne(Wrappers.<AgentRun>lambdaQuery()
                .eq(AgentRun::getId, runId)
                .last("FOR UPDATE"));
        if (run == null) {
            throw new IllegalArgumentException("Run 不存在: " + runId);
        }
        AgentRunEvent lastEvent = runEventMapper.selectOne(Wrappers.<AgentRunEvent>lambdaQuery()
                .select(AgentRunEvent::getSequenceNo)
                .eq(AgentRunEvent::getRunId, runId)
                .orderByDesc(AgentRunEvent::getSequenceNo)
                .last("LIMIT 1"));
        AgentRunEvent event = new AgentRunEvent(
                UUID.randomUUID().toString(), runId, run.conversationId(),
                lastEvent == null ? 1 : lastEvent.sequenceNo() + 1,
                eventType, payload, replyId, blockId, toolCallId, Instant.now());
        runEventMapper.insert(event);
        return event;
    }

    @Transactional(readOnly = true)
    public List<AgentMessage> findMessages(String conversationId, long offset, int limit) {
        requireText(conversationId, "conversationId", 64);
        validatePage(offset, limit);
        return messageMapper.selectList(Wrappers.<AgentMessage>lambdaQuery()
                .eq(AgentMessage::getConversationId, conversationId)
                .orderByAsc(AgentMessage::getSequenceNo)
                .last("LIMIT " + limit + " OFFSET " + offset));
    }

    @Transactional(readOnly = true)
    public AgentConversationHistory findHistory(String conversationId) {
        requireText(conversationId, "conversationId", 64);
        return new AgentConversationHistory(
                messageMapper.selectList(Wrappers.<AgentMessage>lambdaQuery()
                        .eq(AgentMessage::getConversationId, conversationId)
                        .orderByAsc(AgentMessage::getSequenceNo)
                        .last("LIMIT " + MAX_PAGE_SIZE)),
                runMapper.selectList(Wrappers.<AgentRun>lambdaQuery()
                        .eq(AgentRun::getConversationId, conversationId)
                        .orderByAsc(AgentRun::getCreatedAt)),
                runEventMapper.selectList(Wrappers.<AgentRunEvent>lambdaQuery()
                        .eq(AgentRunEvent::getConversationId, conversationId)
                        .orderByAsc(AgentRunEvent::getCreatedAt, AgentRunEvent::getSequenceNo)));
    }

    @Transactional(readOnly = true)
    public List<AgentConversation> findConversations(String tenantId, String userId, long offset, int limit) {
        if (tenantId != null) {
            requireText(tenantId, "tenantId", 64);
        }
        requireText(userId, "userId", 64);
        validatePage(offset, limit);
        var query = Wrappers.<AgentConversation>lambdaQuery()
                .eq(AgentConversation::getUserId, userId)
                .eq(AgentConversation::getStatus, ConversationStatus.ACTIVE);
        if (tenantId == null) {
            query.isNull(AgentConversation::getTenantId);
        } else {
            query.eq(AgentConversation::getTenantId, tenantId);
        }
        return conversationMapper.selectList(query.orderByDesc(AgentConversation::getUpdatedAt)
                .last("LIMIT " + limit + " OFFSET " + offset));
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

}
