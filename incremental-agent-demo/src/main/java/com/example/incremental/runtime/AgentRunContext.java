package com.example.incremental.runtime;

/**
 * Identifies one agent execution without carrying workflow-specific state.
 */
public record AgentRunContext(String correlationId, String threadId, String runId) {
}
