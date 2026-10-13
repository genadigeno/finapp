package com.finapp.app.lending;

import com.finapp.lending.DelinquencyBucket;
import com.finapp.lending.LoanConditionStore;
import com.finapp.lending.LoanProduct;
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
 * {@code finapp.lending.delinquency{product, bucket}} and {@code finapp.lending.default{product}} (`P11-TSK-024`;
 * {@code PHASE_11_PLAN.md} section 15, ADR-0098): how many accounts in servicing stand in each past-due bucket, and how
 * many are defaulted, per product - as the daily evaluation last recorded them ({@code loan_condition_event}'s latest row
 * of each kind, at most a business date behind a repayment). One series per product and bucket, and one per product,
 * registered eagerly. Read from the database, so every instance reports the same counts - aggregate with {@code max()},
 * never {@code sum()}; cached 15 s behind a per-instance refresh floor - never truth; NaN when unreadable, never zero for
 * a read that failed. <strong>Account counts only</strong> - never an amount, a party, a loan id, and never an account's
 * DPD (ADR-0072, ADR-0098's security impact: the bucket is a tag only as a category every past-due account in it shares).
 */
@Slf4j
public final class LoanConditionMetrics {

    /** Exported as {@code finapp_lending_delinquency}, the plan's name. */
    public static final String DELINQUENCY = "finapp.lending.delinquency";

    /** Exported as {@code finapp_lending_default}, the plan's name. */
    public static final String DEFAULT = "finapp.lending.default";

    static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    private final LoanConditionStore store;
    private final LoanServicingMetrics.Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, Optional.empty()));

    private record Counts(Map<LoanProduct, Map<DelinquencyBucket, Long>> pastDue, Map<LoanProduct, Long> defaulted) {}

    private record Cached(Instant takenAt, Optional<Counts> counts) {}

    public LoanConditionMetrics(LoanConditionStore store, LoanServicingMetrics.Connections connections, Clock clock,
            MeterRegistry registry) {
        this.store = Objects.requireNonNull(store, "store");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(registry, "registry");
        for (LoanProduct product : LoanProduct.values()) {
            String productTag = product.name().toLowerCase(Locale.ROOT);
            for (DelinquencyBucket bucket : DelinquencyBucket.values()) {
                Gauge.builder(DELINQUENCY, this, metrics -> metrics.pastDue(product, bucket))
                        .description("Loan accounts in servicing by product and recorded days-past-due bucket - NaN when"
                                + " unreadable. Fleet-wide from every instance: aggregate with max(), never sum()")
                        .tags("product", productTag, "bucket", bucket.name().toLowerCase(Locale.ROOT))
                        .strongReference(true)
                        .register(registry);
            }
            Gauge.builder(DEFAULT, this, metrics -> metrics.defaulted(product))
                    .description("Defaulted loan accounts in servicing by product, as recorded - NaN when unreadable."
                            + " Fleet-wide from every instance: aggregate with max(), never sum()")
                    .tags("product", productTag)
                    .strongReference(true)
                    .register(registry);
        }
    }

    private double pastDue(LoanProduct product, DelinquencyBucket bucket) {
        return refreshed().counts()
                .map(counts -> (double) counts.pastDue().getOrDefault(product, Map.of()).getOrDefault(bucket, 0L))
                .orElse(Double.NaN);
    }

    private double defaulted(LoanProduct product) {
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
            log.warn("The loan condition counts could not be read: {}", unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }
}
