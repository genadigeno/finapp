package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplConditionStore;
import com.finapp.bnpl.BnplDelinquencyBucket;
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
 * {@code finapp.bnpl.delinquency{product, bucket}} and {@code finapp.bnpl.default{product}} (`P12-TSK-020`;
 * {@code PHASE_12_PLAN.md} section 15; lending's {@code LoanConditionMetrics}): how many {@code ACTIVE} obligations stand in
 * each past-due bucket, and how many are defaulted, per product - as the daily evaluation last recorded them (the latest
 * {@code bnpl_condition_event} row of each kind, at most a business date behind a repayment). One series per product and
 * bucket, and one per product, registered eagerly. Read from the database, so every instance reports the same counts -
 * aggregate with {@code max()}, never {@code sum()}; cached 15 s behind a per-instance refresh floor - never truth; NaN when
 * unreadable, never zero for a read that failed. <strong>Obligation counts only</strong> - never an amount, a party, an
 * obligation id, and never an obligation's DPD (the bucket is a tag only as a category every past-due obligation in it
 * shares).
 */
@Slf4j
public final class BnplConditionMetrics {

    /** Exported as {@code finapp_bnpl_delinquency}, the plan's name. */
    public static final String DELINQUENCY = "finapp.bnpl.delinquency";

    /** Exported as {@code finapp_bnpl_default}, the plan's name. */
    public static final String DEFAULT = "finapp.bnpl.default";

    static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    private final BnplConditionStore store;
    private final BnplAccrualMetrics.Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, Optional.empty()));

    private record Counts(Map<BnplProduct, Map<BnplDelinquencyBucket, Long>> pastDue, Map<BnplProduct, Long> defaulted) {}

    private record Cached(Instant takenAt, Optional<Counts> counts) {}

    public BnplConditionMetrics(BnplConditionStore store, BnplAccrualMetrics.Connections connections, Clock clock,
            MeterRegistry registry) {
        this.store = Objects.requireNonNull(store, "store");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(registry, "registry");
        for (BnplProduct product : BnplProduct.values()) {
            String productTag = product.name().toLowerCase(Locale.ROOT);
            for (BnplDelinquencyBucket bucket : BnplDelinquencyBucket.values()) {
                Gauge.builder(DELINQUENCY, this, metrics -> metrics.pastDue(product, bucket))
                        .description("ACTIVE BNPL obligations by product and recorded days-past-due bucket - NaN when"
                                + " unreadable. Fleet-wide from every instance: aggregate with max(), never sum()")
                        .tags("product", productTag, "bucket", bucket.name().toLowerCase(Locale.ROOT))
                        .strongReference(true)
                        .register(registry);
            }
            Gauge.builder(DEFAULT, this, metrics -> metrics.defaulted(product))
                    .description("Defaulted ACTIVE BNPL obligations by product, as recorded - NaN when unreadable."
                            + " Fleet-wide from every instance: aggregate with max(), never sum()")
                    .tags("product", productTag)
                    .strongReference(true)
                    .register(registry);
        }
    }

    private double pastDue(BnplProduct product, BnplDelinquencyBucket bucket) {
        return refreshed().counts()
                .map(counts -> (double) counts.pastDue().getOrDefault(product, Map.of()).getOrDefault(bucket, 0L))
                .orElse(Double.NaN);
    }

    private double defaulted(BnplProduct product) {
        return refreshed().counts()
                .map(counts -> (double) counts.defaulted().getOrDefault(product, 0L))
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
            fresh = new Cached(clock.instant(), Optional.of(new Counts(store.pastDueByProductAndBucket(connection),
                    store.defaultedByProduct(connection))));
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("The BNPL condition counts could not be read: {}", unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }
}
