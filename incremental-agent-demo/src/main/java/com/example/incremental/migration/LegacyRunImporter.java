package com.example.incremental.migration;

import com.example.incremental.config.DemoProperties;
import com.example.incremental.persistence.agent.*;
import com.example.incremental.persistence.agent.mapper.AgentRunMapper;
import com.example.incremental.runtime.AgentRunRecord;
import com.example.incremental.runtime.AgentRunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** One-shot, offline import. Never used by the normal Runtime read/write path. */
public class LegacyRunImporter {
    private final AgentRunMapper runs;
    private final AgentHistoryService history;
    private final ObjectMapper json;
    private final DemoProperties properties;

    public LegacyRunImporter(AgentRunMapper runs, AgentHistoryService history, ObjectMapper json, DemoProperties properties) {
        this.runs = runs;
        this.history = history;
        this.json = json;
        this.properties = properties;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<String> importRuns(boolean apply) throws IOException {
        Path directory = properties.getStateRoot().toAbsolutePath().normalize().resolve("runs");
        if (!Files.isDirectory(directory)) return List.of("没有旧 Run JSON: " + directory);
        List<String> report = new ArrayList<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList()) {
                AgentRunRecord legacy = json.readValue(file.toFile(), AgentRunRecord.class);
                if (legacy.runId() == null || !file.getFileName().toString().equals(legacy.runId() + ".json")
                        || legacy.correlationId() == null || !legacy.correlationId().matches("[0-9a-fA-F-]{36}")
                        || legacy.threadId() == null || legacy.threadId().isBlank() || legacy.status() == null
                        || legacy.createdAt() == null || legacy.updatedAt() == null || legacy.pendingInterrupts() == null) {
                    throw new IllegalStateException("旧 Run 字段不完整或标识不一致: " + file.getFileName());
                }
                if ((legacy.status() == AgentRunStatus.AWAITING_CONFIRM || legacy.status() == AgentRunStatus.RESUMING)
                        && legacy.pendingInterrupts().isEmpty()) {
                    throw new IllegalStateException("待恢复 Run 缺少 interrupts: " + legacy.runId());
                }
                AgentRun existing = runs.selectById(legacy.runId());
                if (existing != null && !legacy.correlationId().equals(existing.conversationId())) {
                    throw new IllegalStateException("数据库 Run 与旧 JSON 的任务归属冲突: " + legacy.runId());
                }
                if (existing != null && (existing.threadId() != null || existing.lockVersion() != 0)) {
                    report.add("SKIP 已迁移或已由数据库管理: " + legacy.runId());
                    continue;
                }
                String interrupts = json.writeValueAsString(legacy.pendingInterrupts());
                // An interrupted resume is not proof that AgentScope consumed its confirmation.
                // Preserve it for inspection rather than silently making it executable again.
                if (existing == null) {
                    Path taskFile = properties.getDataRoot().toAbsolutePath().normalize()
                            .resolve("task-" + legacy.correlationId()).resolve("task.json");
                    var task = json.readTree(taskFile.toFile());
                    if (!legacy.correlationId().equals(task.path("id").asText()) || task.path("userId").asText().isBlank()) {
                        throw new IllegalStateException("无法确定旧 Run 用户归属: " + taskFile);
                    }
                    if (apply) {
                        history.getOrCreateConversation(legacy.correlationId(), null, task.path("userId").asText(),
                                "incremental-writing-agent", "写作任务 " + legacy.correlationId());
                        var message = history.saveMessage(legacy.correlationId(), null, AgentMessageRole.USER,
                                "[旧 Run 导入：原始触发消息不可用]", null, AgentMessageStatus.COMPLETED);
                        runs.insert(new AgentRun(legacy.runId(), legacy.correlationId(), "incremental-writing-agent",
                                message.id(), legacy.status(), null, null, null,
                                legacy.status().terminal() ? legacy.updatedAt() : null, legacy.createdAt(), legacy.updatedAt(),
                                legacy.correlationId(), legacy.threadId(), interrupts, 1));
                    }
                } else if (apply && runs.importLegacy(legacy.runId(), legacy.correlationId(), legacy.threadId(),
                        legacy.status(), interrupts, legacy.updatedAt(),
                        legacy.status().terminal() ? legacy.updatedAt() : null) != 1) {
                    throw new IllegalStateException("迁移期间 Run 已被其他请求修改: " + legacy.runId());
                }
                if (apply) {
                    history.saveRunEvent(legacy.runId(), AgentRunEventType.RUN_STATE_CHANGED,
                            json.writeValueAsString(Map.of("source", "legacy-run-json", "to", legacy.status(),
                                    "threadId", legacy.threadId(), "pendingInterrupts", legacy.pendingInterrupts())),
                            null, null, null);
                }
                report.add((apply ? "IMPORTED " : "WOULD_IMPORT ") + legacy.runId() + " " + legacy.status());
            }
        }
        return report;
    }
}
