package com.finapp.app.risk;

import com.finapp.risk.CustomerRiskProfileStore;
import com.finapp.risk.RiskProofs;
import com.finapp.risk.RiskRating;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * {@code finapp.risk.profile.rating{bucket}} - the parties standing at each customer risk rating (`P13-TSK-024`;
 * {@code PHASE_13_PLAN.md} section 15; {@code INV-RSK-10}'s operator view; `-007` routed the series here). One gauge per
 * bucket, registered eagerly: each {@link RiskRating} by name, and {@link #UNRATED} for the heads born and never rated (no
 * {@code MONITORING} version has judged them - production's state until the activation gate). A bounded vocabulary,
 * never a party ({@code INV-RSK-08}).
 *
 * <p><strong>Never read on the scrape thread</strong> ({@code RescreeningMetrics}' and {@code RiskProofMetrics}' pattern):
 * a scrape that finds the figures older than {@link #MIN_REFRESH} <em>starts</em> one reading on the refresher and
 * answers the last figures meanwhile - a compare-and-set in-flight flag makes it single-flight per instance; the reading
 * is one grouped count on a read-only snapshot armed with {@code statement_timeout} ({@link #over}); a failed reading, or
 * figures older than {@link #STALE_AFTER} (a refresher that never finished), are NaN - "absent", never zero. Every
 * instance reads the same figures: aggregate with {@code max()}, never {@code sum()}. A per-instance cache of a derived
 * count, never truth and never an input to a decision.
 */
@Slf4j
public final class RiskProfileMetrics {

    /** Exported as {@code finapp_risk_profile_rating}. */
    public static final String RATING = "finapp.risk.profile.rating";

    /** The bucket of the heads no {@code MONITORING} version has rated yet. */
    public static final String UNRATED = "UNRATED";

    /** The refresh floor: the distribution moves with recomputes, a day's cadence - five minutes is fresh enough. */
    static final Duration MIN_REFRESH = Duration.ofMinutes(5);

    /** Past this age the figures are NaN: two floors - the refresher did not finish a reading in time. */
    static final Duration STALE_AFTER = MIN_REFRESH.multipliedBy(2);

    private record Cached(Instant takenAt, Optional<CustomerRiskProfileStore.Ratings> ratings) {}

    private final Supplier<CustomerRiskProfileStore.Ratings> ratings;
    private final Executor refresher;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, Optional.empty()));
    private final AtomicBoolean inFlight = new AtomicBoolean();

    /** The ratings counted on one read-only snapshot of {@code transactions}, bounded by {@code statementTimeout}. */
    public static Supplier<CustomerRiskProfileStore.Ratings> over(CustomerRiskProfileStore profiles,
            RiskTransactions transactions, Duration statementTimeout) {
        Objects.requireNonNull(profiles, "profiles");
        Objects.requireNonNull(transactions, "transactions");
        Objects.requireNonNull(statementTimeout, "statementTimeout");
        return () -> transactions.inSnapshot(snapshot -> {
            RiskProofs.bound(snapshot, statementTimeout);
            return profiles.ratings(snapshot);
        });
    }

    /** A refresher that runs each reading on a virtual thread of its own - the composition's. */
    public static Executor virtualThreads() {
        return reading -> Thread.ofVirtual().name("risk-profile-ratings").start(reading);
    }

    /** Every bucket the gauge carries: the ratings by name, then {@link #UNRATED}. */
    public static List<String> buckets() {
        List<String> buckets = new ArrayList<>();
        for (RiskRating rating : RiskRating.values()) {
            buckets.add(rating.name());
        }
        buckets.add(UNRATED);
        return List.copyOf(buckets);
    }

    /**
     * @param ratings one bounded reading of the heads by rating ({@link #over})
     * @param refresher where a reading runs - never the scrape's thread in production ({@link #virtualThreads})
     */
    public RiskProfileMetrics(Supplier<CustomerRiskProfileStore.Ratings> ratings, Executor refresher, Clock clock,
            MeterRegistry registry) {
        this.ratings = Objects.requireNonNull(ratings, "ratings");
        this.refresher = Objects.requireNonNull(refresher, "refresher");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(registry, "registry");
        for (RiskRating rating : RiskRating.values()) {
            register(registry, rating.name(), figures -> figures.at(rating));
        }
        register(registry, UNRATED, CustomerRiskProfileStore.Ratings::unrated);
    }

    private void register(MeterRegistry registry, String bucket,
            java.util.function.ToLongFunction<CustomerRiskProfileStore.Ratings> count) {
        // A count through the ToDoubleFunction Micrometer's Gauge imposes - unboxed (RescreeningMetrics' shape), NaN the
        // sentinel for unreadable; never an amount (NoFloatingPointMoneyRulesTest holds the class to no Double API).
        Gauge.builder(RATING, this, self -> {
                    Optional<CustomerRiskProfileStore.Ratings> figures = self.figure();
                    return figures.isPresent() ? (double) count.applyAsLong(figures.get()) : Double.NaN;
                })
                .tag("bucket", bucket)
                .description("Parties whose customer risk profile stands at the rating - UNRATED for a head no"
                        + " MONITORING version has rated. NaN when unreadable or stale. Read off the scrape thread."
                        + " Fleet-wide from every instance: aggregate with max(), never sum()")
                .strongReference(true)
                .register(registry);
    }

    /** The figures served: the last reading's, empty when it failed or is past {@link #STALE_AFTER} - the gauge's NaN. */
    private Optional<CustomerRiskProfileStore.Ratings> figure() {
        Cached current = refreshed();
        if (Duration.between(current.takenAt(), clock.instant()).compareTo(STALE_AFTER) >= 0) {
            return Optional.empty();
        }
        return current.ratings();
    }

    /** The last figures; when they are past the floor, one more reading started on the refresher unless one runs. */
    private Cached refreshed() {
        Cached current = cached.get();
        if (Duration.between(current.takenAt(), clock.instant()).compareTo(MIN_REFRESH) < 0
                || !inFlight.compareAndSet(false, true)) {
            return current;
        }
        try {
            refresher.execute(() -> {
                try {
                    cached.set(read());
                } finally {
                    inFlight.set(false);
                }
            });
        } catch (RuntimeException refused) {
            // A refused start releases the flag; the figures keep the last and go NaN at two floors.
            inFlight.set(false);
            log.warn("The risk profile ratings' reading could not start: {}", refused.getClass().getSimpleName());
        }
        return cached.get();
    }

    private Cached read() {
        try {
            return new Cached(clock.instant(), Optional.of(ratings.get()));
        } catch (RuntimeException unreadable) {
            log.warn("The risk profile ratings could not be read: {}", unreadable.getClass().getSimpleName());
            return new Cached(clock.instant(), Optional.empty());
        }
    }
}
