package com.example.solution.agent;

import com.example.solution.domain.PlanningDecision;
import com.example.solution.domain.PlanningRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import com.example.solution.tool.SourceToolContext;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public class AgentScopePlanningAgent implements PlanningAgent {

    private final HarnessAgent agent;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public AgentScopePlanningAgent(HarnessAgent agent, ObjectMapper objectMapper, Duration timeout) {
        this.agent = agent;
        this.objectMapper = objectMapper;
        this.timeout = timeout;
    }

    @Override
    public Response plan(PlanningRequest request) {
        return plan(request, ignored -> {
        });
    }

    @Override
    public Response plan(PlanningRequest request, AgentExecutionObserver observer) {
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
            String rawResponse = resultText[0] == null || resultText[0].isBlank() ? text.toString() : resultText[0];
            if (rawResponse.isBlank()) {
                throw new IllegalStateException("PlanningAgent 未返回结果");
            }
            PlanningDecision decision = objectMapper.readValue(
                    JsonResponseExtractor.extractObject(rawResponse), PlanningDecision.class);
            return new Response(decision, rawResponse);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("PlanningAgent 结果解析失败", e);
        }
    }

    private RuntimeContext runtimeContext(PlanningRequest request) {
        return RuntimeContext.builder()
                .sessionId("planning-" + request.writingTaskId() + "-" + request.planVersion() + "-" + UUID.randomUUID())
                .userId("solution-system")
                .put(SourceToolContext.class, new SourceToolContext(request.writingTaskId()))
                .build();
    }

}
