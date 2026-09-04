package com.example.incremental.runtime;

public record AgentRunDecision(String toolCallId, boolean approved, String feedback) {

    public AgentRunDecision(String toolCallId, boolean approved) {
        this(toolCallId, approved, null);
    }
}

