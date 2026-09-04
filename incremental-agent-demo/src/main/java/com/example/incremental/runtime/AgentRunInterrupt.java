package com.example.incremental.runtime;

import java.util.Map;

public record AgentRunInterrupt(
        String interruptId,
        String toolCallId,
        String toolName,
        Map<String, Object> toolInput) {
}

