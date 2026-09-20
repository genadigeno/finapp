package com.finapp.payments;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * The PaymentAttempt: the provider-facing try ({@code P5-TSK-007}, ADR-0045) — the row that owns
 * <em>every</em> provider fact: the per-operation idempotency references we minted
 * ({@code INV-PAY-04}), the references the provider answered with, the amounts the issuer
 * promised and the provider captured, and the mapped failure reason ({@code INV-PAY-03}). The
 * intent answers the customer; this row answers the operator and Phase 8's reconciliation —
 * different questions, ADR-0045's reason for two aggregates.
 *
 * <p><strong>One constructor holds every invariant, and every path shares it</strong> — birth,
 * the six doors and {@link #rehydrate} — so a corrupt row is refused on read-back ahead of the
 * schema's {@code CHECK}s ({@code P5-TSK-008}). Unlike the intent, the attempt's coherence is
 * <strong>status-dependent by design, because transitions carry their payloads</strong> — the
 * fact arrives with the answer that established it:
 *
 * <ul>
 *   <li>the authorization idempotency reference exists from birth — the dispatch is committed
 *       before the provider is asked (ADR-0046), so there is never an attempt row without it;
 *   <li>the issuer's promise is one fact: provider reference and authorized amount together or
 *       not at all, present exactly from {@code AUTHORIZED} onward;
 *   <li>the capture idempotency reference is minted by {@link #dispatchCapture}, so
 *       {@code AUTHORIZED} does not carry it and every capture-stage state does;
 *   <li>captured amount ⇔ capture provider reference ⇔ {@code CAPTURED}, both directions;
 *   <li>mapped reason ⇔ {@code FAILED}, both directions (the {@code Transfer.failureReason}
 *       idiom) — and a {@code FAILED} holding the issuer's promise but no capture dispatch is
 *       refused: {@code AUTHORIZED → FAILED} is not an edge, so no path produces that shape and
 *       rehydrate treats it as the corrupt row it is;
 *   <li>{@code INV-PAY-05}'s row-local half: captured ≤ authorized, same currency, both
 *       strictly positive — in the constructor, not only at the door, so trust-the-database is
 *       structurally impossible.
 * </ul>
 *
 * <p>Because payloads arrive with transitions, {@code P5-TSK-008}'s {@code UPDATE} grant for
 * this table is wider than the intent's one column — status plus the columns whose facts the
 * doors carry — stated here so the narrowing there is a reconciliation, not a rediscovery.
 *
 * <p><strong>What this row deliberately does not hold</strong>: the requested amount (the
 * intent's fact — the authorized amount is the <em>issuer's</em> answer, not a copy of the
 * ask); amount-currency ⇔ wallet-currency agreement (the command's authoritative resolution and
 * the schema's composite FK — an aggregate holding ids cannot check it); refund state (the
 * refund rows'); provider-side expiry metadata (retained evidence, no consumer —
 * {@code PHASE_5_PLAN.md} §8). A class rather than a record, load-bearing: a record's generated
 * {@code toString} renders {@link Money}, and payment amounts are {@code RESTRICTED-FINANCIAL}
 * ({@code INV-AUD-02}).
 *
 * <p>Transition doors are per-outcome through one machine check ({@code INV-LIFE-02}).
 * Production callers: birth and {@link #authorize}/{@link #fail} at the auth stage are
 * {@code P5-TSK-009}/{@code -010}'s outcome transactions, {@link #dispatchCapture} and
 * {@link #capture} are {@code P5-TSK-010}'s, the {@code *OutcomeUnknown} doors and the
 * resolution edges out of them are the resolvers' ({@code P5-TSK-013}/{@code -014},
 * {@code PAYMENT_LIFECYCLES.md} §7) — shipped with the machine and exercised by the exhaustive
 * sweep until they arrive (the {@code CustomerAccount.moveTo} precedent).
 */
public final class PaymentAttempt {

    private final PaymentAttemptId id;
    private final PaymentIntentId intentId;
    private final ProviderIdempotencyReference authorizationReference;
    private final ProviderIdempotencyReference captureReference;
    private final ProviderReference authorizationProviderReference;
    private final Money authorizedAmount;
    private final ProviderReference captureProviderReference;
    private final Money capturedAmount;
    private final PaymentFailureReason failureReason;
    private final PaymentAttemptStatus status;
    private final Instant createdAt;

    private PaymentAttempt(
            PaymentAttemptId id,
            PaymentIntentId intentId,
            ProviderIdempotencyReference authorizationReference,
            ProviderIdempotencyReference captureReference,
            ProviderReference authorizationProviderReference,
            Money authorizedAmount,
            ProviderReference captureProviderReference,
            Money capturedAmount,
            PaymentFailureReason failureReason,
            PaymentAttemptStatus status,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.intentId = Objects.requireNonNull(intentId, "intentId must not be null");
        this.authorizationReference = Objects.requireNonNull(
                authorizationReference,
                "authorizationReference must not be null - the dispatch is committed before the"
                        + " provider is asked, so no attempt exists without one (ADR-0046)");
        this.captureReference = captureReference;
        this.authorizationProviderReference = authorizationProviderReference;
        this.authorizedAmount = authorizedAmount;
        this.captureProviderReference = captureProviderReference;
        this.capturedAmount = capturedAmount;
        this.failureReason = failureReason;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");

        // Mapped reason <=> FAILED, both directions (the Transfer.failureReason idiom). The
        // reason is THIS row's fact - the intent's FAILED carries no copy (one fact, one place).
        if ((failureReason != null) != (status == PaymentAttemptStatus.FAILED)) {
            throw new IllegalArgumentException(
                    "a payment attempt carries a failure reason exactly when FAILED - "
                            + status + " with reason " + failureReason + " is incoherent");
        }

        // The issuer's promise is one fact: reference and amount together or not at all.
        if ((authorizationProviderReference != null) != (authorizedAmount != null)) {
            throw new IllegalArgumentException(
                    "the authorization's provider reference and authorized amount are one fact -"
                            + " one without the other is incoherent");
        }

        // Which facts each stage requires and forbids - the machine's shape, held on the row.
        boolean authorized = authorizedAmount != null;
        boolean captureDispatched = captureReference != null;
        switch (status) {
            case AUTH_DISPATCHED, AUTH_UNKNOWN -> {
                if (authorized || captureDispatched) {
                    throw new IllegalArgumentException(
                            "an attempt in " + status + " carries nothing beyond its birth"
                                    + " facts - an authorization or capture fact here is"
                                    + " incoherent");
                }
            }
            case AUTHORIZED -> {
                if (!authorized) {
                    throw new IllegalArgumentException(
                            "an AUTHORIZED attempt must carry the issuer's promise - the"
                                    + " provider reference and authorized amount");
                }
                if (captureDispatched) {
                    throw new IllegalArgumentException(
                            "an AUTHORIZED attempt has no capture reference yet -"
                                    + " dispatchCapture is the act that mints it");
                }
            }
            case CAPTURE_DISPATCHED, CAPTURE_UNKNOWN, CAPTURED -> {
                if (!authorized || !captureDispatched) {
                    throw new IllegalArgumentException(
                            "an attempt in " + status + " must carry the authorization pair and"
                                    + " the capture's idempotency reference");
                }
            }
            case FAILED -> {
                // Two shapes and only two: failed at authorization (bare) or failed at capture
                // (promise + capture reference). AUTHORIZED -> FAILED is not an edge - VOIDED's
                // job, Phase 6 - so a FAILED row holding the promise without a capture dispatch
                // was written by no path this machine permits.
                if (authorized != captureDispatched) {
                    throw new IllegalArgumentException(
                            "a FAILED attempt failed at authorization (no authorization facts)"
                                    + " or at capture (authorization pair and capture reference"
                                    + " both present) - this row is neither shape");
                }
            }
        }

        // Captured amount <=> capture provider reference <=> CAPTURED, both directions.
        if ((capturedAmount != null) != (captureProviderReference != null)) {
            throw new IllegalArgumentException(
                    "the capture's provider reference and captured amount are one fact - one"
                            + " without the other is incoherent");
        }
        if ((capturedAmount != null) != (status == PaymentAttemptStatus.CAPTURED)) {
            throw new IllegalArgumentException(
                    "an attempt carries a captured amount exactly when CAPTURED - status "
                            + status + " is incoherent with what this row holds");
        }

        // INV-PAY-05's row-local half, in the constructor so every path - the capture door AND
        // read-back - judges it; trust-the-database is structurally impossible. Messages name
        // facts and currencies, never values: they reach logs, and payment amounts are
        // RESTRICTED-FINANCIAL (INV-AUD-02).
        if (authorizedAmount != null && !authorizedAmount.isPositive()) {
            throw new IllegalArgumentException(
                    "an authorized amount must be strictly positive; refused a non-positive"
                            + " amount in " + authorizedAmount.currency());
        }
        if (capturedAmount != null) {
            if (!capturedAmount.isPositive()) {
                throw new IllegalArgumentException(
                        "a captured amount must be strictly positive; refused a non-positive"
                                + " amount in " + capturedAmount.currency());
            }
            if (!capturedAmount.currency().equals(authorizedAmount.currency())) {
                throw new IllegalArgumentException(
                        "a capture must be in the authorization's currency; refused "
                                + capturedAmount.currency() + " against "
                                + authorizedAmount.currency());
            }
            if (capturedAmount.compareTo(authorizedAmount) > 0) {
                throw new IllegalArgumentException(
                        "capture is bounded by authorization (INV-PAY-05): refused a captured"
                                + " amount exceeding the authorized amount in "
                                + capturedAmount.currency());
            }
        }
    }

    /**
     * A new attempt, born {@code AUTH_DISPATCHED} with its authorization idempotency reference
     * already minted — the dispatch commits before the provider is asked (ADR-0046), inside
     * confirm's transaction ({@code P5-TSK-009}). Birth is the only door to it.
     */
    public static PaymentAttempt create(
            IdGenerator ids,
            Clock clock,
            PaymentIntentId intentId,
            ProviderIdempotencyReference authorizationReference) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        return new PaymentAttempt(
                PaymentAttemptId.next(ids),
                intentId,
                authorizationReference,
                null, null, null, null, null, null,
                PaymentAttemptStatus.AUTH_DISPATCHED,
                Instant.now(clock));
    }

    /**
     * A row read back from storage, through the same constructor — so a corrupt row is refused
     * on read-back, ahead of the schema's own {@code CHECK}s.
     */
    public static PaymentAttempt rehydrate(
            PaymentAttemptId id,
            PaymentIntentId intentId,
            ProviderIdempotencyReference authorizationReference,
            ProviderIdempotencyReference captureReference,
            ProviderReference authorizationProviderReference,
            Money authorizedAmount,
            ProviderReference captureProviderReference,
            Money capturedAmount,
            PaymentFailureReason failureReason,
            PaymentAttemptStatus status,
            Instant createdAt) {
        return new PaymentAttempt(
                id, intentId, authorizationReference, captureReference,
                authorizationProviderReference, authorizedAmount, captureProviderReference,
                capturedAmount, failureReason, status, createdAt);
    }

    /** The issuer's promise arrived: reference and amount together, one fact. */
    public PaymentAttempt authorize(ProviderReference providerReference, Money amount) {
        requireLegal(PaymentAttemptStatus.AUTHORIZED);
        return new PaymentAttempt(
                id, intentId, authorizationReference, captureReference,
                providerReference, amount, captureProviderReference, capturedAmount,
                failureReason, PaymentAttemptStatus.AUTHORIZED, createdAt);
    }

    /** The authorization's outcome is unknown — commit the honest state ({@code INV-LIFE-03}). */
    public PaymentAttempt authorizationOutcomeUnknown() {
        requireLegal(PaymentAttemptStatus.AUTH_UNKNOWN);
        return new PaymentAttempt(
                id, intentId, authorizationReference, captureReference,
                authorizationProviderReference, authorizedAmount, captureProviderReference,
                capturedAmount, failureReason, PaymentAttemptStatus.AUTH_UNKNOWN, createdAt);
    }

    /** The capture dispatch, its idempotency reference minted by this act ({@code INV-PAY-04}). */
    public PaymentAttempt dispatchCapture(ProviderIdempotencyReference reference) {
        Objects.requireNonNull(reference, "the capture's idempotency reference must not be null");
        requireLegal(PaymentAttemptStatus.CAPTURE_DISPATCHED);
        return new PaymentAttempt(
                id, intentId, authorizationReference, reference,
                authorizationProviderReference, authorizedAmount, captureProviderReference,
                capturedAmount, failureReason, PaymentAttemptStatus.CAPTURE_DISPATCHED,
                createdAt);
    }

    /** The capture's outcome is unknown — the second {@code INV-LIFE-03} state. */
    public PaymentAttempt captureOutcomeUnknown() {
        requireLegal(PaymentAttemptStatus.CAPTURE_UNKNOWN);
        return new PaymentAttempt(
                id, intentId, authorizationReference, captureReference,
                authorizationProviderReference, authorizedAmount, captureProviderReference,
                capturedAmount, failureReason, PaymentAttemptStatus.CAPTURE_UNKNOWN, createdAt);
    }

    /**
     * The provider captured. The constructor judges {@code INV-PAY-05}'s bound — captured ≤
     * authorized, same currency — whichever path built the state.
     */
    public PaymentAttempt capture(ProviderReference providerReference, Money amount) {
        requireLegal(PaymentAttemptStatus.CAPTURED);
        return new PaymentAttempt(
                id, intentId, authorizationReference, captureReference,
                authorizationProviderReference, authorizedAmount, providerReference, amount,
                failureReason, PaymentAttemptStatus.CAPTURED, createdAt);
    }

    /** The try failed, at either stage, with the mapped reason — this row's fact. */
    public PaymentAttempt fail(PaymentFailureReason reason) {
        Objects.requireNonNull(reason, "a FAILED attempt requires its mapped reason");
        requireLegal(PaymentAttemptStatus.FAILED);
        return new PaymentAttempt(
                id, intentId, authorizationReference, captureReference,
                authorizationProviderReference, authorizedAmount, captureProviderReference,
                capturedAmount, reason, PaymentAttemptStatus.FAILED, createdAt);
    }

    /** The machine's one check ({@code INV-LIFE-02}), whichever door the transition arrives by. */
    private void requireLegal(PaymentAttemptStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalPaymentAttemptTransitionException(id, status, target);
        }
    }

    public PaymentAttemptId id() {
        return id;
    }

    public PaymentIntentId intentId() {
        return intentId;
    }

    /** Minted at birth; what the provider is queried by ({@code INV-PAY-04}, §7 resolvers). */
    public ProviderIdempotencyReference authorizationReference() {
        return authorizationReference;
    }

    /** Minted by {@link #dispatchCapture}; {@code null} until then. */
    public ProviderIdempotencyReference captureReference() {
        return captureReference;
    }

    /** The issuer's reference; {@code null} until {@code AUTHORIZED}. */
    public ProviderReference authorizationProviderReference() {
        return authorizationProviderReference;
    }

    /** The issuer's promised amount; {@code null} until {@code AUTHORIZED}. */
    public Money authorizedAmount() {
        return authorizedAmount;
    }

    /** The capture's provider reference; {@code null} unless {@code CAPTURED}. */
    public ProviderReference captureProviderReference() {
        return captureProviderReference;
    }

    /** What refunds are bounded by ({@code INV-PAY-05}); {@code null} unless {@code CAPTURED}. */
    public Money capturedAmount() {
        return capturedAmount;
    }

    /** The mapped, enumerated reason ({@code INV-PAY-03}); {@code null} unless {@code FAILED}. */
    public PaymentFailureReason failureReason() {
        return failureReason;
    }

    public PaymentAttemptStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
