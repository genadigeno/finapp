package com.finapp.app.lifecycle;

import java.time.Duration;

/**
 * A component whose stop is bounded (`P15-TSK-008`): every one of the platform's {@code SmartLifecycle} components says
 * how long its stop can take at worst, its interrupt's grace included, and {@code ShutdownOrderTest} holds each below the
 * real context's {@code spring.lifecycle.timeout-per-shutdown-phase} - a stop past it would be cut off by Spring, not
 * finished.
 */
public interface BoundedStop {

    /**
     * @return the longest this component's {@code stop()} takes before it returns, whatever its work is doing
     */
    Duration stopBound();
}
