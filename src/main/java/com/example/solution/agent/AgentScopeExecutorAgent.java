package com.example.solution.agent;

import com.example.solution.domain.ExecutionRequest;
import com.example.solution.domain.ExecutionResult;
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
import java.util.function.Consumer;

public class AgentScopeExecutorAgent implements ExecutorAgent {

    private final HarnessAgent agent;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public AgentScopeExecutorAgent(HarnessAgent agent, ObjectMapper objectMapper, Duration timeout) {
        this.agent = agent;
        this.objectMapper = objectMapper;
        this.timeout = timeout;
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request, Consumer<String> onPartial) {
        return execute(request, onPartial, ignored -> {
        });
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request, Consumer<String> onPartial,
                                   AgentExecutionObserver observer) {
        String input;
        try {
            input = objectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("ExecutorAgent 请求序列化失败", e);
        }
        StringBuilder full = new StringBuilder();
        ExecutionResult[] parsed = new ExecutionResult[1];
        AgentScopeEventObserver eventObserver = new AgentScopeEventObserver(observer);
        try {
            // 最终输出是结构化 JSON，流式 delta 在 JSON 闭合前不能视为章节正文。
            // 进度通过 AgentScopeEventObserver 对外观测，避免把模型中间文本污染 checkpoint。
            agent.streamEvents(List.of(new UserMessage(input)), runtimeContext(request))
                    .doOnNext(event -> {
                        eventObserver.accept(event);
                        if (event instanceof TextBlockDeltaEvent delta) {
                            full.append(delta.getDelta());
                        } else if (event instanceof AgentResultEvent result) {
                            try {
                                parsed[0] = parse(result.getResult().getTextContent());
                            } catch (RuntimeException ignored) {
                                // 权威结果解析失败时回退到累积 delta。
                            }
                        }
                    })
                    .blockLast(timeout);
            return parsed[0] != null ? parsed[0] : parse(full.toString());
        } catch (RuntimeException e) {
            throw new IllegalStateException("ExecutorAgent 执行失败", e);
        }
    }

    private ExecutionResult parse(String text) {
        try {
            return objectMapper.readValue(JsonResponseExtractor.extractObject(text), ExecutionResult.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalStateException("ExecutorAgent 结果解析失败", e);
        }
    }

    private RuntimeContext runtimeContext(ExecutionRequest request) {
        return RuntimeContext.builder()
                .sessionId("execution-" + request.writingTaskId() + "-" + request.currentTask().id() + "-" + UUID.randomUUID())
                .userId("solution-system")
                .put(SourceToolContext.class, new SourceToolContext(request.writingTaskId()))
                .build();
    }

}
