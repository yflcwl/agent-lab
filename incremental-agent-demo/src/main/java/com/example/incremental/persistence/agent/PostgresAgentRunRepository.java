package com.example.incremental.persistence.agent;

import com.example.incremental.persistence.agent.mapper.AgentRunMapper;
import com.example.incremental.runtime.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Repository
@ConditionalOnProperty(name = "demo.rag-store", havingValue = "pgvector")
public class PostgresAgentRunRepository implements AgentRunRepository {
    private final AgentRunMapper mapper;
    private final AgentHistoryService history;
    private final ObjectMapper json;

    public PostgresAgentRunRepository(AgentRunMapper mapper, AgentHistoryService history, ObjectMapper json) {
        this.mapper = mapper;
        this.history = history;
        this.json = json;
    }

    @Override
    @Transactional
    public void create(AgentRunContext run, String userId, String agentId, String title, String userMessage) {
        history.getOrCreateConversation(run.correlationId(), null, userId, agentId, title);
        var message = history.saveMessage(run.correlationId(), null, AgentMessageRole.USER,
                StringUtils.hasText(userMessage) ? userMessage : "继续", null, AgentMessageStatus.COMPLETED);
        Instant now = Instant.now();
        mapper.insert(new AgentRun(run.runId(), run.correlationId(), agentId, message.id(), AgentRunStatus.CREATED,
                null, null, null, null, now, now, run.correlationId(), run.threadId(), "[]", 0));
    }

    @Override
    @Transactional
    public void createRetry(String failedRunId, AgentRunContext run, String agentId) {
        AgentRun failed = mapper.selectById(failedRunId);
        if (failed == null || !run.correlationId().equals(failed.correlationId())) {
            throw new IllegalArgumentException("重试 Run 不属于原任务");
        }
        Instant now = Instant.now();
        mapper.insert(new AgentRun(run.runId(), failed.conversationId(), agentId, failed.triggerMessageId(),
                AgentRunStatus.CREATED, null, null, null, null, now, now,
                run.correlationId(), run.threadId(), "[]", 0));
    }

    @Override
    public AgentRunRecord find(String runId) {
        AgentRun run = mapper.selectById(runId);
        if (run == null) throw new IllegalArgumentException("Agent Run 不存在: " + runId);
        return record(run);
    }

    @Override
    public AgentRunRecord findAwaitingConfirmation(String correlationId) {
        AgentRun run = mapper.findAwaitingConfirmation(correlationId);
        return run == null ? null : record(run);
    }

    @Override
    @Transactional
    public void transition(AgentRunRecord expected, AgentRunStatus status, List<AgentRunInterrupt> interrupts,
                           String errorCode, String errorMessage) {
        Instant now = Instant.now();
        if (mapper.transition(expected.runId(), expected.lockVersion(), status, json(interrupts),
                errorCode, errorMessage, status == AgentRunStatus.RUNNING ? now : null,
                status.terminal() ? now : null, now) != 1) {
            throw new IllegalStateException("Run 状态已被其他请求修改，请重新读取");
        }
        history.saveRunEvent(expected.runId(), AgentRunEventType.RUN_STATE_CHANGED,
                json(Map.of("from", expected.status(), "to", status, "pendingInterrupts", interrupts,
                        "version", expected.lockVersion() + 1)), null, null, null);
    }

    private AgentRunRecord record(AgentRun run) {
        try {
            List<AgentRunInterrupt> interrupts = json.readValue(run.pendingInterruptsJson(), new TypeReference<>() {});
            return new AgentRunRecord(run.id(), run.correlationId(), run.threadId(), run.status(), interrupts,
                    run.createdAt(), run.updatedAt(), run.lockVersion());
        } catch (java.io.IOException error) {
            throw new IllegalStateException("读取 Run interrupt 失败: " + run.id(), error);
        }
    }

    private String json(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("序列化 Run 失败", error);
        }
    }
}
