package com.finapp.credit;

import java.time.Duration;

/**
 * What the platform learns when a decision commits (`P10-TSK-016`) - the metrics' seam, called after the commit, never
 * inside it: a decision is recorded whether or not anyone is counting.
 */
public interface DecisionObserver {

    /** No observer. */
    DecisionObserver NONE = (decision, policyVersion, latency) -> { };

    /**
     * {@code decision} committed under policy version number {@code policyVersion}, {@code latency} after its request
     * was submitted.
     */
    void recorded(CreditDecision decision, int policyVersion, Duration latency);
}
