package com.finapp.payments;

import com.finapp.ledger.HoldId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * The Refund: a bounded return referencing a <em>captured attempt</em> ({@code P5-TSK-007},
 * ADR-0045) — its own aggregate with its own machine, never an edge on the intent or the
 * attempt. Refund totals are derived by views over these rows and stored nowhere; the intent's
 * {@code SUCCEEDED} and the attempt's {@code CAPTURED} stay stable while refunds accumulate
 * beside them.
 *
 * <p><strong>{@code INV-PAY-05}'s domain half is judged where the refund is born.</strong>
 * {@link #create} refuses a refund against anything but a {@code CAPTURED} attempt, a currency
 * that is not the capture's, and — the bound itself — an amount that would take the sum of
 * non-{@code FAILED} refunds past the captured amount. The sibling total is an explicit
 * argument, <strong>and the signature is the contract</strong>: it must be read under the
 * command's lock on the attempt row (lock-then-look, {@code PHASE_5_PLAN.md} §7,
 * {@code P5-TSK-015}), because a sum read outside the lock is a race, not a fact. A refund to
 * the exact remaining amount is legal — the bound is ≤, not &lt;.
 *
 * <p><strong>What the one constructor cannot re-judge, named honestly</strong>: the sum bound.
 * A single rehydrated row cannot see its siblings, so read-back coherence here is row-local
 * (presence, positivity, reference ⇔ {@code COMPLETED}) and the cross-row half under
 * concurrency belongs to the schema's in-trigger bound ({@code P5-TSK-008}, the {@code V009}
 * pattern) — for every writer, which is what {@code INV-PAY-05}'s own text demands.
 *
 * <p>Facts this row always carries, from birth: the required {@code reason} — refund creation
 * is a privileged act and the reason is part of the act ({@code PHASE_5_PLAN.md} §8/§11), never
 * optional; the {@link HoldId} of the Phase 3 hold the dispatch placed on the customer wallet
 * (the funds being returned must not be spent mid-flight — completion releases-and-posts
 * atomically, failure releases with nothing posted, {@code P5-TSK-015}); and the
 * {@link ProviderIdempotencyReference} minted at dispatch ({@code INV-PAY-04}). The provider's
 * own reference arrives with {@code COMPLETED}, both directions. Deliberately no mapped
 * failure reason: {@code PHASE_5_PLAN.md} §8 gives the refund row no such column — the
 * provider's answer lives in retained evidence, and a taxonomy would be vocabulary with no
 * consumer.
 *
 * <p>A class rather than a record ({@code INV-AUD-02} — {@link Money} in a generated
 * {@code toString}); typed {@link HoldId} because the {@code payments → ledger} edge exists for
 * exactly this. Doors are per-outcome through one machine check ({@code INV-LIFE-02});
 * production callers are {@code P5-TSK-015}'s outcome transaction and the §7 resolvers —
 * exercised by the exhaustive sweep until they arrive.
 */
public final class Refund {

    private final RefundId id;
    private final PaymentAttemptId attemptId;
    private final Money amount;
    private final String reason;
    private final HoldId holdReference;
    private final ProviderIdempotencyReference providerIdempotencyReference;
    private final ProviderReference providerReference;
    private final RefundStatus status;
    private final Instant createdAt;

