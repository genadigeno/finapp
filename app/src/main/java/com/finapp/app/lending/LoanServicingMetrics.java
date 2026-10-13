package com.finapp.app.lending;

import com.finapp.lending.CollectionOutcome;
import com.finapp.lending.LoanServicing;
import com.finapp.lending.LoanServicingStore;
import com.finapp.lending.RepaymentKind;
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
 * The accrual's series (`P11-TSK-016`, PHASE_11_PLAN.md section 15):
 *
 * <ul>
 *   <li>{@code finapp.lending.accrual{outcome}} - each accrued date of a committed servicing step, counted once after its
 *       transaction committed: {@code posted} (the row and its entry) or {@code zero} (the row alone - a zero day); and
 *       each servicing step that failed ({@code failed} - rolled back, retried at the claim's cadence). Registered eagerly
 *       for every outcome.
 *   <li>{@code finapp.lending.accrual.lag} - seconds since the end of the oldest elapsed date an instalment loan in
 *       servicing has not accrued, on the database clock in its pinned zone; 0 when every elapsed date is accrued, NaN
 *       when unreadable - never zero for a read that failed. The alert sits above one day (`P11-TSK-030`). Read from the
 *       database, so every instance reports the same value - aggregate with {@code max()}, never {@code sum()}; cached
 *       15 s behind a per-instance refresh floor - never truth.
 *   <li>{@code finapp.lending.billing{kind, outcome}} (`P11-TSK-017`) - each billed item of a committed servicing step
 *       ({@code kind = instalment}, {@code outcome = billed}) and each credit-balance application at one
 *       ({@code outcome = credit_applied}), counted once after the step's transaction committed. Registered eagerly for
 *       every outcome; a line's statement ({@code kind = statement}) is {@code P11-TSK-022}'s to add.
 *   <li>{@code finapp.lending.collection{outcome}} (`P11-TSK-019`) - each automatic collection attempt of a committed
 *       servicing step, by its outcome ({@code collected}, {@code partial}, {@code insufficient_funds},
 *       {@code wallet_not_postable}, {@code nothing_due}), counted once after the step's transaction committed. Registered
 *       eagerly for every outcome. A collection that moved money is also one {@code finapp.lending.repayment} of
 *       {@code kind = auto_collection} ({@code repaid}, or {@code closed} when the step closed the loan), and one that
 *       could not ({@code insufficient_funds}, {@code wallet_not_postable}) one {@code insufficient_funds} there -
 *       {@link RepaymentMetrics}' series, the customer's door's beside it.
 * </ul>
 *
 * <p>Never an amount, a party, a loan id or a date in a tag (ADR-0072).
 */
@Slf4j
public final class LoanServicingMetrics {

    /** Exported as {@code finapp_lending_accrual}, the plan's name. */
    public static final String ACCRUAL = "finapp.lending.accrual";

    /** Exported as {@code finapp_lending_accrual_lag}. */
    public static final String LAG = "finapp.lending.accrual.lag";

    /** Exported as {@code finapp_lending_billing} (`P11-TSK-017`). */
    public static final String BILLING = "finapp.lending.billing";

    /** Exported as {@code finapp_lending_collection} (`P11-TSK-019`), the plan's name. */
    public static final String COLLECTION = "finapp.lending.collection";

    /** The billing series' closed set of outcomes. */
    public enum BillingOutcome {
        BILLED,
        CREDIT_APPLIED;

        String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    static final Duration MIN_REFRESH = Duration.ofSeconds(15);

    /** The closed set of outcomes. */
    public enum Outcome {
        POSTED,
        ZERO,
        FAILED;

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
    private final RepaymentMetrics repayments;
    private final LoanServicingStore store;
    private final Connections connections;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>(new Cached(Instant.EPOCH, Optional.empty()));

    private record Cached(Instant takenAt, Optional<Optional<Duration>> lag) {}

    public LoanServicingMetrics(LoanServicingStore store, Connections connections, Clock clock, MeterRegistry registry,
            RepaymentMetrics repayments) {
        this.store = Objects.requireNonNull(store, "store");
        this.repayments = Objects.requireNonNull(repayments, "repayments");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.registry = Objects.requireNonNull(registry, "registry");
        for (Outcome outcome : Outcome.values()) {
            counter(outcome);
        }
        for (BillingOutcome outcome : BillingOutcome.values()) {
            billing(outcome);
        }
        for (CollectionOutcome outcome : CollectionOutcome.values()) {
            collection(outcome);
        }
        Gauge.builder(LAG, this, LoanServicingMetrics::lagSeconds)
                .description("Seconds since the end of the oldest elapsed date an instalment loan in servicing has not"
                        + " accrued, on the database clock in its pinned zone - 0 when none, NaN when unreadable. Alert"
                        + " above one day. Fleet-wide from every instance: aggregate with max(), never sum()")
                .baseUnit("seconds")
                .strongReference(true)
                .register(registry);
    }

    /** One committed servicing step - its accrued dates and its billed items counted. */
    public void record(LoanServicing.Outcome step) {
        Objects.requireNonNull(step, "step");
        if (step.posted() > 0) {
            counter(Outcome.POSTED).increment(step.posted());
        }
        if (step.zero() > 0) {
            counter(Outcome.ZERO).increment(step.zero());
        }
        if (step.billed() > 0) {
            billing(BillingOutcome.BILLED).increment(step.billed());
        }
        if (step.applied() > 0) {
            billing(BillingOutcome.CREDIT_APPLIED).increment(step.applied());
        }
        int moved = 0;
        for (CollectionOutcome outcome : CollectionOutcome.values()) {
            int attempts = step.collected(outcome);
            if (attempts == 0) {
                continue;
            }
            collection(outcome).increment(attempts);
            if (outcome.collected()) {
                moved += attempts;
            } else if (outcome.failed()) {
                for (int i = 0; i < attempts; i++) {
                    repayments.record(RepaymentKind.AUTO_COLLECTION, RepaymentMetrics.Outcome.INSUFFICIENT_FUNDS);
                }
            }
        }
        // A collection that closed the loan is the step's last: counted closing, the others repaid. When no collection
        // moved money, a closure was the step's own (after a credit-balance application) and no repayment is counted.
        int closing = moved > 0 && step.closed() ? 1 : 0;
        for (int i = 0; i < moved - closing; i++) {
            repayments.record(RepaymentKind.AUTO_COLLECTION, RepaymentMetrics.Outcome.REPAID);
        }
        if (closing == 1) {
            repayments.record(RepaymentKind.AUTO_COLLECTION, RepaymentMetrics.Outcome.CLOSED);
        }
    }

    /** One servicing step that failed and rolled back. */
    public void failed() {
        counter(Outcome.FAILED).increment();
    }

    private double lagSeconds() {
        return refreshed().lag()
                .map(lag -> lag.map(elapsed -> elapsed.toMillis() / 1000.0).orElse(0.0))
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
            fresh = new Cached(clock.instant(), Optional.of(store.oldestUnaccruedLag(connection)));
        } catch (SQLException | RuntimeException unreadable) {
            log.warn("The accrual lag could not be read: {}", unreadable.getClass().getSimpleName());
            fresh = new Cached(clock.instant(), Optional.empty());
        }
        cached.set(fresh);
        return fresh;
    }

    private Counter billing(BillingOutcome outcome) {
        return Counter.builder(BILLING)
                .description("Billed items of committed servicing steps (billed) and the credit-balance applications at"
                        + " them (credit_applied) - an instalment loan's instalments and post-maturity periods")
                .tags("kind", "instalment", "outcome", outcome.tag())
                .register(registry);
    }

    private Counter collection(CollectionOutcome outcome) {
        return Counter.builder(COLLECTION)
                .description("Automatic collection attempts of committed servicing steps, by outcome - collected,"
                        + " partial, insufficient_funds, wallet_not_postable, nothing_due")
                .tags("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .register(registry);
    }

    private Counter counter(Outcome outcome) {
        return Counter.builder(ACCRUAL)
                .description("Accrued dates of committed servicing steps - posted (the row and its entry) or zero (the row"
                        + " alone) - and servicing steps that failed and rolled back")
                .tags("outcome", outcome.tag())
                .register(registry);
    }
}
