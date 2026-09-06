package com.example.incremental.persistence.agent;

public enum AgentRunEventType {
    RUN_STATE_CHANGED,
    RUN_STARTED,
    REASONING_SUMMARY,
    TOOL_CALL,
    TOOL_RESULT,
    REQUIRE_CONFIRM,
    RUN_RESUMED,
    RUN_FINISHED,
    RUN_FAILED
}
