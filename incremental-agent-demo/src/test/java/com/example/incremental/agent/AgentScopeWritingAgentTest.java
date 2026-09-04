package com.example.incremental.agent;

import io.agentscope.core.agui.model.AguiResume;
import io.agentscope.core.agui.adapter.strategy.AgentEventConverterRegistry;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentScopeWritingAgentTest {

    @Test
    void expandsSubagentToolArgumentEventsInsteadOfEmittingRawEvents() {
        ToolCallDeltaEvent agentEvent = new ToolCallDeltaEvent("reply-1", "call-1", "search", "{\"query\":\"消防\"}");
        agentEvent.withSource("source-research-agent");
        AguiEvent event = new AguiEvent.Raw("thread-1", "run-1", agentEvent, agentEvent.getSource());

        AguiEvent expanded = AgentScopeWritingAgent.expandSubagentRawEvent(event);

        assertThat(expanded).isInstanceOf(AguiEvent.Custom.class);
        AguiEvent.Custom custom = (AguiEvent.Custom) expanded;
        assertThat(custom.name()).isEqualTo("subagent.tool_args");
        assertThat(custom.value()).isEqualTo(Map.of(
                "source", "source-research-agent",
                "type", "TOOL_CALL_DELTA",
                "toolCallId", "call-1",
                "toolName", "search",
                "delta", "{\"query\":\"消防\"}"));
    }

    @Test
    void convertsAguiResumeIntoAgentScopeConfirmResult() {
        ToolUseBlock toolCall = new ToolUseBlock(
                "call-1", "commit_chapter", Map.of("stage_id", "stage-1"))
                .withState(ToolCallState.ASKING);

        var results = AgentScopeWritingAgent.toConfirmResults(
                List.of(new AguiResume("reply-1:call-1", AguiResume.STATUS_RESOLVED,
                        Map.of("approved", true))),
                Map.of("reply-1:call-1", "call-1"),
                Map.of("call-1", toolCall));

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.isConfirmed()).isTrue();
            assertThat(result.getToolCall()).isSameAs(toolCall);
        });
    }

    @Test
    void keepsDashScopeToolArgumentFragmentsInTheOriginalStream() {
        AgentEventConverterRegistry registry = new AgentEventConverterRegistry(
                List.of(new StreamingToolCallAguiEventConverter()), List.of());
        AguiStreamContext context = new AguiStreamContext("thread-1", "run-1",
                io.agentscope.core.agui.adapter.AguiAdapterConfig.builder().build());

        var start = registry.convert(new ToolCallStartEvent("reply-1", "call-1", "save_content"), context);
        var first = registry.convert(new ToolCallDeltaEvent(
                "reply-1", "call-1", "save_content", "{\"content\":\"第"), context);
        var fragment = registry.convert(new ToolCallDeltaEvent(
                "reply-1", "fragment-2", "__fragment__", "一章\"}"), context);
        var end = registry.convert(new ToolCallEndEvent("reply-1", "call-1", "save_content"), context);

        assertThat(start).singleElement().isInstanceOf(AguiEvent.ToolCallStart.class);
        assertThat(first).singleElement().isInstanceOf(AguiEvent.ToolCallArgs.class)
                .extracting(value -> ((AguiEvent.ToolCallArgs) value).toolCallId())
                .isEqualTo("call-1");
        assertThat(fragment).singleElement().isInstanceOf(AguiEvent.ToolCallArgs.class)
                .extracting(value -> ((AguiEvent.ToolCallArgs) value).toolCallId())
                .isEqualTo("call-1");
        assertThat(end).singleElement().isInstanceOf(AguiEvent.ToolCallEnd.class);
    }
}

