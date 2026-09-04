package com.example.solution.agent;

import com.example.solution.domain.ConversationRequest;

public interface ConversationAgent {

    String reply(ConversationRequest request, AgentExecutionObserver observer);
}
