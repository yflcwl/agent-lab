package com.example.incremental.writing;

/** Controls whether the parent writing task may execute or schedule a Run. */
public enum TaskStatus {
    RUNNING,
    PAUSE_REQUESTED,
    PAUSED,
    RESUMING,
    COMPLETED,
    FAILED,
    CANCELLED;

    public boolean terminal() {
        return this == COMPLETED || this == CANCELLED;
    }
}
