package com.example.solution.agent;

import com.example.solution.domain.ConversationRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public class AgentScopeConversationAgent implements ConversationAgent {

    private final HarnessAgent agent;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public AgentScopeConversationAgent(HarnessAgent agent, ObjectMapper objectMapper, Duration timeout) {
        this.agent = agent;
        this.objectMapper = objectMapper;
        this.timeout = timeout;
    }

    @Override
    public String reply(ConversationRequest request, AgentExecutionObserver observer) {
        try {
            String input = objectMapper.writeValueAsString(request);
            StringBuilder text = new StringBuilder();
            String[] resultText = new String[1];
            AgentScopeEventObserver eventObserver = new AgentScopeEventObserver(observer);
            agent.streamEvents(List.of(new UserMessage(input)), runtimeContext(request))
                    .doOnNext(event -> {
                        eventObserver.accept(event);
                        if (event instanceof TextBlockDeltaEvent delta) {
                            text.append(delta.getDelta());
                        } else if (event instanceof AgentResultEvent result) {
                            resultText[0] = result.getResult().getTextContent();
                        }
                    })
                    .blockLast(timeout);
            String reply = resultText[0] == null || resultText[0].isBlank() ? text.toString() : resultText[0];
            if (reply.isBlank()) {
                throw new IllegalStateException("ConversationAgent 未返回回复");
            }
            return reply;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("ConversationAgent 请求序列化失败", e);
        }
    }

    private RuntimeContext runtimeContext(ConversationRequest request) {
        return RuntimeContext.builder()
                .sessionId("conversation-" + request.writingTaskId() + "-" + UUID.randomUUID())
                .userId("solution-system")
                .put(com.example.solution.tool.SourceToolContext.class,
                        new com.example.solution.tool.SourceToolContext(request.writingTaskId()))
                .build();
    }
}