    private Refund(
            RefundId id,
            PaymentAttemptId attemptId,
            Money amount,
            String reason,
            HoldId holdReference,
            ProviderIdempotencyReference providerIdempotencyReference,
            ProviderReference providerReference,
            RefundStatus status,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.attemptId = Objects.requireNonNull(attemptId, "attemptId must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.reason = Objects.requireNonNull(
                reason, "reason must not be null - a refund is a privileged act");
        this.holdReference = Objects.requireNonNull(
                holdReference,
                "holdReference must not be null - the dispatch places the hold, so no refund"
                        + " exists without one");
        this.providerIdempotencyReference = Objects.requireNonNull(
                providerIdempotencyReference,
                "providerIdempotencyReference must not be null (INV-PAY-04)");
        this.providerReference = providerReference;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");

        if (reason.isBlank()) {
            throw new IllegalArgumentException(
                    "a refund's reason must not be blank - the reason is part of the"
                            + " privileged act");
        }
        // Never zero, never negative - a negative refund is a charge wearing a refund's
        // clothes. The message names the fact and the currency, never the value (INV-AUD-02).
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "a refund amount must be strictly positive; refused a non-positive amount"
                            + " in " + amount.currency());
        }
        // The provider's reference <=> COMPLETED, both directions: it is the completion's
        // fact, and a COMPLETED refund that cannot name the provider's refund is not evidence
        // Phase 8 can reconcile.
        if ((providerReference != null) != (status == RefundStatus.COMPLETED)) {
            throw new IllegalArgumentException(
                    "a refund carries the provider's reference exactly when COMPLETED - "
                            + status + " is incoherent with what this row holds");
        }
    }

    /**
     * A new refund, born {@code DISPATCHED}, judged against the captured attempt —
     * {@code INV-PAY-05}'s domain half. {@code alreadyRefunded} is the sum of this attempt's
     * non-{@code FAILED} refunds, <strong>read under the command's lock on the attempt row</strong>
     * ({@code P5-TSK-015}); the concurrent half of the same bound is the schema trigger's.
     */
    public static Refund create(
            IdGenerator ids,
            Clock clock,
            PaymentAttempt attempt,
            Money amount,
            Money alreadyRefunded,
            String reason,
            HoldId holdReference,
            ProviderIdempotencyReference providerIdempotencyReference) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(attempt, "attempt must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(alreadyRefunded, "alreadyRefunded must not be null");

        if (attempt.status() != PaymentAttemptStatus.CAPTURED) {
            throw new IllegalArgumentException(
                    "a refund references a CAPTURED attempt (INV-PAY-05) - attempt "
                            + attempt.id() + " is " + attempt.status());
        }
        Money captured = attempt.capturedAmount();
        if (!amount.currency().equals(captured.currency())) {
            throw new IllegalArgumentException(
                    "a refund must be in the capture's currency; refused " + amount.currency()
                            + " against " + captured.currency());
        }
        if (!alreadyRefunded.currency().equals(captured.currency())) {
            throw new IllegalArgumentException(
                    "the refunded-so-far sum must be in the capture's currency; refused "
                            + alreadyRefunded.currency() + " against " + captured.currency());
        }
        if (alreadyRefunded.isNegative()) {
            throw new IllegalArgumentException(
                    "the refunded-so-far sum cannot be negative; refused a negative sum in "
                            + alreadyRefunded.currency());
        }
        // The bound itself: sum of non-FAILED refunds <= captured, refund-to-the-penny legal.
        // The message names the fact and the currency, never any amount (INV-AUD-02).
        if (amount.plus(alreadyRefunded).compareTo(captured) > 0) {
            throw new IllegalArgumentException(
                    "refunds are bounded by the capture (INV-PAY-05): refused a refund taking"
                            + " the refunded sum past the captured amount in "
                            + captured.currency());
        }
        return new Refund(
                RefundId.next(ids),
                attempt.id(),
                amount,
                reason,
                holdReference,
                providerIdempotencyReference,
                null,
                RefundStatus.DISPATCHED,
                Instant.now(clock));
    }

    /**
     * A row read back from storage, through the same constructor — row-local coherence refused
     * on read-back; the sum bound is the trigger's (above).
     */
    public static Refund rehydrate(
            RefundId id,
            PaymentAttemptId attemptId,
            Money amount,
            String reason,
            HoldId holdReference,
            ProviderIdempotencyReference providerIdempotencyReference,
            ProviderReference providerReference,
            RefundStatus status,
            Instant createdAt) {
        return new Refund(
                id, attemptId, amount, reason, holdReference, providerIdempotencyReference,
                providerReference, status, createdAt);
    }

    /** The provider refunded; the posting and the hold release commit beside this transition. */
    public Refund complete(ProviderReference reference) {
        requireLegal(RefundStatus.COMPLETED);
        return new Refund(
                id, attemptId, amount, reason, holdReference, providerIdempotencyReference,
                reference, RefundStatus.COMPLETED, createdAt);
    }

    /** The provider refused; the hold releases with nothing posted. */
    public Refund fail() {
        requireLegal(RefundStatus.FAILED);
        return new Refund(
                id, attemptId, amount, reason, holdReference, providerIdempotencyReference,
                providerReference, RefundStatus.FAILED, createdAt);
    }

    /** The refund's outcome is unknown — commit the honest state ({@code INV-LIFE-03}). */
    public Refund outcomeUnknown() {
        requireLegal(RefundStatus.UNKNOWN);
        return new Refund(
                id, attemptId, amount, reason, holdReference, providerIdempotencyReference,
                providerReference, RefundStatus.UNKNOWN, createdAt);
    }

    /** The machine's one check ({@code INV-LIFE-02}), whichever door the transition arrives by. */
    private void requireLegal(RefundStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalRefundTransitionException(id, status, target);
        }
    }

    public RefundId id() {
        return id;
    }

    /** The captured attempt this refund references and is bounded by ({@code INV-PAY-05}). */
    public PaymentAttemptId attemptId() {
        return attemptId;
    }

    public Money amount() {
        return amount;
    }

    /** Required — refund creation is a privileged act and the reason is part of the act. */
    public String reason() {
        return reason;
    }

    /** The Phase 3 hold the dispatch placed on the customer wallet. */
    public HoldId holdReference() {
        return holdReference;
    }

    /** Minted at dispatch ({@code INV-PAY-04}); what the provider is queried by. */
    public ProviderIdempotencyReference providerIdempotencyReference() {
        return providerIdempotencyReference;
    }

    /** The provider's refund reference; {@code null} unless {@code COMPLETED}. */
    public ProviderReference providerReference() {
        return providerReference;
    }

    public RefundStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
