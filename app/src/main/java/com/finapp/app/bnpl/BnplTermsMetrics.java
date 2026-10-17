package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplProduct;
import com.finapp.bnpl.BnplTermsStore;
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
 * {@code finapp.bnpl.terms.active{product}} (`P12-TSK-004`, {@code PHASE_12_PLAN.md} section 15; lending's
 * {@code LoanTermsMetrics}): the version number of the product's {@code ACTIVE} terms version, 0 when it has none, NaN
 * when unreadable - never zero for a read that failed. No migration activates a version (ADR-0102 decision 4), so until a
 * person other than its proposer activates one the product is not offered: production reads 0 - the closed state made
 * visible (`PHASE_12_PLAN.md` section 1.1).
 *
 * <p>Registered eagerly per product, so every product has its series from startup; read from the database, so every
 * instance reports the same value - aggregate with {@code max()}, never {@code sum()}. Cached for 15 s behind a
 * per-instance refresh floor - never truth. A version number names no rate, fee or person.
 */
@Slf4j
public final class BnplTermsMetrics {

    /** Spelled with dots - exported as {@code finapp_bnpl_terms_active}, the plan's name. */
    public static final String ACTIVE = "finapp.bnpl.terms.active";

    static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** A connection source - its own seam, the metrics precedent. */
    @FunctionalInterface
    public interface Connections {
        Connection open() throws SQLException;
    }

    private final BnplTermsStore store;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, Optional.empty()));

    private record Cached(Instant takenAt, Optional<Map<BnplProduct, Integer>> activeVersions) {}

    public BnplTermsMetrics(BnplTermsStore store, Connections connections, Clock clock, MeterRegistry registry) {
        this.store = store;
        this.connections = connections;
        this.clock = clock;
        for (BnplProduct product : BnplProduct.values()) {
            Gauge.builder(ACTIVE, this, self -> self.activeVersionOf(product))
                    .tag("product", product.name())
                    .description(
                            "The version number of this BNPL product's ACTIVE terms - 0 when it has none, and the product"
                                    + " is not offered until a person other than its proposer activates one. NaN when"
                                    + " unreadable, never zero. Fleet-wide from every instance: aggregate with max(),"
                                    + " never sum()")
                    .strongReference(true)
                    .register(registry);
        }
    }

    private double activeVersionOf(BnplProduct product) {
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
            log.warn("The active BNPL terms versions could not be read: {}", unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }
}
