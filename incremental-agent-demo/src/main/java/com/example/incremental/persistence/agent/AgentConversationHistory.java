package com.example.incremental.persistence.agent;

import java.util.List;

public record AgentConversationHistory(
        List<AgentMessage> messages,
        List<AgentRun> runs,
        List<AgentRunEvent> events) {
}
