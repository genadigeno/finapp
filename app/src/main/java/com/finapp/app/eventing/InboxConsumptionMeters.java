package com.finapp.app.eventing;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Objects;

/**
 * {@code finapp.inbox.consumption{outcome}} registered whether or not this instance consumes (`P13-TSK-024`;
 * {@code P1-TSK-029}'s rule - "eager either way"). {@link InboxConsumers} registers the series when its loops exist, which
 * is only where {@code finapp.inbox.consumer.enabled} holds; the alert on a stalled partition
 * ({@code InboxConsumptionFailing}, {@code infra/prometheus/rules/risk.yml} - risk's signal intake is the inbox's largest
 * consumer) must evaluate a series on every scraped instance, the guards' contexts with the consumer off among them.
 * Micrometer answers a second registration of one name and tag set with the first meter, so the consumer counts into
 * these very counters. Per-instance counters; nothing here coordinates anything. The thrown polls'
 * {@code finapp.inbox.poll.failures{outcome}} (`P15-TSK-026`'s review fix round) is registered here
 * for the same reason, and so is {@code finapp.inbox.consumer.evictions} (`P15-TSK-025`).
 */
public final class InboxConsumptionMeters {

    /** The outcomes the consumption counter carries - {@link InboxConsumers}' own. */
    public static final List<String> OUTCOMES = List.of("processed", "duplicate", "contended", "failed", "parked");

    /** The outcomes the poll-failure counter carries (`P15-TSK-026`'s review fix round). */
    public static final List<String> POLL_FAILURE_OUTCOMES = List.of("refused", "failed");

    public InboxConsumptionMeters(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        OUTCOMES.forEach(outcome -> InboxConsumers.outcome(registry, outcome));
        POLL_FAILURE_OUTCOMES.forEach(outcome -> InboxConsumers.pollFailure(registry, outcome));
        InboxConsumers.eviction(registry);
    }
}
