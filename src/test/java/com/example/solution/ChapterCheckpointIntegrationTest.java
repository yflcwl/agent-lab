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
import com.example.solution.domain.ResolvedPlannedChapter;
import com.example.solution.domain.WritingTaskState;
import com.example.solution.infrastructure.WritingTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "solution.agent.max-retries=0")
@Import(ChapterCheckpointIntegrationTest.CheckpointStubConfiguration.class)
class ChapterCheckpointIntegrationTest {

    @Autowired
    private TaskManager taskManager;

    @Autowired
    private WritingTaskRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetStubState() {
        CheckpointStubConfiguration.executorCalls.set(0);
        CheckpointStubConfiguration.lastResumeContent.set(null);
    }

    @Test
    void resumesFromCheckpointAfterRestart() throws Exception {
        long taskId = taskManager.createTask("生成测试方案", """
                # 测试方案
                ## 1. 方案概述
                描述目标。
                """);
        taskManager.uploadSource(taskId, new org.springframework.mock.web.MockMultipartFile("file", "背景资料.md",
                "text/markdown", "# 背景资料\n测试项目资料".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        taskManager.start(taskId);

        // 第一次执行：stub 先写出 partial 再抛异常，模拟服务中断（max-retries=0 不会自动重试）。
        awaitFirstExecutionFinished(taskId);
        Thread.sleep(100);

        // 重启恢复只回滚中断章节；用户明确重试后，第二次执行应收到 resumeContent。
        taskManager.recoverAndResume();
        taskManager.retry(taskId);

        var state = awaitChapterWaitingReview(taskId);
        var chapter = state.taskList().getFirst();
        assertThat(chapter.content()).contains(CheckpointStubConfiguration.PARTIAL);
        assertThat(chapter.content()).contains("续写完成");
        assertThat(CheckpointStubConfiguration.lastResumeContent.get()).isEqualTo(CheckpointStubConfiguration.PARTIAL);
        assertThat(repository.findCheckpoint(taskId, chapter.id())).isEmpty();
        assertThat(state.traces().stream().anyMatch(trace -> "RESUME_CHECKPOINT".equals(trace.eventType()))).isTrue();
    }

    @Test
    void recoverExpiredExecutionsRollsBackButKeepsCheckpoint() {
        long taskId = taskManager.createTask("生成测试方案", """
                # 测试方案
                ## 1. 方案概述
                描述目标。
                """);
        repository.createPlan(taskId, "测试分析",
                List.of(new ResolvedPlannedChapter("heading-2", "方案概述", "要求", List.of(), 1)));
        var chapter = repository.findChapters(taskId).getFirst();
        repository.beginExecution(taskId, chapter.id(), Duration.ofMinutes(5));
        repository.upsertCheckpoint(taskId, chapter.id(), chapter.contentVersion(), "部分正文");

        repository.recoverExpiredExecutions();

        assertThat(repository.findChapter(taskId, chapter.id()).status()).isEqualTo(ChapterTaskStatus.NOT_STARTED);
        assertThat(repository.findCheckpoint(taskId, chapter.id())).isPresent();
    }

    private void awaitFirstExecutionFinished(long taskId) throws Exception {
        for (int i = 0; i < 100; i++) {
            var state = taskManager.getState(taskId);
            boolean exhausted = state.traces().stream()
                    .anyMatch(trace -> "AUTO_RETRY_EXHAUSTED".equals(trace.eventType()));
            if (exhausted) {
                return;
            }
            Thread.sleep(Duration.ofMillis(25));
        }
        throw new AssertionError("第一次执行未以 AUTO_RETRY_EXHAUSTED 结束");
    }

    private WritingTaskState awaitChapterWaitingReview(long taskId) throws Exception {
        for (int i = 0; i < 100; i++) {
            var state = taskManager.getState(taskId);
            if (state.taskList().stream().anyMatch(task -> task.status() == ChapterTaskStatus.WAITING_REVIEW)) {
                return state;
            }
            Thread.sleep(Duration.ofMillis(25));
        }
        throw new AssertionError("章节未进入 WAITING_REVIEW");
    }

    @TestConfiguration
    static class CheckpointStubConfiguration {
        static final String PARTIAL = "已生成的部分正文";
        static final AtomicInteger executorCalls = new AtomicInteger();
        static final AtomicReference<String> lastResumeContent = new AtomicReference<>();

        @Bean
        PlanningAgent planningAgent() {
            return request -> {
                if (request.taskList().isEmpty()) {
                    List<PlannedChapter> tasks = request.templateSections().stream()
                            .filter(section -> section.level() == 2)
                            .map(section -> new PlannedChapter(section.nodeId(), List.of(),
                                    Integer.parseInt(section.nodeId().substring(8))))
                            .toList();
                    return new PlanningAgent.Response(new PlanningDecision(PlanningDecisionType.CREATE_PLAN,
                            "测试模板分析", tasks, tasks.getFirst().templateNodeId(), List.of(), "创建计划"), "stub");
                }
                var next = request.taskList().stream()
                        .filter(task -> task.status() == ChapterTaskStatus.NOT_STARTED).findFirst();
                PlanningDecision decision = next
                        .map(task -> new PlanningDecision(PlanningDecisionType.SELECT_TASK, null, List.of(),
                                task.chapterKey(), List.of(task.chapterKey()), "选择下一任务"))
                        .orElseGet(() -> new PlanningDecision(PlanningDecisionType.WAIT, null,
                                List.of(), null, List.of(), "等待"));
                return new PlanningAgent.Response(decision, "stub");
            };
        }

        @Bean
        ExecutorAgent executorAgent() {
            return (request, onPartial) -> {
                int call = executorCalls.incrementAndGet();
                lastResumeContent.set(request.resumeContent());
                if (call == 1) {
                    onPartial.accept(PARTIAL);
                    throw new IllegalStateException("模拟服务中断");
                }
                return new ExecutionResult(ExecutionResult.ResultStatus.SUCCESS,
                        (request.resumeContent() == null ? "" : request.resumeContent()) + "，续写完成",
                        "本章摘要", null, null);
            };
        }

        @Bean
        ConversationAgent conversationAgent() {
            return (request, observer) -> "测试回复：" + request.userMessage();
        }
    }
}
