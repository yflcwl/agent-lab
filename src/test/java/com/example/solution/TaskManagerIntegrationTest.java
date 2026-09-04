package com.example.solution;

import com.example.solution.agent.ExecutorAgent;
import com.example.solution.agent.PlanningAgent;
import com.example.solution.agent.ConversationAgent;
import com.example.solution.application.TaskManager;
import com.example.solution.domain.ChapterTaskStatus;
import com.example.solution.domain.ExecutionResult;
import com.example.solution.domain.PlannedChapter;
import com.example.solution.domain.PlanningDecision;
import com.example.solution.domain.PlanningDecisionType;
import com.example.solution.domain.ReviewDecision;
import com.example.solution.domain.TaskMessageRole;
import com.example.solution.domain.TaskMessageType;
import com.example.solution.domain.WritingTaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TaskManagerIntegrationTest.StubAgentConfiguration.class)
class TaskManagerIntegrationTest {

    @Autowired
    private TaskManager taskManager;

    @Test
    void completesReviewAndRevisionLifecycle() throws Exception {
        long taskId = taskManager.createTask("生成测试方案", """
                # 测试方案
                ## 1. 方案概述
                描述目标。
                ## 2. 总体架构
                描述总体架构。
                """);
        taskManager.uploadSource(taskId, new org.springframework.mock.web.MockMultipartFile("file", "背景资料.md",
                "text/markdown", "# 背景资料\n测试项目资料".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        taskManager.start(taskId);

        var firstState = await(taskId, ChapterTaskStatus.WAITING_REVIEW);
        var first = firstState.taskList().stream()
                .filter(task -> task.status() == ChapterTaskStatus.WAITING_REVIEW).findFirst().orElseThrow();
        assertThat(firstState.outlineNodes()).hasSize(3);
        var root = firstState.outlineNodes().getFirst();
        assertThat(root.title()).isEqualTo("测试方案");
        assertThat(firstState.outlineNodes().stream().skip(1)).allMatch(node -> node.parentNodeId().equals(root.id()));
        assertThat(firstState.taskList()).allMatch(task -> task.outlineNodeId() != null);
        assertThat(first.content()).contains("初稿");
        assertThat(firstState.agentRuns()).hasSize(2);
        assertThat(firstState.agentRuns()).allMatch(run -> "SUCCEEDED".equals(run.status()));

        taskManager.review(taskId, first.id(), ReviewDecision.APPROVE, null);
        var secondState = await(taskId, ChapterTaskStatus.WAITING_REVIEW);
        var second = secondState.taskList().stream()
                .filter(task -> task.status() == ChapterTaskStatus.WAITING_REVIEW).findFirst().orElseThrow();
        assertThat(second.id()).isNotEqualTo(first.id());

        taskManager.review(taskId, second.id(), ReviewDecision.REVISE, "增加部署说明");
        var revisedState = awaitContentVersion(taskId, second.id(), 2);
        var revised = revisedState.taskList().stream().filter(task -> task.id().equals(second.id())).findFirst().orElseThrow();
        assertThat(revised.status()).isEqualTo(ChapterTaskStatus.WAITING_REVIEW);
        assertThat(revised.content()).contains("增加部署说明");

        taskManager.review(taskId, second.id(), ReviewDecision.APPROVE, null);
        var completed = awaitCompleted(taskId);
        assertThat(completed.task().status()).isEqualTo(WritingTaskStatus.COMPLETED);

        taskManager.sendMessage(taskId, null, TaskMessageType.QUESTION, "当前任务完成了吗？");
        var answered = awaitAgentMessage(taskId);
        assertThat(answered.messages()).anyMatch(message -> message.role() == TaskMessageRole.AGENT
                && message.content().contains("当前任务完成了吗？"));
        assertThat(answered.agentRuns()).anyMatch(run -> "ConversationAgent".equals(run.agentName())
                && "SUCCEEDED".equals(run.status()));
    }

    private com.example.solution.domain.WritingTaskState await(long taskId, ChapterTaskStatus status) throws Exception {
        for (int i = 0; i < 100; i++) {
            var state = taskManager.getState(taskId);
            if (state.taskList().stream().anyMatch(task -> task.status() == status)) {
                return state;
            }
            Thread.sleep(Duration.ofMillis(25));
        }
        throw new AssertionError("未等到章节状态 " + status);
    }

    private com.example.solution.domain.WritingTaskState awaitContentVersion(long taskId, long chapterId, int version)
            throws Exception {
        for (int i = 0; i < 100; i++) {
            var state = taskManager.getState(taskId);
            if (state.taskList().stream().anyMatch(task -> task.id() == chapterId && task.contentVersion() >= version)) {
                return state;
            }
            Thread.sleep(Duration.ofMillis(25));
        }
        throw new AssertionError("未等到章节版本 " + version);
    }

    private com.example.solution.domain.WritingTaskState awaitCompleted(long taskId) throws Exception {
        for (int i = 0; i < 100; i++) {
            var state = taskManager.getState(taskId);
            if (state.task().status() == WritingTaskStatus.COMPLETED) {
                return state;
            }
            Thread.sleep(Duration.ofMillis(25));
        }
        throw new AssertionError("任务未完成");
    }

    private com.example.solution.domain.WritingTaskState awaitAgentMessage(long taskId) throws Exception {
        for (int i = 0; i < 100; i++) {
            var state = taskManager.getState(taskId);
            if (state.messages().stream().anyMatch(message -> message.role() == TaskMessageRole.AGENT)) {
                return state;
            }
            Thread.sleep(Duration.ofMillis(25));
        }
        throw new AssertionError("未等到 Agent 对话回复");
    }

    @TestConfiguration
    static class StubAgentConfiguration {

        @Bean
        PlanningAgent planningAgent() {
            return request -> {
                if (request.taskList().isEmpty()) {
                    List<PlannedChapter> tasks = request.templateSections().stream()
                            .filter(section -> section.level() == 2)
                            .map(section -> new PlannedChapter(section.nodeId(), List.of(), Integer.parseInt(section.nodeId().substring(8))))
                            .toList();
                    return new PlanningAgent.Response(new PlanningDecision(PlanningDecisionType.CREATE_PLAN,
                            "测试模板分析", tasks, tasks.getFirst().templateNodeId(), List.of(), "创建计划"), "stub");
                }
                var next = request.taskList().stream()
                        .filter(task -> task.status() == ChapterTaskStatus.REVISING).findFirst()
                        .or(() -> request.taskList().stream().filter(task -> task.status() == ChapterTaskStatus.NOT_STARTED).findFirst());
                PlanningDecision decision = next
                        .map(task -> new PlanningDecision(request.pendingFeedback().isEmpty()
                                        ? PlanningDecisionType.SELECT_TASK : PlanningDecisionType.REPLAN,
                                null, request.pendingFeedback().isEmpty() ? List.of() : request.taskList().stream()
                                .map(item -> new PlannedChapter(item.chapterKey(), item.dependencies(), item.priority())).toList(),
                                task.chapterKey(), List.of(task.chapterKey()), "选择下一任务"))
                        .orElseGet(() -> new PlanningDecision(PlanningDecisionType.WAIT, null,
                                List.of(), null, List.of(), "等待"));
                return new PlanningAgent.Response(decision, "stub");
            };
        }

        @Bean
        ExecutorAgent executorAgent() {
            return (request, onPartial) -> new ExecutionResult(ExecutionResult.ResultStatus.SUCCESS,
                    "本章初稿。" + (request.feedback() == null ? "" : request.feedback()),
                    "本章摘要", null, null);
        }

        @Bean
        ConversationAgent conversationAgent() {
            return (request, observer) -> "测试回复：" + request.userMessage();
        }
    }
}
