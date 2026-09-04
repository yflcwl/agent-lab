package com.example.solution.agent;

@FunctionalInterface
public interface AgentExecutionObserver {

    void onEvent(AgentExecutionEvent event);
}
