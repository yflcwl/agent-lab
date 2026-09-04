package com.example.solution.agent;

import com.example.solution.domain.ExecutionRequest;
import com.example.solution.domain.ExecutionResult;

import java.util.function.Consumer;

public interface ExecutorAgent {

    ExecutionResult execute(ExecutionRequest request, Consumer<String> onPartial);

    default ExecutionResult execute(ExecutionRequest request, Consumer<String> onPartial,
                                    AgentExecutionObserver observer) {
        return execute(request, onPartial);
    }

    default ExecutionResult execute(ExecutionRequest request) {
        return execute(request, ignored -> {
        });
    }
}
