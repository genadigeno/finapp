package com.finapp.app.abuse;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@code finapp.abuse.requests{type,outcome}}: what the doors did (`P15-TSK-022`). Twenty-five series and no more.
 *
 * <p><strong>This is the whole record of a refused request.</strong> Not an audit row and not a log line per refusal: writing one per
 * refusal would make a flood write the audit table or fill the log, which turns the limit into the attack. Eager on every instance
 * so an alert or dashboard has a series before the first request.
 */
public final class SourceLimitMeters {

    private static final String NAME = "finapp.abuse.requests";

    /** A closed set of what a door can do with a request. Every outcome but the first two answered {@code 503}, each for its own cause. */
    enum Outcome {
        ADMITTED("admitted"),
        REFUSED("refused"),
        AT_CAPACITY("at_capacity"),
        STORE_UNREACHABLE("store_unreachable"),
        STORE_ERROR("store_error");

        private final String tag;

        Outcome(String tag) {
            this.tag = tag;
        }
    }

    private final Map<Door, Map<Outcome, Counter>> counters = new EnumMap<>(Door.class);

    public SourceLimitMeters(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");
        for (Door door : Door.values()) {
            Map<Outcome, Counter> perOutcome = new EnumMap<>(Outcome.class);
            for (Outcome outcome : Outcome.values()) {
                perOutcome.put(
                        outcome,
                        Counter.builder(NAME)
                                .tag("type", door.label())
                                .tag("outcome", outcome.tag)
                                .description("Requests each public door admitted, refused, or could not count")
                                .register(registry));
            }
            counters.put(door, perOutcome);
        }
    }

    void record(Door door, Outcome outcome) {
        counters.get(door).get(outcome).increment();
    }
}
