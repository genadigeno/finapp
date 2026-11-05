package com.finapp.app.accounting;

import com.finapp.accounting.AccountingBreaks;
import com.finapp.accounting.BreakType;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * {@code finapp.accounting.break.open{type}} (`P14-TSK-010`; {@code PHASE_14_PLAN.md} section 16): per {@link BreakType}, how many accounting breaks are not {@code RESOLVED} -
 * {@code OPEN} or {@code ACKNOWLEDGED}, the ones the close discloses, <strong>per generation</strong>, so an {@code ACKNOWLEDGED} generation a later one has superseded
 * still counts and an acknowledged break may hold it above zero for good. It is the dashboard's count and no alert reads it: {@code AccountingBreakAged} reads
 * {@link AccountingBreakAgeMetrics} (`P14-TSK-020`; `P14-TSK-012`'s item 2). Every type registered from the start (0 when none),
 * no value (NaN at Micrometer's edge) when unreadable - never zero for a read that failed. Read from the database, so every
 * instance reports the same value - aggregate with {@code max()}, never {@code sum()}. Bounded by {@link AccountingGaugeRead}, cached for 15 s (the
 * {@code AccountingPeriodMetrics} floor). The {@code type} tag is the closed enum's name - never a break, a period or a
 * subject. A whole number read as a {@code Long} - never a floating-point signature.
 */
public final class AccountingBreakMetrics {

    /** Spelled with dots - exported as {@code finapp_accounting_break_open}, the plan's name. */
    public static final String OPEN = "finapp.accounting.break.open";

    private static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    private final AccountingGaugeRead<Map<BreakType, Long>> read;

    public AccountingBreakMetrics(AccountingBreaks breaks, GlSnapshots snapshots, AccountingGaugeRefresher refresher, Clock clock, MeterRegistry registry) {
        Objects.requireNonNull(breaks, "breaks");
        this.read = new AccountingGaugeRead<>("The open accounting breaks", snapshots, refresher, clock, MIN_REFRESH,
                connection -> Map.copyOf(breaks.openByType(connection)));
        for (BreakType type : BreakType.values()) {
            Gauge.builder(OPEN, () -> open(type))
                    .tag("type", type.name())
                    .description("Accounting breaks not RESOLVED - OPEN or ACKNOWLEDGED, every generation - per break type (INV-ACC-12,"
                            + " INV-ACC-05); no value when unreadable. Fleet-wide from every instance: aggregate with"
                            + " max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
    }

    /** The cached count for {@code type}; {@code null} - no value - when the read failed. */
    Long open(BreakType type) {
        Map<BreakType, Long> open = read.current();
        return open == null ? null : open.getOrDefault(type, 0L);
    }
}
