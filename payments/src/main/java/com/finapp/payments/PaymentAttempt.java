package com.finapp.payments;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.security.Sensitive;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

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
    private final RailId rail;
    private final InteractionModel interactionModel;
    private final ProviderIdempotencyReference authorizationReference;
    private final ProviderIdempotencyReference captureReference;
    private final ProviderReference authorizationProviderReference;
    private final Money authorizedAmount;
    private final ProviderReference captureProviderReference;
    private final Money capturedAmount;
    private final ProviderIdempotencyReference voidReference;
    private final ProviderReference voidProviderReference;
    private final PaymentFailureReason failureReason;
    private final PaymentAttemptStatus status;
    private final Instant createdAt;

    // ------------------------------------------------------------- the push model's facts
    // (`P7-TSK-009`, ADR-0062 §5) - in their own columns, exactly as the P7-TSK-002
    // constructor promised: OUR end-to-end reference minted at birth (INV-PAY-04), the
    // payer's authorization handle (a capability URL - Sensitive by construction), the
    // scheme's transaction reference and settlement cycle (Phase 8's keys), and the
    // initiation permit - the last outbound contact, forward-only for every writer.
    private final EndToEndReference endToEndReference;
    private final Sensitive<String> authorizationHandle;
    private final ProviderReference schemeReference;
    private final String settlementCycle;
    private final Instant lastDispatchedAt;

    private PaymentAttempt(
            PaymentAttemptId id,
            PaymentIntentId intentId,
            RailId rail,
            InteractionModel interactionModel,
            ProviderIdempotencyReference authorizationReference,
            ProviderIdempotencyReference captureReference,
            ProviderReference authorizationProviderReference,
            Money authorizedAmount,
            ProviderReference captureProviderReference,
            Money capturedAmount,
            ProviderIdempotencyReference voidReference,
            ProviderReference voidProviderReference,
            PaymentFailureReason failureReason,
            PaymentAttemptStatus status,
            Instant createdAt,
            EndToEndReference endToEndReference,
            Sensitive<String> authorizationHandle,
            ProviderReference schemeReference,
            String settlementCycle,
            Instant lastDispatchedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.intentId = Objects.requireNonNull(intentId, "intentId must not be null");
        this.rail = Objects.requireNonNull(
                rail,
                "rail must not be null - which rail the money travels on is a birth fact, and"
                        + " every capability decision keys on it (ADR-0059, P7-TSK-001)");
        this.interactionModel = Objects.requireNonNull(
                interactionModel,
                "interactionModel must not be null - which machine this attempt lives in is a"
                        + " birth fact (ADR-0059, P7-TSK-002)");
        this.authorizationReference = authorizationReference;
        this.captureReference = captureReference;
        this.authorizationProviderReference = authorizationProviderReference;
        this.authorizedAmount = authorizedAmount;
        this.captureProviderReference = captureProviderReference;
        this.capturedAmount = capturedAmount;
        this.voidReference = voidReference;
        this.voidProviderReference = voidProviderReference;
        this.failureReason = failureReason;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
        this.endToEndReference = endToEndReference;
        this.authorizationHandle = authorizationHandle;
        this.schemeReference = schemeReference;
        this.settlementCycle = settlementCycle;
        this.lastDispatchedAt = lastDispatchedAt;

        // WHICH MACHINE, FIRST (P7-TSK-002, ADR-0059 section 2): the status must be the
        // model's own; the authorization dispatch reference exists exactly on the two-step
        // model (ADR-0046 - committed before the provider is asked, so no two-step attempt
        // exists without one; a push execution's and a book movement's own references arrive
        // with their rails' tasks, in their own columns); and no two-step fact rides another
        // model's row.
        if (!interactionModel.statuses().contains(status)) {
            throw new IllegalArgumentException(
                    "a " + interactionModel + " attempt cannot be " + status
                            + " - the status is another machine's (ADR-0059, INV-LIFE-02)");
        }
        if ((interactionModel == InteractionModel.TWO_STEP)
                != (authorizationReference != null)) {
            throw new IllegalArgumentException(
                    "the authorization dispatch reference exists exactly on the two-step model"
                            + " (ADR-0046, ADR-0059): a two-step attempt never lacks one, and"
                            + " no other model carries one");
        }
        if (interactionModel != InteractionModel.TWO_STEP
                && (captureReference != null
                        || authorizationProviderReference != null
                        || authorizedAmount != null
                        || captureProviderReference != null
                        || capturedAmount != null
                        || voidReference != null
                        || voidProviderReference != null)) {
            throw new IllegalArgumentException(
                    "a " + interactionModel + " attempt carries no two-step fact: its own"
                            + " facts arrive with its rail's task (P7-TSK-002)");
        }

        // The push model's facts, confined and coherent both ways (P7-TSK-009, ADR-0062
        // section 5). OUR reference and the initiation permit exist exactly on push rows -
        // birth facts, the withdrawal's discipline; the scheme's pair arrives exactly with
        // EXECUTED (a BOOK row is born EXECUTED with no scheme, which is why the pair rule
        // is model-scoped); the handle rides only a push row, whatever its state - the
        // initiation it opened is history the terminals keep.
        if ((interactionModel == InteractionModel.PUSH) != (endToEndReference != null)) {
            throw new IllegalArgumentException(
                    "the end-to-end reference exists exactly on the push model (INV-PAY-04):"
                            + " a push attempt never lacks one, and no other model carries"
                            + " one");
        }
        if ((interactionModel == InteractionModel.PUSH) != (lastDispatchedAt != null)) {
            throw new IllegalArgumentException(
                    "the initiation permit exists exactly on the push model - the last"
                            + " outbound contact is a push birth fact (ADR-0062 section 3)");
        }
        if (lastDispatchedAt != null && lastDispatchedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException(
                    "an initiation permit never precedes the row's birth");
        }
        if (interactionModel != InteractionModel.PUSH
                && (authorizationHandle != null
                        || schemeReference != null
                        || settlementCycle != null)) {
            throw new IllegalArgumentException(
                    "a " + interactionModel + " attempt carries no push fact: the handle and"
                            + " the scheme's pair are the push model's own (P7-TSK-009)");
        }
        if (interactionModel == InteractionModel.PUSH
                && (schemeReference != null) != (status == PaymentAttemptStatus.EXECUTED)) {
            throw new IllegalArgumentException(
                    "a push attempt carries the scheme's transaction reference exactly when"
                            + " EXECUTED - status " + status + " is incoherent with what this"
                            + " row holds");
        }
        if (settlementCycle != null && schemeReference == null) {
            throw new IllegalArgumentException(
                    "a settlement cycle rides only an executed push attempt, beside the"
                            + " scheme's reference");
        }

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
        boolean voidDispatched = voidReference != null;
        switch (status) {
            case AUTH_DISPATCHED, AUTH_UNKNOWN -> {
                if (authorized || captureDispatched || voidDispatched) {
                    throw new IllegalArgumentException(
                            "an attempt in " + status + " carries nothing beyond its birth"
                                    + " facts - an authorization, capture or void fact here is"
                                    + " incoherent");
                }
            }
            case AUTHORIZED -> {
                if (!authorized) {
                    throw new IllegalArgumentException(
                            "an AUTHORIZED attempt must carry the issuer's promise - the"
                                    + " provider reference and authorized amount");
                }
                if (captureDispatched || voidDispatched) {
                    throw new IllegalArgumentException(
                            "an AUTHORIZED attempt has no capture or void reference yet -"
                                    + " dispatchCapture and dispatchVoid are the acts that"
                                    + " mint them");
                }
            }
            case CAPTURE_DISPATCHED, CAPTURE_UNKNOWN, CAPTURED -> {
                if (!authorized || !captureDispatched) {
                    throw new IllegalArgumentException(
                            "an attempt in " + status + " must carry the authorization pair and"
                                    + " the capture's idempotency reference");
                }
                if (voidDispatched) {
                    throw new IllegalArgumentException(
                            "an attempt in " + status + " carries no void reference - the"
                                    + " void, once dispatched, is the row's state"
                                    + " (P7-TSK-004)");
                }
            }
            case VOID_DISPATCHED, VOID_UNKNOWN, VOIDED -> {
                // The void releases a standing authorization (P7-TSK-004): the promise is
                // required; the capture reference MAY be present (the declined-capture
                // redirect's history) but the captured pair never is - nothing was taken.
                if (!authorized || !voidDispatched) {
                    throw new IllegalArgumentException(
                            "an attempt in " + status + " must carry the authorization pair"
                                    + " and the void's idempotency reference (INV-PAY-04)");
                }
                if (capturedAmount != null) {
                    throw new IllegalArgumentException(
                            "a voided attempt captured nothing: a captured amount in "
                                    + status + " is incoherent (INV-REV-03's whole premise)");
                }
            }
            case FAILED -> {
                // Three shapes now (P7-TSK-004): failed at authorization (bare), failed at
                // capture (promise + capture reference, no void), or failed at the void
                // (promise + void reference; the capture reference may ride along from the
                // declined-capture redirect). A FAILED row holding the promise with NEITHER
                // a capture nor a void dispatch was written by no path this machine permits.
                if (voidDispatched) {
                    if (!authorized) {
                        throw new IllegalArgumentException(
                                "a FAILED void carries the authorization pair it tried to"
                                        + " release - a void reference without the promise is"
                                        + " incoherent");
                    }
                } else if (authorized != captureDispatched) {
                    throw new IllegalArgumentException(
                            "a FAILED attempt failed at authorization (no authorization facts)"
                                    + " or at capture (authorization pair and capture reference"
                                    + " both present) or at the void (void reference present) -"
                                    + " this row is none of those shapes");
                }
            }
        }

        // The void's provider acknowledgement exists exactly when VOIDED, both directions.
        if ((voidProviderReference != null) != (status == PaymentAttemptStatus.VOIDED)) {
            throw new IllegalArgumentException(
                    "an attempt carries the void's provider reference exactly when VOIDED -"
                            + " status " + status + " is incoherent with what this row holds");
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
            RailId rail,
            ProviderIdempotencyReference authorizationReference) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        return new PaymentAttempt(
                PaymentAttemptId.next(ids),
                intentId,
                rail,
                // The two-step birth door: this command IS the card-shaped dispatch. The
                // other models' births arrive with their rails' tasks (P7-TSK-002).
                InteractionModel.TWO_STEP,
                authorizationReference,
                null, null, null, null, null, null, null, null,
                PaymentAttemptStatus.AUTH_DISPATCHED,
                Instant.now(clock),
                null, null, null, null, null);
    }

    /**
     * A push pay-in, born {@code AWAITING_PAYER} with OUR end-to-end reference already
     * minted and its initiation permit stamped (`P7-TSK-009`, ADR-0062 §5) — the dispatch
     * fact commits before the scheme is asked to open the initiation (ADR-0046,
     * {@code INV-PAY-04}). Instants truncate to the column's microsecond resolution AT MINT
     * (the `P7-TSK-004` clock-precision class, prevented).
     */
    public static PaymentAttempt createPush(
            IdGenerator ids, Clock clock, PaymentIntentId intentId, RailId rail) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Instant born = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        return new PaymentAttempt(
                PaymentAttemptId.next(ids),
                intentId,
                rail,
                InteractionModel.PUSH,
                null, null, null, null, null, null, null, null, null,
                PaymentAttemptStatus.AWAITING_PAYER,
                born,
                new EndToEndReference(ids.next().toString().replace("-", "")),
                null, null, null,
                born);
    }

    /**
     * A book attempt, born {@code EXECUTED} (`P7-TSK-011`, ADR-0059 §6): the book rail has
     * no wire, so there is nothing to dispatch, nothing to wait for and nothing to
     * reference — the attempt exists exactly when its posting commits, in the same
     * transaction, or not at all. No idempotency reference is minted because no external
     * dedupe exists to present it to: the posting key is the once-arbiter.
     */
    public static PaymentAttempt createBook(
            IdGenerator ids, Clock clock, PaymentIntentId intentId, RailId rail) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        return new PaymentAttempt(
                PaymentAttemptId.next(ids),
                intentId,
                rail,
                InteractionModel.BOOK,
                null, null, null, null, null, null, null, null, null,
                PaymentAttemptStatus.EXECUTED,
                Instant.now(clock),
                null, null, null, null,
                null);
    }

    /**
     * A row read back from storage, through the same constructor — so a corrupt row is refused
     * on read-back, ahead of the schema's own {@code CHECK}s.
     */
    public static PaymentAttempt rehydrate(
            PaymentAttemptId id,
            PaymentIntentId intentId,
            RailId rail,
            InteractionModel interactionModel,
            ProviderIdempotencyReference authorizationReference,
            ProviderIdempotencyReference captureReference,
            ProviderReference authorizationProviderReference,
            Money authorizedAmount,
            ProviderReference captureProviderReference,
            Money capturedAmount,
            ProviderIdempotencyReference voidReference,
            ProviderReference voidProviderReference,
            PaymentFailureReason failureReason,
            PaymentAttemptStatus status,
            Instant createdAt,
            EndToEndReference endToEndReference,
            Sensitive<String> authorizationHandle,
            ProviderReference schemeReference,
            String settlementCycle,
            Instant lastDispatchedAt) {
        return new PaymentAttempt(
                id, intentId, rail, interactionModel, authorizationReference, captureReference,
                authorizationProviderReference, authorizedAmount, captureProviderReference,
                capturedAmount, voidReference, voidProviderReference, failureReason,
                status, createdAt, endToEndReference, authorizationHandle, schemeReference,
                settlementCycle, lastDispatchedAt);
    }

    /** The issuer's promise arrived: reference and amount together, one fact. */
    public PaymentAttempt authorize(ProviderReference providerReference, Money amount) {
        requireLegal(PaymentAttemptStatus.AUTHORIZED);
        return new PaymentAttempt(
                id, intentId, rail, interactionModel, authorizationReference, captureReference,
                providerReference, amount, captureProviderReference, capturedAmount,
                voidReference, voidProviderReference,
                failureReason, PaymentAttemptStatus.AUTHORIZED, createdAt,
                endToEndReference, authorizationHandle, schemeReference, settlementCycle,
                lastDispatchedAt);
    }

    /** The authorization's outcome is unknown — commit the honest state ({@code INV-LIFE-03}). */
    public PaymentAttempt authorizationOutcomeUnknown() {
        requireLegal(PaymentAttemptStatus.AUTH_UNKNOWN);
        return new PaymentAttempt(
                id, intentId, rail, interactionModel, authorizationReference, captureReference,
                authorizationProviderReference, authorizedAmount, captureProviderReference,
                capturedAmount, voidReference, voidProviderReference, failureReason,
                PaymentAttemptStatus.AUTH_UNKNOWN, createdAt,
                endToEndReference, authorizationHandle, schemeReference, settlementCycle,
                lastDispatchedAt);
    }

    /** The capture dispatch, its idempotency reference minted by this act ({@code INV-PAY-04}). */
    public PaymentAttempt dispatchCapture(ProviderIdempotencyReference reference) {
        Objects.requireNonNull(reference, "the capture's idempotency reference must not be null");
        requireLegal(PaymentAttemptStatus.CAPTURE_DISPATCHED);
        return new PaymentAttempt(
                id, intentId, rail, interactionModel, authorizationReference, reference,
                authorizationProviderReference, authorizedAmount, captureProviderReference,
                capturedAmount, voidReference, voidProviderReference, failureReason,
                PaymentAttemptStatus.CAPTURE_DISPATCHED, createdAt,
                endToEndReference, authorizationHandle, schemeReference, settlementCycle,
                lastDispatchedAt);
    }

    /** The capture's outcome is unknown — the second {@code INV-LIFE-03} state. */
    public PaymentAttempt captureOutcomeUnknown() {
        requireLegal(PaymentAttemptStatus.CAPTURE_UNKNOWN);
        return new PaymentAttempt(
                id, intentId, rail, interactionModel, authorizationReference, captureReference,
                authorizationProviderReference, authorizedAmount, captureProviderReference,
                capturedAmount, voidReference, voidProviderReference, failureReason,
                PaymentAttemptStatus.CAPTURE_UNKNOWN, createdAt,
                endToEndReference, authorizationHandle, schemeReference, settlementCycle,
                lastDispatchedAt);
    }

    /**
     * The provider captured. The constructor judges {@code INV-PAY-05}'s bound — captured ≤
     * authorized, same currency — whichever path built the state.
     */
    public PaymentAttempt capture(ProviderReference providerReference, Money amount) {
        requireLegal(PaymentAttemptStatus.CAPTURED);
        return new PaymentAttempt(
                id, intentId, rail, interactionModel, authorizationReference, captureReference,
                authorizationProviderReference, authorizedAmount, providerReference, amount,
                voidReference, voidProviderReference,
                failureReason, PaymentAttemptStatus.CAPTURED, createdAt,
                endToEndReference, authorizationHandle, schemeReference, settlementCycle,
                lastDispatchedAt);
    }

    /** The try failed, at either stage, with the mapped reason — this row's fact. */
    public PaymentAttempt fail(PaymentFailureReason reason) {
        Objects.requireNonNull(reason, "a FAILED attempt requires its mapped reason");
        requireLegal(PaymentAttemptStatus.FAILED);
        return new PaymentAttempt(
                id, intentId, rail, interactionModel, authorizationReference, captureReference,
                authorizationProviderReference, authorizedAmount, captureProviderReference,
                capturedAmount, voidReference, voidProviderReference, reason,
                PaymentAttemptStatus.FAILED, createdAt,
                endToEndReference, authorizationHandle, schemeReference, settlementCycle,
                lastDispatchedAt);
    }

    /**
     * The void dispatch (`P7-TSK-004`): our reference minted by this act and stored with the
     * transition, before the provider is asked to release the authorization
     * ({@code INV-PAY-04}). Legal from {@code AUTHORIZED} (a cancellation, an operator) and
     * from the capture stages (the declined-capture redirect, capability-gated by the
     * caller - this door holds the machine, {@code PaymentOutcomes} holds the capability).
     */
    public PaymentAttempt dispatchVoid(ProviderIdempotencyReference reference) {
        Objects.requireNonNull(reference, "the void's idempotency reference must not be null");
        requireLegal(PaymentAttemptStatus.VOID_DISPATCHED);
        return new PaymentAttempt(
                id, intentId, rail, interactionModel, authorizationReference, captureReference,
                authorizationProviderReference, authorizedAmount, captureProviderReference,
                capturedAmount, reference, voidProviderReference, failureReason,
                PaymentAttemptStatus.VOID_DISPATCHED, createdAt,
                endToEndReference, authorizationHandle, schemeReference, settlementCycle,
                lastDispatchedAt);
    }

    /** The provider released the authorization: the void's acknowledgement, one fact. */
    public PaymentAttempt voided(ProviderReference providerReference) {
        Objects.requireNonNull(providerReference, "the void's provider reference must not be null");
        requireLegal(PaymentAttemptStatus.VOIDED);
        return new PaymentAttempt(
                id, intentId, rail, interactionModel, authorizationReference, captureReference,
                authorizationProviderReference, authorizedAmount, captureProviderReference,
                capturedAmount, voidReference, providerReference, failureReason,
                PaymentAttemptStatus.VOIDED, createdAt,
                endToEndReference, authorizationHandle, schemeReference, settlementCycle,
                lastDispatchedAt);
    }

    /** The void's outcome is unknown — the third {@code INV-LIFE-03} state on this machine. */
    public PaymentAttempt voidOutcomeUnknown() {
        requireLegal(PaymentAttemptStatus.VOID_UNKNOWN);
        return new PaymentAttempt(
                id, intentId, rail, interactionModel, authorizationReference, captureReference,
                authorizationProviderReference, authorizedAmount, captureProviderReference,
                capturedAmount, voidReference, voidProviderReference, failureReason,
                PaymentAttemptStatus.VOID_UNKNOWN, createdAt,
                endToEndReference, authorizationHandle, schemeReference, settlementCycle,
                lastDispatchedAt);
    }

    // ------------------------------------------------------------- the push doors (P7-TSK-009)

    /**
     * The scheme opened the initiation: the payer's authorization handle, stored once
     * (`P7-TSK-009`). <strong>Not a transition</strong> — the row stays {@code AWAITING_PAYER};
     * the handle is a payload the freeze rules then hold for every writer. Refused on any
     * other state, and refused twice: the scheme deduplicates on our reference, so a second
     * opening carries the same handle and the store's conditional converges instead.
     */
    public PaymentAttempt openInitiation(Sensitive<String> handle) {
        Objects.requireNonNull(handle, "the authorization handle must not be null");
        if (status != PaymentAttemptStatus.AWAITING_PAYER) {
            throw new IllegalPaymentAttemptTransitionException(
                    id, status, PaymentAttemptStatus.AWAITING_PAYER);
        }
        if (authorizationHandle != null) {
            throw new IllegalArgumentException(
                    "an initiation opens once: the handle is already stored, and a differing"
                            + " second one would mean the scheme broke its own dedupe");
        }
        return new PaymentAttempt(
                id, intentId, rail, interactionModel, authorizationReference, captureReference,
                authorizationProviderReference, authorizedAmount, captureProviderReference,
                capturedAmount, voidReference, voidProviderReference, failureReason,
                status, createdAt, endToEndReference, handle, schemeReference, settlementCycle,
                lastDispatchedAt);
    }

    /**
     * The payer's PSP executed and the scheme confirmed: the transaction reference and the
     * settlement cycle arrive with the transition that needs them (Phase 8's keys) — the
     * inbound edge, {@code AWAITING_PAYER → EXECUTED} (`P7-TSK-009`, ADR-0062 §5).
     */
    public PaymentAttempt execute(ProviderReference scheme, Optional<String> cycle) {
        Objects.requireNonNull(scheme, "the scheme's transaction reference must not be null");
        Objects.requireNonNull(cycle, "cycle must not be null");
        requireLegal(PaymentAttemptStatus.EXECUTED);
        return new PaymentAttempt(
                id, intentId, rail, interactionModel, authorizationReference, captureReference,
                authorizationProviderReference, authorizedAmount, captureProviderReference,
                capturedAmount, voidReference, voidProviderReference, failureReason,
                PaymentAttemptStatus.EXECUTED, createdAt, endToEndReference,
                authorizationHandle, scheme, cycle.orElse(null), lastDispatchedAt);
    }

    /**
     * The sweep's outbound contact, stamped forward (`P7-TSK-009`, ADR-0062 §3 adapted): the
     * permit paces re-initiations and inquiries — its conditional renewal is the wire-noise
     * arbiter among instances, never a money guard, because an initiation moves nothing and
     * re-initiating converges on the scheme's dedupe. Truncated at mint (the `P7-TSK-004`
     * clock-precision class). Only a waiting row is ever contacted again.
     */
    public PaymentAttempt withInitiationPermit(Instant at) {
        Objects.requireNonNull(at, "at must not be null");
        if (status != PaymentAttemptStatus.AWAITING_PAYER) {
            throw new IllegalPaymentAttemptTransitionException(
                    id, status, PaymentAttemptStatus.AWAITING_PAYER);
        }
        Instant truncated = at.truncatedTo(ChronoUnit.MICROS);
        if (truncated.isBefore(lastDispatchedAt)) {
            throw new IllegalArgumentException(
                    "an initiation permit only moves forward (ADR-0062 section 3)");
        }
        return new PaymentAttempt(
                id, intentId, rail, interactionModel, authorizationReference, captureReference,
                authorizationProviderReference, authorizedAmount, captureProviderReference,
                capturedAmount, voidReference, voidProviderReference, failureReason,
                status, createdAt, endToEndReference, authorizationHandle, schemeReference,
                settlementCycle, truncated);
    }

    /** The machine's one check ({@code INV-LIFE-02}), whichever door the transition arrives by. */
    private void requireLegal(PaymentAttemptStatus target) {
        if (!interactionModel.permits(status, target)) {
            throw new IllegalPaymentAttemptTransitionException(id, status, target);
        }
    }

    public PaymentAttemptId id() {
        return id;
    }

    public PaymentIntentId intentId() {
        return intentId;
    }

    /**
     * The rail this attempt was dispatched on — a birth fact, frozen for every writer
     * (`P7-TSK-001`, ADR-0059). The name keys into the build's declared
     * {@link RailCapabilities} through {@link PaymentRails}, so an outcome resolver on any
     * instance reads the stored decision, never its own wiring.
     */
    public RailId rail() {
        return rail;
    }

    /**
     * Which machine this attempt lives in — a birth fact beside the rail (`P7-TSK-002`,
     * ADR-0059 §2). The legal edges are the model's own ({@link InteractionModel#edges}), and
     * every layer keys on it: this aggregate's guard, the store's writer-side check, and
     * `V012`'s generated trigger.
     */
    public InteractionModel interactionModel() {
        return interactionModel;
    }

    /** Minted at birth; what the provider is queried by ({@code INV-PAY-04}, §7 resolvers). */
    public ProviderIdempotencyReference authorizationReference() {
        return authorizationReference;
    }

    /** Minted by {@link #dispatchCapture}; {@code null} until then. */
    /** Our void reference (`P7-TSK-004`, {@code INV-PAY-04}) — stored beside the
     * authorization's for reconciliation, minted by {@link #dispatchVoid}. */
    public ProviderIdempotencyReference voidReference() {
        return voidReference;
    }

    /** The provider's acknowledgement of the release — exactly when {@code VOIDED}. */
    public ProviderReference voidProviderReference() {
        return voidProviderReference;
    }

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

    /** OUR reference on the push model ({@code INV-PAY-04}): minted at birth, what the
     * scheme deduplicates on, what the callback and the inquiry attribute by, what Phase 8
     * joins on. {@code null} on every other model. */
    public EndToEndReference endToEndReference() {
        return endToEndReference;
    }

    /** The payer's authorization handle — a capability URL, {@link Sensitive} end to end;
     * empty until the scheme opens the initiation. Its {@code expose()} sites are the
     * store's bind and the owner-facing render, both registered (`P7-TSK-009`). */
    public Optional<Sensitive<String>> authorizationHandle() {
        return Optional.ofNullable(authorizationHandle);
    }

    /** The scheme's transaction reference — exactly when a push row is {@code EXECUTED}. */
    public Optional<ProviderReference> schemeReference() {
        return Optional.ofNullable(schemeReference);
    }

    /** The scheme's settlement-cycle identifier, riding only an executed push row. */
    public Optional<String> settlementCycle() {
        return Optional.ofNullable(settlementCycle);
    }

    /** The initiation permit: the last outbound contact, forward-only (`P7-TSK-009`). */
    public Instant lastDispatchedAt() {
        return lastDispatchedAt;
    }
}
