package com.finapp.app.accounting;

import com.finapp.accounting.AccountingPeriods;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code finapp.accounting.period.open.age} (`P14-TSK-005`; {@code PHASE_14_PLAN.md} section 16; alert
 * {@code AccountingCloseOverdue}, `-020`): whole days the oldest period not yet {@code CLOSED} has been past its lock
 * grace - 0 when none is, unreadable reported as no value (NaN at Micrometer's edge), never zero for a read that failed.
 * A close nobody runs leaves the ledger's history open to correction; this gauge makes it loud. Read from the database,
 * so every instance reports the same value - aggregate with {@code max()}, never {@code sum()}. Cached for 15 s (the
 * {@code CreditReviewMetrics} floor). No tag: an age names no period. A whole number read as a {@code Long} - never a
 * floating-point signature ({@code NoFloatingPointMoneyRulesTest}).
 */
@Slf4j
public final class AccountingPeriodMetrics {

    /** Spelled with dots - exported as {@code finapp_accounting_period_open_age}, the plan's name. */
    public static final String OPEN_AGE = "finapp.accounting.period.open.age";

    private static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** A connection source - its own seam, the metrics precedent. */
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final AccountingPeriods periods;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, null));

    private record Cached(Instant takenAt, Long days) {}

    public AccountingPeriodMetrics(AccountingPeriods periods, Connections connections, Clock clock, MeterRegistry registry) {
        this.periods = periods;
        this.connections = connections;
        this.clock = clock;
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
        Cached current = cached.get();
        if (Duration.between(current.takenAt(), clock.instant()).compareTo(MIN_REFRESH) < 0) {
            return current.days();
        }
        Cached fresh;
        try (Connection connection = connections.open()) {
            connection.setReadOnly(true);
            fresh = new Cached(clock.instant(), periods.overdueDays(connection));
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("The oldest unclosed accounting period could not be read: {}", unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), null);
        }
        cached.set(fresh);
        return fresh.days();
    }
}
