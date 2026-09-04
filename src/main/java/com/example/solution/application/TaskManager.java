package com.example.solution.application;

import com.example.solution.agent.ExecutorAgent;
import com.example.solution.agent.PlanningAgent;
import com.example.solution.agent.AgentExecutionEvent;
import com.example.solution.agent.ConversationAgent;
import com.example.solution.config.SolutionAgentProperties;
import com.example.solution.domain.ChapterCheckpoint;
import com.example.solution.domain.ChapterContext;
import com.example.solution.domain.ChapterFeedback;
import com.example.solution.domain.ChapterTask;
import com.example.solution.domain.ChapterTaskStatus;
import com.example.solution.domain.ExecutionRequest;
import com.example.solution.domain.ExecutionResult;
import com.example.solution.domain.PlanningDecision;
import com.example.solution.domain.PlanningDecisionType;
import com.example.solution.domain.PlanningRequest;
import com.example.solution.domain.ReviewDecision;
import com.example.solution.domain.ResolvedPlannedChapter;
import com.example.solution.domain.TemplateSection;
import com.example.solution.domain.WritingTask;
import com.example.solution.domain.WritingTaskState;
import com.example.solution.domain.WritingTaskStatus;
import com.example.solution.domain.ConversationRequest;
import com.example.solution.domain.TaskMessage;
import com.example.solution.domain.TaskMessageRole;
import com.example.solution.domain.TaskMessageStatus;
import com.example.solution.domain.TaskMessageType;
import com.example.solution.infrastructure.WritingTaskRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class TaskManager {

    private static final Logger log = LoggerFactory.getLogger(TaskManager.class);
    private static final String CONTENT_CHECKPOINT_PREFIX = "CONTENT:";

    private final WritingTaskRepository repository;
    private final SourceDocumentService sourceDocumentService;
    private final MarkdownTemplateParser templateParser;
    private final PlanningDecisionValidator decisionValidator;
    private final PlanningAgent planningAgent;
    private final ExecutorAgent executorAgent;
    private final ConversationAgent conversationAgent;
    private final TaskEventService eventService;
    private final ObjectMapper objectMapper;
    private final Executor taskExecutor;
    private final SolutionAgentProperties properties;
    private final Set<Long> runningTasks = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Map<Long, Integer> retryAttempts = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<Long> queuedMessageWakeups = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public TaskManager(WritingTaskRepository repository, SourceDocumentService sourceDocumentService, MarkdownTemplateParser templateParser,
                       PlanningDecisionValidator decisionValidator, PlanningAgent planningAgent,
                       ExecutorAgent executorAgent, ConversationAgent conversationAgent, TaskEventService eventService, ObjectMapper objectMapper,
                       @Qualifier("taskExecutor") Executor taskExecutor, SolutionAgentProperties properties) {
        this.repository = repository;
        this.sourceDocumentService = sourceDocumentService;
        this.templateParser = templateParser;
        this.decisionValidator = decisionValidator;
        this.planningAgent = planningAgent;
        this.executorAgent = executorAgent;
        this.conversationAgent = conversationAgent;
        this.eventService = eventService;
        this.objectMapper = objectMapper;
        this.taskExecutor = taskExecutor;
        this.properties = properties;
    }

    public long createTask(String goal, String templateMarkdown) {
        if (!StringUtils.hasText(goal)) {
            throw new IllegalArgumentException("Goal 不能为空");
        }
        List<TemplateSection> templateSections = templateParser.parse(templateMarkdown);
        long taskId = repository.createTask(goal.trim(), templateMarkdown.trim());
        repository.createTemplateOutline(taskId, templateSections);
        repository.recordTrace(taskId, "TaskManager", "TASK_CREATED", "任务已创建，等待上传背景资料");
        publish(taskId, "TASK_CHANGED");
        return taskId;
    }

    public void uploadSource(long taskId, MultipartFile file) {
        sourceDocumentService.upload(taskId, file);
        publish(taskId, "TASK_CHANGED");
    }

    public void start(long taskId) {
        if (repository.findSources(taskId).stream().noneMatch(source -> "READY".equals(source.extractionStatus()))) {
            throw new IllegalStateException("请至少上传一份可读取的背景资料后再启动任务");
        }
        repository.recordTrace(taskId, "TaskManager", "TASK_STARTED", "背景资料已就绪，开始调用 PlanningAgent");
        publish(taskId, "TASK_CHANGED");
        schedule(taskId);
    }

    public void retry(long taskId) {
        repository.findTask(taskId);
        retryAttempts.remove(taskId);
        repository.recordTrace(taskId, "TaskManager", "MANUAL_RETRY", "用户请求重新执行任务");
        publish(taskId, "AGENT_TRACE");
        schedule(taskId);
    }

    public WritingTaskState getState(long taskId) {
        WritingTask task = repository.findTask(taskId);
        return new WritingTaskState(task, templateParser.parse(task.templateMarkdown()), repository.findOutlineNodes(taskId),
                repository.findChapters(taskId), repository.findPendingFeedback(taskId), repository.findSources(taskId),
                repository.findTraces(taskId), repository.findAgentRuns(taskId), repository.findAgentRunEvents(taskId),
                repository.findTaskMessages(taskId));
    }

    public String getContent(long taskId) {
        repository.findTask(taskId);
        StringBuilder content = new StringBuilder();
        for (ChapterTask chapter : repository.findChapters(taskId)) {
            if (!StringUtils.hasText(chapter.content())) {
                continue;
            }
            if (!content.isEmpty()) {
                content.append("\n\n");
            }
            content.append("## ").append(chapter.title())
                    .append("\n\n").append(chapter.content().trim());
        }
        return content.toString();
    }

    public void review(long taskId, long chapterTaskId, ReviewDecision decision, String feedback) {
        repository.findTask(taskId);
        repository.findChapter(taskId, chapterTaskId);
        if (decision == ReviewDecision.APPROVE) {
            repository.approveChapter(taskId, chapterTaskId);
        } else {
            if (!StringUtils.hasText(feedback)) {
                throw new IllegalArgumentException("要求修改时必须填写修改意见");
            }
            repository.requestRevision(taskId, chapterTaskId, feedback.trim());
        }
        publish(taskId, "TASK_CHANGED");
        schedule(taskId);
    }

    public void sendMessage(long taskId, Long chapterTaskId, TaskMessageType messageType, String content) {
        repository.findTask(taskId);
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("消息不能为空");
        }
        if (messageType == null) {
            throw new IllegalArgumentException("消息类型不能为空");
        }
        if (messageType == TaskMessageType.CHAPTER_INSTRUCTION) {
            if (chapterTaskId == null) {
                throw new IllegalArgumentException("章节修改指令必须指定章节");
            }
            repository.findChapter(taskId, chapterTaskId);
        }
        repository.addTaskMessage(taskId, messageType == TaskMessageType.CHAPTER_INSTRUCTION ? chapterTaskId : null,
                TaskMessageRole.USER, messageType, content.trim(), TaskMessageStatus.QUEUED, null);
        repository.recordTrace(taskId, "User", "MESSAGE_QUEUED", "用户发送了" + switch (messageType) {
            case QUESTION -> "问题";
            case CHAPTER_INSTRUCTION -> "章节修改指令";
            case PLAN_INSTRUCTION -> "计划调整指令";
        });
        publish(taskId, "TASK_MESSAGE");
        if (runningTasks.contains(taskId)) {
            queuedMessageWakeups.add(taskId);
        } else {
            schedule(taskId);
        }
    }

    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter subscribe(long taskId) {
        repository.findTask(taskId);
        return eventService.subscribe(taskId);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAndResume() {
        List<Long> interruptedTaskIds = repository.findExecutingTaskIds();
        repository.recoverExpiredExecutions();
        repository.deleteLegacyCheckpoints();
        repository.findFinishedRunningTaskIds().forEach(repository::completeTask);
        interruptedTaskIds.forEach(this::schedule);
    }

    private void schedule(long taskId) {
        if (!runningTasks.add(taskId)) {
            queuedMessageWakeups.add(taskId);
            return;
        }
        taskExecutor.execute(() -> {
            try {
                processOnce(taskId);
            } finally {
                runningTasks.remove(taskId);
                if (queuedMessageWakeups.remove(taskId)) {
                    schedule(taskId);
                }
            }
        });
    }

    private void processOnce(long taskId) {
        Long executingChapterId = null;
        Long activeAgentRunId = null;
        try {
            WritingTaskState state = getState(taskId);
            TaskMessage question = state.messages().stream()
                    .filter(message -> message.role() == TaskMessageRole.USER)
                    .filter(message -> message.status() == TaskMessageStatus.QUEUED)
                    .filter(message -> message.messageType() == TaskMessageType.QUESTION)
                    .findFirst().orElse(null);
            if (question != null) {
                answerQuestion(taskId, state, question);
                return;
            }
            boolean appliedInstruction = applyReadyChapterInstructions(taskId, state.messages());
            if (appliedInstruction) {
                state = getState(taskId);
            }
            if (state.sourceDocuments().stream().noneMatch(source -> "READY".equals(source.extractionStatus()))) return;
            List<TaskMessage> planInstructions = state.messages().stream()
                    .filter(message -> message.role() == TaskMessageRole.USER)
                    .filter(message -> message.status() == TaskMessageStatus.QUEUED)
                    .filter(message -> message.messageType() == TaskMessageType.PLAN_INSTRUCTION)
                    .toList();
            boolean waitingReview = state.taskList().stream()
                    .anyMatch(task -> task.status() == ChapterTaskStatus.WAITING_REVIEW);
            if (state.task().status() == WritingTaskStatus.COMPLETED
                    || (waitingReview && planInstructions.isEmpty())) {
                return;
            }
            if (!state.taskList().isEmpty()
                    && state.taskList().stream().allMatch(task -> task.status() == ChapterTaskStatus.COMPLETED)) {
                repository.completeTask(taskId);
                publish(taskId, "TASK_COMPLETED");
                return;
            }

            PlanningRequest request = new PlanningRequest(taskId, state.task().goal(), state.task().templateMarkdown(),
                    state.task().templateAnalysis(), state.templateSections(), state.taskList(),
                    state.task().planVersion(), state.pendingFeedback(), state.sourceDocuments(), planInstructions);
            repository.recordTrace(taskId, "PlanningAgent", "PLANNING_STARTED", "正在根据模板和已上传资料规划章节任务");
            publish(taskId, "AGENT_TRACE");
            long planningRunId = repository.startAgentRun(taskId, null, "PlanningAgent", "PLAN");
            activeAgentRunId = planningRunId;
            repository.recordAgentRunEvent(planningRunId, "RUN_CREATED", null, null, "正在启动规划 Agent");
            publish(taskId, "AGENT_OBSERVATION");
            PlanningAgent.Response response = planningAgent.plan(request,
                    event -> observe(taskId, planningRunId, event));
            repository.finishAgentRun(planningRunId, "SUCCEEDED", null);
            activeAgentRunId = null;
            PlanningDecision decision = response.decision();
            planInstructions.forEach(message -> repository.updateTaskMessageStatus(message.id(), TaskMessageStatus.PROCESSED, planningRunId));
            repository.recordTrace(taskId, "PlanningAgent", "REASONING_SUMMARY",
                    decision.reason() == null ? "已完成当前任务分析，准备确定章节计划" : decision.reason());
            publish(taskId, "AGENT_TRACE");
            validateAndRecord(state, response);

            if (state.taskList().isEmpty() && decision.decisionType() == PlanningDecisionType.CREATE_PLAN) {
                repository.createPlan(taskId, decision.templateAnalysis(), resolvePlan(decision.tasks(), state.templateSections()));
            } else if (decision.decisionType() == PlanningDecisionType.REPLAN) {
                repository.updatePlan(taskId, decision.templateAnalysis(), resolvePlan(decision.tasks(), state.templateSections()));
            }
            if (decision.decisionType() == PlanningDecisionType.WAIT) {
                publish(taskId, "TASK_CHANGED");
                return;
            }
            if (waitingReview) {
                publish(taskId, "TASK_CHANGED");
                return;
            }

            ChapterTask selected = repository.findChapterByKey(taskId, decision.selectedTaskKey());
            if (!repository.beginExecution(taskId, selected.id(), properties.getLeaseDuration())) {
                throw new IllegalStateException("章节状态已变化，无法开始执行: " + selected.chapterKey());
            }
            executingChapterId = selected.id();
            publish(taskId, "CHAPTER_EXECUTING");
            repository.recordTrace(taskId, "ExecutorAgent", "CHAPTER_STARTED", "正在生成第 " + selected.chapterKey() + " 章：" + selected.title());
            publish(taskId, "AGENT_TRACE");

            ChapterTask executingTask = repository.findChapter(taskId, selected.id());
            List<ChapterTask> currentTasks = repository.findChapters(taskId);
            Optional<ChapterCheckpoint> checkpoint = repository.findCheckpoint(taskId, selected.id());
            String resumeContent = checkpoint
                    .filter(item -> item.contentVersion() == executingTask.contentVersion())
                    .map(ChapterCheckpoint::partialContent)
                    .filter(content -> content.startsWith(CONTENT_CHECKPOINT_PREFIX))
                    .map(content -> content.substring(CONTENT_CHECKPOINT_PREFIX.length()))
                    .orElse(null);
            if (checkpoint.isPresent() && resumeContent == null) {
                repository.deleteCheckpoint(taskId, selected.id());
            }
            if (StringUtils.hasText(resumeContent)) {
                repository.recordTrace(taskId, "ExecutorAgent", "RESUME_CHECKPOINT",
                        "从已生成的 " + resumeContent.length() + " 字符继续撰写第 " + selected.chapterKey() + " 章");
                publish(taskId, "AGENT_TRACE");
            }
            ExecutionRequest executionRequest = new ExecutionRequest(taskId, state.task().goal(), executingTask,
                    relatedContexts(executingTask, currentTasks), feedbackFor(selected, state.pendingFeedback()),
                    state.sourceDocuments(), resumeContent, chapterInstructionsFor(selected.id(), state.messages()));
            long executionRunId = repository.startAgentRun(taskId, selected.id(), "ExecutorAgent", "EXECUTE");
            activeAgentRunId = executionRunId;
            repository.recordAgentRunEvent(executionRunId, "RUN_CREATED", null, null,
                    "正在启动章节执行 Agent");
            publish(taskId, "AGENT_OBSERVATION");
            ExecutionResult result = executorAgent.execute(executionRequest,
                    partial -> repository.upsertCheckpoint(taskId, selected.id(), executingTask.contentVersion(),
                            CONTENT_CHECKPOINT_PREFIX + partial),
                    event -> observe(taskId, executionRunId, event));
            if (result == null || result.result() != ExecutionResult.ResultStatus.SUCCESS
                    || !StringUtils.hasText(result.content())) {
                String error = result == null ? "ExecutorAgent 未返回结果"
                        : StringUtils.hasText(result.errorMessage()) ? result.errorMessage() : "ExecutorAgent 执行失败";
                throw new IllegalStateException(error);
            }
            repository.finishAgentRun(executionRunId, "SUCCEEDED", null);
            activeAgentRunId = null;
            chapterInstructionsFor(selected.id(), state.messages()).forEach(message ->
                    repository.updateTaskMessageStatus(message.id(), TaskMessageStatus.PROCESSED, executionRunId));
            repository.saveExecutionSuccess(taskId, executingTask, result.content().trim(), result.summary());
            if (StringUtils.hasText(result.summary())) {
                repository.recordTrace(taskId, "ExecutorAgent", "REASONING_SUMMARY", result.summary());
                publish(taskId, "AGENT_TRACE");
            }
            repository.recordTrace(taskId, "ExecutorAgent", "CHAPTER_FINISHED", "第 " + selected.chapterKey() + " 章已生成，等待用户审核");
            publish(taskId, "AGENT_TRACE");
            if (!state.pendingFeedback().isEmpty()) {
                repository.markFeedbackProcessed(taskId);
            }
            if (applyReadyChapterInstructions(taskId, repository.findTaskMessages(taskId))) {
                queuedMessageWakeups.add(taskId);
            }
            retryAttempts.remove(taskId);
            publish(taskId, "CHAPTER_WAITING_REVIEW");
        } catch (Exception e) {
            log.warn("任务 {} 执行失败: {}", taskId, e.getMessage(), e);
            if (activeAgentRunId != null) {
                repository.finishAgentRun(activeAgentRunId, "FAILED", e.getMessage());
                repository.recordAgentRunEvent(activeAgentRunId, "RUN_FAILED", null, null,
                        e.getMessage() == null ? "Agent 执行失败" : e.getMessage());
                publish(taskId, "AGENT_OBSERVATION");
            }
            repository.recordTrace(taskId, executingChapterId == null ? "TaskManager" : "ExecutorAgent",
                    "EXECUTION_FAILED", e.getMessage() == null ? "执行失败" : e.getMessage());
            publish(taskId, "AGENT_TRACE");
            if (executingChapterId != null) {
                repository.saveExecutionFailure(taskId, executingChapterId, e.getMessage());
            } else {
                repository.recordTaskError(taskId, e.getMessage());
            }
            publish(taskId, "EXECUTION_FAILED");
            String errorMessage = e.getMessage() == null ? "执行失败" : e.getMessage();
            boolean retryable = !errorMessage.contains("结果解析失败");
            int attempt = retryAttempts.merge(taskId, 1, Integer::sum);
            if (retryable && attempt <= properties.getMaxRetries()) {
                repository.recordTrace(taskId, "TaskManager", "AUTO_RETRY_SCHEDULED",
                        "第 " + attempt + " 次自动重试将在 " + properties.getRetryDelay().toSeconds() + " 秒后开始：" + errorMessage);
                publish(taskId, "AGENT_TRACE");
                CompletableFuture.delayedExecutor(properties.getRetryDelay().toMillis(), TimeUnit.MILLISECONDS, taskExecutor)
                        .execute(() -> schedule(taskId));
            } else {
                repository.recordTrace(taskId, "TaskManager", retryable ? "AUTO_RETRY_EXHAUSTED" : "AUTO_RETRY_SKIPPED",
                        retryable ? "自动重试已达上限，请检查异常后点击重试：" + errorMessage
                                : "模型返回格式不符合约定，已停止自动重试，请检查后手动重新执行：" + errorMessage);
                publish(taskId, "AGENT_TRACE");
            }
        }
    }

    private void validateAndRecord(WritingTaskState state, PlanningAgent.Response response) {
        PlanningDecision decision = response.decision();
        try {
            decisionValidator.validate(decision, state.templateSections(), state.taskList());
            repository.recordDecision(state.task().id(), state.task().planVersion(), decision.decisionType().name(),
                    response.rawResponse(), writeJson(decision), true, null, decision.reason());
        } catch (RuntimeException e) {
            String type = decision == null || decision.decisionType() == null ? "UNKNOWN" : decision.decisionType().name();
            repository.recordDecision(state.task().id(), state.task().planVersion(), type,
                    response.rawResponse(), writeJson(decision), false, e.getMessage(), null);
            throw e;
        }
    }

    private List<ChapterContext> relatedContexts(ChapterTask selected, List<ChapterTask> taskList) {
        Set<String> dependencies = new HashSet<>(selected.dependencies());
        List<ChapterContext> result = new ArrayList<>();
        for (ChapterTask task : taskList) {
            if (task.status() == ChapterTaskStatus.COMPLETED && dependencies.contains(task.chapterKey())) {
                result.add(new ChapterContext(task.chapterKey(), task.title(), task.summary(), task.content()));
            }
        }
        return result;
    }

    private List<ResolvedPlannedChapter> resolvePlan(List<com.example.solution.domain.PlannedChapter> plan,
                                                      List<TemplateSection> templateSections) {
        java.util.Map<String, TemplateSection> sections = templateSections.stream()
                .collect(java.util.stream.Collectors.toMap(TemplateSection::nodeId, section -> section));
        return plan.stream().map(item -> {
            TemplateSection section = sections.get(item.templateNodeId());
            return new ResolvedPlannedChapter(item.templateNodeId(), section.title(), section.requirement(),
                    item.dependencies(), item.priority());
        }).toList();
    }

    private String feedbackFor(ChapterTask selected, List<ChapterFeedback> feedback) {
        return feedback.stream().filter(item -> item.chapterTaskId().equals(selected.id()))
                .map(ChapterFeedback::feedbackText).filter(StringUtils::hasText)
                .reduce((left, right) -> left + "\n" + right).orElse(null);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Agent 决策序列化失败", e);
        }
    }

    private void observe(long taskId, long agentRunId, AgentExecutionEvent event) {
        repository.recordAgentRunEvent(agentRunId, event.eventType(), event.toolCallId(), event.toolName(), event.detail());
        publish(taskId, "AGENT_OBSERVATION");
    }

    private boolean applyReadyChapterInstructions(long taskId, List<TaskMessage> messages) {
        boolean applied = false;
        for (TaskMessage message : messages) {
            if (message.role() != TaskMessageRole.USER || message.status() != TaskMessageStatus.QUEUED
                    || message.messageType() != TaskMessageType.CHAPTER_INSTRUCTION || message.chapterTaskId() == null) {
                continue;
            }
            ChapterTask chapter = repository.findChapter(taskId, message.chapterTaskId());
            if (chapter.status() == ChapterTaskStatus.WAITING_REVIEW || chapter.status() == ChapterTaskStatus.COMPLETED) {
                repository.requestRevisionFromMessage(taskId, chapter.id(), message.content());
                repository.updateTaskMessageStatus(message.id(), TaskMessageStatus.PROCESSED, null);
                applied = true;
            }
        }
        return applied;
    }

    private List<TaskMessage> chapterInstructionsFor(long chapterTaskId, List<TaskMessage> messages) {
        return messages.stream()
                .filter(message -> message.role() == TaskMessageRole.USER)
                .filter(message -> message.status() == TaskMessageStatus.QUEUED)
                .filter(message -> message.messageType() == TaskMessageType.CHAPTER_INSTRUCTION)
                .filter(message -> Long.valueOf(chapterTaskId).equals(message.chapterTaskId()))
                .toList();
    }

    private void answerQuestion(long taskId, WritingTaskState state, TaskMessage question) {
        repository.updateTaskMessageStatus(question.id(), TaskMessageStatus.PROCESSING, null);
        long runId = repository.startAgentRun(taskId, question.chapterTaskId(), "ConversationAgent", "CONVERSATION");
        repository.recordAgentRunEvent(runId, "RUN_CREATED", null, null, "正在处理用户问题");
        publish(taskId, "AGENT_OBSERVATION");
        try {
            String reply = conversationAgent.reply(new ConversationRequest(taskId, state.task().goal(), state.taskList(),
                    state.sourceDocuments(), question.content()), event -> observe(taskId, runId, event));
            repository.finishAgentRun(runId, "SUCCEEDED", null);
            repository.updateTaskMessageStatus(question.id(), TaskMessageStatus.PROCESSED, runId);
            repository.addTaskMessage(taskId, question.chapterTaskId(), TaskMessageRole.AGENT, TaskMessageType.QUESTION,
                    reply.trim(), TaskMessageStatus.PROCESSED, runId);
        } catch (RuntimeException e) {
            repository.finishAgentRun(runId, "FAILED", e.getMessage());
            repository.updateTaskMessageStatus(question.id(), TaskMessageStatus.FAILED, runId);
            repository.addTaskMessage(taskId, question.chapterTaskId(), TaskMessageRole.SYSTEM, TaskMessageType.QUESTION,
                    "暂时无法回答该问题：" + (e.getMessage() == null ? "Agent 执行失败" : e.getMessage()),
                    TaskMessageStatus.FAILED, runId);
        }
        publish(taskId, "TASK_MESSAGE");
    }

    private void publish(long taskId, String eventType) {
        try {
            WritingTask task = repository.findTask(taskId);
            eventService.publish(taskId, eventType, task.stateVersion());
        } catch (RuntimeException ignored) {
            eventService.publish(taskId, eventType, 0L);
        }
    }
}
