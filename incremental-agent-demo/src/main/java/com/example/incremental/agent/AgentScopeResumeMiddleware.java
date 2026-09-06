package com.example.incremental.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Compatibility for AgentScope 2.0.1 confirmation resumes; contains no workflow decisions. */
public class AgentScopeResumeMiddleware implements MiddlewareBase {

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent, RuntimeContext ctx, ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        // ConfirmResult(false) has already been consumed by AgentScope here. Only close calls
        // backed by its actual DENIED result; never clear or approve an outstanding ASK.
        var context = ctx.getAgentState().contextMutable();
        var deniedIds = context.stream().flatMap(msg -> msg.getContentBlocks(ToolResultBlock.class).stream())
                .filter(result -> result.getState() == ToolResultState.DENIED)
                .map(ToolResultBlock::getId).collect(Collectors.toSet());
        if (!deniedIds.isEmpty()) {
            for (int i = 0; i < context.size(); i++) {
                Msg msg = context.get(i);
                if (msg.getContentBlocks(ToolUseBlock.class).stream().anyMatch(tool ->
                        tool.getState() == ToolCallState.ASKING && deniedIds.contains(tool.getId()))) {
                    context.set(i, msg.withContent(msg.getContent().stream().map(block ->
                            block instanceof ToolUseBlock tool && tool.getState() == ToolCallState.ASKING
                                    && deniedIds.contains(tool.getId())
                                    ? tool.withState(ToolCallState.FINISHED) : block).toList()));
                }
            }
        }
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent, RuntimeContext ctx, ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        Feedback feedback = ctx.get(Feedback.class);
        if (feedback == null) {
            return next.apply(input);
        }
        // 2.0.1 discards message content on the ASK branch. Append it once, after tool results,
        // both to the persisted session and to this first resumed model request.
        ctx.put(Feedback.class, null);
        ctx.getAgentState().contextMutable().add(feedback.message());
        var messages = new ArrayList<>(input.messages());
        messages.add(feedback.message());
        return next.apply(new ReasoningInput(messages, input.tools(), input.options()));
    }

    public record Feedback(UserMessage message) {
    }
}
