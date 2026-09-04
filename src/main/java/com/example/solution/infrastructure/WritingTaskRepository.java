package com.example.solution.infrastructure;

import com.example.solution.domain.ChapterCheckpoint;
import com.example.solution.domain.ChapterFeedback;
import com.example.solution.domain.ChapterTask;
import com.example.solution.domain.ChapterTaskStatus;
import com.example.solution.domain.DocumentOutlineNode;
import com.example.solution.domain.ResolvedPlannedChapter;
import com.example.solution.domain.ReviewDecision;
import com.example.solution.domain.WritingTask;
import com.example.solution.domain.WritingTaskStatus;
import com.example.solution.domain.SourceDocument;
import com.example.solution.domain.AgentTrace;
import com.example.solution.domain.AgentRun;
import com.example.solution.domain.AgentRunEvent;
import com.example.solution.domain.TaskMessage;
import com.example.solution.domain.TaskMessageRole;
import com.example.solution.domain.TaskMessageStatus;
import com.example.solution.domain.TaskMessageType;
import com.example.solution.domain.TemplateSection;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class WritingTaskRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public WritingTaskRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public long createTask(String goal, String templateMarkdown) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO writing_task (goal, template_markdown, status, created_at, updated_at)
                    VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """, new String[]{"id"});
            statement.setString(1, goal);
            statement.setString(2, templateMarkdown);
            statement.setString(3, WritingTaskStatus.RUNNING.name());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建方案任务失败");
        }
        return key.longValue();
    }

    public WritingTask findTask(long taskId) {
        return jdbcTemplate.query("SELECT * FROM writing_task WHERE id = ?", taskMapper(), taskId)
                .stream().findFirst().orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    public List<ChapterTask> findChapters(long taskId) {
        return jdbcTemplate.query("""
                SELECT chapter.*, binding.outline_node_id
                FROM chapter_task chapter
                LEFT JOIN chapter_task_outline binding ON binding.chapter_task_id = chapter.id
                WHERE chapter.writing_task_id = ?
                ORDER BY chapter.priority_value, chapter.id
                """, chapterMapper(), taskId);
    }

    public ChapterTask findChapter(long taskId, long chapterTaskId) {
        return jdbcTemplate.query("""
                SELECT chapter.*, binding.outline_node_id FROM chapter_task chapter
                LEFT JOIN chapter_task_outline binding ON binding.chapter_task_id = chapter.id
                WHERE chapter.writing_task_id = ? AND chapter.id = ?
                """, chapterMapper(), taskId, chapterTaskId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("章节任务不存在: " + chapterTaskId));
    }

    public ChapterTask findChapterByKey(long taskId, String chapterKey) {
        return jdbcTemplate.query("""
                SELECT chapter.*, binding.outline_node_id FROM chapter_task chapter
                LEFT JOIN chapter_task_outline binding ON binding.chapter_task_id = chapter.id
                WHERE chapter.writing_task_id = ? AND chapter.chapter_key = ?
                """, chapterMapper(), taskId, chapterKey).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("章节任务不存在: " + chapterKey));
    }

    public List<ChapterFeedback> findPendingFeedback(long taskId) {
        return jdbcTemplate.query("""
                SELECT * FROM chapter_feedback WHERE writing_task_id = ? AND processed = FALSE ORDER BY id
                """, feedbackMapper(), taskId);
    }

    public void createTemplateOutline(long taskId, List<TemplateSection> templateSections) {
        Deque<DocumentOutlineNode> parents = new ArrayDeque<>();
        for (int index = 0; index < templateSections.size(); index++) {
            TemplateSection section = templateSections.get(index);
            int displayOrder = index;
            while (!parents.isEmpty() && parents.peek().level() >= section.level()) {
                parents.pop();
            }
            Long parentNodeId = parents.isEmpty() ? null : parents.peek().id();
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO document_outline_node
                        (writing_task_id, parent_node_id, node_key, template_node_id, level_value, title, requirement,
                        display_order, origin, locked, outline_version, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                        """, new String[]{"id"});
                statement.setLong(1, taskId);
                if (parentNodeId == null) statement.setNull(2, java.sql.Types.BIGINT); else statement.setLong(2, parentNodeId);
                statement.setString(3, "outline-" + section.nodeId());
                statement.setString(4, section.nodeId());
                statement.setInt(5, section.level());
                statement.setString(6, section.title());
                statement.setString(7, section.requirement());
                statement.setInt(8, displayOrder);
                statement.setString(9, "TEMPLATE");
                statement.setBoolean(10, true);
                statement.setInt(11, 1);
                return statement;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key == null) throw new IllegalStateException("创建动态大纲节点失败");
            parents.push(new DocumentOutlineNode(key.longValue(), taskId, parentNodeId, "outline-" + section.nodeId(),
                    section.nodeId(), section.level(), section.title(), section.requirement(), displayOrder, "TEMPLATE", true, 1, null));
        }
    }

    public List<DocumentOutlineNode> findOutlineNodes(long taskId) {
        return jdbcTemplate.query("""
                SELECT * FROM document_outline_node WHERE writing_task_id = ? ORDER BY display_order, id
                """, (rs, rowNum) -> new DocumentOutlineNode(rs.getLong("id"), rs.getLong("writing_task_id"),
                rs.getObject("parent_node_id", Long.class), rs.getString("node_key"), rs.getString("template_node_id"),
                rs.getInt("level_value"), rs.getString("title"), rs.getString("requirement"), rs.getInt("display_order"),
                rs.getString("origin"), rs.getBoolean("locked"), rs.getInt("outline_version"),
                rs.getTimestamp("created_at").toLocalDateTime()), taskId);
    }

    public SourceDocument addSource(long taskId, String filename, String storedPath, String type, long size, String hash,
                                    String extractionStatus, String extractionError) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO source_document (writing_task_id, original_filename, stored_path, file_type, file_size,
                    file_hash, extraction_status, extraction_error, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, new String[]{"id"});
            statement.setLong(1, taskId); statement.setString(2, filename); statement.setString(3, storedPath);
            statement.setString(4, type); statement.setLong(5, size); statement.setString(6, hash);
            statement.setString(7, extractionStatus); statement.setString(8, extractionError);
            return statement;
        }, keyHolder);
        return findSource(taskId, keyHolder.getKey().longValue());
    }

    public List<SourceDocument> findSources(long taskId) {
        return jdbcTemplate.query("SELECT * FROM source_document WHERE writing_task_id = ? ORDER BY id", sourceMapper(), taskId);
    }

    public SourceDocument findSource(long taskId, long sourceId) {
        return jdbcTemplate.query("SELECT * FROM source_document WHERE writing_task_id = ? AND id = ?", sourceMapper(), taskId, sourceId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("资料不存在: " + sourceId));
    }

    public void recordTrace(long taskId, String actor, String eventType, String detail) {
        jdbcTemplate.update("""
                INSERT INTO agent_trace (writing_task_id, actor, event_type, detail, created_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, taskId, actor, eventType, detail);
    }

    public List<AgentTrace> findTraces(long taskId) {
        return jdbcTemplate.query("SELECT * FROM agent_trace WHERE writing_task_id = ? ORDER BY id DESC LIMIT 100", (rs, rowNum) ->
                new AgentTrace(rs.getLong("id"), rs.getLong("writing_task_id"), rs.getString("actor"),
                        rs.getString("event_type"), rs.getString("detail"), rs.getTimestamp("created_at").toLocalDateTime()), taskId);
    }

    public long startAgentRun(long taskId, Long chapterTaskId, String agentName, String runType) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO agent_run (writing_task_id, chapter_task_id, agent_name, run_type, status, started_at)
                    VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, new String[]{"id"});
            statement.setLong(1, taskId);
            if (chapterTaskId == null) {
                statement.setNull(2, java.sql.Types.BIGINT);
            } else {
                statement.setLong(2, chapterTaskId);
            }
            statement.setString(3, agentName);
            statement.setString(4, runType);
            statement.setString(5, "RUNNING");
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("创建 Agent 运行记录失败");
        }
        return key.longValue();
    }

    public void finishAgentRun(long runId, String status, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE agent_run SET status = ?, error_message = ?, ended_at = CURRENT_TIMESTAMP WHERE id = ?
                """, status, errorMessage, runId);
    }

    public void recordAgentRunEvent(long runId, String eventType, String toolCallId, String toolName, String detail) {
        jdbcTemplate.update("""
                INSERT INTO agent_run_event (agent_run_id, event_type, tool_call_id, tool_name, detail, created_at)
                VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, runId, eventType, toolCallId, toolName, detail);
    }

    public List<AgentRun> findAgentRuns(long taskId) {
        return jdbcTemplate.query("""
                SELECT * FROM agent_run WHERE writing_task_id = ? ORDER BY id DESC
                """, (rs, rowNum) -> new AgentRun(rs.getLong("id"), rs.getLong("writing_task_id"),
                rs.getObject("chapter_task_id", Long.class), rs.getString("agent_name"), rs.getString("run_type"),
                rs.getString("status"), rs.getString("error_message"),
                rs.getTimestamp("started_at").toLocalDateTime(),
                rs.getTimestamp("ended_at") == null ? null : rs.getTimestamp("ended_at").toLocalDateTime()), taskId);
    }

    public List<AgentRunEvent> findAgentRunEvents(long taskId) {
        return jdbcTemplate.query("""
                SELECT agent_event.* FROM agent_run_event agent_event
                JOIN agent_run agent_run_row ON agent_run_row.id = agent_event.agent_run_id
                WHERE agent_run_row.writing_task_id = ?
                ORDER BY agent_event.id DESC
                """, (rs, rowNum) -> new AgentRunEvent(rs.getLong("id"), rs.getLong("agent_run_id"),
                rs.getString("event_type"), rs.getString("tool_call_id"), rs.getString("tool_name"),
                rs.getString("detail"), rs.getTimestamp("created_at").toLocalDateTime()), taskId);
    }

    public long addTaskMessage(long taskId, Long chapterTaskId, TaskMessageRole role, TaskMessageType messageType,
                               String content, TaskMessageStatus status, Long relatedAgentRunId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO task_message (writing_task_id, chapter_task_id, role, message_type, content, status,
                    related_agent_run_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """, new String[]{"id"});
            statement.setLong(1, taskId);
            if (chapterTaskId == null) statement.setNull(2, java.sql.Types.BIGINT); else statement.setLong(2, chapterTaskId);
            statement.setString(3, role.name());
            statement.setString(4, messageType.name());
            statement.setString(5, content);
            statement.setString(6, status.name());
            if (relatedAgentRunId == null) statement.setNull(7, java.sql.Types.BIGINT); else statement.setLong(7, relatedAgentRunId);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) throw new IllegalStateException("保存任务消息失败");
        return key.longValue();
    }

    public List<TaskMessage> findTaskMessages(long taskId) {
        return jdbcTemplate.query("SELECT * FROM task_message WHERE writing_task_id = ? ORDER BY id", taskMessageMapper(), taskId);
    }

    public List<TaskMessage> findQueuedMessages(long taskId) {
        return jdbcTemplate.query("""
                SELECT * FROM task_message WHERE writing_task_id = ? AND role = ? AND status = ? ORDER BY id
                """, taskMessageMapper(), taskId, TaskMessageRole.USER.name(), TaskMessageStatus.QUEUED.name());
    }

    public void updateTaskMessageStatus(long messageId, TaskMessageStatus status, Long relatedAgentRunId) {
        jdbcTemplate.update("UPDATE task_message SET status = ?, related_agent_run_id = ? WHERE id = ?",
                status.name(), relatedAgentRunId, messageId);
    }

    public void requestRevisionFromMessage(long taskId, long chapterTaskId, String feedback) {
        int updated = jdbcTemplate.update("""
                UPDATE chapter_task SET status = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND writing_task_id = ? AND status IN (?, ?)
                """, ChapterTaskStatus.REVISING.name(), chapterTaskId, taskId,
                ChapterTaskStatus.WAITING_REVIEW.name(), ChapterTaskStatus.COMPLETED.name());
        if (updated != 1) {
            throw new IllegalStateException("当前章节尚未到可应用修改指令的安全状态");
        }
        jdbcTemplate.update("""
                INSERT INTO chapter_feedback
                (writing_task_id, chapter_task_id, decision, feedback_text, processed, created_at)
                VALUES (?, ?, ?, ?, FALSE, CURRENT_TIMESTAMP)
                """, taskId, chapterTaskId, ReviewDecision.REVISE.name(), feedback);
        jdbcTemplate.update("""
                UPDATE writing_task SET status = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, WritingTaskStatus.RUNNING.name(), taskId);
        touchTask(taskId, null, null);
    }

    public void createPlan(long taskId, String templateAnalysis, List<ResolvedPlannedChapter> chapters) {
        Map<String, Long> outlineIds = new HashMap<>();
        for (DocumentOutlineNode outlineNode : findOutlineNodes(taskId)) {
            outlineIds.put(outlineNode.templateNodeId(), outlineNode.id());
        }
        for (ResolvedPlannedChapter chapter : chapters) {
            Long outlineNodeId = outlineIds.get(chapter.templateNodeId());
            if (outlineNodeId == null) throw new IllegalStateException("章节没有对应的动态大纲节点: " + chapter.templateNodeId());
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbcTemplate.update(connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO chapter_task
                        (writing_task_id, chapter_key, title, requirement, dependencies, priority_value, status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """, new String[]{"id"});
                statement.setLong(1, taskId);
                statement.setString(2, chapter.templateNodeId());
                statement.setString(3, chapter.title());
                statement.setString(4, chapter.requirement());
                statement.setString(5, writeDependencies(chapter.dependencies()));
                statement.setInt(6, chapter.priority());
                statement.setString(7, ChapterTaskStatus.NOT_STARTED.name());
                return statement;
            }, keyHolder);
            Number chapterTaskId = keyHolder.getKey();
            if (chapterTaskId == null) throw new IllegalStateException("创建章节任务失败");
            jdbcTemplate.update("INSERT INTO chapter_task_outline (chapter_task_id, outline_node_id) VALUES (?, ?)",
                    chapterTaskId.longValue(), outlineNodeId);
        }
        jdbcTemplate.update("""
                UPDATE writing_task SET template_analysis = ?, plan_version = plan_version + 1,
                state_version = state_version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, templateAnalysis, taskId);
    }

    public void updatePlan(long taskId, String templateAnalysis, List<ResolvedPlannedChapter> chapters) {
        for (ResolvedPlannedChapter chapter : chapters) {
            jdbcTemplate.update("""
                    UPDATE chapter_task SET title = ?, requirement = ?, dependencies = ?, priority_value = ?,
                    updated_at = CURRENT_TIMESTAMP
                    WHERE writing_task_id = ? AND chapter_key = ? AND status <> ?
                    """, chapter.title(), chapter.requirement(), writeDependencies(chapter.dependencies()), chapter.priority(),
                    taskId, chapter.templateNodeId(), ChapterTaskStatus.COMPLETED.name());
        }
        jdbcTemplate.update("""
                UPDATE writing_task SET template_analysis = COALESCE(?, template_analysis),
                plan_version = plan_version + 1, state_version = state_version + 1,
                updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, templateAnalysis, taskId);
    }

    public boolean beginExecution(long taskId, long chapterTaskId, Duration leaseDuration) {
        LocalDateTime leaseUntil = LocalDateTime.now().plus(leaseDuration);
        int updated = jdbcTemplate.update("""
                UPDATE chapter_task SET previous_status = status, status = ?, lease_until = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND writing_task_id = ? AND status IN (?, ?)
                """, ChapterTaskStatus.EXECUTING.name(), Timestamp.valueOf(leaseUntil), chapterTaskId, taskId,
                ChapterTaskStatus.NOT_STARTED.name(), ChapterTaskStatus.REVISING.name());
        if (updated == 1) {
            touchTask(taskId, null, null);
        }
        return updated == 1;
    }

    public void saveExecutionSuccess(long taskId, ChapterTask chapter, String content, String summary) {
        int nextVersion = chapter.contentVersion() + 1;
        jdbcTemplate.update("""
                INSERT INTO chapter_version (chapter_task_id, version_no, content, summary, created_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, chapter.id(), nextVersion, content, summary);
        jdbcTemplate.update("""
                UPDATE chapter_task SET status = ?, previous_status = NULL, content = ?, summary = ?,
                content_version = ?, lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND writing_task_id = ? AND status = ?
                """, ChapterTaskStatus.WAITING_REVIEW.name(), content, summary, nextVersion,
                chapter.id(), taskId, ChapterTaskStatus.EXECUTING.name());
        deleteCheckpoint(taskId, chapter.id());
        touchTask(taskId, null, null);
    }

    public void saveExecutionFailure(long taskId, long chapterTaskId, String errorMessage) {
        jdbcTemplate.update("""
                UPDATE chapter_task SET status = COALESCE(previous_status, ?), previous_status = NULL,
                lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND writing_task_id = ? AND status = ?
                """, ChapterTaskStatus.NOT_STARTED.name(), chapterTaskId, taskId, ChapterTaskStatus.EXECUTING.name());
        touchTask(taskId, null, errorMessage);
    }

    public void approveChapter(long taskId, long chapterTaskId) {
        int updated = jdbcTemplate.update("""
                UPDATE chapter_task SET status = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND writing_task_id = ? AND status = ?
                """, ChapterTaskStatus.COMPLETED.name(), chapterTaskId, taskId,
                ChapterTaskStatus.WAITING_REVIEW.name());
        if (updated != 1) {
            throw new IllegalStateException("只有等待审核的章节才能通过");
        }
        jdbcTemplate.update("""
                INSERT INTO chapter_feedback (writing_task_id, chapter_task_id, decision, processed, created_at)
                VALUES (?, ?, ?, TRUE, CURRENT_TIMESTAMP)
                """, taskId, chapterTaskId, ReviewDecision.APPROVE.name());
        touchTask(taskId, null, null);
    }

    public void requestRevision(long taskId, long chapterTaskId, String feedback) {
        int updated = jdbcTemplate.update("""
                UPDATE chapter_task SET status = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND writing_task_id = ? AND status = ?
                """, ChapterTaskStatus.REVISING.name(), chapterTaskId, taskId,
                ChapterTaskStatus.WAITING_REVIEW.name());
        if (updated != 1) {
            throw new IllegalStateException("只有等待审核的章节才能要求修改");
        }
        jdbcTemplate.update("""
                INSERT INTO chapter_feedback
                (writing_task_id, chapter_task_id, decision, feedback_text, processed, created_at)
                VALUES (?, ?, ?, ?, FALSE, CURRENT_TIMESTAMP)
                """, taskId, chapterTaskId, ReviewDecision.REVISE.name(), feedback);
        touchTask(taskId, null, null);
    }

    public void markFeedbackProcessed(long taskId) {
        jdbcTemplate.update("UPDATE chapter_feedback SET processed = TRUE WHERE writing_task_id = ? AND processed = FALSE", taskId);
    }

    public void recordDecision(long taskId, int planVersion, String decisionType, String rawResponse,
                               String structuredDecision, boolean valid, String validationError, String reason) {
        jdbcTemplate.update("""
                INSERT INTO planning_decision
                (writing_task_id, plan_version, decision_type, raw_response, structured_decision, valid, validation_error, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, taskId, planVersion, decisionType, rawResponse, structuredDecision, valid, validationError);
        touchTask(taskId, reason, valid ? null : validationError);
    }

    public void completeTask(long taskId) {
        jdbcTemplate.update("""
                UPDATE writing_task SET status = ?, state_version = state_version + 1,
                updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, WritingTaskStatus.COMPLETED.name(), taskId);
    }

    public void recordTaskError(long taskId, String errorMessage) {
        touchTask(taskId, null, errorMessage);
    }

    public List<Long> findRunningTaskIds() {
        return jdbcTemplate.queryForList("SELECT id FROM writing_task WHERE status = ?", Long.class,
                WritingTaskStatus.RUNNING.name());
    }

    public List<Long> findExecutingTaskIds() {
        return jdbcTemplate.queryForList("""
                SELECT DISTINCT chapter.writing_task_id FROM chapter_task chapter
                JOIN writing_task task ON task.id = chapter.writing_task_id
                WHERE chapter.status = ? AND task.status = ?
                """, Long.class, ChapterTaskStatus.EXECUTING.name(), WritingTaskStatus.RUNNING.name());
    }

    public List<Long> findFinishedRunningTaskIds() {
        return jdbcTemplate.queryForList("""
                SELECT id FROM writing_task task
                WHERE status = ?
                AND EXISTS (SELECT 1 FROM chapter_task chapter WHERE chapter.writing_task_id = task.id)
                AND NOT EXISTS (SELECT 1 FROM chapter_task chapter
                                WHERE chapter.writing_task_id = task.id AND chapter.status <> ?)
                """, Long.class, WritingTaskStatus.RUNNING.name(), ChapterTaskStatus.COMPLETED.name());
    }

    public void recoverExpiredExecutions() {
        // 单实例重启后，任何 EXECUTING 章节都必然是中断的僵尸，回滚到进入执行前的状态，
        // 保留 chapter_checkpoint 中的部分正文供续写。
        jdbcTemplate.update("""
                UPDATE chapter_task SET status = COALESCE(previous_status, ?), previous_status = NULL,
                lease_until = NULL, updated_at = CURRENT_TIMESTAMP
                WHERE status = ?
                """, ChapterTaskStatus.NOT_STARTED.name(), ChapterTaskStatus.EXECUTING.name());
    }

    public void deleteLegacyCheckpoints() {
        jdbcTemplate.update("DELETE FROM chapter_checkpoint WHERE partial_content NOT LIKE ?", "CONTENT:%");
    }

    public void upsertCheckpoint(long taskId, long chapterTaskId, int contentVersion, String partialContent) {
        jdbcTemplate.update("""
                INSERT INTO chapter_checkpoint (writing_task_id, chapter_task_id, content_version, partial_content, updated_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE content_version = ?, partial_content = ?, updated_at = CURRENT_TIMESTAMP
                """, taskId, chapterTaskId, contentVersion, partialContent, contentVersion, partialContent);
    }

    public Optional<ChapterCheckpoint> findCheckpoint(long taskId, long chapterTaskId) {
        return jdbcTemplate.query("""
                SELECT * FROM chapter_checkpoint WHERE writing_task_id = ? AND chapter_task_id = ?
                """, checkpointMapper(), taskId, chapterTaskId).stream().findFirst();
    }

    public void deleteCheckpoint(long taskId, long chapterTaskId) {
        jdbcTemplate.update("DELETE FROM chapter_checkpoint WHERE writing_task_id = ? AND chapter_task_id = ?",
                taskId, chapterTaskId);
    }

    private void touchTask(long taskId, String decision, String error) {
        jdbcTemplate.update("""
                UPDATE writing_task SET last_decision = COALESCE(?, last_decision), last_error = ?,
                state_version = state_version + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?
                """, decision, error, taskId);
    }

    private RowMapper<WritingTask> taskMapper() {
        return (rs, rowNum) -> new WritingTask(
                rs.getLong("id"), rs.getString("goal"), rs.getString("template_markdown"),
                rs.getString("template_analysis"), WritingTaskStatus.valueOf(rs.getString("status")),
                rs.getInt("plan_version"), rs.getLong("state_version"), rs.getString("last_decision"),
                rs.getString("last_error"), rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private RowMapper<ChapterTask> chapterMapper() {
        return (rs, rowNum) -> new ChapterTask(
                rs.getLong("id"), rs.getLong("writing_task_id"), rs.getObject("outline_node_id", Long.class), rs.getString("chapter_key"),
                rs.getString("title"), rs.getString("requirement"), readDependencies(rs.getString("dependencies")),
                rs.getInt("priority_value"), ChapterTaskStatus.valueOf(rs.getString("status")),
                rs.getString("previous_status") == null ? null : ChapterTaskStatus.valueOf(rs.getString("previous_status")),
                rs.getString("content"), rs.getString("summary"), rs.getInt("content_version"),
                rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toLocalDateTime(),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime());
    }

    private RowMapper<ChapterFeedback> feedbackMapper() {
        return (rs, rowNum) -> new ChapterFeedback(
                rs.getLong("id"), rs.getLong("writing_task_id"), rs.getLong("chapter_task_id"),
                ReviewDecision.valueOf(rs.getString("decision")), rs.getString("feedback_text"),
                rs.getBoolean("processed"), rs.getTimestamp("created_at").toLocalDateTime());
    }

    private RowMapper<SourceDocument> sourceMapper() {
        return (rs, rowNum) -> new SourceDocument(rs.getLong("id"), rs.getLong("writing_task_id"),
                rs.getString("original_filename"), rs.getString("stored_path"), rs.getString("file_type"),
                rs.getLong("file_size"), rs.getString("file_hash"), rs.getString("extraction_status"),
                rs.getString("extraction_error"), rs.getTimestamp("created_at").toLocalDateTime());
    }

    private RowMapper<ChapterCheckpoint> checkpointMapper() {
        return (rs, rowNum) -> new ChapterCheckpoint(rs.getLong("chapter_task_id"),
                rs.getInt("content_version"), rs.getString("partial_content"));
    }

    private RowMapper<TaskMessage> taskMessageMapper() {
        return (rs, rowNum) -> new TaskMessage(rs.getLong("id"), rs.getLong("writing_task_id"),
                rs.getObject("chapter_task_id", Long.class), TaskMessageRole.valueOf(rs.getString("role")),
                TaskMessageType.valueOf(rs.getString("message_type")), rs.getString("content"),
                TaskMessageStatus.valueOf(rs.getString("status")), rs.getObject("related_agent_run_id", Long.class),
                rs.getTimestamp("created_at").toLocalDateTime());
    }

    private String writeDependencies(List<String> dependencies) {
        try {
            return objectMapper.writeValueAsString(dependencies == null ? List.of() : dependencies);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("章节依赖序列化失败", e);
        }
    }

    private List<String> readDependencies(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("章节依赖数据无效", e);
        }
    }
}
