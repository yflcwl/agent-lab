package com.example.incremental.agent;

import com.example.incremental.config.DemoProperties;
import com.example.incremental.persistence.writing.InMemoryChapterStageRepository;
import com.example.incremental.persistence.agent.AgentHistoryService;
import com.example.incremental.runtime.AgentRunDecision;
import com.example.incremental.runtime.AgentRunRuntime;
import com.example.incremental.runtime.AgentRunStatus;
import com.example.incremental.runtime.AgentRunEventRecorder;
import com.example.incremental.runtime.InMemoryAgentRunRepository;
import com.example.incremental.runtime.RoundRunner;
import com.example.incremental.workspace.TaskWorkspaceService;
import com.example.incremental.writing.ChapterStageCoordinator;
import com.example.incremental.writing.ChapterStageStatus;
import com.example.incremental.writing.WritingTask;
import com.example.incremental.writing.WritingToolContext;
import com.example.incremental.writing.WritingWorkflow;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uses the real AgentScope loop, permission gate, tools and JSON state store; only the LLM is scripted. */
class AgentScopeHitlFlowTest {

    @TempDir
    Path directory;
    private TaskWorkspaceService workspace;
    private AgentRunRuntime runtime;
    private RoundRunner runner;
    private HarnessAgent harness;
    private WritingTask task;
    private final ScriptedModel model = new ScriptedModel();

    @BeforeEach
    void setUp() {
        DemoProperties properties = new DemoProperties();
        properties.setDataRoot(directory.resolve("data"));
        properties.setStateRoot(directory.resolve("state"));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        workspace = new TaskWorkspaceService(mapper, properties, new InMemoryChapterStageRepository());
        ChapterStageCoordinator coordinator = new ChapterStageCoordinator(workspace);
        WritingTools writingTools = new WritingTools(workspace, coordinator);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(writingTools);
        toolkit.registerTool(new CandidateTools(writingTools));
        PermissionContextState permissions = PermissionContextState.builder().mode(PermissionMode.BYPASS)
                .addAskRule("commit_chapter", new PermissionRule(
                        "commit_chapter", null, PermissionBehavior.ASK, "chapter-review"))
                .build();
        harness = HarnessAgent.builder().name("hitl-test").agentId("hitl-test")
                .model(model).sysPrompt("按用户反馈修改当前章节，提交前必须审核。")
                .workspace(directory.resolve("workspace"))
                .stateStore(new JsonFileAgentStateStore(properties.getStateRoot()))
                .toolkit(toolkit).permissionContext(permissions)
                .enablePendingToolRecovery(true).stopOnReject(false)
                .middleware(new AgentScopeResumeMiddleware()).maxIters(10)
                .disableFilesystemTools().disableMemoryHooks().disableMemoryTools()
                .disableShellTool().disableDefaultWorkspaceSkills().disableToolsConfig().build();
        runtime = new AgentRunRuntime(new InMemoryAgentRunRepository(), new AgentRunEventRecorder(mapper,
                new DefaultListableBeanFactory().getBeanProvider(AgentHistoryService.class)));
        runner = new RoundRunner(new WritingWorkflow(workspace, coordinator, properties), runtime,
                new AgentExecutor(new AgentScopeWritingAgent(harness, Duration.ofSeconds(15), permissions)));
        task = workspace.createTask("hitl-user", "# 参考文档", Map.of("资料.md", "项目背景资料"));
    }

    @AfterEach
    void close() {
        harness.close();
    }

