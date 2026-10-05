package com.finapp.payments;

import com.finapp.ledger.HoldService;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The refund command: hold, then post (`P5-TSK-015`, ADR-0048 §4) — the same
 * dispatch-before-call choreography as every provider operation, with `P3-TSK-015`'s owed
 * composition held at its centre: <strong>the dispatch transaction reserves what the refund
 * will take with a Phase 3 hold placed inside the account-row lock</strong>, so the money a
 * provider may be about to return is unspendable for the whole flight of its indecision
 * ({@code INV-BAL-04} doing refund duty).
 *
 * <h2>What is reserved is asked, not assumed (`P6-TSK-015`, ADR-0054)</h2>
 *
 * <p>Phase 5 held the gross, which is what a wallet refund takes. A merchant-bound refund takes
 * the gross out of the payable and, under a {@code RETURNED} policy, puts the fee share back in
 * the same entry — so a bound judging the gross refused a full refund that would have landed
 * the payable at exactly zero. The hold is now sized by
 * {@link RefundComposition#reserve}, asked through {@link PaymentOutcomes} of the composition
 * that will write the lines, so this module still never learns what a fee is.
 *
 * <h2>The bound, at two ranks by design</h2>
 *
 * <p>The domain judges {@code sum(non-FAILED) + this ≤ captured} <strong>under the command's
 * {@code FOR UPDATE} on the attempt row</strong> — the lock-then-look contract
 * {@link Refund#create}'s javadoc has carried since `P5-TSK-007`, because two dispatchers
 * summing without the lock is the `P2-TSK-015` write-skew shape. `V004`'s {@code BEFORE
 * INSERT} trigger holds the identical bound under advisory-lock namespace 3 for every writer
 * that never ran this code ({@code INV-PAY-05}, {@code INV-REV-02}).
 *
 * <h2>Lock order: attempt → account, always</h2>
 *
 * <p>The attempt lock is taken before {@link HoldService#place}'s account lock — the same
 * attempt→account order the capture outcome uses — so the two money paths cannot deadlock
 * each other (the `P5-TSK-013` 40P01 lesson, applied in advance rather than found again).
 *
 * <h2>The three honest outcomes</h2>
 *
 * <p>Completion <strong>releases-and-posts atomically</strong> (DR wallet / CR clearing, key
 * {@code payment-refund:<refundId>} — the capture's exact inverse pair); failure releases
 * with nothing posted; ambiguity commits {@code UNKNOWN} <strong>with the hold
 * standing</strong> — {@code INV-LIFE-03} with money visibly parked on it. All through
 * {@link PaymentOutcomes#applyRefund}, the one code path the webhook resolver consumes too
 * (`P5-TSK-016`) — and each terminal transition publishes its fact from inside the
 * conditional, so duplicates emit nothing.
 *
 * <h2>The response of record (`P5-TSK-016`)</h2>
 *
 * <p>The first two-transaction keyed command: Tx1 commits the dispatch beside the claim held
 * {@code IN_PROGRESS} ({@code IdempotentExecutor.begin}), Tx2 completes the claim with the
 * judged {@code refundId|status} — so a replay renders what this key was answered,
 * byte-for-byte (platform {@code V003}'s freeze), never a re-read. The crash between the two
 * is the lease's case: the retry takes the claim over and {@code dispatchOrConverge} finds
 * the committed work by `V008`'s dispatch key — no second hold, no second row, the wire
 * re-driven with the stored reference ({@code INV-PAY-04}).
 *
 * <h2>The operator commands; the platform applies</h2>
 *
 * <p>The dispatch is the operator's reasoned act ({@code PAYMENT_REFUND} checked at the
 * boundary, the reason required — {@code INV-AUD-03}); the outcome is the platform's, through
 * this class's enumerated {@code enterSystem()} site — the `P5-TSK-009` reasoning, refund
 * form.
 */
@RequiredArgsConstructor
public final class PaymentRefund {

    static final String IDEMPOTENCY_SCOPE = "payment.refund";

    @NonNull private final TransactionRunner transactions;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final PaymentIntentStore<Connection> intents;
    @NonNull private final PaymentAttemptStore<Connection> attempts;
    @NonNull private final RefundStore<Connection> refunds;
    @NonNull private final ProviderEvidenceStore<Connection> evidence;
    @NonNull private final HoldService holds;
    @NonNull private final PaymentProvider provider;
    @NonNull private final PaymentOutcomes outcomes;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** The build's declared rails (`P7-TSK-001`, ADR-0059): the refund mode is the rail's call. */
    @NonNull private final PaymentRails rails;

    /**
     * The push rail, when this deployment configures one (`P7-TSK-010`): the
     * {@code RETURN_PAYMENT} mode's wire — a return is a new outbound push citing the
     * original's scheme reference, never a provider reversal. Empty refuses the return
     * branch inside Tx1 with nothing written ({@link PushRailUnavailableException} — the
     * confirmation's pattern). Last, so no existing positional argument moved.
     */
    @NonNull private final Optional<PushRail> pushRail;

    /**
     * What the operator learns — the status honestly, {@code UNKNOWN} included.
     *
     * @param replayed the recorded judgement was rendered; no wire call happened
     * @param acting this call's own conditional made the committed status (`P5-TSK-017`) —
     *     false for a replay and for a takeover that found the crashed flight already
     *     resolved by another resolver, so the door counts throughput once per judgement
     */
    public record RefundResult(
            RefundId refund, RefundStatus status, boolean replayed, boolean acting) {}

    /**
     * Tx1's yield, carried across the connectionless gap.
     *
     * @param send whether a send of our reference was permitted — false when a takeover found the
     *     crashed flight's refund already resolved, and then nothing is sent and the claim answers
     *     the row's truth
     * @param firstSend whether this is the dispatch's own birth send — the only send whose
     *     refused connection can prove that nothing was ever transmitted
     * @param permit the send permit this flight committed, as stored ({@code V009}); a refused
     *     connection fails the refund only while the locked row's permit is still this one
     */
    private record Dispatch(
            Refund refund,
            PaymentIntentId intent,
            LedgerAccountId wallet,
            ProviderReference capture,
            boolean send,
            boolean firstSend,
            Instant permit,
            // The rail-aware half (P7-TSK-010): which wire this refund rides, and - for
            // the return - the ORIGINAL's scheme reference it cites as its destination.
            InteractionModel model,
            Optional<ProviderReference> originalScheme,
            // The book rail's flag (P7-TSK-011): TRUE exactly when THIS flight's Tx1
            // completed the whole refund - no wire exists, so the outcome commits with
            // the dispatch and Tx2 only records the response - and the acting bit must
            // say so, because the completion really was this call's own.
            boolean bookCompleted) {}

    /**
     * Dispatches (or replays) the refund and applies the provider's answer.
     *
     * @throws UnknownPaymentException the intent names nothing — the caller's one 404
     * @throws PaymentNotRefundableException no refundable attempt — {@code CAPTURED} on the
     *     card, {@code EXECUTED} on a push rail (`P7-TSK-010`) — the caller's 409
     * @throws RefundExceedsCaptureException the bound against the mode's own base — the
     *     caller's 422
     * @throws PushRailUnavailableException a return on an unconfigured deployment — nothing
     *     written, the caller's 503
     * @throws com.finapp.ledger.HoldExceedsAvailableBalanceException the account the refund
     *     debits cannot fund what it will take, now ({@code INV-BAL-04}) — a customer who has
     *     spent the money, or a merchant's payable short of the refund's net (ADR-0054); the
     *     caller's 409, nothing written
     * @throws com.finapp.platform.idempotency.IdempotencyConflictException the key was used
     *     for a materially different request ({@code INV-IDEM-03})
     * @throws com.finapp.platform.idempotency.IdempotencyInProgressException another flight
     *     holds this key and its lease is running — deterministic, bounded, and true
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect.
    public RefundResult refund(
            PaymentIntentId intentId, Money amount, String reason, String idempotencyKey) {
        Objects.requireNonNull(intentId, "intentId must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        Actor operator = SecurityContext.require();
        Correlation correlation = PaymentCreation.resolvedCorrelation();

        // Tx1: the claim and the dispatch - the hold, the row, the audit and the
        // RefundInitiated fact, one commit, before the provider can possibly have acted
        // (ADR-0046) - with the claim held IN_PROGRESS (the executor's two-transaction
        // shape, P5-TSK-016): the response of record is the JUDGED outcome, so it cannot be
        // written here, where no judgement exists yet.
        IdempotencyKey claimKey = new IdempotencyKey(IDEMPOTENCY_SCOPE, idempotencyKey);
        Dispatch[] holder = new Dispatch[1];
        IdempotentExecutor.BeginOutcome begun =
                transactions.inTransaction(
                        uow ->
                                executor.begin(
                                        uow,
                                        claimKey,
                                        RequestFingerprint.sha256(
                                                canonicalForm(
                                                        operator, intentId, amount, reason)),
                                        claimed -> {
                                            Dispatch dispatched =
                                                    dispatchOrConverge(
                                                            claimed, idempotencyKey, intentId,
                                                            amount, reason, operator,
                                                            correlation);
                                            holder[0] = dispatched;
                                            return dispatched
                                                    .refund()
                                                    .id()
                                                    .value()
                                                    .toString()
                                                    .getBytes(StandardCharsets.UTF_8);
                                        }));
        if (begun.replay().isPresent()) {
            // The response of record, byte for byte (platform V003's freeze) - never a
            // re-read: what this key was answered is what this key is answered.
            return parsedReplay(begun.replay().get());
        }
        Dispatch dispatch = holder[0];

        // The provider call - between the transactions, holding no database connection
        // (ADR-0046, P1-TSK-026) - and only under a committed permit: a takeover that found the
        // crashed flight's refund already resolved sends nothing and answers the row's truth.
        // An exception propagates: the dispatch stays committed with its hold standing and
        // visible - never a fabricated outcome. WHICH wire is the stored rail's declared
        // mode (P7-TSK-010): the card's provider refund against the capture, or the push
        // rail's RETURN citing the original's scheme reference.
        Optional<ProviderAnswer> answer =
                dispatch.send() ? Optional.of(sent(dispatch)) : Optional.empty();

        // Tx2: the outcome, applied as the platform - a provider's answer has no session
        // (the enumerated enterSystem() site, refund form).
        try (SecurityContext.Scope ignored = SecurityContext.enterSystem()) {
            return transactions.inTransaction(
                    uow -> {
                        // The source state, LOCKED: DISPATCHED on the fresh flight, and on a
                        // taken-over one possibly UNKNOWN - or already terminal, when another
                        // resolver finished it, in which case this call converges with the truth
                        // and applies nothing. Unlocked, a webhook committing between this read
                        // and the conditional left the claim answering the verdict this flight
                        // held rather than the row's state (the Phase 6 -> 7 transition).
                        RefundStore.LockedRefund locked =
                                refunds.lockForOutcome(uow, dispatch.refund().id())
                                        .orElseThrow();
                        Refund current = locked.refund();
                        PaymentOutcomes.RefundApplied applied =
                                answer.isPresent() && resolvable(current.status())
                                        ? outcomes.applyRefund(
                                                uow,
                                                dispatch.intent(),
                                                current,
                                                current.status(),
                                                judged(answer.get().verdict(), dispatch, locked),
                                                answer.get().providerReference(),
                                                dispatch.wallet(),
                                                correlation)
                                        : new PaymentOutcomes.RefundApplied(
                                                current.status(), false);
                        RefundStatus committed = applied.status();
                        // Whatever the mapping said, what arrived is retained (INV-HIST-02) -
                        // AFTER the outcome's row lock (the P5-TSK-013 lock-order rule).
                        answer.flatMap(ProviderAnswer::evidence)
                                .ifPresent(
                                        bytes ->
                                                evidence.append(
                                                        uow,
                                                        Optional.empty(),
                                                        Optional.of(dispatch.refund().id()),
                                                        EvidenceKind.RESPONSE,
                                                        bytes,
                                                        Instant.now(clock)));
                        // The response of record, committed WITH the outcome and frozen from
                        // here (platform V003, INV-LIFE-04). A false return is a takeover
                        // race's loser converging - its conditional writes lost the same way.
                        executor.complete(
                                uow,
                                claimKey,
                                true,
                                StoredResponse.of(
                                        renderedForm(dispatch.refund().id(), committed),
                                        "text/plain"));
                        return new RefundResult(
                                dispatch.refund().id(),
                                committed,
                                false,
                                // The book completion acted in Tx1 (P7-TSK-011): the row
                                // reads terminal here, so applied.acting() is false, and
                                // the flag carries whose act the completion really was.
                                applied.acting() || dispatch.bookCompleted());
                    });
        }
    }

    private static boolean resolvable(RefundStatus status) {
        return status == RefundStatus.DISPATCHED || status == RefundStatus.UNKNOWN;
    }

    /**
     * The one send, on the dispatched rail's own wire (`P7-TSK-010`): a two-step refund
     * executes against the capture; a push refund is a RETURN — a new transfer citing the
     * original's scheme reference. The return's answer folds onto the provider vocabulary
     * verbatim (ACCEPTED is the approval, REJECTED the decline, the connection-refusal and
     * ambiguity words identical), so {@code judged()}'s permit rule and
     * {@link PaymentOutcomes#applyRefund} run unchanged — one judgement, whatever the rail.
     */
    private ProviderAnswer sent(Dispatch dispatch) {
        if (dispatch.model() != InteractionModel.PUSH) {
            return provider.refund(
                    new PaymentProvider.RefundRequest(
                            dispatch.refund().providerIdempotencyReference(),
                            dispatch.capture(),
                            dispatch.refund().amount()));
        }
        PushAnswer returned =
                pushRail
                        .orElseThrow(PushRailUnavailableException::new)
                        .sendReturn(
                                new PushRail.ReturnPayment(
                                        new EndToEndReference(
                                                dispatch.refund()
                                                        .providerIdempotencyReference()
                                                        .value()),
                                        dispatch.originalScheme()
                                                .orElseThrow(
                                                        () ->
                                                                new IllegalStateException(
                                                                        "an EXECUTED push"
                                                                            + " attempt carries"
                                                                            + " its scheme"
                                                                            + " reference: the"
                                                                            + " coherence rule"
                                                                            + " guarantees it")),
                                        dispatch.refund().amount()));
        return switch (returned.verdict()) {
            case ACCEPTED ->
                    ProviderAnswer.approved(
                            returned.schemeReference().orElseThrow(),
                            returned.evidence().orElse(new byte[0]));
            case REJECTED ->
                    returned.evidence()
                            .map(ProviderAnswer::declined)
                            .orElseGet(() -> ProviderAnswer.declined(new byte[0]));
            case NOTHING_SENT -> ProviderAnswer.nothingSent();
            default ->
                    returned.evidence()
                            .map(ProviderAnswer::indeterminate)
                            .orElseGet(ProviderAnswer::indeterminate);
        };
    }

    /**
     * What a verdict proves about THIS refund, judged against the locked row (the Phase 6 → 7
     * transition; ADR-0057 §3's rule, brought to the flow that had the re-sending takeover first).
     *
     * <p>A refused connection proves only that THIS send transmitted nothing. That makes it a
     * failure only when this was the dispatch's own first send and no later send has been
     * permitted since — the locked row's permit is still the one this flight committed. After a
     * takeover's re-send, an earlier send may have reached the provider and been paid: concluding
     * {@code FAILED} would release the hold on money the customer already has, and free the bound
     * for a second refund of it. So it counts as what it is — we do not know — and the refund
     * stays {@code UNKNOWN} with its hold standing for a resolver that does ({@code INV-LIFE-03}).
     * Every other verdict is the provider's own answer about our reference, and stands.
     */
    private static ProviderAnswer.Verdict judged(
            ProviderAnswer.Verdict verdict, Dispatch dispatch, RefundStore.LockedRefund locked) {
        if (verdict == ProviderAnswer.Verdict.NOTHING_SENT
                && !(dispatch.firstSend() && locked.lastDispatchedAt().equals(dispatch.permit()))) {
            return ProviderAnswer.Verdict.INDETERMINATE;
        }
        return verdict;
    }

    /** The claim's stored judgement: {@code <refundId>|<status>}, parsed back on replay. */
    private static byte[] renderedForm(RefundId refund, RefundStatus status) {
        return (refund.value() + "|" + status.name()).getBytes(StandardCharsets.UTF_8);
    }

    private static RefundResult parsedReplay(StoredResponse stored) {
        String body =
                new String(
                        stored.bodyBytes()
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "a completed refund claim stores its"
                                                                + " judgement; an empty body is"
                                                                + " a wiring defect")),
                        StandardCharsets.UTF_8);
        int separator = body.indexOf('|');
        return new RefundResult(
                RefundId.of(UUID.fromString(body.substring(0, separator))),
                RefundStatus.valueOf(body.substring(separator + 1)),
                true,
                false);
    }

    /**
     * The {@code DispatchCommand} contract made real: a lease takeover re-runs this against
     * work the crashed flight already committed, so the first act is the convergence lookup
     * by the dispatch key ({@code V008}). Found with the same facts, the crashed flight's
     * dispatch stands — no second hold, no second row, no duplicate audit or fact:
     *
     * <ul>
     *   <li>still resolvable, a new send permit is committed ({@code V009}) and the wire
     *       re-drives with the reference already stored ({@code INV-PAY-04}'s whole point);
     *   <li>already resolved — a webhook, the sweep, or the crashed flight's own late answer
     *       finished it — nothing is sent, and the claim answers the row's truth.
     * </ul>
     *
     * <p>Found with DIFFERENT facts, the key is refused ({@link RefundKeyReusedException}):
     * {@code V008} binds a key to one refund for ever, for longer than the claim that first
     * carried it. *(This read "a key resurfacing after the claim's retention swept it —
     * different facts, or a finished refund — is a NEW command, and dispatches fresh" until the
     * Phase 6 → 7 transition: the unique index made that fresh dispatch a {@code 500}, and a
     * takeover finding a finished refund took the same path, so its claim never completed.)*
     */
    private Dispatch dispatchOrConverge(
            Connection uow,
            String dispatchKey,
            PaymentIntentId intentId,
            Money amount,
            String reason,
            Actor operator,
            Correlation correlation) {
        Optional<Refund> existing = refunds.findByDispatchKey(uow, dispatchKey);
        if (existing.isEmpty()) {
            return dispatch(uow, dispatchKey, intentId, amount, reason, operator, correlation);
        }
        Refund found = existing.get();
        PaymentAttempt attempt =
                attempts.findById(uow, found.attemptId())
                        .orElseThrow(UnknownPaymentException::new);
        if (!(attempt.intentId().equals(intentId)
                && found.amount().equals(amount)
                && found.reason().equals(reason))) {
            throw new RefundKeyReusedException();
        }
        PaymentIntent intent =
                intents.findById(uow, intentId).orElseThrow(UnknownPaymentException::new);
        // The conditional renewal IS the permit: a refund another resolver moved out of the
        // resolvable states since the lookup matches no row, and then nothing may be sent.
        Optional<Instant> permit =
                resolvable(found.status())
                        ? refunds.renewSendPermit(uow, found.id(), Instant.now(clock))
                        : Optional.empty();
        // A taken-over BOOK refund can never still be resolvable: dispatch and outcome are
        // one transaction, so a committed row is terminal and the permit renewal above
        // matched nothing - send stays false and Tx2 answers the row's truth.
        return new Dispatch(
                found,
                intentId,
                intent.creditAccount(),
                attempt.captureProviderReference(),
                permit.isPresent(),
                false,
                permit.orElse(null),
                attempt.interactionModel(),
                attempt.schemeReference(),
                false);
    }

    /** The claimed dispatch: bound under the attempt lock, hold inside the account lock. */
    private Dispatch dispatch(
            Connection uow,
            String dispatchKey,
            PaymentIntentId intentId,
            Money amount,
            String reason,
            Actor operator,
            Correlation correlation) {
        PaymentIntent intent =
                intents.findById(uow, intentId).orElseThrow(UnknownPaymentException::new);
        PaymentAttempt loose =
                attempts.findForIntent(uow, intentId)
                        .orElseThrow(PaymentNotRefundableException::noAttempt);

        // THE LOCK, then the look (P5-TSK-015): the sibling sum is current because no other
        // dispatcher can pass this point until we commit or roll back.
        PaymentAttempt attempt =
                attempts.lockById(uow, loose.id()).orElseThrow(UnknownPaymentException::new);

        // THE RAIL'S REFUND MODE, read from the STORED rail under the lock just taken
        // (P7-TSK-001, ADR-0059 section 1) - and since P7-TSK-010 this command executes TWO
        // of the declared modes: PROVIDER_REFUND against the capture, RETURN_PAYMENT as a
        // new push citing the original. The book refund lands with its rail (P7-TSK-011).
        // Each mode's ELIGIBLE state is its machine's own terminal, and its BOUND BASE is
        // that machine's returned-money fact: the captured amount for the card, the
        // EXECUTED amount for the push - which the attempt row deliberately does not copy,
        // so the base is the intent's frozen ask, the value the confirmation door proved
        // equal to what the scheme executed (INV-PAY-05's push half; V018 holds the same
        // rule for every writer).
        RailCapabilities.RefundMode refundMode =
                rails.capabilitiesOf(attempt.rail()).refundMode();
        Money base =
                switch (refundMode) {
                    case PROVIDER_REFUND -> {
                        if (attempt.status() != PaymentAttemptStatus.CAPTURED) {
                            throw new PaymentNotRefundableException(attempt.status());
                        }
                        yield attempt.capturedAmount();
                    }
                    case RETURN_PAYMENT -> {
                        if (attempt.status() != PaymentAttemptStatus.EXECUTED) {
                            throw new PaymentNotRefundableException(attempt.status());
                        }
                        if (pushRail.isEmpty()) {
                            // Refused BEFORE the hold: the whole transaction rolls back
                            // with nothing written, the honest 503 (the confirmation's
                            // ObjectProvider decision, at the return).
                            throw new PushRailUnavailableException();
                        }
                        yield intent.amount();
                    }
                    case NONE ->
                            // No attempt rides a credits-only rail: routing refuses every PAY_IN on
                            // it (P9-TSK-014, DIRECTION_UNSUPPORTED) - a stored attempt there is a
                            // wiring fault, loud before anything is written.
                            throw new IllegalStateException(
                                    "the attempt's rail '" + attempt.rail().value() + "' declares"
                                            + " RefundMode.NONE and carries no pay-in (ADR-0080)");
                    case BOOK_REFUND -> {
                        // The book rail's refund (P7-TSK-011, ADR-0059 section 6): the
                        // eligible subject is the book machine's own EXECUTED, the base is
                        // the intent's frozen ask - and the PAIR LOCK comes first, in the
                        // fixed order, because this flow holds the payable (the hold below)
                        // while its posting touches the wallet: the book payment's cycle,
                        // other direction (P4-TST-001's measured lesson).
                        if (attempt.status() != PaymentAttemptStatus.EXECUTED) {
                            throw new PaymentNotRefundableException(attempt.status());
                        }
                        outcomes.lockPairInFixedOrder(
                                uow,
                                intent.debitAccount()
                                        .orElseThrow(
                                                () ->
                                                        new IllegalStateException(
                                                                "a book refund's intent"
                                                                    + " carries its debit"
                                                                    + " wallet: V019's XOR"
                                                                    + " holds it for every"
                                                                    + " writer")),
                                intent.creditAccount());
                        yield intent.amount();
                    }
                };
        Money alreadyRefunded =
                refunds.sumNonFailedFor(uow, attempt.id(), base.currency());
        // THE COMBINED BOUND (P7-TSK-013, INV-DSP-01, ADR-0061 section 3): refunds and the
        // chargebacks standing on this payment together never take more from the counterparty
        // than the capture credited it - read under the attempt lock just taken, the one every
        // chargeback's split is judged under, so a refund racing a chargeback sees it or is
        // seen by it. Zero on the push and book rails: they declare no chargebacks.
        Money alreadyTaken =
                alreadyRefunded.plus(
                        outcomes.chargedBackToCounterparty(uow, attempt.id(), base.currency()));
        if (!amount.currency().equals(base.currency())
                || amount.plus(alreadyTaken).compareTo(base) > 0) {
            // The honest 422 BEFORE any hold is placed: nothing written, nothing reserved.
            throw new RefundExceedsCaptureException(base.currency());
        }

        // WHAT THE REFUND WILL TAKE, NOT ITS GROSS (P6-TSK-015, ADR-0054): asked of the
        // composition that will write the lines, in payment vocabulary - the gross for a
        // wallet, the net for a merchant's payable, whose fee share comes back in the same
        // entry. The COMPLETED sum, read under the attempt lock, is the floor of the orders the
        // completion can still see; never the non-failed one, which a failing sibling undercuts.
        Money reservation =
                outcomes.refundReservation(
                        uow,
                        new RefundReservation(
                                intentId,
                                attempt.id(),
                                intent.creditAccount(),
                                amount,
                                refunds.sumCompletedFor(uow, attempt.id(), amount.currency())));

        // The Phase 3 hold, placed inside the account-row lock (ADR-0048 §4) and sized by the
        // very figure the bound judges: from this commit until the outcome, what the refund
        // will take is unspendable (INV-BAL-04). Throws with nothing written when the account
        // cannot fund it.
        com.finapp.ledger.Hold hold = holds.place(uow, intent.creditAccount(), reservation);

        // The reference, minted per rail (INV-PAY-04): the card's provider shape; for the
        // return a 32-hex value that must fit the scheme wire's 35-character end-to-end
        // bound, judged at mint by the factory (P7-TSK-010); for the book refund a marked
        // platform value - no wire exists, so the reference is a row fact, never a dedupe
        // key (the posting key is the once-arbiter, P7-TSK-011).
        Refund refund =
                switch (refundMode) {
                    case RETURN_PAYMENT ->
                            Refund.createReturn(
                                    ids,
                                    clock,
                                    attempt,
                                    base,
                                    amount,
                                    alreadyRefunded,
                                    reason,
                                    hold.id(),
                                    new ProviderIdempotencyReference(
                                            ids.next().toString().replace("-", "")));
                    case NONE ->
                            throw new IllegalStateException(
                                    "RefundMode.NONE was refused above, before the hold");
                    case BOOK_REFUND ->
                            Refund.createBookRefund(
                                    ids,
                                    clock,
                                    attempt,
                                    base,
                                    amount,
                                    alreadyRefunded,
                                    reason,
                                    hold.id(),
                                    new ProviderIdempotencyReference("bkr-" + ids.next()));
                    case PROVIDER_REFUND ->
                            Refund.create(
                                    ids,
                                    clock,
                                    attempt,
                                    amount,
                                    // The card's combined figure: the factory re-judges the
                                    // same bound the command just did (P7-TSK-013).
                                    alreadyTaken,
                                    reason,
                                    hold.id(),
                                    new ProviderIdempotencyReference("rfd-" + ids.next()));
                };
        refunds.insert(uow, refund, dispatchKey);

        Instant now = Instant.now(clock);
        // The dispatch's own fact, in the transaction that commits it (INV-EVT-01; plan §10:
        // RefundInitiated is legitimate because this commit is durable before the outcome
        // exists). Announced through the shared component - one envelope vocabulary.
        outcomes.announceRefundInitiated(uow, refund, intentId, correlation, now);
        audit.append(
                uow,
                new AuditRecord(
                        AuditId.next(ids),
                        operator,
                        now,
                        PaymentsAuditAction.PAYMENT_REFUND_DISPATCHED,
                        PaymentCreation.TARGET_TYPE,
                        intentId.value().toString(),
                        // The REQUIRED reason (INV-AUD-03): the operator's own words, in the
                        // record's reason field where the reversal precedent put it.
                        Optional.of(reason),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        // Identifiers only - never an amount (INV-AUD-02).
                        Optional.of(
                                "intent=" + intentId
                                        + ", attempt=" + attempt.id()
                                        + ", refund=" + refund.id()
                                        + ", reference="
                                        + refund.providerIdempotencyReference().value())));

        // The birth permit, as the database stored it (V009): the value a refused connection on
        // this first send is later judged against, read back rather than taken from this
        // instance's clock at a finer precision than the column keeps.
        Instant permit =
                refunds.lockForOutcome(uow, refund.id())
                        .map(RefundStore.LockedRefund::lastDispatchedAt)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a refund inserted in this transaction is"
                                                        + " readable in it"));

        if (refundMode == RailCapabilities.RefundMode.BOOK_REFUND) {
            // THE BOOK REFUND COMPLETES HERE, IN THIS TRANSACTION (P7-TSK-011, ADR-0059
            // section 6): no wire exists, so there is no connectionless gap - the
            // compensating movement, the hold's release, the COMPLETED transition and the
            // terminal fact commit with the dispatch or roll back with it. The actor is
            // the OPERATOR in context: no resolver ever finishes a book refund, so no
            // platform actor is claimed. The stored provider reference is a marked
            // platform value - the entry is the whole external record (INV-SET-01,
            // vacuously: nothing external settles).
            PaymentOutcomes.RefundApplied completed =
                    outcomes.applyRefund(
                            uow,
                            intentId,
                            refund,
                            RefundStatus.DISPATCHED,
                            ProviderAnswer.Verdict.APPROVED,
                            Optional.of(new ProviderReference("bke-" + ids.next())),
                            intent.creditAccount(),
                            correlation);
            return new Dispatch(
                    refund,
                    intentId,
                    intent.creditAccount(),
                    null,
                    false,
                    false,
                    permit,
                    attempt.interactionModel(),
                    Optional.empty(),
                    completed.acting());
        }
        return new Dispatch(
                refund,
                intentId,
                intent.creditAccount(),
                attempt.captureProviderReference(),
                true,
                true,
                permit,
                attempt.interactionModel(),
                attempt.schemeReference(),
                false);
    }

    /** The operator and the money's meaning ({@code INV-IDEM-03}); correlation excluded. */
    private static byte[] canonicalForm(
            Actor operator, PaymentIntentId intentId, Money amount, String reason) {
        return (IDEMPOTENCY_SCOPE
                        + "|" + operator.id()
                        + "|" + intentId.value()
                        + "|" + amount.minorUnits()
                        + "|" + amount.currency().code()
                        + "|" + amount.scale()
                        + "|" + reason)
                .getBytes(StandardCharsets.UTF_8);
    }
}
