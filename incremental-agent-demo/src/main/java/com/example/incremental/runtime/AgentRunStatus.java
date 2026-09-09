package com.example.incremental.runtime;

public enum AgentRunStatus {
    CREATED,
    RUNNING,
    PAUSED,
    AWAITING_CONFIRM,
    RESUMING,
    FINISHED,
    ERROR,
    CANCELLED;

    public boolean terminal() {
        return this == FINISHED || this == CANCELLED;
    }
}

