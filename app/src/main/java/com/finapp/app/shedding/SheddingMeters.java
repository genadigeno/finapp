package com.finapp.app.shedding;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * {@code finapp.shedding.requests{type,outcome}}: what the shedding door did (`P16-TSK-016`, ADR-0137). Eight series and no more:
 * {@code type} is the class ({@code command}, {@code read}), {@code outcome} is {@code admitted} or why it was shed ({@code pool},
 * {@code permits}, {@code deadline}).
 *
 * <p><strong>How to read it.</strong> The door counts each request once, {@code admitted}, {@code pool} or {@code permits}. The deadline
 * check, after authentication, counts the commands it sheds as {@code deadline}: those the door had admitted. So the shed share of a
 * class is {@code (pool + permits + deadline) / (admitted + pool + permits)}. A request the door exempts (the operator surface, a
 * declared {@link NotShed} door, anything not ours) is not counted here.
 *
 * <p><strong>This is the whole record of a shed.</strong> No audit row and no log line per shed: one per refusal would make an overload
 * write the audit table or fill the log, which is the {@code SourceLimitMeters} reasoning. Eager on every instance, so a dashboard has
 * a series before the first request. Per instance and not authoritative: a sum over the fleet is a rate, never a count of record.
 */
public final class SheddingMeters {

    static final String NAME = "finapp.shedding.requests";

    private final Map<LoadShedding.Type, Map<LoadShedding.Outcome, Counter>> counters = new EnumMap<>(LoadShedding.Type.class);

    public SheddingMeters(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");
        for (LoadShedding.Type type : LoadShedding.Type.values()) {
            Map<LoadShedding.Outcome, Counter> perOutcome = new EnumMap<>(LoadShedding.Outcome.class);
            for (LoadShedding.Outcome outcome : LoadShedding.Outcome.values()) {
                perOutcome.put(outcome, Counter.builder(NAME)
                        .tag("type", type.tag())
                        .tag("outcome", outcome.tag())
                        .description("Requests the shedding door admitted, or shed before their handler and why")
                        .register(registry));
            }
            counters.put(type, perOutcome);
        }
    }

    void record(LoadShedding.Type type, LoadShedding.Outcome outcome) {
        counters.get(type).get(outcome).increment();
    }
}
