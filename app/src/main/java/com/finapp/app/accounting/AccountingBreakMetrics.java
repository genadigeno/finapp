package com.finapp.app.accounting;

import com.finapp.accounting.AccountingBreaks;
import com.finapp.accounting.BreakType;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code finapp.accounting.break.open{type}} (`P14-TSK-010`; {@code PHASE_14_PLAN.md} section 16; alert
 * {@code AccountingBreakAged}, `-020`): per {@link BreakType}, how many accounting breaks are not {@code RESOLVED} -
 * {@code OPEN} or {@code ACKNOWLEDGED}, the ones the close discloses. Every type registered from the start (0 when none),
 * no value (NaN at Micrometer's edge) when unreadable - never zero for a read that failed. Read from the database, so every
 * instance reports the same value - aggregate with {@code max()}, never {@code sum()}. Cached for 15 s (the
 * {@code AccountingPeriodMetrics} floor). The {@code type} tag is the closed enum's name - never a break, a period or a
 * subject. A whole number read as a {@code Long} - never a floating-point signature.
 */
@Slf4j
public final class AccountingBreakMetrics {

    /** Spelled with dots - exported as {@code finapp_accounting_break_open}, the plan's name. */
    public static final String OPEN = "finapp.accounting.break.open";

    private static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** A connection source - its own seam, the metrics precedent. */
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final AccountingBreaks breaks;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, null));

    private record Cached(Instant takenAt, Map<BreakType, Long> open) {}

    public AccountingBreakMetrics(AccountingBreaks breaks, Connections connections, Clock clock, MeterRegistry registry) {
        this.breaks = Objects.requireNonNull(breaks, "breaks");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
        for (BreakType type : BreakType.values()) {
            Gauge.builder(OPEN, () -> open(type))
                    .tag("type", type.name())
                    .description("Accounting breaks not RESOLVED - OPEN or ACKNOWLEDGED - per break type (INV-ACC-12,"
                            + " INV-ACC-05); no value when unreadable. Fleet-wide from every instance: aggregate with"
                            + " max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
    }

    /** The cached count for {@code type}; {@code null} - no value - when the read failed. */
    Long open(BreakType type) {
        Map<BreakType, Long> open = reading();
        return open == null ? null : open.getOrDefault(type, 0L);
    }

    private Map<BreakType, Long> reading() {
        Cached current = cached.get();
        if (Duration.between(current.takenAt(), clock.instant()).compareTo(MIN_REFRESH) < 0) {
            return current.open();
        }
        Cached fresh;
        try (Connection connection = connections.open()) {
            connection.setReadOnly(true);
            fresh = new Cached(clock.instant(), Map.copyOf(breaks.openByType(connection)));
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("The open accounting breaks could not be read: {}", unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), null);
        }
        cached.set(fresh);
        return fresh.open();
    }
}
