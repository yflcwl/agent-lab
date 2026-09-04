package com.example.incremental.runtime;

import io.agentscope.core.agui.adapter.strategy.AgentEventConverter;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.ToolUseBlock;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Converts AgentScope permission HITL events into AG-UI run interrupts. */
public final class RequireUserConfirmAguiEventConverter implements AgentEventConverter {

    @Override
    public Set<Class<? extends AgentEvent>> eventTypes() {
        return Set.of(RequireUserConfirmEvent.class);
    }

    @Override
    public void convert(AgentEvent event, AguiStreamContext context) {
        RequireUserConfirmEvent confirmation = (RequireUserConfirmEvent) event;
        confirmation.getToolCalls().forEach(toolCall -> context.addInterrupt(toInterrupt(confirmation.getReplyId(), toolCall)));
    }

    public static AguiEvent.Interrupt toInterrupt(String replyId, ToolUseBlock toolCall) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("toolName", toolCall.getName());
        if (toolCall.getInput() != null && !toolCall.getInput().isEmpty()) {
            metadata.put("toolInput", toolCall.getInput());
        }
        if (replyId != null && !replyId.isBlank()) {
            metadata.put("replyId", replyId);
        }
        return new AguiEvent.Interrupt(
                interruptId(replyId, toolCall.getId()),
                "tool_call",
                "等待用户确认工具调用：" + toolCall.getName(),
                toolCall.getId(),
                null,
                null,
                Map.copyOf(metadata));
    }

    private static String interruptId(String replyId, String toolCallId) {
        return replyId == null || replyId.isBlank() ? toolCallId : replyId + ":" + toolCallId;
    }
}

