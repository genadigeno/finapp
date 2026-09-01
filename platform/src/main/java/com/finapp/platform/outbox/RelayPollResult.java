package com.finapp.platform.outbox;

/**
 * What one poll cycle did.
 *
 * <p>Returned rather than only logged because these are the numbers a relay is operated on:
 * ADR-0005 makes outbox depth, relay lag and poison-message count first-class monitored
 * metrics, and a caller that cannot see the outcome of a cycle cannot produce any of them.
 *
 * @param aggregatesDrained aggregates this instance locked and worked on
 * @param published events accepted by the transport and marked published
 * @param failed publication attempts that failed and will be retried
 * @param deadLettered rows abandoned after exhausting their attempts; each one blocks its
 *     aggregate and is an operational incident, never a routine number
 */
public record RelayPollResult(int aggregatesDrained, int published, int failed, int deadLettered) {

    /** Nothing was pending, or every pending aggregate was already held by another instance. */
    public static RelayPollResult idle() {
        return new RelayPollResult(0, 0, 0, 0);
    }

    /** Whether this cycle did any work at all — the signal a poller uses to decide to back off. */
    public boolean didWork() {
        return published > 0 || failed > 0 || deadLettered > 0;
    }
}
