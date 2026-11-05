package com.finapp.app.accounting;

import com.finapp.accounting.ClosedMonthRuns;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * {@code finapp.accounting.close.month.unrun} (`P14-TSK-020`; `P14-TSK-012`'s item 7; alert {@code AccountingClosedMonthLostItsRun}): how many sources of the latest {@code CLOSED} month hold a repudiated run and no run that counts ({@link ClosedMonthRuns}). What a person decides about that month is owner item (l)'s, still
 * open: this says it happened, and reads one month, so an older month's stays {@code RECONCILIATION_STATUS}'s to show. From the rows, aggregate with {@code max()}; no value when unreadable; bounded by {@link AccountingGaugeRead}, cached 60 s, since it costs three statements and a repudiation is a person's rare act. No tag: a count names no source.
 */
public final class ClosedMonthRunMetrics {

    /** Spelled with dots - exported as {@code finapp_accounting_close_month_unrun}. */
    public static final String UNRUN = "finapp.accounting.close.month.unrun";

    private static final Duration MIN_REFRESH = Duration.ofSeconds(60);

    private final AccountingGaugeRead<Long> read;

    public ClosedMonthRunMetrics(ClosedMonthRuns months, GlSnapshots snapshots, AccountingGaugeRefresher refresher, Clock clock, MeterRegistry registry) {
        Objects.requireNonNull(months, "months");
        this.read = new AccountingGaugeRead<>("The latest closed month's runs", snapshots, refresher, clock, MIN_REFRESH, months::repudiatedWithoutARun);
        Gauge.builder(UNRUN, this::unrun)
                .description("Sources of the latest CLOSED month holding a repudiated run and no run that counts; no value when unreadable."
                        + " Fleet-wide from every instance: aggregate with max(), never sum()")
                .strongReference(true)
                .register(registry);
    }

    /** The cached count; {@code null} - no value - when the read failed. */
    Long unrun() {
        return read.current();
    }
}
