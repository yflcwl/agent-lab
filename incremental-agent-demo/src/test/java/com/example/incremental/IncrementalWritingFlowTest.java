package com.example.incremental;

import com.example.incremental.agent.ResearchTools;
import com.example.incremental.runtime.RequireUserConfirmAguiEventConverter;
import com.example.incremental.agent.WritingTools;
import com.example.incremental.writing.WritingTask;
import com.example.incremental.writing.ChapterStageStatus;
import com.example.incremental.runtime.AgentRunDecision;
import com.example.incremental.writing.WritingTaskView;
import com.example.incremental.runtime.AgentRunRuntime;
import com.example.incremental.runtime.RoundRunner;
import com.example.incremental.workspace.TaskWorkspaceService;
import com.example.incremental.rag.TempRagService;
import com.example.incremental.rag.TempRagEmbeddingModel;
import com.example.incremental.writing.WritingAgent;
import com.example.incremental.writing.WritingRunCommand;
import com.example.incremental.writing.WritingToolContext;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "demo.agent.enabled=false",
        "demo.data-root=${java.io.tmpdir}/incremental-agent-demo-test-${random.uuid}",
        "demo.state-root=${java.io.tmpdir}/incremental-agent-state-test-${random.uuid}",
        "demo.rag-store=in-memory",
        "demo.rag-embedding-dimensions=2"
})
@Import(IncrementalWritingFlowTest.StubAgentConfiguration.class)
class IncrementalWritingFlowTest {

    private final TaskWorkspaceService workspaceService;
    private final RoundRunner roundRunner;
    private final WritingTools writingTools;
    private final ResearchTools researchTools;
    private final AgentRunRuntime agentRunRuntime;
    private final PermissionContextState writingPermissionContext;
    private final TempRagService tempRagService;

    @LocalServerPort
    private int serverPort;

    @Autowired
    IncrementalWritingFlowTest(
            TaskWorkspaceService workspaceService,
            RoundRunner roundRunner,
            WritingTools writingTools,
            ResearchTools researchTools,
            AgentRunRuntime agentRunRuntime,
            PermissionContextState writingPermissionContext,
            TempRagService tempRagService) {
        this.workspaceService = workspaceService;
        this.roundRunner = roundRunner;
        this.writingTools = writingTools;
        this.researchTools = researchTools;
        this.agentRunRuntime = agentRunRuntime;
        this.writingPermissionContext = writingPermissionContext;
        this.tempRagService = tempRagService;
    }

    @BeforeEach
    void resetStub() {
        StubAgentConfiguration.calls.set(0);
        StubAgentConfiguration.sessions.clear();
        StubAgentConfiguration.messages.clear();
        StubAgentConfiguration.interruptNextRun.set(false);
        StubAgentConfiguration.skipCommitNextRun.set(false);
    }

    @Test
    void createsAndSavesOnlyOneDynamicContentPerRound() {
        WritingTask task = workspaceService.createTask("user-1", """
                # 完整参考方案
                本方案先交代背景，再说明实施安排，最后总结预期成效。
                全文使用正式、简洁、事实导向的表达方式。
                """, Map.of("背景.md", "需要分别形成项目概况和实施安排两项正文"));

        assertThat(workspaceService.listContents(task.id())).isEmpty();

        var firstEvents = roundRunner.run(task.id()).collectList().block();
        assertThat(firstEvents).isNotEmpty();
        assertThat(workspaceService.hasDocumentSummary(task.id())).isTrue();
        assertThat(workspaceService.readDocumentSummary(task.id())).contains("资料摘要", "参考文档风格");
        assertThat(workspaceService.listContents(task.id())).hasSize(1);
        assertThat(workspaceService.listContents(task.id()).getFirst().title()).isEqualTo("项目概况");
        assertThat(workspaceService.listChapterMemories(task.id())).hasSize(1);
        assertThat(workspaceService.readDocumentState(task.id())).contains("项目概况");
        assertThat(workspaceService.readWorkingPlan(task.id())).contains("实施安排");

        var secondEvents = roundRunner.run(task.id(), "下一章重点说明实施步骤").collectList().block();
        assertThat(secondEvents).isNotEmpty();
        assertThat(workspaceService.listContents(task.id())).hasSize(2);
        assertThat(workspaceService.listContents(task.id()).get(1).title()).isEqualTo("实施安排");

        var finishedEvents = roundRunner.run(task.id()).collectList().block();
        assertThat(finishedEvents).isNotEmpty();
        assertThat(workspaceService.listContents(task.id())).hasSize(2);
        assertThat(StubAgentConfiguration.sessions).hasSize(3).doesNotHaveDuplicates();
        assertThat(StubAgentConfiguration.sessions.get(0))
                .startsWith(task.sessionId() + "-chapter-001-");
        assertThat(StubAgentConfiguration.sessions.get(1))
                .startsWith(task.sessionId() + "-chapter-002-");
        assertThat(StubAgentConfiguration.sessions.get(2))
                .startsWith(task.sessionId() + "-chapter-003-");
        assertThat(StubAgentConfiguration.messages)
                .containsExactly("", "下一章重点说明实施步骤", "");
        assertThat(workspaceService.getFullContent(task.id()))
                .contains("第一轮正文")
                .contains("第二轮正文");
    }

