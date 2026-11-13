package com.finapp.platform.outbox;

/**
 * What one poll cycle did.
 *
 * <p>Returned rather than only logged because these are the numbers a relay is operated on:
 * ADR-0005 makes outbox depth, relay lag and poison-message count first-class monitored
 * metrics, and a caller that cannot see the outcome of a cycle cannot produce any of them.
 *
 * @param aggregatesDrained aggregates this instance locked and found something to attempt on;
 *     one another instance drained after this cycle read its candidates is not counted
 *     (`P16-TSK-013`, ADR-0136)
 * @param published events accepted by the transport and marked published
 * @param failed publication attempts that failed and will be retried
 * @param deadLettered rows abandoned after exhausting their attempts; each one blocks its
 *     aggregate and is an operational incident, never a routine number
 * @param batchFull whether the cycle took every slot of its batch and no attempt failed: the
 *     backlog likely holds more that is due, and the schedule may poll again at once rather
 *     than after its interval (ADR-0136). A cycle that failed anything is never full, so a
 *     broker in trouble keeps the interval and each row keeps its own backoff
 */
public record RelayPollResult(int aggregatesDrained, int published, int failed, int deadLettered, boolean batchFull) {

    /** Nothing was pending, or every pending aggregate was already held by another instance. */
    public static RelayPollResult idle() {
        return new RelayPollResult(0, 0, 0, 0, false);
    }

    /** Whether this cycle did any work at all — the signal a poller uses to decide to back off. */
    public boolean didWork() {
        return published > 0 || failed > 0 || deadLettered > 0;
    }
}
