package com.example.incremental.runtime;

import com.example.incremental.config.DemoProperties;
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
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Repository
public class AgentRunStore {

    private final ObjectMapper objectMapper;
    private final ObjectProvider<AgentHistoryService> agentHistoryServiceProvider;
    private final Path runDirectory;

    public AgentRunStore(
            ObjectMapper objectMapper,
            ObjectProvider<AgentHistoryService> agentHistoryServiceProvider,
            DemoProperties properties) {
        this.objectMapper = objectMapper;
        this.agentHistoryServiceProvider = agentHistoryServiceProvider;
        this.runDirectory = properties.getStateRoot().toAbsolutePath().normalize().resolve("runs");
    }

    public synchronized void create(AgentRunRecord record) {
        save(record);
    }

    public synchronized AgentRunRecord find(String runId) {
        Path file = file(runId);
        try {
            if (!Files.isRegularFile(file)) {
                throw new IllegalArgumentException("Agent Run 不存在: " + runId);
            }
            return objectMapper.readValue(file.toFile(), AgentRunRecord.class);
        } catch (IOException e) {
            throw new IllegalStateException("读取 Agent Run 失败", e);
        }
    }

    public synchronized AgentRunRecord findAwaitingConfirmation(String correlationId) {
        if (!Files.isDirectory(runDirectory)) {
            return null;
        }
        try (var files = Files.list(runDirectory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .map(this::read)
                    .filter(record -> correlationId.equals(record.correlationId()))
                    .filter(record -> record.status() == AgentRunStatus.AWAITING_CONFIRM
                            || record.status() == AgentRunStatus.RESUMING)
                    .filter(record -> !record.pendingInterrupts().isEmpty())
                    .max(Comparator.comparing(AgentRunRecord::updatedAt))
                    .orElse(null);
        } catch (IOException e) {
            throw new IllegalStateException("读取待确认 Agent Run 失败", e);
        }
    }

    public synchronized void update(String runId, AgentRunStatus status, List<AgentRunInterrupt> interrupts) {
        AgentRunRecord current = find(runId);
        save(new AgentRunRecord(current.runId(), current.correlationId(), current.threadId(), status,
                List.copyOf(interrupts), current.createdAt(), Instant.now()));
    }

    public RunHistory beginHistory(
            AgentRunContext run,
            String userId,
            String agentId,
            String title,
            String userMessage) {
        AgentHistoryService historyService = agentHistoryServiceProvider.getIfAvailable();
        if (historyService == null) {
            return null;
        }
        historyService.getOrCreateConversation(run.correlationId(), null, userId, agentId, title);
        String content = StringUtils.hasText(userMessage) ? userMessage : "继续";
        var message = historyService.saveMessage(run.correlationId(), null, AgentMessageRole.USER, content, null,
                AgentMessageStatus.COMPLETED);
        historyService.createRun(run.runId(), run.correlationId(), agentId, message.id());
        return new RunHistory(historyService, run.correlationId(), message.id(), run.runId());
    }

    public RunHistory beginRetryHistory(RunHistory history, AgentRunContext run, String agentId) {
        if (history == null) {
            return null;
        }
        history.historyService().createRun(run.runId(), history.conversationId(), agentId, history.triggerMessageId());
        return new RunHistory(history.historyService(), history.conversationId(), history.triggerMessageId(), run.runId());
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
                history.historyService().updateRunStatus(event.getRunId(),
                        com.example.incremental.persistence.agent.AgentRunStatus.RUNNING, null, null);
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
                persistAssistantMessage(history, event.getRunId(), assistantContent, AgentMessageStatus.FAILED);
                history.historyService().saveRunEvent(event.getRunId(), AgentRunEventType.RUN_FAILED,
                        eventPayload(event, "errorCode", error.code(), "errorMessage", error.message()),
                        null, null, null);
                history.historyService().updateRunStatus(event.getRunId(),
                        com.example.incremental.persistence.agent.AgentRunStatus.FAILED, error.code(), error.message());
                finalized.set(true);
                return;
            }
            if (event instanceof AguiEvent.RunFinished finished) {
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
                    history.historyService().updateRunStatus(event.getRunId(),
                            com.example.incremental.persistence.agent.AgentRunStatus.WAITING, null, null);
                } else {
                    history.historyService().saveRunEvent(event.getRunId(), AgentRunEventType.RUN_FINISHED,
                            eventPayload(event, "outcome", "COMPLETED"), null, null, null);
                    persistAssistantMessage(history, event.getRunId(), assistantContent, AgentMessageStatus.COMPLETED);
                    history.historyService().updateRunStatus(event.getRunId(),
                            com.example.incremental.persistence.agent.AgentRunStatus.COMPLETED, null, null);
                }
                finalized.set(true);
            }
        }).doOnError(error -> {
            if (finalized.compareAndSet(false, true)) {
                persistAssistantMessage(history, history.runId(), assistantContent, AgentMessageStatus.FAILED);
                history.historyService().saveRunEvent(history.runId(), AgentRunEventType.RUN_FAILED,
                        eventPayload(null, "errorMessage", safeMessage(error)), null, null, null);
                history.historyService().updateRunStatus(history.runId(),
                        com.example.incremental.persistence.agent.AgentRunStatus.FAILED,
                        "RUN_FAILED", safeMessage(error));
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

    private AgentRunRecord read(Path file) {
        try {
            return objectMapper.readValue(file.toFile(), AgentRunRecord.class);
        } catch (IOException e) {
            throw new IllegalStateException("读取 Agent Run 失败", e);
        }
    }

    private synchronized void save(AgentRunRecord record) {
        try {
            Files.createDirectories(runDirectory);
            Path target = file(record.runId());
            Path temporary = Files.createTempFile(runDirectory, "." + record.runId(), ".tmp");
            try {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), record);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException e) {
            throw new IllegalStateException("保存 Agent Run 失败", e);
        }
    }

    private Path file(String runId) {
        if (runId == null || !runId.matches("(?:run|writing)-[0-9a-fA-F-]+")) {
            throw new IllegalArgumentException("runId 不合法");
        }
        Path result = runDirectory.resolve(runId + ".json").normalize();
        if (!result.startsWith(runDirectory)) {
            throw new IllegalArgumentException("runId 不合法");
        }
        return result;
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
