package com.example.incremental.agent;

import io.agentscope.core.agui.adapter.strategy.AgentEventConverter;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps DashScope's id-less streaming tool-argument fragments attached to their tool call. */
final class StreamingToolCallAguiEventConverter implements AgentEventConverter {

    private final Map<String, String> activeToolCalls = new ConcurrentHashMap<>();

    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        return Set.of(ToolCallStartEvent.class, ToolCallDeltaEvent.class, ToolCallEndEvent.class);
    }

    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {
        if (event instanceof ToolCallStartEvent start) {
            if (!isFragment(start.getToolCallName())) {
                activeToolCalls.put(start.getReplyId(), start.getToolCallId());
                context.startToolCall(start.getToolCallId(), start.getToolCallName());
            }
            return;
        }
        if (event instanceof ToolCallDeltaEvent delta) {
            String toolCallId = isFragment(delta.getToolCallName())
                    ? activeToolCalls.get(delta.getReplyId())
                    : delta.getToolCallId();
            if (toolCallId != null) {
                context.appendToolCallArgs(toolCallId, delta.getDelta());
            }
            return;
        }
        ToolCallEndEvent end = (ToolCallEndEvent) event;
        if (!isFragment(end.getToolCallName())) {
            context.endToolCall(end.getToolCallId());
            activeToolCalls.remove(end.getReplyId(), end.getToolCallId());
        }
    }

    private boolean isFragment(String toolCallName) {
        return toolCallName != null && toolCallName.startsWith("__");
    }
}
