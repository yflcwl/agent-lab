package com.example.incremental.runtime;

/** Stable runtime boundaries at which an in-flight Run may stop cooperatively. */
public enum RunSafePoint {
    BEFORE_AGENT_STEP,
    AFTER_AGENT_STEP,
    AFTER_LLM_CALL,
    AFTER_TOOL_CALL,
    BEFORE_BUSINESS_COMMIT,
    WAITING_FOR_CONFIRMATION
}
