package com.finapp.app.lending;

import com.finapp.lending.LoanProduct;
import com.finapp.lending.LoanTermsStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code finapp.lending.terms.active{product}} (`P11-TSK-005`, PHASE_11_PLAN.md section 15): the version number of the
 * product's {@code ACTIVE} terms version, 0 when it has none, NaN when unreadable - never zero for a read that failed.
 * No migration activates a version (ADR-0092 section 1), so until a person other than its proposer activates one the
 * product is not offered; production's expected state until the activation gate opens (PHASE_11_PLAN.md section 1.1),
 * so the alert on 0 for an offered product is enabled per environment (`P11-TSK-030`).
 *
 * <p>Registered eagerly per product, so every product has its series from startup; read from the database, so every
 * instance reports the same value - aggregate with {@code max()}, never {@code sum()}. Cached for 15 s behind a
 * per-instance refresh floor - never truth. A version number names no rate, fee or person ({@code CreditPolicyMetrics}'
 * shape).
 */
@Slf4j
public final class LoanTermsMetrics {

    /** Spelled with dots - exported as {@code finapp_lending_terms_active}, the plan's name. */
    public static final String ACTIVE = "finapp.lending.terms.active";

    static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** A connection source - its own seam, the metrics precedent. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final LoanTermsStore store;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, Optional.empty()));

    private record Cached(Instant takenAt, Optional<Map<LoanProduct, Integer>> activeVersions) {}

    public LoanTermsMetrics(LoanTermsStore store, Connections connections, Clock clock, MeterRegistry registry) {
        this.store = store;
        this.connections = connections;
        this.clock = clock;
        for (LoanProduct product : LoanProduct.values()) {
            Gauge.builder(ACTIVE, this, self -> self.activeVersionOf(product))
                    .tag("product", product.name())
                    .description(
                            "The version number of this product's ACTIVE loan terms - 0 when it has none, and the product"
                                    + " is not offered until a person other than its proposer activates one. NaN when"
                                    + " unreadable, never zero. Fleet-wide from every instance: aggregate with max(),"
                                    + " never sum()")
                    .strongReference(true)
                    .register(registry);
        }
    }

    private double activeVersionOf(LoanProduct product) {
        return refreshed().activeVersions()
                .map(versions -> (double) versions.getOrDefault(product, 0))
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
            fresh = new Cached(clock.instant(), Optional.of(Map.copyOf(store.activeVersions(connection))));
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("The active loan terms versions could not be read: {}", unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }
}
