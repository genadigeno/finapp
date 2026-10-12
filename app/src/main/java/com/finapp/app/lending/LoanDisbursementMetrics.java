package com.finapp.app.lending;

import com.finapp.lending.CancellationReason;
import com.finapp.lending.LoanDisbursementStore;
import com.finapp.lending.LoanDisbursements;
import com.finapp.lending.PayoutPreference;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * The disbursement's series (`P11-TSK-014`, PHASE_11_PLAN.md section 15):
 *
 * <ul>
 *   <li>{@code finapp.lending.disbursement{payout, outcome}} - the plan's {@code {path, outcome}}, the key renamed
 *       because {@code path} is a forbidden tag fragment ({@code MetricNames}); every committed disbursement step that did something,
 *       counted once after its transaction committed: posted, retried (the wallet could not be posted), or cancelled
 *       with its reason. A step that found nothing to do counts nothing. Registered eagerly for every path and outcome.
 *   <li>{@code finapp.lending.disbursement.pending.age} - seconds the oldest {@code PENDING} disbursement has waited, on
 *       the database clock; 0 when none waits, NaN when unreadable - never zero for a read that failed. The alert sits
 *       above the disbursement deadline (`P11-TSK-030`). Read from the database, so every instance reports the same
 *       value - aggregate with {@code max()}, never {@code sum()}; cached 15 s behind a per-instance refresh floor -
 *       never truth.
 * </ul>
 *
 * <p>Never an amount, a party or a loan id in a tag (ADR-0072).
 */
@Slf4j
public final class LoanDisbursementMetrics {

    /** Spelled with dots - exported as {@code finapp_lending_disbursement}, the plan's name. */
    public static final String DISBURSEMENT = "finapp.lending.disbursement";

    /** Exported as {@code finapp_lending_disbursement_pending_age}. */
    public static final String PENDING_AGE = "finapp.lending.disbursement.pending.age";

    static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** The closed set of outcomes. */
    public enum Outcome {
        POSTED,
        RETRYING,
        STANDING_LOST,
        DISBURSEMENT_FAILED;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** A connection source - its own seam, the metrics precedent. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final MeterRegistry registry;
    private final LoanDisbursementStore store;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, Optional.empty()));

    private record Cached(Instant takenAt, Optional<Optional<Duration>> age) {}

    public LoanDisbursementMetrics(
            LoanDisbursementStore store, Connections connections, Clock clock, MeterRegistry registry) {
        this.store = Objects.requireNonNull(store, "store");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.registry = Objects.requireNonNull(registry, "registry");
        for (PayoutPreference path : PayoutPreference.values()) {
            for (Outcome outcome : Outcome.values()) {
                counter(path, outcome);
            }
        }
        Gauge.builder(PENDING_AGE, this, LoanDisbursementMetrics::oldestPendingSeconds)
                .description("Seconds the oldest PENDING loan disbursement has waited, on the database clock - 0 when none"
                        + " waits, NaN when unreadable. Alert above the disbursement deadline. Fleet-wide from every"
                        + " instance: aggregate with max(), never sum()")
                .baseUnit("seconds")
                .strongReference(true)
                .register(registry);
    }

    /** One committed disbursement step - counted only when it did something. */
    public void record(LoanDisbursements.Outcome step) {
        Objects.requireNonNull(step, "step");
        Outcome outcome = switch (step.kind()) {
            case POSTED -> Outcome.POSTED;
            case RETRYING -> Outcome.RETRYING;
            case CANCELLED -> step.reason().orElseThrow() == CancellationReason.STANDING_LOST
                    ? Outcome.STANDING_LOST
                    : Outcome.DISBURSEMENT_FAILED;
            case NOTHING -> null;
        };
        if (outcome != null) {
            counter(step.path().orElseThrow(), outcome).increment();
        }
    }

    private double oldestPendingSeconds() {
        return refreshed().age()
                .map(age -> age.map(waited -> waited.toMillis() / 1000.0).orElse(0.0))
                .orElse(Double.NaN);
    }

    private Cached refreshed() {
        Cached current = cached.get();
        if (Duration.between(current.takenAt(), clock.instant()).compareTo(MIN_REFRESH) < 0) {
            return current;
        }
        Cached fresh;
        try (Connection connection = connections.open()) {
            connection.setReadOnly(true);
            fresh = new Cached(clock.instant(), Optional.of(store.oldestPendingAge(connection)));
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("The oldest pending disbursement could not be read: {}", unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }

    private Counter counter(PayoutPreference path, Outcome outcome) {
        return Counter.builder(DISBURSEMENT)
                .description("Loan disbursement steps that did something - posted, retried because the wallet could not"
                        + " be posted, or cancelled for a lost standing or a failed disbursement - by path and outcome")
                .tags("payout", path.name().toLowerCase(Locale.ROOT), "outcome", outcome.tag())
                .register(registry);
    }
}