    @Test
    void approvalConsumesPersistedAskAndExecutesOriginalCommitToolExactlyOnce() throws Exception {
        enqueueCandidate("original", "第一版正文");
        var initial = runner.run(task.id()).collectList().block(Duration.ofSeconds(20));
        String runId = initial.getFirst().getRunId();
        assertThat(initial).as("Initial events: %s; model inputs: %s", initial, model.inputs)
                .noneMatch(AguiEvent.RunError.class::isInstance);
        assertThat(runtime.find(runId).status()).isEqualTo(AgentRunStatus.AWAITING_CONFIRM);
        String stageId = workspace.findOpenChapterStage(task.id()).stageId();
        assertThat(workspace.listContents(task.id())).isEmpty();
        assertThat(toolCalls(runId)).filteredOn(call -> call.getState() == ToolCallState.ASKING)
                .extracting(ToolUseBlock::getId).containsExactly("commit-original");
        model.responses.add(() -> TextBlock.builder().text("章节提交完成").build());

        var resumed = runner.resume(task.id(), runId, List.of(new AgentRunDecision("commit-original", true)))
                .collectList().block(Duration.ofSeconds(20));

        assertThat(resumed).noneMatch(AguiEvent.RunError.class::isInstance)
                .allMatch(event -> runId.equals(event.getRunId()));
        assertThat(workspace.readChapterStage(task.id(), stageId)).contains("\"COMMITTED\"");
        assertThat(workspace.listContents(task.id())).hasSize(1);
        assertThat(runtime.find(runId).status()).isEqualTo(AgentRunStatus.FINISHED);
        assertThat(runtime.find(runId).pendingInterrupts()).isEmpty();
        assertThat(toolCalls(runId)).noneMatch(call -> call.getState() == ToolCallState.ASKING);
        assertThat(toolResults(runId)).filteredOn(result -> "commit-original".equals(result.getId()))
                .singleElement().satisfies(result -> assertThat(result.getState()).isEqualTo(ToolResultState.SUCCESS));
        assertThatThrownBy(() -> runner.resume(task.id(), runId,
                List.of(new AgentRunDecision("commit-original", true))).collectList().block())
                .hasMessageContaining("不在等待确认状态");
        assertThat(directory.resolve("state/runs")).doesNotExist();
    }

    @Test
    void rejectionConsumesOldAskAndRewritesInSameRunAndSessionBeforeNewApproval() throws Exception {
        enqueueCandidate("original", "第一版正文");
        var initial = runner.run(task.id()).collectList().block(Duration.ofSeconds(20));
        String runId = initial.getFirst().getRunId();
        assertThat(initial).as("Initial events: %s; model inputs: %s", initial, model.inputs)
                .noneMatch(AguiEvent.RunError.class::isInstance);
        String sessionId = runtime.find(runId).threadId();
        String oldStageId = workspace.findOpenChapterStage(task.id()).stageId();
        toolCalls(runId); // Force a reload of the persisted ASK, as after a process restart.
        int firstResumedModelInput = model.inputs.size();
        enqueueCandidate("rewrite", "补充实施依据后的正文");

        var rewrittenEvents = runner.resume(task.id(), runId,
                List.of(new AgentRunDecision("commit-original", false, "请补充实施依据")))
                .collectList().block(Duration.ofSeconds(20));

        assertThat(rewrittenEvents).noneMatch(AguiEvent.RunError.class::isInstance)
                .allMatch(event -> runId.equals(event.getRunId()) && sessionId.equals(event.getThreadId()));
        assertThat(workspace.readChapterStage(task.id(), oldStageId)).contains("\"REJECTED\"");
        assertThat(workspace.listContents(task.id())).isEmpty();
        var candidate = workspace.findOpenChapterStage(task.id());
        assertThat(candidate.stageId()).isNotEqualTo(oldStageId);
        assertThat(candidate.content().sequence()).isEqualTo(1);
        assertThat(candidate.content().title()).isEqualTo("项目概况");
        assertThat(candidate.status()).isEqualTo(ChapterStageStatus.AWAITING_REVIEW);
        assertThat(runtime.find(runId).pendingInterrupts()).extracting(interrupt -> interrupt.toolCallId())
                .containsExactly("commit-rewrite");
        assertThat(toolCalls(runId)).filteredOn(call -> call.getState() == ToolCallState.ASKING)
                .extracting(ToolUseBlock::getId).containsExactly("commit-rewrite");
        assertThat(toolResults(runId)).filteredOn(result -> "commit-original".equals(result.getId()))
                .singleElement().satisfies(result -> assertThat(result.getState()).isEqualTo(ToolResultState.DENIED));
        assertThat(model.inputs.get(firstResumedModelInput).stream().flatMap(msg -> msg.getContentBlocks(TextBlock.class).stream())
                .map(TextBlock::getText)).anyMatch(text -> text.contains("请补充实施依据") && text.contains("旧 Stage"));
        assertThat(persistedContext(runId).stream().flatMap(msg -> msg.getContentBlocks(TextBlock.class).stream())
                .map(TextBlock::getText).filter(text -> text.contains("审核意见："))).hasSize(1);

        model.responses.add(() -> TextBlock.builder().text("修改稿提交完成").build());
        runner.resume(task.id(), runId, List.of(new AgentRunDecision("commit-rewrite", true)))
                .collectList().block(Duration.ofSeconds(20));
        assertThat(toolCalls(runId)).noneMatch(call -> call.getState() == ToolCallState.ASKING);
        assertThat(workspace.listContents(task.id())).hasSize(1);
        assertThat(runtime.find(runId).status()).isEqualTo(AgentRunStatus.FINISHED);
        assertThat(directory.resolve("state/runs")).doesNotExist();
    }

