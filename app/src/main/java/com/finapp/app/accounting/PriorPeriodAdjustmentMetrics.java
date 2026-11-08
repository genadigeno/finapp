package com.finapp.app.accounting;

import com.finapp.accounting.PriorPeriodAdjustmentStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * {@code finapp.accounting.adjustment.standing} (`P14-TSK-020`; `P14-TSK-013`'s routed note; alert {@code AccountingAdjustmentStanding}): how many prior-period adjustments have stood {@code PROPOSED} past {@code finapp.accounting.adjustment.standing-after} on the database's clock. One
 * that can no longer post stands {@code PROPOSED} until a person rejects it, and otherwise only {@code GET .../prior-period-adjustments} and its {@code blockedBy} show it. It counts whether or not each could still post: {@code stillStands} is judged reading two month rows under their locks, and a
 * second copy of that here would drift. Beside it, the same door: {@code finapp.accounting.cutoff.refused{command="adjustment"}}. From the rows, aggregate with {@code max()}; no value when unreadable; bounded by {@link AccountingGaugeRead}, cached 15 s. No tag: a count names no adjustment, and no column here holds an amount.
 */
public final class PriorPeriodAdjustmentMetrics {

    /** Spelled with dots - exported as {@code finapp_accounting_adjustment_standing}. */
    public static final String STANDING = "finapp.accounting.adjustment.standing";

    private static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    private final AccountingGaugeRead<Long> read;

    public PriorPeriodAdjustmentMetrics(PriorPeriodAdjustmentStore store, Duration standingAfter, GlSnapshots snapshots, Clock clock, MeterRegistry registry) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(standingAfter, "standingAfter");
        if (standingAfter.isNegative() || standingAfter.isZero()) {
            throw new IllegalArgumentException("a proposal's bound is positive: at zero every proposal born would read standing");
        }
        this.read = new AccountingGaugeRead<>("The prior-period adjustments standing", snapshots, clock, MIN_REFRESH,
                connection -> store.standingPast(connection, standingAfter));
        Gauge.builder(STANDING, this::standing)
                .description("Prior-period adjustments PROPOSED past the bound, whether or not each could still post; no value when unreadable."
                        + " Fleet-wide from every instance: aggregate with max(), never sum()")
                .strongReference(true)
                .register(registry);
    }

    /** The cached count; {@code null} - no value - when the read failed. */
    Long standing() {
        return read.current();
    }
}
