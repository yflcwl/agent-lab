package com.example.incremental.agent;

import com.example.incremental.writing.ChapterStage;
import com.example.incremental.writing.WritingTask;
import com.example.incremental.writing.WritingAgent;
import com.example.incremental.writing.WritingRunCommand;
import com.example.incremental.writing.WritingToolContext;
import com.example.incremental.runtime.RequireUserConfirmAguiEventConverter;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.adapter.strategy.AgentEventConverterRegistry;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.model.AguiResume;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.SubagentExposedEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.permission.PermissionContextState;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AgentScopeWritingAgent implements WritingAgent {

    private static final String ROUND_REQUEST = """
            这是当前写作任务的一章独立工作。同一章内的用户追问或失败重试会继续使用当前 Session；只有本章正文及状态完整保存后，下一章才使用新 Session。

            本轮用户消息：
            <user_message>
            %s
            </user_message>

            %s

            首次执行本章时，先加载适用于当前增量写作任务的工作区 skill，再按其流程完成本章。
            如果是同一章内的继续执行，基于当前 Session 中已有的工作和用户新消息继续，不要无条件重启已经完成的步骤。
            若技能流程判断全部必要章节均已完成，不要调用 save_content，直接说明完成依据。
            当本章正文、章节记忆、滚动状态和临时计划都已暂存后，必须调用 commit_chapter(stage_id) 请求用户审核；不要自行宣布提交成功。
            """;

    private static final String RECOVER_STAGE_REQUEST = """
            执行内部命令：RECOVER_STAGE。

            目标 ChapterStage：%s。
            这是一次受限的故障恢复，不接收也不处理新的用户写作要求。先调用 read_staged_chapter，确认候选正文和缺失字段；随后仅补齐缺失的 save_chapter_memory、update_document_state、update_working_plan。
            不得调用资料 Agent、原文读取、save_document_summary 或 save_content，不得重写正文。候选 Stage 完整后必须调用 commit_chapter(stage_id) 请求用户审核。
            """;

    private static final String REQUEST_CHAPTER_COMMIT = """
            执行内部命令：REQUEST_CHAPTER_COMMIT。

            目标 ChapterStage：%s。先调用 read_staged_chapter 核对该 Stage 已完整，然后只调用 commit_chapter(stage_id) 请求用户审核。不得改写正文、章节记忆、滚动状态或临时计划，不接收也不处理新的用户写作要求。
            """;

    private final HarnessAgent agent;
    private final AguiAgentAdapter adapter;
    private final AguiAdapterConfig adapterConfig;
    private final AgentEventConverterRegistry eventConverterRegistry;
    private final PermissionContextState permissionContext;

    public AgentScopeWritingAgent(
            HarnessAgent agent,
            Duration timeout,
            PermissionContextState permissionContext) {
        this.agent = agent;
        this.permissionContext = permissionContext;
        this.adapterConfig = AguiAdapterConfig.builder()
                .enableReasoning(true)
                .emitToolCallArgs(true)
                .runTimeout(timeout)
                .addEventConverter(new StreamingToolCallAguiEventConverter())
                .addEventConverter(new RequireUserConfirmAguiEventConverter())
                .baseEventPropertiesEnricherEnabled(true)
                .build();
        this.adapter = new AguiAgentAdapter(agent, adapterConfig);
        this.eventConverterRegistry = new AgentEventConverterRegistry(
                adapterConfig.getEventConverters(), adapterConfig.getEventEnrichers(),
                adapterConfig.isEmitSubagentEventsAsNative());
    }

    @Override
    public Flux<AguiEvent> streamRound(
            WritingTask task,
            String chapterSessionId,
            String runId,
            WritingRunCommand command,
            WritingToolContext toolContext) {
        replacePermissionContext(task, chapterSessionId);
        List<ToolUseBlock> pending = List.copyOf(pendingAskingToolCalls(task, chapterSessionId).values());
        if (!pending.isEmpty()) {
            return Flux.just(
                    new AguiEvent.RunStarted(chapterSessionId, runId),
                    new AguiEvent.RunFinished(chapterSessionId, runId, null,
                            new AguiEvent.RunFinishedInterruptOutcome(pending.stream()
                                    .map(tool -> RequireUserConfirmAguiEventConverter.toInterrupt(null, tool))
                                    .toList())));
        }
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId(task.userId())
                .sessionId(chapterSessionId)
                .put(WritingToolContext.class, toolContext)
                .build();
        String request = switch (command.type()) {
            case RECOVER_STAGE -> recoveryRequest(command, toolContext);
            case REQUEST_CHAPTER_COMMIT -> requestChapterCommit(command, toolContext);
            case WRITE_CHAPTER -> ROUND_REQUEST.formatted(
                    command.userMessage().isBlank()
                            ? "没有补充要求，请按当前临时章节计划继续。"
                            : command.userMessage(),
                    "");
        };
        RunAgentInput input = RunAgentInput.builder()
                .threadId(chapterSessionId)
                .runId(runId)
                .messages(List.of(AguiMessage.userMessage("user-" + runId,
                        request)))
                .build();
        return adapter.run(input, runtimeContext)
                .map(AgentScopeWritingAgent::expandSubagentRawEvent)
                .doFinally(signal -> agent.clearStateCache(task.userId(), chapterSessionId));
    }

    private String recoveryRequest(WritingRunCommand command, WritingToolContext toolContext) {
        ChapterStage recoveryStage = toolContext.recoveryStage();
        if (recoveryStage == null || !command.stageId().equals(recoveryStage.stageId())) {
            throw new IllegalStateException("RECOVER_STAGE 与当前 ChapterStage 不一致");
        }
        return RECOVER_STAGE_REQUEST.formatted(command.stageId());
    }

    private String requestChapterCommit(WritingRunCommand command, WritingToolContext toolContext) {
        ChapterStage stage = toolContext.recoveryStage();
        if (stage == null || !stage.isComplete() || !command.stageId().equals(stage.stageId())) {
            throw new IllegalStateException("REQUEST_CHAPTER_COMMIT 与当前完整 ChapterStage 不一致");
        }
        return REQUEST_CHAPTER_COMMIT.formatted(command.stageId());
    }

    @Override
    public Flux<AguiEvent> resumeRound(
            WritingTask task,
            String chapterSessionId,
            String runId,
            List<AguiResume> resume,
            Map<String, String> resumeToolCallIds,
            WritingToolContext toolContext,
            String message) {
        replacePermissionContext(task, chapterSessionId);
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId(task.userId())
                .sessionId(chapterSessionId)
                .put(WritingToolContext.class, toolContext)
                .build();
        RunAgentInput input = RunAgentInput.builder()
                .threadId(chapterSessionId)
                .runId(runId)
                .resume(resume)
                .build();
        UserMessage confirmation = UserMessage.builder()
                .textContent(message)
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS,
                        toConfirmResults(resume, resumeToolCallIds,
                                pendingAskingToolCalls(task, chapterSessionId))))
                .build();
        if (message != null && !message.isBlank()) {
            runtimeContext.put(AgentScopeResumeMiddleware.Feedback.class,
                    new AgentScopeResumeMiddleware.Feedback(new UserMessage(message)));
        }
        AguiStreamContext streamContext = new AguiStreamContext(
                chapterSessionId, runId, adapterConfig, input);
        return agent.streamEvents(confirmation, runtimeContext)
                .concatMapIterable(event -> eventConverterRegistry.convert(event, streamContext))
                .concatWith(Flux.defer(() -> Flux.fromIterable(eventConverterRegistry.enrich(
                        null, streamContext.finishPendingEvents(), streamContext))))
                .map(AgentScopeWritingAgent::expandSubagentRawEvent)
                .doFinally(signal -> agent.clearStateCache(task.userId(), chapterSessionId));
    }

    static AguiEvent expandSubagentRawEvent(AguiEvent event) {
        if (!(event instanceof AguiEvent.Raw raw) || !(raw.event() instanceof AgentEvent agentEvent)) {
            return event;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("source", raw.source());
        value.put("type", agentEvent.getType().name());
        if (agentEvent instanceof ToolCallDeltaEvent toolCall) {
            value.put("toolCallId", toolCall.getToolCallId());
            value.put("toolName", toolCall.getToolCallName());
            value.put("delta", toolCall.getDelta());
            return subagentCustom(raw, "subagent.tool_args", value);
        }
        if (agentEvent instanceof ToolResultStartEvent toolResult) {
            value.put("toolCallId", toolResult.getToolCallId());
            value.put("toolName", toolResult.getToolCallName());
            return subagentCustom(raw, "subagent.tool_result", value);
        }
        if (agentEvent instanceof ToolResultTextDeltaEvent toolResult) {
            value.put("toolCallId", toolResult.getToolCallId());
            value.put("toolName", toolResult.getToolCallName());
            value.put("delta", toolResult.getDelta());
            return subagentCustom(raw, "subagent.tool_result", value);
        }
        if (agentEvent instanceof ToolResultDataDeltaEvent toolResult) {
            value.put("toolCallId", toolResult.getToolCallId());
            value.put("toolName", toolResult.getToolCallName());
            value.put("data", String.valueOf(toolResult.getData()));
            return subagentCustom(raw, "subagent.tool_result", value);
        }
        if (agentEvent instanceof ModelCallStartEvent modelCall) {
            value.put("replyId", modelCall.getReplyId());
            return subagentCustom(raw, "subagent.model_call", value);
        }
        if (agentEvent instanceof ModelCallEndEvent modelCall) {
            value.put("replyId", modelCall.getReplyId());
            if (modelCall.getUsage() != null) {
                value.put("usage", modelCall.getUsage().toString());
            }
            return subagentCustom(raw, "subagent.model_call", value);
        }
        if (agentEvent instanceof SubagentExposedEvent subagent) {
            value.put("subagentId", subagent.getSubagentId());
            value.put("agentId", subagent.getAgentId());
            value.put("sessionId", subagent.getSessionId());
            value.put("label", subagent.getLabel());
            return subagentCustom(raw, "subagent.exposed", value);
        }
        return subagentCustom(raw, "subagent.event", value);
    }

    private static AguiEvent.Custom subagentCustom(
            AguiEvent.Raw raw,
            String name,
            Map<String, Object> value) {
        return new AguiEvent.Custom(raw.threadId(), raw.runId(), name, value,
                raw.timestamp(), raw.rawEvent());
    }

    static List<ConfirmResult> toConfirmResults(
            List<AguiResume> resume,
            Map<String, String> resumeToolCallIds,
            Map<String, ToolUseBlock> pendingToolCalls) {
        var confirmedIds = resume.stream().map(decision -> resumeToolCallIds.get(decision.getInterruptId()))
                .collect(java.util.stream.Collectors.toSet());
        if (resume.isEmpty() || confirmedIds.size() != resume.size()
                || !confirmedIds.equals(pendingToolCalls.keySet())) {
            throw new IllegalArgumentException("resume 必须消费当前 Session 的全部 ASK tool call，且不能重复确认");
        }
        return resume.stream().map(decision -> {
            String toolCallId = resumeToolCallIds.get(decision.getInterruptId());
            ToolUseBlock toolCall = pendingToolCalls.get(toolCallId);
            if (toolCall == null) {
                throw new IllegalArgumentException(
                        "找不到待确认的 AgentScope tool call: " + toolCallId);
            }
            boolean approved = decision.isResolved();
            if (decision.getPayload() instanceof Map<?, ?> payload
                    && payload.get("approved") instanceof Boolean value) {
                approved = value;
            }
            return new ConfirmResult(approved, toolCall);
        }).toList();
    }

    private void replacePermissionContext(WritingTask task, String chapterSessionId) {
        agent.getDelegate().replacePermissionContext(task.userId(), chapterSessionId, permissionContext);
    }

    private Map<String, ToolUseBlock> pendingAskingToolCalls(WritingTask task, String chapterSessionId) {
        Map<String, ToolUseBlock> pending = new LinkedHashMap<>();
        List<Msg> context = agent.getDelegate().getAgentState(task.userId(), chapterSessionId).getContext();
        var resolvedIds = context.stream().flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                .map(ToolResultBlock::getId).collect(java.util.stream.Collectors.toSet());
        context.stream()
                .flatMap(message -> message.getContent().stream())
                .filter(ToolUseBlock.class::isInstance)
                .map(ToolUseBlock.class::cast)
                .filter(toolCall -> toolCall.getState() == ToolCallState.ASKING)
                .filter(toolCall -> !resolvedIds.contains(toolCall.getId()))
                .forEach(toolCall -> pending.put(toolCall.getId(), toolCall));
        return Map.copyOf(pending);
    }

}

