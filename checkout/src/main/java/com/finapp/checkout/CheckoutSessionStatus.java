package com.finapp.checkout;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The CheckoutSession machine (`P6-TSK-006`, ADR-0053 §4, {@code CHECKOUT_MERCHANT_LIFECYCLES.md}
 * §2, {@code INV-LIFE-01}):
 *
 * <pre>
 * OPEN            → PAYMENT_PENDING | EXPIRED | ABANDONED
 * PAYMENT_PENDING → COMPLETED | EXPIRED
 * EXPIRED         → COMPLETED_LATE
 * </pre>
 *
 * <p>Declared on the enum so the machine is readable in one place and a test can enumerate every
 * transition rather than the ones somebody remembered. The schema {@code CHECK}s are generated
 * from {@link #sqlValueList()} and the trigger's edges from {@link #permittedTransitions()};
 * {@code CheckoutSessionMigrationTest} fails the build if this enum and `V002` disagree.
 *
 * <h2>Three edges are absent, and each absence is a decision</h2>
 *
 * <p><strong>No {@code PAYMENT_PENDING → ABANDONED}.</strong> A session whose payment is in
 * flight cannot be cancelled: cancelling would leave money moving with no commercial home, which
 * is exactly the state {@code INV-MER-06} exists to prevent. Abandonment is for an offer nobody
 * acted on.
 *
 * <p><strong>No failure state at all.</strong> A declined payment is the <em>payment's</em> state
 * (ADR-0045's machines, unchanged). The session stays {@code PAYMENT_PENDING} and the customer
 * retries on the same intent (ADR-0053 §3). A {@code FAILED} session would end an offer the
 * customer has not given up on, and would need a producer for un-failing it.
 *
 * <p><strong>No {@code EXPIRED → COMPLETED}, only {@code EXPIRED → COMPLETED_LATE}.</strong> The
 * honest condition stays countable instead of being laundered into the ordinary one: a capture
 * that landed after the clock ran out is a different fact from one that landed in time, and an
 * operator is entitled to see how often it happens (ADR-0053's last consequence).
 *
 * <h2>Expiry is a state the sweeper earns — and the clock is checked anyway</h2>
 *
 * <p>{@code EXPIRED} is produced by a sweeper (`P6-TSK-008`), never by a
 * {@code WHERE expires_at < now()} filter pretending to be a state (ADR-0053 §4) — that is what
 * makes expiry countable, audited and present in history. But a sweeper runs on its own cadence,
 * so between the deadline and the sweep a row still reads {@code OPEN}. {@link CheckoutSession}
 * therefore <strong>also</strong> refuses to start anything once the clock has passed. The clock
 * check is not a substitute for the state; it is a guard in front of it, and without it a sweeper
 * one minute behind is a minute in which money lands on a dead offer.
 */
public enum CheckoutSessionStatus {

    /** The merchant created the offer; the customer has not committed. */
    OPEN,

    /** The customer confirmed; a payment intent is dispatched and the provider is deciding. */
    PAYMENT_PENDING,

    /** The payment captured in time; the order exists; the merchant is credited. */
    COMPLETED,

    /**
     * The capture landed <strong>after</strong> expiry; the order exists anyway
     * ({@code INV-MER-06}). The modelled race loser's win — money is never auto-reversed by a
     * clock, because undoing a movement that succeeded means starting a second one with its own
     * failure modes, which turns a timing artefact into financial risk.
     */
    COMPLETED_LATE,

    /** The clock ran out before money landed. Earned by the sweeper, never by a query. */
    EXPIRED,

    /** The merchant or customer cancelled an offer nobody acted on. */
    ABANDONED;

    /** The states reachable from this one. */
    public Set<CheckoutSessionStatus> permittedTransitions() {
        return switch (this) {
            case OPEN -> EnumSet.of(PAYMENT_PENDING, EXPIRED, ABANDONED);
            case PAYMENT_PENDING -> EnumSet.of(COMPLETED, EXPIRED);
            case EXPIRED -> EnumSet.of(COMPLETED_LATE);
            case COMPLETED, COMPLETED_LATE, ABANDONED ->
                    EnumSet.noneOf(CheckoutSessionStatus.class);
        };
    }

    public boolean isTerminal() {
        return permittedTransitions().isEmpty();
    }

    public boolean canTransitionTo(CheckoutSessionStatus target) {
        return permittedTransitions().contains(target);
    }

    /** Whether a session in this state may still start something — a confirmation, a dispatch. */
    public boolean acceptsNewWork() {
        return this == OPEN;
    }

    /** The states as a SQL literal list, for the {@code CHECK} constraint. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(status -> "'" + status.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
