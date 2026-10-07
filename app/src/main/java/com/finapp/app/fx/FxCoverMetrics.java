package com.finapp.app.fx;

import com.finapp.fx.CoverKind;
import com.finapp.fx.CoverObserver;
import com.finapp.fx.CoverStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * The cover's meters (`P9-TSK-012`; ADR-0077's operational impact, PHASE_9_PLAN.md section 15):
 * {@code finapp.fx.cover{provider, type, outcome}} - the cover's kind under the registered
 * {@code type} key -, {@code finapp.fx.cover.latency{provider, type}} from birth to execution, and
 * the database-read gauges {@code finapp.fx.cover.unknown.active}, {@code .unknown.age} (since the
 * oldest UNKNOWN cover's latest permit - the {@code INV-LIFE-03} alert) and
 * {@code finapp.fx.cover.open.age} (the oldest uncovered position's). Verdicts, counts and ages only
 * - never an amount, a reference or a rate (ADR-0072). Registered eagerly for every declared provider.
 */
@Slf4j
public final class FxCoverMetrics implements CoverObserver {

    public static final String COVER = "finapp.fx.cover";
    public static final String LATENCY = "finapp.fx.cover.latency";
    public static final String UNKNOWN_ACTIVE = "finapp.fx.cover.unknown.active";
    public static final String UNKNOWN_AGE = "finapp.fx.cover.unknown.age";
    public static final String OPEN_AGE = "finapp.fx.cover.open.age";
    static final Duration MIN_REFRESH = Duration.ofSeconds(5);

    /** Opens a connection for one gauge reading. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final CoverStore covers;
    private final Connections connections;
    private final Clock clock;
    private final MeterRegistry registry;
    private final AtomicReference<Reading> cached = new AtomicReference<>(new Reading(Instant.EPOCH, Optional.empty()));

    public FxCoverMetrics(
            Collection<String> providerCodes, CoverStore covers, Connections connections, Clock clock, MeterRegistry registry) {
        this.covers = Objects.requireNonNull(covers, "covers must not be null");
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
        for (String provider : providerCodes) {
            for (CoverKind kind : CoverKind.values()) {
                for (CoverObserver.Outcome outcome : CoverObserver.Outcome.values()) {
                    counter(provider, kind, outcome);
                }
                timer(provider, kind);
            }
        }
        Gauge.builder(UNKNOWN_ACTIVE, this, self -> self.reading().board().map(board -> (double) board.active()).orElse(Double.NaN))
                .description("FX covers UNKNOWN now: sent, unanswered, awaiting an inquiry's knowledge"
                        + " (INV-LIFE-03). NaN when unreadable. Fleet-wide: max(), never sum()")
                .strongReference(true)
                .register(registry);
        Gauge.builder(UNKNOWN_AGE, this, self -> self.reading().board()
                        .map(board -> board.unknown().oldestAge().map(FxCoverMetrics::seconds).orElse(0d))
                        .orElse(Double.NaN))
                .description("Seconds since the oldest UNKNOWN FX cover's latest permit - 0 when none,"
                        + " NaN when unreadable. Alerting")
                .baseUnit("seconds")
                .strongReference(true)
                .register(registry);
        Gauge.builder(OPEN_AGE, this, self -> self.reading().board()
                        .map(board -> board.open().map(FxCoverMetrics::seconds).orElse(0d))
                        .orElse(Double.NaN))
                .description("Seconds since the oldest uncovered FX position leg was booked - a cover"
                        + " DISPATCHED, UNKNOWN or REJECTED. 0 when none, NaN when unreadable. Alerting")
                .baseUnit("seconds")
                .strongReference(true)
                .register(registry);
    }

    @Override
    public void outcome(String providerCode, CoverKind kind, CoverObserver.Outcome outcome) {
        counter(providerCode, kind, outcome).increment();
    }

    @Override
    public void executed(String providerCode, CoverKind kind, Duration sinceBirth) {
        if (!sinceBirth.isNegative()) {
            timer(providerCode, kind).record(sinceBirth);
        }
    }

    private Counter counter(String provider, CoverKind kind, CoverObserver.Outcome outcome) {
        return Counter.builder(COVER)
                .tag("provider", provider)
                .tag("type", kind.name().toLowerCase(Locale.ROOT))
                .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .description("FX cover outcomes: executed, off_plan (alerting), computed_deviation (alerting),"
                        + " rate_incoherent (alerting), rejected, unknown, requoted, requote_refused (alerting), voided,"
                        + " anomaly (alerting). A count, never an amount")
                .register(registry);
    }

    private Timer timer(String provider, CoverKind kind) {
        return Timer.builder(LATENCY)
                .tag("provider", provider)
                .tag("type", kind.name().toLowerCase(Locale.ROOT))
                .description("From an FX cover's birth (the acceptance) to its execution - the uncovered"
                        + " position's life")
                .register(registry);
    }

    private Reading reading() {
        Reading current = cached.get();
        if (!current.takenAt().plus(MIN_REFRESH).isBefore(clock.instant())) {
            return current;
        }
        Reading fresh;
        try (Connection connection = connections.open()) {
            fresh = new Reading(clock.instant(),
                    Optional.of(new Board(covers.unknownBoard(connection), covers.oldestOpenAge(connection))));
        } catch (Exception unreadable) {
            log.warn("Could not read the FX cover board; its gauges report absent rather than zero: {}",
                    unreadable.getClass().getSimpleName());
            fresh = new Reading(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }

    private static double seconds(Duration age) {
        return age.toMillis() / 1000d;
    }

    private record Board(CoverStore.UnknownBoard unknown, Optional<Duration> open) {
        long active() {
            return unknown.active();
        }
    }

    private record Reading(Instant takenAt, Optional<Board> board) {}
}
