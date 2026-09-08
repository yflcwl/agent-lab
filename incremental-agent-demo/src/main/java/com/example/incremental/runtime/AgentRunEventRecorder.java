package com.example.incremental.runtime;

import com.example.incremental.persistence.agent.AgentConversationHistory;
import com.example.incremental.persistence.agent.AgentHistoryService;
import com.example.incremental.persistence.agent.AgentMessage;
import com.example.incremental.persistence.agent.AgentMessageRole;
import com.example.incremental.persistence.agent.AgentMessageStatus;
import com.example.incremental.persistence.agent.AgentRunEventType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.event.AguiEvent;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AgentRunEventRecorder {

    private final ObjectMapper objectMapper;
    private final ObjectProvider<AgentHistoryService> agentHistoryServiceProvider;

    public AgentRunEventRecorder(ObjectMapper objectMapper, ObjectProvider<AgentHistoryService> historyProvider) {
        this.objectMapper = objectMapper;
        this.agentHistoryServiceProvider = historyProvider;
    }

    public RunHistory findHistory(String runId) {
        AgentHistoryService historyService = agentHistoryServiceProvider.getIfAvailable();
        if (historyService == null) {
            return null;
        }
        var run = historyService.findRun(runId);
        return run == null ? null : new RunHistory(historyService, run.conversationId(), run.triggerMessageId(), run.id());
    }

    public AgentConversationHistory findConversationHistory(String conversationId) {
        AgentHistoryService historyService = agentHistoryServiceProvider.getIfAvailable();
        return historyService == null
                ? new AgentConversationHistory(List.of(), List.of(), List.of())
                : historyService.findHistory(conversationId);
    }

    public List<AgentMessage> findMessages(String conversationId, long offset, int limit) {
        AgentHistoryService historyService = agentHistoryServiceProvider.getIfAvailable();
        return historyService == null ? List.of() : historyService.findMessages(conversationId, offset, limit);
    }

    public Flux<AguiEvent> recordEvents(RunHistory history, Flux<AguiEvent> events) {
        if (history == null) {
            return events;
        }
        StringBuilder assistantContent = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        AtomicBoolean finalized = new AtomicBoolean();
        return events.doOnNext(event -> {
            if (event instanceof AguiEvent.TextMessageContent content) {
                assistantContent.append(content.delta());
                return;
            }
            if (event instanceof AguiEvent.ReasoningMessageContent content) {
                reasoning.append(content.delta());
                return;
            }
            if (event instanceof AguiEvent.RunStarted) {
                history.historyService().saveRunEvent(event.getRunId(), AgentRunEventType.RUN_STARTED,
                        eventPayload(event), null, null, null);
                return;
            }
            if (event instanceof AguiEvent.ToolCallStart toolCall) {
                history.historyService().saveRunEvent(event.getRunId(), AgentRunEventType.TOOL_CALL,
                        eventPayload(event, "toolName", toolCall.toolCallName()), null, null, toolCall.toolCallId());
                return;
            }
            if (event instanceof AguiEvent.ToolCallResult toolResult) {
                history.historyService().saveRunEvent(event.getRunId(), AgentRunEventType.TOOL_RESULT,
                        eventPayload(event, "content", toolResult.content()), null, null, toolResult.toolCallId());
                return;
            }
            if (event instanceof AguiEvent.RunError error) {
                persistAssistantMessage(history, event.getRunId(), failureContent(assistantContent, error.message()),
                        AgentMessageStatus.FAILED);
                history.historyService().saveRunEvent(event.getRunId(), AgentRunEventType.RUN_FAILED,
                        eventPayload(event, "errorCode", error.code(), "errorMessage", error.message()),
                        null, null, null);
                finalized.set(true);
                return;
            }
            if (event instanceof AguiEvent.RunFinished finished && !finalized.get()) {
                persistReasoningSummary(history, event.getRunId(), reasoning);
                if (finished.outcome() instanceof AguiEvent.RunFinishedInterruptOutcome outcome) {
                    outcome.interrupts().forEach(interrupt -> history.historyService().saveRunEvent(
                            event.getRunId(), AgentRunEventType.REQUIRE_CONFIRM,
                            eventPayload(event, "interruptId", interrupt.id(), "toolName",
                                    interrupt.metadata() == null ? null : interrupt.metadata().get("toolName")),
                            null, null, interrupt.toolCallId()));
                    history.historyService().saveRunEvent(event.getRunId(), AgentRunEventType.RUN_FINISHED,
                            eventPayload(event, "outcome", "WAITING"), null, null, null);
                    persistAssistantMessage(history, event.getRunId(), assistantContent, AgentMessageStatus.COMPLETED);
                } else {
                    history.historyService().saveRunEvent(event.getRunId(), AgentRunEventType.RUN_FINISHED,
                            eventPayload(event, "outcome", "COMPLETED"), null, null, null);
                    persistAssistantMessage(history, event.getRunId(), assistantContent, AgentMessageStatus.COMPLETED);
                }
                finalized.set(true);
            }
        }).doOnError(error -> {
            if (finalized.compareAndSet(false, true)) {
                persistAssistantMessage(history, history.runId(), failureContent(assistantContent, safeMessage(error)),
                        AgentMessageStatus.FAILED);
                history.historyService().saveRunEvent(history.runId(), AgentRunEventType.RUN_FAILED,
                        eventPayload(null, "errorMessage", safeMessage(error)), null, null, null);
            }
        });
    }

    private void persistReasoningSummary(RunHistory history, String runId, StringBuilder reasoning) {
        if (!reasoning.isEmpty()) {
            history.historyService().saveRunEvent(runId, AgentRunEventType.REASONING_SUMMARY,
                    eventPayload(null, "content", reasoning.toString()), null, null, null);
        }
    }

    private void persistAssistantMessage(
            RunHistory history, String runId, StringBuilder content, AgentMessageStatus status) {
        if (!content.isEmpty()) {
            history.historyService().saveMessage(history.conversationId(), runId,
                    AgentMessageRole.ASSISTANT, content.toString(), null, status);
        }
    }

    private StringBuilder failureContent(StringBuilder assistantContent, String errorMessage) {
        StringBuilder content = new StringBuilder(assistantContent);
        if (!content.isEmpty()) {
            content.append("\n\n");
        }
        return content.append("本轮执行失败：").append(errorMessage);
    }

    private String eventPayload(AguiEvent event, Object... fields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (event != null) {
            payload.put("type", event.getType().name());
            payload.put("threadId", event.getThreadId());
            payload.put("timestamp", event.timestamp());
        }
        for (int index = 0; index < fields.length; index += 2) {
            if (fields[index + 1] != null) {
                payload.put((String) fields[index], fields[index + 1]);
            }
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化 Agent Run 事件失败", e);
        }
    }

    private String safeMessage(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank() ? "未知错误" : error.getMessage();
    }

    public record RunHistory(
            AgentHistoryService historyService,
            String conversationId,
            String triggerMessageId,
            String runId) {
    }
}
