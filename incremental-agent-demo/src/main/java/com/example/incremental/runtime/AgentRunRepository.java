package com.example.incremental.runtime;

import java.util.List;

public interface AgentRunRepository {

    void create(AgentRunContext run, String userId, String agentId, String title, String userMessage);

    void createRetry(String failedRunId, AgentRunContext run, String agentId);

    AgentRunRecord find(String runId);

    AgentRunRecord findAwaitingConfirmation(String correlationId);

    void transition(AgentRunRecord expected, AgentRunStatus status, List<AgentRunInterrupt> interrupts,
                    String errorCode, String errorMessage);
}
