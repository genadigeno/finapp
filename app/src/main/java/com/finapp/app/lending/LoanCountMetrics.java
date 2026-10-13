package com.finapp.app.lending;

import com.finapp.lending.LoanKind;
import com.finapp.lending.LoanProduct;
import com.finapp.lending.LoanStatus;
import com.finapp.lending.LoanStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code finapp.lending.loans{product, status}} (`P11-TSK-023`; {@code PHASE_11_PLAN.md} section 15): how many loan
 * accounts are in each state, per product - a closing line visible as {@code CLOSING} from its request until it closes.
 * One series per product and each state its kind can hold (an instalment loan never {@code CLOSING}, a line never
 * {@code PENDING_DISBURSEMENT} or {@code CANCELLED} - {@code lending V006}'s {@code loan_status_is_the_kinds}), registered
 * eagerly. Read from the database, so every instance reports the same counts - aggregate with {@code max()}, never
 * {@code sum()}; cached 15 s behind a per-instance refresh floor - never truth; NaN when unreadable, never zero for a read
 * that failed. Counts only - never an amount, a party or a loan id (ADR-0072).
 */
@Slf4j
public final class LoanCountMetrics {

    /** Exported as {@code finapp_lending_loans}, the plan's name. */
    public static final String LOANS = "finapp.lending.loans";

    static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    private final LoanStore store;
    private final LoanServicingMetrics.Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, Optional.empty()));

    private record Cached(Instant takenAt, Optional<Map<LoanProduct, Map<LoanStatus, Long>>> counts) {}

    public LoanCountMetrics(LoanStore store, LoanServicingMetrics.Connections connections, Clock clock,
            MeterRegistry registry) {
        this.store = Objects.requireNonNull(store, "store");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(registry, "registry");
        for (LoanProduct product : LoanProduct.values()) {
            for (LoanStatus status : statesOf(product.kind())) {
                Gauge.builder(LOANS, this, metrics -> metrics.count(product, status))
                        .description("Loan accounts by product and state - NaN when unreadable. Fleet-wide from every"
                                + " instance: aggregate with max(), never sum()")
                        .tags("product", product.name().toLowerCase(Locale.ROOT),
                                "status", status.name().toLowerCase(Locale.ROOT))
                        .strongReference(true)
                        .register(registry);
            }
        }
    }

    /** The states an account of {@code kind} can hold. */
    static Set<LoanStatus> statesOf(LoanKind kind) {
        return kind == LoanKind.INSTALMENT
                ? EnumSet.of(LoanStatus.PENDING_DISBURSEMENT, LoanStatus.ACTIVE, LoanStatus.CLOSED, LoanStatus.CANCELLED)
                : EnumSet.of(LoanStatus.ACTIVE, LoanStatus.CLOSING, LoanStatus.CLOSED);
    }

    private double count(LoanProduct product, LoanStatus status) {
        return refreshed().counts()
                .map(counts -> (double) counts.getOrDefault(product, Map.of()).getOrDefault(status, 0L))
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
            fresh = new Cached(clock.instant(), Optional.of(store.countsByProductAndStatus(connection)));
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("The loan counts could not be read: {}", unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }
}