    private void enqueueCandidate(String id, String text) {
        model.responses.add(() -> new ToolUseBlock("prepare-" + id, "prepare_candidate", Map.of("text", text)));
        model.responses.add(() -> new ToolUseBlock("commit-" + id, "commit_chapter",
                Map.of("stage_id", workspace.findOpenChapterStage(task.id()).stageId())));
    }

    private List<Msg> persistedContext(String runId) {
        String sessionId = runtime.find(runId).threadId();
        harness.clearStateCache(task.userId(), sessionId);
        return harness.getDelegate().getAgentState(task.userId(), sessionId).getContext();
    }

    private List<ToolUseBlock> toolCalls(String runId) {
        return persistedContext(runId).stream().flatMap(msg -> msg.getContentBlocks(ToolUseBlock.class).stream()).toList();
    }

    private List<ToolResultBlock> toolResults(String runId) {
        return persistedContext(runId).stream().flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream()).toList();
    }

    public static class CandidateTools {
        private final WritingTools writingTools;

        CandidateTools(WritingTools writingTools) {
            this.writingTools = writingTools;
        }

        @Tool(name = "prepare_candidate", description = "测试中暂存完整章节候选")
        public String prepare(@ToolParam(name = "text", description = "正文") String text, WritingToolContext context) {
            context.markResearchLocated();
            writingTools.saveContent("项目概况", "正式文风", "## 项目概况\n\n" + text, context);
            writingTools.saveChapterMemory("# 章节记忆\n\n" + text, context);
            writingTools.updateDocumentState("# 文档状态\n\n" + text, context);
            writingTools.updateWorkingPlan("""
                    {"version":1,"completed":["项目概况"],"nextDirection":"实施安排",
                     "remainingDirections":["实施安排"],"adjustmentReason":"当前章节已暂存"}
                    """, context);
            return context.stageId();
        }
    }

    private static class ScriptedModel implements Model {
        final Queue<Supplier<ContentBlock>> responses = new ArrayDeque<>();
        final List<List<Msg>> inputs = new ArrayList<>();

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            inputs.add(List.copyOf(messages));
            ContentBlock block = responses.remove().get();
            if (block instanceof ToolUseBlock tool) {
                block = new ToolUseBlock(tool.getId(), tool.getName(), tool.getInput(),
                        io.agentscope.core.util.JsonUtils.getJsonCodec().toJson(tool.getInput()), null);
            }
            return Flux.just(ChatResponse.builder().content(List.of(block)).build());
        }

        @Override
        public String getModelName() {
            return "scripted-hitl-test";
        }
    }
}
