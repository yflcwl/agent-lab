package com.example.incremental.runtime;

import java.util.concurrent.atomic.AtomicBoolean;

/** Ephemeral control signal for one live Run; it is intentionally separate from persisted status. */
public final class RunControl {

    private final AtomicBoolean pauseRequested = new AtomicBoolean();
    private volatile Runnable pauseHandler = () -> { };

    public boolean pauseRequested() {
        return pauseRequested.get();
    }

    public void requestPause() {
        if (pauseRequested.compareAndSet(false, true)) {
            pauseHandler.run();
        }
    }

    public void onPauseRequested(Runnable handler) {
        pauseHandler = handler == null ? () -> { } : handler;
        if (pauseRequested()) {
            pauseHandler.run();
        }
    }
}
