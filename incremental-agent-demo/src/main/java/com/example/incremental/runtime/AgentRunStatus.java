package com.example.incremental.runtime;

public enum AgentRunStatus {
    CREATED,
    RUNNING,
    AWAITING_CONFIRM,
    RESUMING,
    FINISHED,
    ERROR,
    CANCELLED;

    public boolean terminal() {
        return this == FINISHED || this == ERROR || this == CANCELLED;
    }
}

