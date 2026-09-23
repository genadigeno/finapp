package com.finapp.app.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * What became of the offers this platform made (`P6-TSK-008`, {@code PHASE_6_PLAN} §15) —
 * {@code finapp.checkout.session}, one counter per terminal outcome.
 *
 * <h2>Acting transitions only, and that is the whole discipline</h2>
 *
 * <p>Every increment here happens because <strong>this</strong> caller's conditional transition
 * returned {@code true}. Ten sweepers on one overdue session, a capture racing an expiry, a
 * customer clicking twice — all are one counted outcome, because the losers' row counts were
 * {@code false} and they increment nothing. That is the `P5-TSK-017` rule at a second
 * vocabulary: a meter that counted attempts rather than effects would report throughput for
 * decisions nobody made, and would grow with the instance count instead of the business.
 *
 * <h2>Where each one is counted, and the one honest compromise</h2>
 *
 * <p>{@link Outcome#EXPIRED} is counted by the sweeper's schedule, from the tick's own tally,
 * <strong>after</strong> each row's transaction committed — {@code PaymentSweeperSchedule}'s
 * seam exactly. {@link Outcome#ABANDONED} is counted by the surface after its transaction
 * returned.
 *
 * <p>{@link Outcome#COMPLETED} and {@link Outcome#COMPLETED_LATE} are counted <em>inside</em>
 * the completion's transaction, and the reason is worth stating rather than hiding: a
 * completion is driven by a capture, whose transaction belongs to {@code payments} and reaches
 * checkout through a composition port. Getting the outcome back out to a post-commit point
 * would mean threading a checkout-shaped return value through three {@code payments} classes
 * that must not know what a checkout is ({@code INV-PAY-03}). The guard that actually matters —
 * the convergence guard, which is what `P5-TSK-017` was about — is fully intact, because the
 * increment is behind the conditional. What is not guarded is a transaction that commits the
 * transition and then fails afterwards, which overcounts by one and is the cheaper error.
 *
 * <h2>Per instance</h2>
 *
 * <p>Counters are in this JVM's registry; {@code rate()} and {@code sum()} aggregate across the
 * fleet at the scrape. Nothing here is state anything reads back ({@code CLAUDE.md} rule 12) —
 * the count of record is always the table.
 */
public final class CheckoutMeters {

    private static final String SESSION = "finapp.checkout.session";

    /**
     * The four ways an offer ends. There is deliberately no counter for a session that is still
     * {@code OPEN} or {@code PAYMENT_PENDING}: those are not outcomes, they are waiting.
     */
    public enum Outcome {
        /** A capture landed inside the offer's window. */
        COMPLETED,
        /**
         * A capture landed <em>after</em> the offer expired ({@code INV-MER-06}). The number
         * this whole task exists to make visible: if it is not rare, either the offer window is
         * too short or the provider is too slow, and both are operator decisions that need the
         * figure before they can be made.
         */
        COMPLETED_LATE,
        /** A deadline passed with nobody paying, or with a payment that never landed. */
        EXPIRED,
        /** A merchant withdrew its own offer before anybody paid. */
        ABANDONED
    }

    private final Map<Outcome, Counter> outcomes = new EnumMap<>(Outcome.class);

    /**
     * Public for the reason {@code PaymentMeters} records: the checkout suites compose the real
     * doors over a {@code SimpleMeterRegistry} of their own, so they exercise the wired counting
     * path rather than a double.
     */
    public CheckoutMeters(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry must not be null");
        for (Outcome outcome : Outcome.values()) {
            outcomes.put(
                    outcome,
                    Counter.builder(SESSION)
                            .tag("outcome", lower(outcome.name()))
                            .description(
                                    "Checkout sessions by how they ENDED, counted once per"
                                        + " session on the conditional transition that actually"
                                        + " committed: completed, completed_late (money that"
                                        + " landed after the offer expired - INV-MER-06),"
                                        + " expired and abandoned. Racing sweepers, a retried"
                                        + " confirmation and a duplicate capture outcome are"
                                        + " never throughput. Per instance; rate() and sum()"
                                        + " aggregate")
                            .register(registry));
        }
    }

    /** One offer's ending, counted once, on the transition that committed it. */
    public void session(Outcome outcome) {
        outcomes.get(outcome).increment();
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
