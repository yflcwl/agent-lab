package com.example.incremental.runtime;

import com.example.incremental.config.DemoProperties;
import com.example.incremental.runtime.AgentRunInterrupt;
import com.example.incremental.runtime.AgentRunDecision;
import com.example.incremental.runtime.AgentRunRecord;
import com.example.incremental.runtime.AgentRunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiResume;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Comparator;

@Service
public class AgentRunRuntime {

    private final ObjectMapper objectMapper;
    private final Path runDirectory;

    public AgentRunRuntime(ObjectMapper objectMapper, DemoProperties properties) {
        this.objectMapper = objectMapper;
        this.runDirectory = properties.getStateRoot().toAbsolutePath().normalize().resolve("runs");
    }

    public Flux<AguiEvent> track(String correlationId, String threadId, String runId, Flux<AguiEvent> events) {
        save(new AgentRunRecord(runId, correlationId, threadId, AgentRunStatus.RUNNING,
                List.of(), Instant.now(), Instant.now()));
        return events.doOnNext(event -> trackEvent(runId, event))
                .doOnError(error -> update(runId, AgentRunStatus.ERROR, List.of()));
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
                    .map(path -> read(path))
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

    public synchronized ResumeRequest prepareResume(
            String correlationId, String runId, List<AgentRunDecision> decisions) {
        AgentRunRecord current = find(runId);
        if (!correlationId.equals(current.correlationId())) {
            throw new IllegalArgumentException("Agent Run 不属于当前任务");
        }
        if (current.status() != AgentRunStatus.AWAITING_CONFIRM
                && current.status() != AgentRunStatus.RESUMING) {
            throw new IllegalStateException("Agent Run 当前不在等待确认状态");
        }
        if (decisions == null || decisions.isEmpty()) {
            throw new IllegalArgumentException("至少需要提供一个 toolCall 决策");
        }
        Map<String, AgentRunInterrupt> interruptsByToolCallId = new LinkedHashMap<>();
        current.pendingInterrupts().forEach(interrupt -> interruptsByToolCallId.put(interrupt.toolCallId(), interrupt));
        if (decisions.size() != interruptsByToolCallId.size()
                || decisions.stream().map(AgentRunDecision::toolCallId).anyMatch(id -> !interruptsByToolCallId.containsKey(id))
                || decisions.stream().map(AgentRunDecision::toolCallId).collect(java.util.stream.Collectors.toSet()).size()
                != decisions.size()) {
            throw new IllegalArgumentException("resume 必须为当前所有 pending tool call 各提供一次决策");
        }
        List<AguiResume> resume = decisions.stream().map(decision -> {
            AgentRunInterrupt interrupt = interruptsByToolCallId.get(decision.toolCallId());
            return new AguiResume(interrupt.interruptId(), decision.approved()
                    ? AguiResume.STATUS_RESOLVED : AguiResume.STATUS_CANCELLED,
                    Map.of("approved", decision.approved()));
        }).toList();
        Map<String, String> toolCallIds = new LinkedHashMap<>();
        current.pendingInterrupts().forEach(interrupt -> toolCallIds.put(interrupt.interruptId(), interrupt.toolCallId()));
        update(runId, AgentRunStatus.RESUMING, current.pendingInterrupts());
        return new ResumeRequest(current.threadId(), resume, Map.copyOf(toolCallIds));
    }

    public synchronized void restoreAwaitingConfirmation(String runId) {
        AgentRunRecord current = find(runId);
        update(runId, AgentRunStatus.AWAITING_CONFIRM, current.pendingInterrupts());
    }

    public synchronized void completeResume(String runId) {
        AgentRunRecord current = find(runId);
        if (current.status() != AgentRunStatus.RESUMING) {
            throw new IllegalStateException("Agent Run 当前不在恢复状态");
        }
        update(runId, AgentRunStatus.FINISHED, List.of());
    }

    private synchronized void trackEvent(String runId, AguiEvent event) {
        if (event instanceof AguiEvent.RunError) {
            update(runId, AgentRunStatus.ERROR, List.of());
            return;
        }
        if (!(event instanceof AguiEvent.RunFinished finished)) {
            return;
        }
        if (find(runId).status() == AgentRunStatus.ERROR) {
            return;
        }
        if (finished.outcome() instanceof AguiEvent.RunFinishedInterruptOutcome outcome) {
            List<AgentRunInterrupt> interrupts = outcome.interrupts().stream()
                    .map(interrupt -> new AgentRunInterrupt(
                            interrupt.id(),
                            interrupt.toolCallId(),
                            interrupt.metadata() == null ? null : (String) interrupt.metadata().get("toolName"),
                            interrupt.metadata() == null ? Map.of() : toolInput(interrupt.metadata())))
                    .toList();
            update(runId, AgentRunStatus.AWAITING_CONFIRM, interrupts);
            return;
        }
        update(runId, AgentRunStatus.FINISHED, List.of());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toolInput(Map<String, Object> metadata) {
        Object value = metadata.get("toolInput");
        return value instanceof Map<?, ?> input ? (Map<String, Object>) input : Map.of();
    }

    private void update(String runId, AgentRunStatus status, List<AgentRunInterrupt> interrupts) {
        AgentRunRecord current = find(runId);
        save(new AgentRunRecord(current.runId(), current.correlationId(), current.threadId(), status,
                List.copyOf(interrupts), current.createdAt(), Instant.now()));
    }

    private AgentRunRecord read(Path file) {
        try {
            return objectMapper.readValue(file.toFile(), AgentRunRecord.class);
        } catch (IOException e) {
            throw new IllegalStateException("读取 Agent Run 失败", e);
        }
    }

    public record ResumeRequest(
            String threadId,
            List<AguiResume> resume,
            Map<String, String> resumeToolCallIds) {
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
        if (runId == null || !runId.matches("writing-[0-9a-fA-F-]+")) {
            throw new IllegalArgumentException("runId 不合法");
        }
        Path result = runDirectory.resolve(runId + ".json").normalize();
        if (!result.startsWith(runDirectory)) {
            throw new IllegalArgumentException("runId 不合法");
        }
        return result;
    }
}

