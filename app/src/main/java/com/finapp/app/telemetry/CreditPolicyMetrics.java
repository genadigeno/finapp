package com.finapp.app.telemetry;

import com.finapp.credit.CreditPolicyStore;
import com.finapp.credit.CreditProduct;
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
 * {@code finapp.credit.policy.active{product}} (`P10-TSK-012`, PHASE_10_PLAN.md section 15): the version number of the
 * product's {@code ACTIVE} credit policy, 0 when it has none, NaN when unreadable - never zero for a read that failed.
 * No migration activates a policy (ADR-0086 section 4), so until a person other than its proposer activates a product's
 * version - the seeded v1, proposed by its migration, by one officer (OPERATIONS_RUNBOOK.md section 6) - the product
 * is not offered and no request for it is accepted or decided; this gauge is what makes that loud, and an offered product reading 0 is alerted (`P10-TSK-020`).
 * Registered eagerly per offered product, so every product has its series from startup; read from the database, so
 * every instance reports the same value - aggregate with {@code max()}, never {@code sum()}. Cached for 15 s, the
 * {@link RuleSetMissingMetrics} floor. A version number names no threshold and no person.
 */
@Slf4j
public final class CreditPolicyMetrics {

    /** Spelled with dots - exported as {@code finapp_credit_policy_active}, the plan's name. */
    public static final String ACTIVE = "finapp.credit.policy.active";

    private static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** A connection source - its own seam, the metrics precedent. */
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final CreditPolicyStore store;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, Optional.empty()));

    private record Cached(Instant takenAt, Optional<Map<CreditProduct, Integer>> activeVersions) {}

    public CreditPolicyMetrics(CreditPolicyStore store, Connections connections, Clock clock, MeterRegistry registry) {
        this.store = store;
        this.connections = connections;
        this.clock = clock;
        for (CreditProduct product : CreditProduct.values()) {
            Gauge.builder(ACTIVE, this, self -> self.activeVersionOf(product))
                    .tag("product", product.name())
                    .description(
                            "The version number of this product's ACTIVE credit policy - 0 when it has none, and no"
                                    + " request for it is accepted or decided until a person other than its proposer"
                                    + " activates one (alerted). NaN"
                                    + " when unreadable, never zero. Fleet-wide from every instance: aggregate with"
                                    + " max(), never sum()")
                    .strongReference(true)
                    .register(registry);
        }
    }

    private double activeVersionOf(CreditProduct product) {
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
            log.warn("The active credit policies could not be read: {}", unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }
}
