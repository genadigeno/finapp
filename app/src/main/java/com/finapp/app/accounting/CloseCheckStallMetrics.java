package com.finapp.app.accounting;

import com.finapp.accounting.CloseRunStall;
import com.finapp.accounting.CloseRunStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * {@code finapp.accounting.close.check.stalled} (`P14-TSK-020`; `P14-TSK-017`'s review, I-5, the owner's ruling of 2026-11-03; alert {@code AccountingCloseCheckStalled}): how many attempts of a present run have stood claimable past {@link
 * CloseRunStall#after()} with nobody claiming them - the leg stalled after the run's birth, which {@code close.run.stalled} cannot see: nine {@code PENDING} rows, their leases past, that gauge reading 0.
 *
 * <p><strong>Not a stall</strong> ({@code CloseRunStore.stalledAttempts}): what the claim itself holds back, so {@code OPEN_ACCOUNTING_BREAKS} behind a raising check is a dependency's wait; a set awaited, whose attempt is deferred and re-claimed every poll; an
 * attempt behind a live worker, since a small fleet draining a queue past a long judge is working. <strong>The limit</strong>: a judge hung with its heartbeat alive hides a stall so for at most {@code finapp.accounting.close.max-run}.
 *
 * <p>The same bound as the run's birth, ten polls of the check leg: nothing a healthy leg does leaves a claimable attempt that long while no claim is live. From the database, on the database's clock, registered whether or not this instance runs the schedule;
 * aggregate with {@code max()}, never {@code sum()}. No value when unreadable, never zero. Bounded by {@link AccountingGaugeRead}, cached 15 s. No tag: a count names no period and no check.
 */
public final class CloseCheckStallMetrics {

    /** Spelled with dots - exported as {@code finapp_accounting_close_check_stalled}. */
    public static final String STALLED = "finapp.accounting.close.check.stalled";

    private static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    private final AccountingGaugeRead<Long> read;

    public CloseCheckStallMetrics(CloseRunStore store, CloseRunStall stall, GlSnapshots snapshots, Clock clock, MeterRegistry registry) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(stall, "stall");
        this.read = new AccountingGaugeRead<>("The stalled close checks", snapshots, clock, MIN_REFRESH,
                connection -> store.stalledAttempts(connection, stall.after()));
        Gauge.builder(STALLED, this::stalled)
                .description("Attempts of a present close run claimable past the bound with nobody claiming them and no claim live - the check leg stopped after the run's"
                        + " birth; no value when unreadable. Fleet-wide from every instance: aggregate with max(), never sum()")
                .strongReference(true)
                .register(registry);
    }

    /** The cached count; {@code null} - no value - when the read failed. */
    Long stalled() {
        return read.current();
    }
}
