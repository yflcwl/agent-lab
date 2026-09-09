package com.example.incremental.runtime;

import java.time.Instant;

/** Runtime boundary only; Agent context is restored from the existing AgentScope session/state. */
public record RunCheckpoint(
        RunSafePoint safePoint,
        AgentRunStatus resumeStatus,
        Instant capturedAt) {
}