    @Test
    void asksOnlyForChapterCommitPermission() {
        assertThat(writingPermissionContext.getMode()).isEqualTo(PermissionMode.BYPASS);
        assertThat(writingPermissionContext.getAskRules()).containsOnlyKeys("commit_chapter");
        assertThat(writingPermissionContext.getAskRules().get("commit_chapter"))
                .singleElement()
                .satisfies(rule -> assertThat(rule.behavior()).isEqualTo(PermissionBehavior.ASK));
    }

    @Test
    void convertsPermissionAskIntoAguiInterrupt() {
        AguiStreamContext streamContext = new AguiStreamContext(
                "thread-1", "run-1", AguiAdapterConfig.defaultConfig());
        ToolUseBlock toolCall = new ToolUseBlock(
                "call-1", "commit_chapter", Map.of("stage_id", "stage-1"))
                .withState(ToolCallState.ASKING);

        new RequireUserConfirmAguiEventConverter().convert(
                new RequireUserConfirmEvent("reply-1", List.of(toolCall)), streamContext);

        assertThat(streamContext.getPendingInterrupts()).singleElement().satisfies(interrupt -> {
            assertThat(interrupt.id()).isEqualTo("reply-1:call-1");
            assertThat(interrupt.toolCallId()).isEqualTo("call-1");
            assertThat(interrupt.metadata()).containsEntry("toolName", "commit_chapter")
                    .containsEntry("toolInput", Map.of("stage_id", "stage-1"));
        });
    }

