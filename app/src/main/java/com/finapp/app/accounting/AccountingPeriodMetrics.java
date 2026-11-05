package com.finapp.app.accounting;

import com.finapp.accounting.AccountingPeriods;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;

/**
 * {@code finapp.accounting.period.open.age} (`P14-TSK-005`; {@code PHASE_14_PLAN.md} section 16; alert
 * {@code AccountingCloseOverdue}, `P14-TSK-020`): whole days the oldest period not yet {@code CLOSED} has been past its lock
 * grace - 0 when none is, unreadable reported as no value (NaN at Micrometer's edge), never zero for a read that failed.
 * A close nobody runs leaves the ledger's history open to correction; this gauge makes it loud. Read from the database, on the
 * database's clock (`P14-TSK-020`: it read the instance's until then), so every instance reports the same value - aggregate with
 * {@code max()}, never {@code sum()}. Bounded by {@link AccountingGaugeRead}, cached for 15 s (the {@code CreditReviewMetrics} floor).
 * No tag: an age names no period. A whole number read as a {@code Long} - never a floating-point signature
 * ({@code NoFloatingPointMoneyRulesTest}). <strong>The published name carries the base unit</strong>: {@code finapp_accounting_period_open_age_days}.
 */
public final class AccountingPeriodMetrics {

    /** Spelled with dots - exported, with its base unit, as {@code finapp_accounting_period_open_age_days}. */
    public static final String OPEN_AGE = "finapp.accounting.period.open.age";

    private static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    private final AccountingGaugeRead<Long> read;

    public AccountingPeriodMetrics(AccountingPeriods periods, GlSnapshots snapshots, AccountingGaugeRefresher refresher, Clock clock, MeterRegistry registry) {
        this.read = new AccountingGaugeRead<>("The oldest unclosed accounting period", snapshots, refresher, clock, MIN_REFRESH, periods::overdueDays);
        Gauge.builder(OPEN_AGE, this::overdueDays)
                .description("Whole days the oldest accounting period not yet CLOSED has been past its lock grace - 0"
                        + " when none is, no value when unreadable. Fleet-wide from every instance: aggregate with max(),"
                        + " never sum()")
                .baseUnit("days")
                .strongReference(true)
                .register(registry);
    }

    /** The cached reading; {@code null} - no value - when the read failed. */
    Long overdueDays() {
        return read.current();
    }
}
