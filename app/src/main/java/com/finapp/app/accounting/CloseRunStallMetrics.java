package com.finapp.app.accounting;

import com.finapp.accounting.CloseRunStall;
import com.finapp.accounting.CloseRunStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * {@code finapp.accounting.close.run.stalled} (`P14-TSK-017`; {@code PHASE_14_PLAN.md} sections 8.1, 16; alert
 * {@code AccountingCloseRunStalled}; the standing condition of the close run's birth by the schedule, the owner's decision of
 * 2026-11-02): how many periods are {@code LOCKED}, past {@link CloseRunStall#after()}, with no run under their present lock. The
 * lock is the ledger's protection and waits for nothing, so a check leg that has stopped - the schedule off, every instance failing -
 * is otherwise silent: the books are cut and nothing judges them, and a report speaks only when somebody asks.
 *
 * <p><strong>From the database, on the database's clock</strong> ({@code CloseRunStore.stalledLocks}): every instance publishes the
 * same number with no leader and no schedule - aggregate with {@code max()}, never {@code sum()} - and the ordinary gap before the
 * first poll never counts. <strong>Registered whether or not this instance runs the schedule</strong>: the fleet with its leg off is
 * exactly the one that must say so. No value (NaN at Micrometer's edge) when unreadable - never zero for a read that failed. Bounded by
 * {@link AccountingGaugeRead}, cached for 15 s (the {@code AccountingPeriodMetrics} floor). No tag: a count names no period. A whole number read as a {@code Long}.
 */
public final class CloseRunStallMetrics {

    /** Spelled with dots - exported as {@code finapp_accounting_close_run_stalled}, the plan's name. */
    public static final String STALLED = "finapp.accounting.close.run.stalled";

    private static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    private final AccountingGaugeRead<Long> read;

    public CloseRunStallMetrics(CloseRunStore store, CloseRunStall stall, GlSnapshots snapshots, AccountingGaugeRefresher refresher, Clock clock,
            MeterRegistry registry) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(stall, "stall");
        this.read = new AccountingGaugeRead<>("The stalled close runs", snapshots, refresher, clock, MIN_REFRESH,
                connection -> store.stalledLocks(connection, stall.after()));
        Gauge.builder(STALLED, this::stalled)
                .description("Periods LOCKED past the bound with no close run under their present lock - the check leg is not"
                        + " running; no value when unreadable. Fleet-wide from every instance: aggregate with max(), never sum()")
                .strongReference(true)
                .register(registry);
    }

    /** The cached count; {@code null} - no value - when the read failed. */
    Long stalled() {
        return read.current();
    }
}