    @Test
    void rejectsASecondSaveInTheSameRound() {
        WritingTask task = workspaceService.createTask("user-1", "# 完整参考文档\n\n这是一篇成稿。", Map.of());
        WritingToolContext context = new WritingToolContext(task.id());

        assertThatThrownBy(() -> writingTools.saveContent(
                "第一项", "参考文档的正式表达方式", "正文一", context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("资料 Agent");
        context.markResearchLocated();

        writingTools.saveContent("第一项", "参考文档的正式表达方式", "正文一", context);

        assertThatThrownBy(() -> writingTools.saveContent("第二项", "参考文档的正式表达方式", "正文二", context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("本轮已经保存过");
        assertThat(workspaceService.listContents(task.id())).isEmpty();
        assertThat(workspaceService.findOpenChapterStage(task.id())).isNotNull();
    }

    @Test
    void limitsARunToItsCurrentChapterStage() {
        WritingTask task = workspaceService.createTask(
                "user-1", "# 完整参考文档", Map.of("资料.md", "背景事实"));
        WritingTask otherTask = workspaceService.createTask(
                "user-1", "# 其他参考文档", Map.of("其他资料.md", "其他事实"));
        WritingToolContext context = new WritingToolContext(task.id());
        context.markResearchLocated();
        writingTools.saveContent("项目概况", "正式表达", "## 项目概况\n\n正文", context);
        var currentStage = workspaceService.findOpenChapterStage(task.id());

        assertThat(writingTools.readStagedChapter(context))
                .contains(currentStage.stageId(), "项目概况");
        assertThatThrownBy(() -> new WritingToolContext(otherTask.id(), currentStage))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不属于当前写作任务");
        assertThatThrownBy(() -> writingTools.readStagedChapter(new WritingToolContext(task.id())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("尚未绑定 ChapterStage");
    }

    @Test
    void keepsTheSameChapterSessionWhenAChapterStopsBeforeSaving() {
        WritingTask task = workspaceService.createTask(
                "user-1", "# 完整参考文档", Map.of("资料.md", "背景事实"));

        roundRunner.run(task.id(), "trigger-stream-error").collectList().block();
        roundRunner.run(task.id(), "继续").collectList().block();

        assertThat(StubAgentConfiguration.sessions).hasSize(2);
        assertThat(StubAgentConfiguration.sessions.get(0))
                .isEqualTo(StubAgentConfiguration.sessions.get(1));
        assertThat(workspaceService.listContents(task.id())).hasSize(1);
    }

    @Test
    void retriesACompleteStageThatMissesTheCommitRequest() {
        WritingTask task = workspaceService.createTask(
                "user-1", "# 完整参考文档", Map.of("资料.md", "背景事实"));
        StubAgentConfiguration.skipCommitNextRun.set(true);

        var events = roundRunner.run(task.id()).collectList().block();

        assertThat(events).extracting(event -> event.getType().name())
                .doesNotContain("RUN_ERROR")
                .contains("RUN_STARTED", "RUN_FINISHED", "CUSTOM");
        assertThat(workspaceService.listContents(task.id())).hasSize(1);
        assertThat(StubAgentConfiguration.sessions).hasSize(2);
        assertThat(StubAgentConfiguration.sessions.getFirst())
                .isEqualTo(StubAgentConfiguration.sessions.getLast());
    }

    @Test
    void commitsARecoveredChapterStageExactlyOnce() {
        WritingTask task = workspaceService.createTask(
                "user-1", "# 完整参考文档", Map.of("资料.md", "背景事实"));
        WritingToolContext interrupted = new WritingToolContext(task.id());
        interrupted.markResearchLocated();
        writingTools.saveContent("项目概况", "正式表达", "## 项目概况\n\n正文", interrupted);

        assertThat(workspaceService.listContents(task.id())).isEmpty();
        var stage = workspaceService.findOpenChapterStage(task.id());
        assertThat(stage).isNotNull();
        WritingToolContext recovery = new WritingToolContext(task.id(), stage);
        assertThatThrownBy(() -> writingTools.saveContent(
                "项目概况", "正式表达", "重写正文", recovery))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能重写正文");
        writingTools.saveChapterMemory("# 章节记忆\n\n已完成项目概况。", recovery);
        writingTools.updateDocumentState("# 文档当前状态\n\n已完成项目概况。", recovery);
        writingTools.updateWorkingPlan("""
                {
                  "version": 1,
                  "completed": ["项目概况"],
                  "nextDirection": "实施安排",
                  "remainingDirections": ["实施安排"],
                  "adjustmentReason": "恢复上一轮状态"
                }
                """, recovery);
        var firstCommit = workspaceService.commitChapter(task.id(), recovery.stageId());
        var retriedCommit = workspaceService.commitChapter(task.id(), recovery.stageId());

        assertThat(firstCommit).isEqualTo(retriedCommit);
        assertThat(workspaceService.findOpenChapterStage(task.id())).isNull();
        assertThat(workspaceService.listContents(task.id())).hasSize(1);
    }

    @Test
    void requestsCommitForACompleteStageBeforeWritingOutputs() {
        WritingTask task = workspaceService.createTask(
                "user-1", "# 完整参考文档", Map.of("资料.md", "背景事实"));
        WritingToolContext context = new WritingToolContext(task.id());
        context.markResearchLocated();
        writingTools.saveContent("项目概况", "正式表达", "## 项目概况\n\n正文", context);
        writingTools.saveChapterMemory("# 章节记忆\n\n已完成项目概况。", context);
        writingTools.updateDocumentState("# 文档当前状态\n\n已完成项目概况。", context);
        writingTools.updateWorkingPlan("""
                {
                  "version": 1,
                  "completed": ["项目概况"],
                  "nextDirection": "实施安排",
                  "remainingDirections": ["实施安排"],
                  "adjustmentReason": "提交完整 Stage"
                }
                """, context);

        var events = roundRunner.run(task.id()).collectList().block();

        assertThat(StubAgentConfiguration.sessions).hasSize(1);
        assertThat(StubAgentConfiguration.messages).containsExactly("");
        assertThat(events).extracting(event -> event.getType().name())
                .containsExactly("RUN_STARTED", "TEXT_MESSAGE_CONTENT", "CUSTOM", "RUN_FINISHED");
        assertThat(workspaceService.listContents(task.id())).hasSize(1);
        assertThat(workspaceService.findOpenChapterStage(task.id())).isNull();
    }

    @Test
    void keepsACompleteStageAwaitingReviewWhenCommitIsInterrupted() {
        WritingTask task = workspaceService.createTask(
                "user-1", "# 完整参考文档", Map.of("资料.md", "背景事实"));
        StubAgentConfiguration.interruptNextRun.set(true);

        var events = roundRunner.run(task.id()).collectList().block();

        assertThat(events).extracting(event -> event.getType().name())
                .containsExactly("RUN_STARTED", "RUN_FINISHED");
        var stage = workspaceService.findOpenChapterStage(task.id());
        assertThat(stage).isNotNull();
        assertThat(stage.status()).isEqualTo(ChapterStageStatus.AWAITING_REVIEW);
        assertThat(workspaceService.listContents(task.id())).isEmpty();
        String runId = events.getFirst().getRunId();
        var run = agentRunRuntime.find(runId);
        assertThat(run.status().name()).isEqualTo("AWAITING_CONFIRM");
        assertThat(run.pendingInterrupts()).singleElement().satisfies(interrupt -> {
            assertThat(interrupt.toolName()).isEqualTo("commit_chapter");
            assertThat(interrupt.toolCallId()).isEqualTo("tool-call-1");
        });
        webClient().get()
                .uri("/api/tasks/{taskId}/pending-review", task.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.runId").isEqualTo(runId)
                .jsonPath("$.stageId").isEqualTo(stage.stageId())
                .jsonPath("$.title").isEqualTo("项目概况")
                .jsonPath("$.markdown").isEqualTo("## 项目概况\n\n第一轮正文")
                .jsonPath("$.interrupts[0].toolCallId").isEqualTo("tool-call-1");
    }

    @Test
    void resumesTheSameInterruptedRunWithAnApprovalDecision() {
        WritingTask task = workspaceService.createTask(
                "user-1", "# 完整参考文档", Map.of("资料.md", "背景事实"));
        StubAgentConfiguration.interruptNextRun.set(true);
        String interruptedRunId = roundRunner.run(task.id()).collectList().block().getFirst().getRunId();

        var events = roundRunner.resume(task.id(), interruptedRunId,
                List.of(new AgentRunDecision("tool-call-1", true))).collectList().block();

        assertThat(events).extracting(event -> event.getType().name())
                .containsExactly("RUN_STARTED", "TEXT_MESSAGE_CONTENT", "CUSTOM", "RUN_FINISHED");
        assertThat(workspaceService.findOpenChapterStage(task.id())).isNull();
        assertThat(workspaceService.listContents(task.id())).hasSize(1);
        assertThat(agentRunRuntime.find(interruptedRunId).status().name()).isEqualTo("FINISHED");
        assertThat(StubAgentConfiguration.sessions).hasSize(2).allMatch(session ->
                session.equals(StubAgentConfiguration.sessions.getFirst()));
        assertThat(events).allMatch(event -> interruptedRunId.equals(event.getRunId()));
    }

    @Test
    void rewritesTheSameChapterWhenReviewIsRejectedWithFeedback() {
        WritingTask task = workspaceService.createTask(
                "user-1", "# 完整参考文档", Map.of("资料.md", "背景事实"));
        StubAgentConfiguration.interruptNextRun.set(true);
        var interruptedEvents = roundRunner.run(task.id()).collectList().block();
        String interruptedRunId = interruptedEvents.getFirst().getRunId();
        String rejectedStageId = workspaceService.findOpenChapterStage(task.id()).stageId();

        var events = roundRunner.resume(task.id(), interruptedRunId,
                List.of(new AgentRunDecision("tool-call-1", false, "请补充实施依据并调整论证顺序")))
                .collectList().block();

        assertThat(events).extracting(event -> event.getType().name())
                .containsExactly("CUSTOM", "RUN_STARTED", "TEXT_MESSAGE_CONTENT", "RUN_FINISHED");
        assertThat(workspaceService.readChapterStage(task.id(), rejectedStageId)).contains("\"status\" : \"REJECTED\"");
        assertThat(workspaceService.listContents(task.id())).isEmpty();
        var rewritten = workspaceService.findOpenChapterStage(task.id());
        assertThat(rewritten.stageId()).isNotEqualTo(rejectedStageId);
        assertThat(rewritten.content().title()).isEqualTo("项目概况");
        assertThat(rewritten.content().sequence()).isEqualTo(1);
        assertThat(rewritten.status()).isEqualTo(ChapterStageStatus.AWAITING_REVIEW);
        assertThat(agentRunRuntime.find(interruptedRunId).status().name()).isEqualTo("AWAITING_CONFIRM");
        assertThat(events).allMatch(event -> interruptedRunId.equals(event.getRunId()));
        assertThat(StubAgentConfiguration.sessions).hasSize(2);
        assertThat(StubAgentConfiguration.sessions).allMatch(session ->
                session.equals(StubAgentConfiguration.sessions.getFirst()));
        assertThat(StubAgentConfiguration.messages.getLast()).contains("请补充实施依据并调整论证顺序");
        assertThat(StubAgentConfiguration.calls).hasValue(1);

        assertThatThrownBy(() -> roundRunner.resume(task.id(), interruptedRunId,
                List.of(new AgentRunDecision("tool-call-1", true))).collectList().block())
                .hasMessageContaining("pending tool call");
        roundRunner.resume(task.id(), interruptedRunId,
                List.of(new AgentRunDecision("tool-call-rewrite", true))).collectList().block();
        assertThat(workspaceService.listContents(task.id())).singleElement()
                .satisfies(content -> assertThat(content.title()).isEqualTo("项目概况"));
        assertThat(agentRunRuntime.find(interruptedRunId).status().name()).isEqualTo("FINISHED");
        assertThat(agentRunRuntime.find(interruptedRunId).pendingInterrupts()).isEmpty();
    }

    @Test
    void keepsTheRunAwaitingReviewWhenRevisionFeedbackIsInvalid() {
        WritingTask task = workspaceService.createTask(
                "user-1", "# 完整参考文档", Map.of("资料.md", "背景事实"));
        StubAgentConfiguration.interruptNextRun.set(true);
        String interruptedRunId = roundRunner.run(task.id()).collectList().block().getFirst().getRunId();

        assertThatThrownBy(() -> roundRunner.resume(task.id(), interruptedRunId,
                List.of(new AgentRunDecision("tool-call-1", false))).collectList().block())
                .hasMessageContaining("请填写审核意见");

        assertThat(agentRunRuntime.find(interruptedRunId).status().name()).isEqualTo("AWAITING_CONFIRM");
        assertThat(workspaceService.findOpenChapterStage(task.id()).status())
                .isEqualTo(ChapterStageStatus.AWAITING_REVIEW);
    }

    @Test
    void retrievesTaskScopedOriginalChunksBeforeSaving() {
        WritingTask task = workspaceService.createTask(
                "user-1",
                "# 参考文档\n\n采用正式表达。",
                Map.of("项目资料.md", "项目位于成都，需要记录操作审计日志。"));
        tempRagService.create(task.id());
        tempRagService.addDocument(task.id(), "项目资料.md", "项目资料.md",
                "项目位于成都，需要记录操作审计日志。").block();
        WritingToolContext context = new WritingToolContext(task.id());

        assertThatThrownBy(() -> researchTools.listOriginalDocuments(
                RuntimeContext.builder().sessionId("writing-parent").build(), context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source-research-agent");
        assertThatThrownBy(() -> researchTools.readOriginalDocument(
                "项目资料.md", 0, 100,
                RuntimeContext.builder().sessionId("writing-parent").build(), context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("source-research-agent");

        RuntimeContext childContext = RuntimeContext.builder()
                .sessionId("sub-fresh-research")
                .build();

        String original = researchTools.readOriginalDocument(
                "项目资料.md", 0, 100, childContext, context);
        assertThat(original).contains("项目资料.md", "操作审计日志");

        String evidence = researchTools.searchOriginalDocuments(
                "审计",
                null,
                5,
                childContext,
                context);

        assertThat(evidence).contains("项目资料.md", "chunk", "操作审计日志");
        writingTools.saveContent("项目概况", "正式表达", "## 项目概况\n\n正文", context);
        assertThat(workspaceService.listContents(task.id())).isEmpty();
    }

    @Test
    void isolatesAndDeletesTemporaryKnowledgeByTask() {
        WritingTask first = workspaceService.createTask("user-1", "# 参考文档", Map.of());
        WritingTask second = workspaceService.createTask("user-1", "# 参考文档", Map.of());
        tempRagService.create(first.id());
        tempRagService.create(second.id());
        tempRagService.addDocument(first.id(), "audit.md", "audit.md", "系统需要操作审计日志。").block();
        tempRagService.addDocument(second.id(), "budget.md", "budget.md", "项目预算需要按月审批。").block();

        assertThat(tempRagService.retrieve(first.id(), "审计", 3).block())
                .extracting(hit -> hit.filename())
                .containsExactly("audit.md");
        assertThat(tempRagService.retrieve(second.id(), "预算", 3).block())
                .extracting(hit -> hit.filename())
                .containsExactly("budget.md");

        tempRagService.delete(first.id());
        assertThatThrownBy(() -> tempRagService.retrieve(first.id(), "审计", 3))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("临时资料索引不存在");
    }

    @Test
    void splitsLongDocumentsIntoOverlappingChunks() {
        WritingTask task = workspaceService.createTask("user-1", "# 参考文档", Map.of());
        tempRagService.create(task.id());
        tempRagService.addDocument(task.id(), "audit.md", "audit.md",
                "操作审计日志必须保留。\n".repeat(100)).block();

        assertThat(tempRagService.retrieve(task.id(), "审计", 20).block())
                .extracting(hit -> hit.chunkId())
                .contains("audit.md-1", "audit.md-2");
    }

    @Test
    void exposesRoundAsReactiveSseStream() {
        WritingTask task = workspaceService.createTask(
                "user-1", "# 完整参考文档\n\n这是一篇成稿。", Map.of("资料.md", "背景事实"));

        var events = webClient().post()
                .uri("/api/tasks/{taskId}/rounds", task.id())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("message", "请先形成章节计划并写第一章"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block();

        assertThat(events).isNotEmpty();
        assertThat(events).anyMatch(event -> event.contains("RUN_STARTED"));
        assertThat(events).anyMatch(event -> event.contains("chapter.saved"));
        assertThat(StubAgentConfiguration.messages).containsExactly("请先形成章节计划并写第一章");
        assertThat(workspaceService.listContents(task.id())).hasSize(1);
    }

    @Test
    void flushesModelOutputBeforeTheRoundCompletes() {
        WritingTask task = workspaceService.createTask(
                "user-1", "# 完整参考文档\n\n这是一篇成稿。", Map.of("资料.md", "背景事实"));
        long startedAt = System.nanoTime();

        var firstEvents = webClient().post()
                .uri("/api/tasks/{taskId}/rounds", task.id())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("message", "trigger-delayed-stream"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .take(2)
                .collectList()
                .block();

        assertThat(firstEvents).anyMatch(event -> event.contains("RUN_STARTED"));
        assertThat(firstEvents).anyMatch(event -> event.contains("延迟输出"));
        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofMillis(850));
    }

    @Test
    void returnsAnSseErrorEventWhenTheAgentFailsAfterTheStreamStarts() {
        WritingTask task = workspaceService.createTask(
                "user-1", "# 完整参考文档\n\n这是一篇成稿。", Map.of("资料.md", "背景事实"));

        var events = webClient().post()
                .uri("/api/tasks/{taskId}/rounds", task.id())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("message", "trigger-stream-error"))
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class)
                .getResponseBody()
                .collectList()
                .block();

        assertThat(events).anyMatch(event -> event.contains("RUN_STARTED"));
        assertThat(events).anyMatch(event -> event.contains("RUN_ERROR"));
        assertThat(events).anyMatch(event -> event.contains("模拟流式执行失败"));
    }

    @Test
    void servesTheConversationWorkspacePage() {
        webClient().get()
                .uri("/")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_HTML)
                .expectBody(String.class)
                .value(html -> assertThat(html)
                        .contains("<div id=\"app\"></div>")
                        .contains("type=\"module\"")
                        .contains("/assets/index-"));
    }

    @Test
    void createsTaskFromACompleteReferenceDocument() {
        webClient().post()
                .uri("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "userId", "user-1",
                        "referenceDocument", "# 完整成稿\n\n这是一篇用于分析写法的完整参考文档。",
                        "sources", Map.of("资料.md", "这是待写内容的事实资料")))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.task.id").isNotEmpty()
                .jsonPath("$.sources[0]").isEqualTo("资料.md");
    }

    @Test
    void listsPreviousWritingTasks() {
        WritingTask task = workspaceService.createTask(
                "list-user", "# 完整参考文档\n\n参考成稿。", Map.of("资料.md", "背景事实"));

        var tasks = webClient().get()
                .uri("/api/tasks")
                .exchange()
                .expectStatus().isOk()
                .returnResult(WritingTaskView.class)
                .getResponseBody()
                .collectList()
                .block();

        assertThat(tasks).extracting(view -> view.task().id()).contains(task.id());
    }

    @Test
    void rejectsRunningAnOldTaskWithoutSources() {
        WritingTask task = workspaceService.createTask(
                "old-user", "# 旧参考文档\n\n旧任务没有背景资料。", Map.of());

        webClient().post()
                .uri("/api/tasks/{taskId}/rounds", task.id())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.error").isEqualTo("该任务没有目标背景资料，不能运行下一轮");
    }

    @Test
    void createsTaskFromUploadedDocxAndPdfFiles() throws IOException {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("userId", "upload-user");
        body.part("referenceDocument", docxFile("reference.docx", "Complete reference writing sample"));
        body.part("sourceFiles", pdfFile("background.pdf", "Target background facts"));

        WritingTaskView view = webClient().post()
                .uri("/api/tasks")
                .body(BodyInserters.fromMultipartData(body.build()))
                .exchange()
                .expectStatus().isOk()
                .returnResult(WritingTaskView.class)
                .getResponseBody()
                .blockFirst();

        assertThat(view).isNotNull();
        assertThat(workspaceService.readReferenceDocument(view.task().id()))
                .contains("Complete reference writing sample");
        assertThat(workspaceService.listSources(view.task().id()))
                .containsExactly("background.pdf");
        assertThat(tempRagService.retrieve(view.task().id(), "background", 3).block())
                .extracting(hit -> hit.content())
                .anyMatch(content -> content.contains("Target background facts"));
    }

    private WebTestClient webClient() {
        return WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + serverPort)
                .build();
    }

    private ByteArrayResource docxFile(String filename, String content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(content);
            document.write(output);
        }
        return namedFile(filename, output.toByteArray());
    }

    private ByteArrayResource pdfFile(String filename, String content) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(50, 700);
                stream.showText(content);
                stream.endText();
            }
            document.save(output);
        }
        return namedFile(filename, output.toByteArray());
    }

    private ByteArrayResource namedFile(String filename, byte[] content) {
        return new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    @TestConfiguration
    static class StubAgentConfiguration {

        static final AtomicInteger calls = new AtomicInteger();
        static final java.util.concurrent.atomic.AtomicBoolean interruptNextRun = new java.util.concurrent.atomic.AtomicBoolean();
        static final java.util.concurrent.atomic.AtomicBoolean skipCommitNextRun = new java.util.concurrent.atomic.AtomicBoolean();
        static final List<String> sessions = new CopyOnWriteArrayList<>();
        static final List<String> messages = new CopyOnWriteArrayList<>();

        @Bean
        @Primary
        TempRagEmbeddingModel embeddingModel() {
            return text -> {
                String normalized = text.toLowerCase();
                return Mono.just(normalized.contains("审计") || normalized.contains("认证")
                        ? new float[]{1F, 0F}
                        : new float[]{0F, 1F});
            };
        }

        @Bean
        @Primary
        WritingAgent writingAgent(WritingTools writingTools) {
            return new WritingAgent() {
                @Override
                public Flux<AguiEvent> streamRound(
                        WritingTask task,
                        String chapterSessionId,
                        String runId,
                        WritingRunCommand command,
                        WritingToolContext context) {
                    return Flux.defer(() -> {
                        sessions.add(chapterSessionId);
                        String message = command.userMessage();
                        messages.add(message);
                        if ("trigger-stream-error".equals(message)) {
                            return Flux.just(new AguiEvent.RunStarted(chapterSessionId, runId),
                                    new AguiEvent.RunError(chapterSessionId, runId,
                                            "模拟流式执行失败", "STUB_ERROR"));
                        }
                        if ("trigger-delayed-stream".equals(message)) {
                            return Flux.concat(
                                    Flux.just(new AguiEvent.RunStarted(chapterSessionId, runId)),
                                    Mono.delay(Duration.ofMillis(500)).map(ignored ->
                                            new AguiEvent.TextMessageContent(chapterSessionId, runId,
                                                    "message-" + runId, "延迟输出")),
                                    Mono.delay(Duration.ofMillis(500)).map(ignored ->
                                            new AguiEvent.RunFinished(chapterSessionId, runId)));
                        }
                        if (command.type() == WritingRunCommand.Type.REQUEST_CHAPTER_COMMIT) {
                            writingTools.commitChapter(command.stageId(), context);
                            return events(chapterSessionId, runId, "已请求章节审核");
                        }
                        context.markResearchLocated();
                        if (!writingTools.readDocumentSummary(context).contains("# 文档摘要")) {
                            writingTools.saveDocumentSummary("""
                                    # 文档摘要

                                    ## 参考文档风格
                                    正式、简洁、事实导向。

                                    ## 资料摘要
                                    背景资料用于形成项目概况和实施安排。
                                    """, context);
                        }
                        int call = calls.incrementAndGet();
                        if (call == 1) {
                            writingTools.saveContent("项目概况", "参考文档的背景展开方式", "## 项目概况\n\n第一轮正文", context);
                            writingTools.saveChapterMemory("# 项目概况记忆\n\n本章已经说明项目概况。", context);
                            writingTools.updateDocumentState("# 文档当前状态\n\n已经完成项目概况，下一步写实施安排。", context);
                            writingTools.updateWorkingPlan("""
                                    {
                                      "version": 1,
                                      "completed": ["项目概况"],
                                      "nextDirection": "实施安排",
                                      "remainingDirections": ["实施安排"],
                                      "adjustmentReason": "第一章已经完成"
                                    }
                                    """, context);
                            if (interruptNextRun.compareAndSet(true, false)) {
                                return Flux.just(new AguiEvent.RunStarted(chapterSessionId, runId),
                                        new AguiEvent.RunFinished(chapterSessionId, runId, null,
                                                new AguiEvent.RunFinishedInterruptOutcome(List.of(
                                                        new AguiEvent.Interrupt("interrupt-1", "ASK", "等待章节审核",
                                                                "tool-call-1", Map.of(), null, Map.of(
                                                                "toolName", "commit_chapter",
                                                                "toolInput", Map.of("stage_id", context.stageId())))))));
                            }
                            if (skipCommitNextRun.compareAndSet(true, false)) {
                                return events(chapterSessionId, runId, "正文和状态已暂存");
                            }
                            writingTools.commitChapter(context.stageId(), context);
                            return events(chapterSessionId, runId, "第一项已保存");
                        }
                        if (call == 2) {
                            writingTools.saveContent("实施安排", "参考文档的分段论述方式", "## 实施安排\n\n第二轮正文", context);
                            writingTools.saveChapterMemory("# 实施安排记忆\n\n本章已经说明实施安排。", context);
                            writingTools.updateDocumentState("# 文档当前状态\n\n项目概况和实施安排已经完成。", context);
                            writingTools.updateWorkingPlan("""
                                    {
                                      "version": 2,
                                      "completed": ["项目概况", "实施安排"],
                                      "nextDirection": "已完成",
                                      "remainingDirections": [],
                                      "adjustmentReason": "计划内容已经覆盖"
                                    }
                                    """, context);
                            writingTools.commitChapter(context.stageId(), context);
                            return events(chapterSessionId, runId, "第二项已保存");
                        }
                        return events(chapterSessionId, runId, "资料中的有效内容已经覆盖完成");
                    });
                }

                @Override
                public Flux<AguiEvent> resumeRound(
                        WritingTask task,
                        String chapterSessionId,
                        String runId,
                        List<io.agentscope.core.agui.model.AguiResume> resume,
                        Map<String, String> resumeToolCallIds,
                        WritingToolContext context,
                        String message) {
                    return Flux.defer(() -> {
                        sessions.add(chapterSessionId);
                        messages.add(message);
                        if (resume.stream().anyMatch(decision -> !decision.isResolved())) {
                            assertThat(context.stage()).isNull();
                            assertThat(resume.getFirst().getPayload()).isInstanceOf(Map.class);
                            assertThat(((Map<?, ?>) resume.getFirst().getPayload()).get("approved")).isEqualTo(false);
                            context.markResearchLocated();
                            writingTools.saveContent("项目概况", "根据审核意见重写", "## 项目概况\n\n补充实施依据后的正文", context);
                            writingTools.saveChapterMemory("# 项目概况记忆\n\n补充实施依据。", context);
                            writingTools.updateDocumentState("# 文档当前状态\n\n项目概况已重写。", context);
                            writingTools.updateWorkingPlan("""
                                    {"version":1,"completed":["项目概况"],"nextDirection":"实施安排",
                                     "remainingDirections":["实施安排"],"adjustmentReason":"根据审核意见重写"}
                                    """, context);
                            return Flux.just(new AguiEvent.RunStarted(chapterSessionId, runId),
                                    new AguiEvent.TextMessageContent(chapterSessionId, runId, "rewrite-" + runId, "已重写，等待审核"),
                                    new AguiEvent.RunFinished(chapterSessionId, runId, null,
                                            new AguiEvent.RunFinishedInterruptOutcome(List.of(
                                                    new AguiEvent.Interrupt("interrupt-rewrite", "ASK", "等待重写审核",
                                                            "tool-call-rewrite", Map.of(), null, Map.of(
                                                            "toolName", "commit_chapter",
                                                            "toolInput", Map.of("stage_id", context.stageId())))))));
                        }
                        writingTools.commitChapter(context.stageId(), context);
                        return events(chapterSessionId, runId, "章节审核已通过");
                    });
                }

                private Flux<AguiEvent> events(String chapterSessionId, String runId, String text) {
                    return Flux.just(new AguiEvent.RunStarted(chapterSessionId, runId),
                            new AguiEvent.TextMessageContent(chapterSessionId, runId, "message-" + runId, text),
                            new AguiEvent.RunFinished(chapterSessionId, runId));
                }
            };
        }
    }
}

