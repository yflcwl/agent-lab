package com.example.solution.agent;

public record AgentExecutionEvent(String eventType, String toolCallId, String toolName, String detail) {
}
