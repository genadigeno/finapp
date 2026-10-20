package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplConditionStore;
import com.finapp.bnpl.BnplObligationStatus;
import com.finapp.bnpl.BnplProduct;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code finapp.bnpl.obligations{product, status}} (`P12-TSK-026`; {@code PHASE_12_PLAN.md} section 15, planned and built
 * here; lending's {@code LoanCountMetrics}): how many customer repayment obligations are {@code ACTIVE} and how many
 * {@code CLOSED}, per product. One series per product and status, registered eagerly. Read from the database, so every
 * instance reports the same counts - aggregate with {@code max()}, never {@code sum()}; cached 15 s behind a per-instance
 * refresh floor - never truth; NaN when unreadable, never zero for a read that failed. Counts only - never an amount, a
 * party, a merchant or an obligation id (ADR-0072).
 */
@Slf4j
public final class BnplObligationMetrics {

    /** Exported as {@code finapp_bnpl_obligations}, the plan's name. */
    public static final String OBLIGATIONS = "finapp.bnpl.obligations";

    static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    private final BnplConditionStore store;
    private final BnplAccrualMetrics.Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, Optional.empty()));

    private record Cached(Instant takenAt, Optional<Map<BnplProduct, Map<BnplObligationStatus, Long>>> counts) {}

    public BnplObligationMetrics(BnplConditionStore store, BnplAccrualMetrics.Connections connections, Clock clock,
            MeterRegistry registry) {
        this.store = Objects.requireNonNull(store, "store");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(registry, "registry");
        for (BnplProduct product : BnplProduct.values()) {
            for (BnplObligationStatus status : BnplObligationStatus.values()) {
                Gauge.builder(OBLIGATIONS, this, metrics -> metrics.count(product, status))
                        .description("BNPL customer repayment obligations by product and status - NaN when unreadable."
                                + " Fleet-wide from every instance: aggregate with max(), never sum()")
                        .tags("product", product.name().toLowerCase(Locale.ROOT),
                                "status", status.name().toLowerCase(Locale.ROOT))
                        .strongReference(true)
                        .register(registry);
            }
        }
    }

    private double count(BnplProduct product, BnplObligationStatus status) {
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
            fresh = new Cached(clock.instant(), Optional.of(store.obligationsByProductAndStatus(connection)));
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("The BNPL obligation counts could not be read: {}", unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }
}
