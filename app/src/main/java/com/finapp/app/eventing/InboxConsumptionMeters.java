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
 * these very counters. Per-instance counters; nothing here coordinates anything.
 */
public final class InboxConsumptionMeters {

    /** The outcomes the consumption counter carries - {@link InboxConsumers}' own. */
    public static final List<String> OUTCOMES = List.of("processed", "duplicate", "contended", "failed");

    public InboxConsumptionMeters(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        OUTCOMES.forEach(outcome -> InboxConsumers.outcome(registry, outcome));
    }
}
