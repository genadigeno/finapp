package com.finapp.app.eventing;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Objects;

/**
 * {@code finapp.retention.swept{type}} and {@code finapp.retention.sweep.failed{type}}, eager on every instance whether or not
 * its sweeps run (`P15-TSK-029`; {@code InboxConsumptionMeters}' reason): a counter that only exists where the schedule does
 * gives an alert no series on an instance with it off. {@code type} is one of the two tables the sweeps delete from.
 */
public final class RetentionSweepMeters {

    private static final String SWEPT = "finapp.retention.swept";

    private static final String FAILED = "finapp.retention.sweep.failed";

    private final MeterRegistry registry;

    public RetentionSweepMeters(MeterRegistry registry, List<String> tables) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        tables.forEach(table -> {
            swept(table);
            failed(table);
        });
    }

    Counter swept(String table) {
        return Counter.builder(SWEPT)
                .tag("type", table)
                .description("Rows deleted by a retention sweep, by table")
                .register(registry);
    }

    Counter failed(String table) {
        return Counter.builder(FAILED)
                .tag("type", table)
                .description("Retention sweep ticks that failed, by table")
                .register(registry);
    }
}
