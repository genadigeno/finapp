package com.finapp.payments;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The one outcome application every resolver shares (`P5-TSK-013`, ADR-0047 §4) — the
 * {@code CheckOutcomeTrail} extraction rule fired: the synchronous response
 * ({@code PaymentConfirmation}/{@code PaymentCapture} Tx2), the webhook resolver and the
 * sweeper (`P5-TSK-014`) are different <em>arrivals</em> of the same judgement, and a second
 * copy of the money-bearing switch is a place where "the {@code CAPTURED} transition, the
 * posting and the intent's {@code SUCCEEDED} are one transaction" (ADR-0048) could quietly
 * stop being true.
 *
 * <h2>The {@code from} parameter is the order-blindness</h2>
 *
 * <p>An authorization outcome applies from {@code AUTH_DISPATCHED} (the synchronous answer,
 * or a webhook healing a crash mid-call) or from {@code AUTH_UNKNOWN} (a resolver answering
 * {@code INV-LIFE-03}'s question); a capture outcome from {@code CAPTURE_DISPATCHED} or
 * {@code CAPTURE_UNKNOWN}. The machine's edges differ only in their source, so the source is
 * an argument — and every write stays a <strong>conditional transition</strong>: out of
 * order, duplicated-with-a-fresh-id, racing another resolver, all land on one row count, and
 * the losers converge with the truth ({@code INV-IDEM-04}; the retained statement is
 * `P5-TSK-012`'s evidence row, not this class's concern).
 *
 * <h2>The caller holds the actor and the transaction</h2>
 *
 * <p>Every application runs as the platform ({@link SecurityContext#require()} inside the
 * caller's {@code enterSystem()} scope — a provider's answer has no session, whichever
 * resolver carries it) and on the caller's connection: the synchronous paths' Tx2, the
 * webhook's delivery transaction (evidence + dedupe + effect, one commit — ADR-0047 §3).
 * A posting failure inside an approved capture fails that whole transaction loudly — no
 * savepoint, deliberately ({@code PaymentCapture}'s recorded stance, preserved by the
 * extraction rather than re-decided).
 */
@Slf4j
@RequiredArgsConstructor
public final class PaymentOutcomes {

    static final String AUTHORIZED_EVENT_TYPE = "payments.PaymentAuthorized";
    static final String CAPTURED_EVENT_TYPE = "payments.PaymentCaptured";
    static final String FAILED_EVENT_TYPE = "payments.PaymentFailed";
    static final String UNKNOWN_EVENT_TYPE = "payments.PaymentStateUnknown";

    /** The push pay-in's terminal fact (`P7-TSK-009`): EXECUTED is not CAPTURED, in the
     * event vocabulary too — no consumer can mistake one rail's completion for another's. */
    static final String EXECUTED_EVENT_TYPE = "payments.PaymentExecuted";

    /** The push completion's posting key (`P7-TSK-009`): the execution's own operation
     * name, one entry per attempt whoever resolves it — {@code payment-capture:}'s sibling,
     * deliberately not its reuse (the vocabulary disjointness, ADR-0059 §2). */
    static final String EXECUTION_POSTING_PREFIX = "payment-execution:";

    /** The void's terminal fact (`P7-TSK-004`): the authorization released, nothing captured. */
    static final String VOIDED_EVENT_TYPE = "payments.AuthorizationVoided";
    // The refund vocabulary (P5-TSK-016; plan §10, MODULE_ARCHITECTURE's register): the two
    // terminal facts, and the dispatch - legitimate where TransferInitiated was not, because
    // under ADR-0046 the dispatch commits durably before its own outcome exists. UNKNOWN
    // deliberately publishes nothing: not a terminal fact, and the standing hold is its
    // visible record.
    static final String REFUND_INITIATED_EVENT_TYPE = "payments.RefundInitiated";
    static final String REFUND_COMPLETED_EVENT_TYPE = "payments.RefundCompleted";
    static final String REFUND_FAILED_EVENT_TYPE = "payments.RefundFailed";

    @NonNull private final PaymentIntentStore<Connection> intents;
    @NonNull private final PaymentAttemptStore<Connection> attempts;
    @NonNull private final RefundStore<Connection> refunds;
    @NonNull private final com.finapp.ledger.HoldService holds;
    @NonNull private final PostingService postings;
    @NonNull private final ChartOfAccounts<Connection> chart;
    @NonNull private final CaptureComposition<Connection> composition;
    @NonNull private final RefundComposition<Connection> refundComposition;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** The build's declared rails (`P7-TSK-001`, ADR-0059): the stored rail's key back to capabilities. */
    @NonNull private final PaymentRails rails;

    /** The suspense parkings (`P7-TSK-009`): the execute arm's second claim pre-check —
     * a scheme reference already PARKED must not also credit, or the money counts twice.
     * Last, so no existing positional argument moved (the Lombok field-order rule). */
    @NonNull private final UnmatchedConfirmationStore<Connection> unmatched;

    /**
     * The ledger's account rows, for the book rail's fixed-order pair lock (`P7-TSK-011`):
     * a book movement explicitly locks BOTH its participants before judging anything,
     * because {@code journal_line}'s FK takes {@code FOR KEY SHARE} on its account and the
     * transfer measured what an unordered {@code FOR UPDATE} against that becomes — 783
     * deadlocks (`P4-TST-001`). Appended last (the constructor is positional history).
     */
    @NonNull private final com.finapp.ledger.LedgerAccountStore<Connection> ledgerAccounts;

    /**
     * The money of disputes (`P7-TSK-013`, ADR-0061 §3): a refund's failure hands the share of
     * any standing chargeback's excess it had caused back to the counterparty, and a refund's
     * dispatch asks it for the combined bound's chargeback term. Appended last (the
     * constructor is positional history).
     */
    @NonNull private final ChargebackAccounting chargebacks;

    /**
     * What committed (or was found committed by the loser of a harmless race).
     *
     * @param acting whether <strong>this</strong> call's conditional transition fired
     *     (`P5-TSK-017`). The row count is the only place the answer exists: a converged
     *     loser and an acting winner return identical states by design, and telemetry that
     *     could not tell them apart would count one judgement N times under a race — the
     *     plan's own "replays/converges never throughput". Never financial truth: the rows
     *     are the record, and an acting call can still be rolled back by the transaction's
     *     owner, which is why the counting seam is the door, post-commit.
     */
    public record Applied(
            PaymentIntentStatus intent,
            PaymentAttemptStatus attempt,
            boolean acting,
            boolean voidPending) {

        Applied(PaymentIntentStatus intent, PaymentAttemptStatus attempt, boolean acting) {
            this(intent, attempt, acting, false);
        }

        /**
         * The declined-capture redirect committed {@code VOID_DISPATCHED} and the send is
         * now the caller's (`P7-TSK-004`): a resolver that owns a provider port performs it
         * ({@code PaymentVoid.completeDispatched}); one that does not - the webhook door -
         * leaves the row visibly dispatched for the sweeper's void leg, which re-sends
         * idempotently by the stored reference.
         */
        Applied withVoidPending() {
            return new Applied(intent, attempt, acting, true);
        }
    }

    /** The refund's committed status, and whether this call's conditional made it so. */
    public record RefundApplied(RefundStatus status, boolean acting) {}

    /**
     * What a refund must reserve on the account it debits (`P6-TSK-015`, ADR-0054), asked of
     * <strong>the same composition that will settle it</strong>.
     *
     * <p>That is the whole reason this lives here rather than in the command: the dispatch's
     * hold and the completion's lines must be priced by ONE composer, because a hold sized by
     * one and lines written by another is a refund reserving the gross and taking the net - or
     * reserving the net and taking the gross, which is the dangerous direction. Wiring cannot
     * split what a single field holds.
     */
    public Money refundReservation(Connection uow, RefundReservation reservation) {
        return refundComposition.reserve(uow, reservation);
    }

    /**
     * What the chargebacks standing on {@code attempt} attribute to its counterparty
     * (`P7-TSK-013`, {@code INV-DSP-01}) — the refund bound's second term, read under the
     * attempt lock the refund's dispatch holds: refunds and chargebacks together never take more
     * from the counterparty than the capture credited it.
     */
    public Money chargedBackToCounterparty(
            Connection uow, PaymentAttemptId attempt, com.finapp.sharedkernel.money.CurrencyCode currency) {
        return chargebacks.attributedStanding(uow, attempt, currency);
    }

    /**
     * Applies an authorization outcome from {@code from} — {@code AUTH_DISPATCHED} or
     * {@code AUTH_UNKNOWN}.
     *
     * @param promisedAmount the dispatched ask the issuer approved — carried by the caller
     *     (Tx1's amount, or the intent row's), never re-read here
     */
    public Applied applyAuthorization(
            Connection uow,
            PaymentIntentId intentId,
            PaymentAttemptId attemptId,
            PaymentAttemptStatus from,
            ProviderAnswer.Verdict verdict,
            Optional<ProviderReference> providerReference,
            Money promisedAmount,
            Correlation correlation) {
        Actor platform = SecurityContext.require();
        Instant now = Instant.now(clock);

        PaymentAttemptStatus committedAttempt;
        PaymentIntentStatus committedIntent = PaymentIntentStatus.PROCESSING;
        boolean acting;
        switch (verdict) {
            case APPROVED -> {
                acting =
                        attempts.authorize(
                                uow, attemptId, from, providerReference.orElseThrow(),
                                promisedAmount);
                if (acting) {
                    attempts.recordTransition(
                            uow, attemptId, from, PaymentAttemptStatus.AUTHORIZED, platform, now);
                    announce(uow, AUTHORIZED_EVENT_TYPE, intentId, "AUTHORIZED",
                            Optional.empty(), correlation, now);
                }
                committedAttempt = PaymentAttemptStatus.AUTHORIZED;
            }
            case DECLINED -> {
                Failed failed =
                        failBoth(uow, intentId, attemptId, from, PaymentFailureReason.DECLINED,
                                correlation, platform, now);
                committedAttempt = failed.status();
                acting = failed.acting();
                committedIntent = PaymentIntentStatus.FAILED;
            }
            case NOTHING_SENT -> {
                Failed failed =
                        failBoth(uow, intentId, attemptId, from,
                                PaymentFailureReason.PROVIDER_UNAVAILABLE, correlation,
                                platform, now);
                committedAttempt = failed.status();
                acting = failed.acting();
                committedIntent = PaymentIntentStatus.FAILED;
            }
            default -> {
                // INDETERMINATE: we do not know is the answer, and it commits (INV-LIFE-03).
                // Only a DISPATCHED source can become UNKNOWN - the conditional refuses the
                // rest, which is what makes an already-unknown attempt converge here.
                acting = attempts.markAuthUnknown(uow, attemptId);
                if (acting) {
                    attempts.recordTransition(
                            uow,
                            attemptId,
                            PaymentAttemptStatus.AUTH_DISPATCHED,
                            PaymentAttemptStatus.AUTH_UNKNOWN,
                            platform,
                            now);
                    announce(uow, UNKNOWN_EVENT_TYPE, intentId, "AUTH_UNKNOWN",
                            Optional.empty(), correlation, now);
                }
                committedAttempt = PaymentAttemptStatus.AUTH_UNKNOWN;
            }
        }

        return answered(uow, intentId, attemptId, verdict.name(), committedAttempt,
                committedIntent, acting, platform, correlation, now);
    }

    /**
     * Applies a capture outcome from {@code from} — {@code CAPTURE_DISPATCHED} or
     * {@code CAPTURE_UNKNOWN}. For APPROVED: the transition, <strong>the posting</strong> and
     * the intent's {@code SUCCEEDED}, one commit (ADR-0048) — the key
     * {@code payment-capture:<attemptId>} makes any duplicate outcome from any resolver
     * structurally unable to post twice ({@code INV-IDEM-01}'s kernel).
     */
    public Applied applyCapture(
            Connection uow,
            PaymentIntentId intentId,
            PaymentAttemptId attemptId,
            PaymentAttemptStatus from,
            ProviderAnswer.Verdict verdict,
            Optional<ProviderReference> providerReference,
            LedgerAccountId wallet,
            Money amount,
            Correlation correlation) {
        Actor platform = SecurityContext.require();
        Instant now = Instant.now(clock);

        PaymentAttemptStatus committedAttempt;
        PaymentIntentStatus committedIntent = PaymentIntentStatus.PROCESSING;
        boolean acting;
        switch (verdict) {
            case APPROVED -> {
                acting =
                        attempts.capture(
                                uow, attemptId, from, providerReference.orElseThrow(), amount);
                if (acting) {
                    attempts.recordTransition(
                            uow, attemptId, from, PaymentAttemptStatus.CAPTURED, platform, now);

                    // THE POSTING - same connection, atomically with the transition
                    // (ADR-0048). No savepoint, deliberately: a posting failure fails this
                    // whole transaction loudly, whichever resolver carried the outcome.
                    //
                    // THE LINES ARE COMPOSED, NOT WRITTEN HERE (P6-TSK-005, ADR-0050 section
                    // 6). A wallet top-up settles in two (DR clearing / CR wallet); a
                    // merchant-bound capture settles in four, with the platform's fee taken
                    // out of the payable in the SAME entry. Which it is depends on the flow
                    // that created the intent, and this module deliberately cannot tell -
                    // the composer runs here, on this connection, after the conditional
                    // transition has been won, so it runs exactly once per capture however
                    // many resolvers raced.
                    LedgerAccount clearing =
                            chart.resolve(
                                    uow,
                                    clearingPurposeOf(uow, attemptId),
                                    amount.currency());
                    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
                    CaptureSettlement settlement =
                            new CaptureSettlement(
                                    intentId, attemptId, clearing.id(), wallet, amount,
                                    correlation, now);
                    com.finapp.ledger.PostingResult posted =
                            postings.post(
                                    uow,
                                    new PostingCommand(
                                            // ONE key, whatever the shape of the entry: a
                                            // duplicate outcome from any resolver posts once,
                                            // and four lines inherit that guarantee wholesale
                                            // because they are ONE entry under it
                                            // (INV-IDEM-01's kernel, unchanged).
                                            "payment-capture:" + attemptId.value(),
                                            today,
                                            today,
                                            attemptId.value().toString(),
                                            composition.settle(uow, settlement)));
                    // THE SEAM'S SECOND MOMENT (P6-TSK-007): the entry exists and its id is
                    // known, so the composing flow can record what it means - a checkout
                    // order carrying the entry that paid for it - in THIS transaction.
                    composition.settled(uow, settlement, posted.entryId().value());

                    // THE CAPTURE FREES HEADROOM (P7-TSK-013, the gate's find): a chargeback
                    // stated before this capture resolved was judged against nothing captured
                    // and rests as excess; the counterparty has just been credited, so the
                    // standing excess comes back to it, under the attempt row this transaction's
                    // conditional already holds - after the capture's own posting.
                    chargebacks.captureLanded(uow, attemptId, intentId, amount, correlation, now);

                    if (intents.transition(
                            uow,
                            intentId,
                            PaymentIntentStatus.PROCESSING,
                            PaymentIntentStatus.SUCCEEDED)) {
                        intents.recordTransition(
                                uow,
                                intentId,
                                PaymentIntentStatus.PROCESSING,
                                PaymentIntentStatus.SUCCEEDED,
                                platform,
                                now);
                    }
                    announce(uow, CAPTURED_EVENT_TYPE, intentId, "CAPTURED",
                            Optional.empty(), correlation, now);
                }
                committedAttempt = PaymentAttemptStatus.CAPTURED;
                committedIntent = PaymentIntentStatus.SUCCEEDED;
            }
            case DECLINED -> {
                // THE REDIRECT (P7-TSK-004, ADR-0059): on a rail whose DECLARED reversals
                // contain VOID - judged from the stored rail, never a name (INV-RAIL-01) -
                // a declined capture releases the standing authorization instead of leaving
                // it to lapse against the customer's funds. The redirect commits
                // VOID_DISPATCHED with its minted reference (INV-PAY-04) in THIS outcome
                // transaction; the send is the caller's, and the sweeper's void leg
                // finishes any redirect whose caller has no provider port (the webhook
                // door). A two-step rail that declares no VOID keeps the Phase 5
                // conclusion: FAILED(DECLINED), the intent with it.
                RailId railOfRow =
                        attempts.findById(uow, attemptId)
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "an attempt an outcome is applied to"
                                                                + " exists"))
                                .rail();
                if (rails.capabilitiesOf(railOfRow)
                        .reversals()
                        .contains(RailCapabilities.Reversal.VOID)) {
                    ProviderIdempotencyReference voidReference =
                            new ProviderIdempotencyReference("void-" + ids.next());
                    acting = attempts.dispatchVoid(uow, attemptId, from, voidReference);
                    if (acting) {
                        attempts.recordTransition(
                                uow,
                                attemptId,
                                from,
                                PaymentAttemptStatus.VOID_DISPATCHED,
                                platform,
                                now);
                    }
                    Applied redirected =
                            answered(uow, intentId, attemptId, verdict.name(),
                                    PaymentAttemptStatus.VOID_DISPATCHED,
                                    PaymentIntentStatus.PROCESSING, acting, platform,
                                    correlation, now);
                    return acting ? redirected.withVoidPending() : redirected;
                }
                Failed failed =
                        failBoth(uow, intentId, attemptId, from, PaymentFailureReason.DECLINED,
                                correlation, platform, now);
                committedAttempt = failed.status();
                acting = failed.acting();
                committedIntent = PaymentIntentStatus.FAILED;
            }
            case NOTHING_SENT -> {
                Failed failed =
                        failBoth(uow, intentId, attemptId, from,
                                PaymentFailureReason.PROVIDER_UNAVAILABLE, correlation,
                                platform, now);
                committedAttempt = failed.status();
                acting = failed.acting();
                committedIntent = PaymentIntentStatus.FAILED;
            }
            default -> {
                // INDETERMINATE: CAPTURE_UNKNOWN commits with NOTHING POSTED (INV-LIFE-03) -
                // the posting arrives only with a resolved CAPTURED.
                acting = attempts.markCaptureUnknown(uow, attemptId);
                if (acting) {
                    attempts.recordTransition(
                            uow,
                            attemptId,
                            PaymentAttemptStatus.CAPTURE_DISPATCHED,
                            PaymentAttemptStatus.CAPTURE_UNKNOWN,
                            platform,
                            now);
                    announce(uow, UNKNOWN_EVENT_TYPE, intentId, "CAPTURE_UNKNOWN",
                            Optional.empty(), correlation, now);
                }
                committedAttempt = PaymentAttemptStatus.CAPTURE_UNKNOWN;
            }
        }

        return answered(uow, intentId, attemptId, verdict.name(), committedAttempt,
                committedIntent, acting, platform, correlation, now);
    }

    /**
     * The sweeper's licence, as one named method ({@code P5-TSK-014}): the provider explicitly
     * answered a resolution query that it never saw our reference
     * ({@link QueryAnswer.Verdict#UNRECOGNISED}), so the operation never happened and failing
     * it destroys nothing — {@code FAILED(NEVER_RECEIVED)} on the attempt, {@code FAILED} on
     * the intent, audited with the query verdict's own word. Fixed here so no caller composes
     * the resolution ad hoc; a 404 or any status code never reaches this method
     * ({@code QueryAnswer}'s fold is the guard one layer down).
     */
    public Applied applyUnrecognised(
            Connection uow,
            PaymentIntentId intentId,
            PaymentAttemptId attemptId,
            PaymentAttemptStatus from,
            Correlation correlation) {
        Actor platform = SecurityContext.require();
        Instant now = Instant.now(clock);
        Failed failed =
                failBoth(uow, intentId, attemptId, from, PaymentFailureReason.NEVER_RECEIVED,
                        correlation, platform, now);
        return answered(uow, intentId, attemptId, QueryAnswer.Verdict.UNRECOGNISED.name(),
                failed.status(), PaymentIntentStatus.FAILED, failed.acting(), platform,
                correlation, now);
    }

    /**
     * Applies an initiation's opening answer (`P7-TSK-009`, ADR-0062 §5) — the pay-by-bank
     * Tx2, and the sweep's re-initiate leg, one judgement.
     *
     * <p>{@code INITIATED} stores the handle once (<strong>not a transition</strong> — the
     * row stays {@code AWAITING_PAYER}; a loser converges because the scheme's dedupe
     * means the handle it held was this one). {@code REFUSED} is knowledge: the scheme
     * would not open it — {@code FAILED(DECLINED)}, the intent with it.
     * {@code NOTHING_SENT} concludes {@code FAILED(PROVIDER_UNAVAILABLE)} <strong>only
     * while no handle is stored</strong> (the {@code failHandleless} conditional — ADR-0062
     * §3 adapted: a row holding a handle has an initiation the payer can still complete,
     * so no unavailability verdict may fail it, whichever instance re-initiated first).
     * {@code INDETERMINATE} moves nothing: the modelled unknown is the handle's absence,
     * and the pay-in sweep's convergent re-initiate resolves it ({@code INV-LIFE-03}).
     */
    public Applied applyInitiation(
            Connection uow,
            PaymentIntentId intentId,
            PaymentAttemptId attemptId,
            InitiationAnswer.Outcome outcome,
            Optional<com.finapp.sharedkernel.security.Sensitive<String>> authorizationHandle,
            Correlation correlation) {
        Actor platform = SecurityContext.require();
        Instant now = Instant.now(clock);

        PaymentAttemptStatus committedAttempt = PaymentAttemptStatus.AWAITING_PAYER;
        PaymentIntentStatus committedIntent = PaymentIntentStatus.PROCESSING;
        boolean acting;
        switch (outcome) {
            case INITIATED -> acting =
                    attempts.openInitiation(uow, attemptId, authorizationHandle.orElseThrow());
            case REFUSED -> {
                Failed failed =
                        failBoth(uow, intentId, attemptId,
                                PaymentAttemptStatus.AWAITING_PAYER,
                                PaymentFailureReason.DECLINED, correlation, platform, now);
                committedAttempt = failed.status();
                acting = failed.acting();
                committedIntent = PaymentIntentStatus.FAILED;
            }
            case NOTHING_SENT -> {
                acting = attempts.failHandleless(
                        uow, attemptId, PaymentFailureReason.PROVIDER_UNAVAILABLE);
                if (acting) {
                    attempts.recordTransition(
                            uow, attemptId, PaymentAttemptStatus.AWAITING_PAYER,
                            PaymentAttemptStatus.FAILED, platform, now);
                    if (intents.transition(
                            uow, intentId, PaymentIntentStatus.PROCESSING,
                            PaymentIntentStatus.FAILED)) {
                        intents.recordTransition(
                                uow, intentId, PaymentIntentStatus.PROCESSING,
                                PaymentIntentStatus.FAILED, platform, now);
                    }
                    announce(uow, FAILED_EVENT_TYPE, intentId, "FAILED",
                            Optional.of(PaymentFailureReason.PROVIDER_UNAVAILABLE),
                            correlation, now);
                }
                committedAttempt = PaymentAttemptStatus.FAILED;
                committedIntent = PaymentIntentStatus.FAILED;
            }
            default -> {
                // INDETERMINATE: the honest answer is the standing row - AWAITING_PAYER,
                // handle-less - and the sweep's re-initiate is its resolution path
                // (INV-LIFE-03: the unknown IS modelled, as the handle's absence).
                return answered(uow, intentId, attemptId, outcome.name(),
                        PaymentAttemptStatus.AWAITING_PAYER, PaymentIntentStatus.PROCESSING,
                        false, platform, correlation, now);
            }
        }
        return answered(uow, intentId, attemptId, outcome.name(), committedAttempt,
                committedIntent, acting, platform, correlation, now);
    }

    /**
     * Applies the payer PSP's execution answer from {@code from} — the signed callback and
     * the initiation inquiry, one judgement (`P7-TSK-009`, ADR-0062 §5).
     *
     * <p>{@code ACCEPTED}: the conditional {@code EXECUTED} transition, <strong>the
     * posting</strong> ({@code payment-execution:<attemptId>}, lines composed by the flow
     * that created the intent — the wallet's two or ADR-0050's four through the existing
     * capture composition), the composing flow's completion (a checkout order born
     * {@code COMPLETED} or {@code COMPLETED_LATE} — {@code INV-MER-06}'s second rail) and
     * the intent's {@code SUCCEEDED}, one commit (ADR-0048). Two claim pre-checks guard the
     * scheme reference before anything moves: one already stored by ANOTHER attempt, or
     * already PARKED in suspense, is the integration break made loud — the first record
     * stands, this statement rests as evidence, nothing credits twice.
     *
     * <p>{@code REJECTED} — the payer refused, or their PSP reported the initiation expired
     * (the scheme's own words rest in the evidence; the core keeps its three reasons,
     * {@code INV-PAY-03}) — fails both. {@code UNRECOGNISED} on a row we hold a handle for
     * is the scheme contradicting itself: loud, nothing moves. {@code INDETERMINATE} moves
     * nothing.
     */
    public Applied applyExecution(
            Connection uow,
            PaymentIntentId intentId,
            PaymentAttemptId attemptId,
            PaymentAttemptStatus from,
            PushInquiryAnswer.Verdict verdict,
            Optional<ProviderReference> schemeReference,
            Optional<String> settlementCycle,
            LedgerAccountId credit,
            Money amount,
            Correlation correlation) {
        Actor platform = SecurityContext.require();
        Instant now = Instant.now(clock);

        PaymentAttemptStatus committedAttempt;
        PaymentIntentStatus committedIntent = PaymentIntentStatus.PROCESSING;
        boolean acting;
        switch (verdict) {
            case ACCEPTED -> {
                ProviderReference scheme = schemeReference.orElseThrow();
                RailId rail =
                        attempts.findById(uow, attemptId)
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "an attempt an outcome is applied to"
                                                                + " exists"))
                                .rail();
                // THE CLAIM PRE-CHECKS (P7-TSK-009): one scheme execution credits once,
                // platform-wide. A reference another attempt stored, or one already parked
                // in suspense, is the break Phase 8's matching must see - recorded loud,
                // never compounded into a second credit (the V015 foreign-claim shape).
                Optional<PaymentAttempt> claimant = attempts.findBySchemeReference(uow, scheme);
                if (claimant.isPresent() && !claimant.get().id().equals(attemptId)) {
                    log.warn(
                            "An execution confirmation for attempt {} named a scheme"
                                    + " reference already recorded on attempt {}; the first"
                                    + " record stands and this statement rests as evidence -"
                                    + " an integration break reconciliation must see",
                            attemptId,
                            claimant.get().id());
                    return answered(uow, intentId, attemptId, verdict.name(), from,
                            committedIntent, false, platform, correlation, now);
                }
                if (unmatched.findByReference(uow, rail, scheme).isPresent()) {
                    log.warn(
                            "An execution confirmation for attempt {} named a scheme"
                                    + " reference already PARKED in suspense; the parking"
                                    + " stands for the operator to resolve, and this row"
                                    + " does not also credit (INV-REC-05, P7-TSK-009)",
                            attemptId);
                    return answered(uow, intentId, attemptId, verdict.name(), from,
                            committedIntent, false, platform, correlation, now);
                }

                acting = attempts.execute(uow, attemptId, from, scheme, settlementCycle);
                if (acting) {
                    attempts.recordTransition(
                            uow, attemptId, from, PaymentAttemptStatus.EXECUTED, platform,
                            now);

                    // THE POSTING - same connection, atomically with the transition
                    // (ADR-0048; the capture arm's stance, on the second inbound rail): the
                    // lines are COMPOSED by the flow that created the intent, the clearing
                    // is the STORED rail's declared position (INV-RAIL-04), and the key
                    // makes any duplicate resolver structurally unable to post twice.
                    LedgerAccount clearing =
                            chart.resolve(
                                    uow, clearingPurposeOf(uow, attemptId), amount.currency());
                    settleExecution(
                            uow, intentId, attemptId, clearing.id(), credit, amount,
                            platform, correlation, now);
                }
                committedAttempt = PaymentAttemptStatus.EXECUTED;
                committedIntent = PaymentIntentStatus.SUCCEEDED;
            }
            case REJECTED -> {
                Failed failed =
                        failBoth(uow, intentId, attemptId, from, PaymentFailureReason.DECLINED,
                                correlation, platform, now);
                committedAttempt = failed.status();
                acting = failed.acting();
                committedIntent = PaymentIntentStatus.FAILED;
            }
            case UNRECOGNISED -> {
                // A scheme that opened this initiation (we hold its handle) answering that
                // it never saw our reference is the scheme contradicting itself - an
                // integration break, loud, moving nothing: failing a row the payer may yet
                // execute against destroys money (the INV-LIFE-03 argument, inverted).
                log.warn(
                        "An initiation inquiry for attempt {} answered UNRECOGNISED although"
                                + " the initiation was opened; nothing moves and the"
                                + " statement rests as evidence - an integration break",
                        attemptId);
                return answered(uow, intentId, attemptId, verdict.name(), from,
                        committedIntent, false, platform, correlation, now);
            }
            default -> {
                // INDETERMINATE: ask again next sweep (INV-LIFE-03).
                return answered(uow, intentId, attemptId, verdict.name(), from,
                        committedIntent, false, platform, correlation, now);
            }
        }
        return answered(uow, intentId, attemptId, verdict.name(), committedAttempt,
                committedIntent, acting, platform, correlation, now);
    }

    /**
     * Applies a void outcome from {@code from} — {@code VOID_DISPATCHED} or
     * {@code VOID_UNKNOWN} (`P7-TSK-004`). {@code APPROVED} concludes the payment: the
     * authorization is released, nothing was captured, nothing posts — {@code VOIDED} on the
     * attempt, {@code FAILED} on the intent (the customer's rail-agnostic machine has no
     * voided vocabulary, ADR-0059 §2), and {@code AuthorizationVoided} publishes with the
     * acting transition. {@code DECLINED} fails both with the mapped reason.
     * {@code INDETERMINATE} commits the honest {@code VOID_UNKNOWN} ({@code INV-LIFE-03}).
     * <strong>{@code NOTHING_SENT} concludes NOTHING</strong>, deliberately: the row stays
     * {@code VOID_DISPATCHED} and the sweeper re-sends by the stored reference — a duplicate
     * void is harmless by definition (releasing a released promise), which is why no send
     * permit exists here where the refund needed `V009`'s (the recorded asymmetry).
     */
    public Applied applyVoid(
            Connection uow,
            PaymentIntentId intentId,
            PaymentAttemptId attemptId,
            PaymentAttemptStatus from,
            ProviderAnswer.Verdict verdict,
            Optional<ProviderReference> providerReference,
            Correlation correlation) {
        Actor platform = SecurityContext.require();
        Instant now = Instant.now(clock);

        PaymentAttemptStatus committedAttempt;
        PaymentIntentStatus committedIntent = PaymentIntentStatus.PROCESSING;
        boolean acting;
        switch (verdict) {
            case APPROVED -> {
                acting = attempts.voided(uow, attemptId, from, providerReference.orElseThrow());
                if (acting) {
                    attempts.recordTransition(
                            uow, attemptId, from, PaymentAttemptStatus.VOIDED, platform, now);
                    if (intents.transition(
                            uow,
                            intentId,
                            PaymentIntentStatus.PROCESSING,
                            PaymentIntentStatus.FAILED)) {
                        intents.recordTransition(
                                uow,
                                intentId,
                                PaymentIntentStatus.PROCESSING,
                                PaymentIntentStatus.FAILED,
                                platform,
                                now);
                    }
                    announce(uow, VOIDED_EVENT_TYPE, intentId, "VOIDED", Optional.empty(),
                            correlation, now);
                }
                committedAttempt = PaymentAttemptStatus.VOIDED;
                committedIntent = PaymentIntentStatus.FAILED;
            }
            case DECLINED -> {
                Failed failed =
                        failBoth(uow, intentId, attemptId, from, PaymentFailureReason.DECLINED,
                                correlation, platform, now);
                committedAttempt = failed.status();
                acting = failed.acting();
                committedIntent = PaymentIntentStatus.FAILED;
            }
            case NOTHING_SENT -> {
                // No conclusion, structurally: the connection was refused before anything
                // left, the promise still stands, and the next send - any instance's - will
                // release it. answered() with acting=false reads and reports the truth.
                return answered(uow, intentId, attemptId, verdict.name(),
                        PaymentAttemptStatus.VOID_DISPATCHED, PaymentIntentStatus.PROCESSING,
                        false, platform, correlation, now);
            }
            default -> {
                acting = attempts.markVoidUnknown(uow, attemptId);
                if (acting) {
                    attempts.recordTransition(
                            uow,
                            attemptId,
                            PaymentAttemptStatus.VOID_DISPATCHED,
                            PaymentAttemptStatus.VOID_UNKNOWN,
                            platform,
                            now);
                    announce(uow, UNKNOWN_EVENT_TYPE, intentId, "VOID_UNKNOWN",
                            Optional.empty(), correlation, now);
                }
                committedAttempt = PaymentAttemptStatus.VOID_UNKNOWN;
            }
        }
        return answered(uow, intentId, attemptId, verdict.name(), committedAttempt,
                committedIntent, acting, platform, correlation, now);
    }

    /**
     * Applies a refund outcome from {@code from} — {@code DISPATCHED} or {@code UNKNOWN}
     * (`P5-TSK-015`, ADR-0048 §4). <strong>Completion releases-and-posts atomically</strong>:
     * the {@code COMPLETED} transition, the hold's release and the
     * {@code payment-refund:<refundId>} posting (DR wallet / CR clearing — the capture's exact
     * inverse pair) are one commit, no savepoint, the capture's recorded stance in refund
     * form. Failure releases with nothing posted — the customer's money is theirs again.
     * Ambiguity commits {@code UNKNOWN} <strong>with the hold standing</strong>
     * ({@code INV-LIFE-03} with money visibly parked on it): the provider may yet have
     * refunded, so the reservation must survive until an outcome does.
     *
     * <p>The refund's outbox events are deliberately absent until `P5-TSK-016` (the scope
     * that names them) — the announce seam here is that task's, the `P5-TSK-012` precedent.
     */
    public RefundApplied applyRefund(
            Connection uow,
            PaymentIntentId intentId,
            Refund refund,
            RefundStatus from,
            ProviderAnswer.Verdict verdict,
            Optional<ProviderReference> providerReference,
            LedgerAccountId wallet,
            Correlation correlation) {
        Actor platform = SecurityContext.require();
        Instant now = Instant.now(clock);

        RefundStatus committed;
        boolean acting;
        switch (verdict) {
            case APPROVED -> {
                acting = refunds.complete(uow, refund.id(), from, providerReference.orElseThrow());
                if (acting) {
                    refunds.recordTransition(
                            uow, refund.id(), from, RefundStatus.COMPLETED, platform, now);
                    holds.release(uow, refund.holdReference());

                    // THE POSTING - same connection, atomically with the transition and the
                    // release (ADR-0048 §4): DR the customer's wallet, CR clearing - the
                    // capture's inverse pair; the key makes any duplicate outcome
                    // structurally unable to post twice. On the BOOK rail the money's
                    // counterpart is the intent's own debit wallet - the compensating
                    // movement returns it where it came from, and no clearing exists to
                    // stand between (P7-TSK-011, ADR-0059 §6).
                    com.finapp.ledger.LedgerAccountId counterpart =
                            refundCounterpartOf(uow, intentId, refund);
                    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));

                    // WHAT HAD ALREADY BEEN RETURNED, EXCLUDING THIS REFUND (`P6-TSK-014`).
                    // The completed sum is read AFTER this refund's own conditional fired, so
                    // it includes this one; the composer is handed the total BEFORE it,
                    // because the difference of two cumulative allocations is the whole of
                    // the proportional arithmetic and neither side of that subtraction may
                    // be guessed. Valid because the release above locked the DEBITED account
                    // FOR UPDATE, and a sibling refund's completion must take the same lock, so
                    // the two sums are read in the order the completions commit. (This named a
                    // FOR UPDATE on the attempt row until the Phase 6 -> 7 transition; no
                    // resolver takes one.)
                    Money refundedBefore =
                            refunds.sumCompletedFor(
                                            uow, refund.attemptId(), refund.amount().currency())
                                    .minus(refund.amount());

                    // THE LINES ARE COMPOSED, NOT WRITTEN HERE (P6-TSK-014, ADR-0050 section
                    // 6). A wallet top-up's refund reverses in two (DR wallet / CR clearing);
                    // a merchant-bound refund returns the gross out of the PAYABLE and, under
                    // a RETURNED policy, gives the merchant back its share of the fee - four
                    // lines. Which it is depends on the flow that created the intent, and
                    // this module deliberately cannot tell.
                    postings.post(
                            uow,
                            new PostingCommand(
                                    "payment-refund:" + refund.id().value(),
                                    today,
                                    today,
                                    refund.id().value().toString(),
                                    refundComposition.settle(
                                            uow,
                                            new RefundSettlement(
                                                    intentId,
                                                    refund.attemptId(),
                                                    refund.id(),
                                                    counterpart,
                                                    wallet,
                                                    refund.amount(),
                                                    refundedBefore,
                                                    correlation,
                                                    now))));
                    // The terminal fact publishes with the transition that commits it
                    // (INV-EVT-01) - inside the conditional, so a duplicate emits nothing.
                    announceRefund(
                            uow, REFUND_COMPLETED_EVENT_TYPE, refund, intentId,
                            correlation, now);
                }
                committed = RefundStatus.COMPLETED;
            }
            case DECLINED, NOTHING_SENT -> {
                acting = refunds.fail(uow, refund.id(), from);
                if (acting) {
                    refunds.recordTransition(
                            uow, refund.id(), from, RefundStatus.FAILED, platform, now);
                    // THE ATTEMPT, LOCKED, BEFORE THE HOLD'S ACCOUNT (P7-TSK-013): a chargeback
                    // standing on this attempt may have counted this refund as non-failed and
                    // parked the double-take it assumed; under the lock the chargeback judges
                    // with, that excess comes back to the counterparty below. Attempt, then
                    // account - the order the dispatch keeps - so the two cannot deadlock.
                    Optional<PaymentAttempt> disputable =
                            chargebacks.lockIfDisputable(uow, refund.attemptId());
                    // The customer's money is theirs again, and the freed budget is the sum
                    // bound's own arithmetic (a FAILED refund no longer counts).
                    holds.release(uow, refund.holdReference());
                    // ADR-0061 section 3's last rule: the money this refund was counted as
                    // returning never went back, so its share of any chargeback's excess is the
                    // counterparty's again - under the same lock, in this same transaction.
                    disputable.ifPresent(
                            locked ->
                                    chargebacks.refundFailed(
                                            uow, locked, refund, intentId, correlation, now));
                    // A refund's failure is a terminal fact and publishes (ADR-0044's
                    // doctrine, plan §10 in as many words).
                    announceRefund(
                            uow, REFUND_FAILED_EVENT_TYPE, refund, intentId, correlation,
                            now);
                }
                committed = RefundStatus.FAILED;
            }
            default -> {
                // INDETERMINATE: UNKNOWN commits and the HOLD STANDS - nothing released,
                // nothing posted, the parked money visible (INV-LIFE-03).
                acting = refunds.markUnknown(uow, refund.id());
                if (acting) {
                    refunds.recordTransition(
                            uow,
                            refund.id(),
                            RefundStatus.DISPATCHED,
                            RefundStatus.UNKNOWN,
                            platform,
                            now);
                }
                committed = RefundStatus.UNKNOWN;
            }
        }

        if (!acting) {
            // A converged application moved nothing: the row's truth, and no record of an
            // outcome this call did not apply (the attempt's rule, the Phase 6 -> 7 transition).
            return new RefundApplied(
                    refunds.findById(uow, refund.id()).map(Refund::status).orElse(committed),
                    false);
        }
        audit.append(
                uow,
                new AuditRecord(
                        AuditId.next(ids),
                        platform,
                        now,
                        PaymentsAuditAction.PAYMENT_OUTCOME_APPLIED,
                        PaymentCreation.TARGET_TYPE,
                        intentId.value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of(
                                "refund=" + refund.id()
                                        + ", verdict=" + verdict
                                        + ", refundStatus=" + committed)));
        return new RefundApplied(committed, true);
    }

    /** The attempt fails with its mapped reason from {@code from}, and the intent with it. */
    /** The failing edge's answer: the committed status, and whether this call made it. */
    private record Failed(PaymentAttemptStatus status, boolean acting) {}

    private Failed failBoth(
            Connection uow,
            PaymentIntentId intentId,
            PaymentAttemptId attemptId,
            PaymentAttemptStatus from,
            PaymentFailureReason reason,
            Correlation correlation,
            Actor platform,
            Instant now) {
        boolean acting = attempts.fail(uow, attemptId, from, reason);
        if (acting) {
            attempts.recordTransition(
                    uow, attemptId, from, PaymentAttemptStatus.FAILED, platform, now);
            if (intents.transition(
                    uow, intentId, PaymentIntentStatus.PROCESSING, PaymentIntentStatus.FAILED)) {
                intents.recordTransition(
                        uow,
                        intentId,
                        PaymentIntentStatus.PROCESSING,
                        PaymentIntentStatus.FAILED,
                        platform,
                        now);
            }
            announce(uow, FAILED_EVENT_TYPE, intentId, "FAILED", Optional.of(reason),
                    correlation, now);
        }
        return new Failed(PaymentAttemptStatus.FAILED, acting);
    }

    /**
     * What an attempt outcome answers (the Phase 6 → 7 transition): the states THIS call's own
     * conditional committed, recorded once — or, when a racing resolver's conditional won, the
     * row's truth and no record at all. Before this, a loser reported the verdict it held as if it
     * had been applied and appended an outcome record for a transition it never made: ten webhooks
     * racing one operation left ten records for one move, and a contradictory loser's record named
     * a state the row does not hold.
     */
    private Applied answered(
            Connection uow,
            PaymentIntentId intentId,
            PaymentAttemptId attemptId,
            String verdict,
            PaymentAttemptStatus committedAttempt,
            PaymentIntentStatus committedIntent,
            boolean acting,
            Actor platform,
            Correlation correlation,
            Instant now) {
        if (!acting) {
            PaymentAttempt current =
                    attempts.findById(uow, attemptId)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "an attempt an outcome was applied to"
                                                            + " exists"));
            PaymentIntent intent =
                    intents.findById(uow, intentId)
                            .orElseThrow(
                                    () ->
                                            new IllegalStateException(
                                                    "an attempt row's intent exists: V003's"
                                                            + " foreign key holds it"));
            return new Applied(intent.status(), current.status(), false);
        }
        appendOutcomeAudit(uow, intentId, attemptId, verdict, committedAttempt, committedIntent,
                platform, correlation, now);
        return new Applied(committedIntent, committedAttempt, true);
    }

    /** Verdict and committed states as enumerated names — never an amount, never provider vocabulary. */
    private void appendOutcomeAudit(
            Connection uow,
            PaymentIntentId intentId,
            PaymentAttemptId attemptId,
            String verdict,
            PaymentAttemptStatus committedAttempt,
            PaymentIntentStatus committedIntent,
            Actor platform,
            Correlation correlation,
            Instant now) {
        audit.append(
                uow,
                new AuditRecord(
                        AuditId.next(ids),
                        platform,
                        now,
                        PaymentsAuditAction.PAYMENT_OUTCOME_APPLIED,
                        PaymentCreation.TARGET_TYPE,
                        intentId.value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation.correlationId(),
                        Optional.of(
                                "attempt=" + attemptId
                                        + ", verdict=" + verdict
                                        + ", attemptStatus=" + committedAttempt
                                        + ", intentStatus=" + committedIntent)));
    }

    /** The outcome's event, in the transaction that commits the fact ({@code INV-EVT-01}). */
    /**
     * The dispatch's own fact (`P5-TSK-016`), written by {@code PaymentRefund}'s Tx1 in the
     * transaction that commits the dispatch — this class already holds the outbox and the
     * vocabulary, so the refund command announces through it rather than growing its own
     * envelope-building copy.
     */
    void announceRefundInitiated(
            Connection uow, Refund refund, PaymentIntentId intentId, Correlation correlation,
            Instant now) {
        announceRefund(uow, REFUND_INITIATED_EVENT_TYPE, refund, intentId, correlation, now);
    }

    /**
     * Identifiers and enumerated names only — never an amount, never provider vocabulary
     * ({@code INV-AUD-02}'s reasoning applied to events; plan §10). The refund is the
     * aggregate; the intent and attempt ride as identifiers for consumers' joins.
     */
    private void announceRefund(
            Connection uow,
            String eventType,
            Refund refund,
            PaymentIntentId intentId,
            Correlation correlation,
            Instant now) {
        outbox.write(
                uow,
                new EventEnvelope(
                        EventId.next(ids),
                        eventType,
                        PaymentCreation.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        refund.id(),
                        "refund",
                        now,
                        PaymentCreation.PRODUCER,
                        correlation.correlationId(),
                        correlation.cause().orElseThrow()),
                EventPayload.of()
                        .with("status", statusFor(eventType))
                        .with("intentId", intentId.value().toString())
                        .with("attemptId", refund.attemptId().value().toString())
                        .toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    private static String statusFor(String refundEventType) {
        return switch (refundEventType) {
            case REFUND_INITIATED_EVENT_TYPE -> RefundStatus.DISPATCHED.name();
            case REFUND_COMPLETED_EVENT_TYPE -> RefundStatus.COMPLETED.name();
            case REFUND_FAILED_EVENT_TYPE -> RefundStatus.FAILED.name();
            default -> throw new IllegalArgumentException(
                    refundEventType + " is not a refund event type");
        };
    }

    private void announce(
            Connection uow,
            String eventType,
            PaymentIntentId intentId,
            String status,
            Optional<PaymentFailureReason> reason,
            Correlation correlation,
            Instant now) {
        EventPayload payload = EventPayload.of().with("status", status);
        if (reason.isPresent()) {
            payload = payload.with("failureReason", reason.get().name());
        }
        outbox.write(
                uow,
                new EventEnvelope(
                        EventId.next(ids),
                        eventType,
                        PaymentCreation.EVENT_VERSION,
                        EventEnvelope.CURRENT_SCHEMA_VERSION,
                        intentId,
                        PaymentCreation.TARGET_TYPE,
                        now,
                        PaymentCreation.PRODUCER,
                        correlation.correlationId(),
                        correlation.cause().orElseThrow()),
                payload.toBytes(),
                EventPayload.MEDIA_TYPE);
    }

    /**
     * The clearing position of the rail the <strong>stored</strong> attempt names
     * (`P7-TSK-001`, ADR-0059 §4) — read off the row, never off the resolving instance's
     * wiring, because any instance may carry this outcome and the posting must land where the
     * dispatch decided ({@code INV-RAIL-04}'s shape, one rail early). Both refusals are wiring
     * faults and fail this transaction loudly before a line posts to a guessed account.
     */
    /**
     * The execution's settle block, shared by every producer of an {@code EXECUTED} money
     * fact (`P7-TSK-011` extracted it from the `P7-TSK-009` acting branch, unchanged): the
     * {@code payment-execution:<attemptId>} posting through the composing flow — which
     * completes a checkout session and births its order in THIS transaction — the intent's
     * conditional {@code PROCESSING → SUCCEEDED}, and the executed fact announced.
     *
     * @param counterpart the account facing the credit in the entry: the STORED rail's
     *     declared clearing position for an external rail ({@code INV-RAIL-04}), or — on
     *     the book rail — the intent's own debit wallet: the platform pays itself, so the
     *     counterpart is the payer's account, never a clearing (ADR-0059 §§4/6)
     */
    private void settleExecution(
            Connection uow,
            PaymentIntentId intentId,
            PaymentAttemptId attemptId,
            com.finapp.ledger.LedgerAccountId counterpart,
            com.finapp.ledger.LedgerAccountId credit,
            Money amount,
            Actor actor,
            Correlation correlation,
            Instant now) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        CaptureSettlement settlement =
                new CaptureSettlement(
                        intentId, attemptId, counterpart, credit, amount, correlation, now);
        com.finapp.ledger.PostingResult posted =
                postings.post(
                        uow,
                        new PostingCommand(
                                EXECUTION_POSTING_PREFIX + attemptId.value(),
                                today,
                                today,
                                attemptId.value().toString(),
                                composition.settle(uow, settlement)));
        // The composing flow's second moment (P6-TSK-007): a checkout session completes -
        // LATE when it expired first (INV-MER-06's second rail) - and its order is born,
        // in THIS transaction.
        composition.settled(uow, settlement, posted.entryId().value());

        if (intents.transition(
                uow, intentId, PaymentIntentStatus.PROCESSING, PaymentIntentStatus.SUCCEEDED)) {
            intents.recordTransition(
                    uow,
                    intentId,
                    PaymentIntentStatus.PROCESSING,
                    PaymentIntentStatus.SUCCEEDED,
                    actor,
                    now);
        }
        announce(uow, EXECUTED_EVENT_TYPE, intentId, "EXECUTED",
                Optional.empty(), correlation, now);
    }

    /**
     * The book rail's first half (`P7-TSK-011`, ADR-0059 §6), inside the CALLER's
     * transaction — the confirmation's — because a book payment is final on posting and has
     * no other moment: the fixed-order pair lock, liveness and availability judged under the
     * wallet's own lock, the attempt born {@code EXECUTED}. The caller then records the
     * dispatch and calls {@link #settleBook} in the SAME transaction, so the trail reads
     * confirmation-then-outcome as every rail's does (the gate's find: one method that
     * settled first wrote the outcome's audit and {@code PaymentExecuted} BEFORE the
     * confirmation's record and {@code RailSelected}). Commits whole or not at all; there is
     * deliberately no {@code enterSystem} here — the acting person IS the actor, because no
     * resolver ever finishes a book payment for them.
     *
     * <p><strong>The pair lock is load-bearing</strong> (`P4-TST-001`'s 783-deadlock
     * measurement): a book payment explicitly holds the wallet and its posting takes
     * {@code FOR KEY SHARE} on the payable; a book refund explicitly holds the payable and
     * its posting touches the wallet. Unordered, that is the AB/BA cycle the transfer
     * measured — so both book flows lock both participants first, in
     * {@code UUID.compareTo} order (every instance agreeing on ONE order is the whole
     * requirement).
     *
     * @throws com.finapp.ledger.HoldExceedsAvailableBalanceException the wallet cannot fund
     *     the payment now ({@code INV-BAL-04}) — thrown with nothing written, the caller's
     *     transaction rolls back whole and the intent still awaits confirmation
     *     (the confirmation is keyless: it converges by the intent's state)
     */
    public BookDispatch dispatchBook(Connection uow, PaymentIntent intent, RailId rail) {
        Money amount = intent.amount();
        com.finapp.ledger.LedgerAccountId wallet =
                intent.debitAccount()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a book execution's intent carries its debit"
                                                        + " wallet: V019's XOR holds it for"
                                                        + " every writer (P7-TSK-011)"));

        lockPairInFixedOrder(uow, wallet, intent.creditAccount());
        // BOTH rows verified ACTIVE under the locks just taken - the creditable FOR SHARE's
        // fact at the stronger rank (the confirmation deliberately skips that read on this
        // branch: SHARE-then-UPDATE on one row across ten racers is the measured cycle).
        requireActive(uow, wallet);
        requireActive(uow, intent.creditAccount());

        // AVAILABILITY under the wallet's lock (INV-BAL-04, INV-BAL-05): the withdrawal's
        // own judgement - place proves affordability against settled minus ACTIVE holds
        // and throws with nothing written - compressed to one commit: the release frees
        // the reservation beside the posting that takes the money for good, and the rows
        // it leaves are the same auditable trace the withdrawal leaves over minutes.
        com.finapp.ledger.Hold funded = holds.place(uow, wallet, amount);

        PaymentAttempt attempt = PaymentAttempt.createBook(ids, clock, intent.id(), rail);
        attempts.insert(uow, attempt);
        return new BookDispatch(attempt, funded.id());
    }

    /**
     * The book payment's second half (`P7-TSK-011`), in the SAME transaction as
     * {@link #dispatchBook} and after the caller has recorded the dispatch - so the audit
     * trail and the outbox read causally, confirmation before outcome, exactly as every
     * other rail's do: the funded hold released beside the posting that takes the money
     * for good, the shared settle block (session completion, order, {@code SUCCEEDED},
     * {@code PaymentExecuted}), and the outcome's own audit record.
     */
    public void settleBook(
            Connection uow, PaymentIntent intent, BookDispatch dispatched,
            Correlation correlation) {
        Actor person = SecurityContext.require();
        Instant now = Instant.now(clock);
        Money amount = intent.amount();
        PaymentAttempt attempt = dispatched.attempt();
        com.finapp.ledger.LedgerAccountId wallet = intent.debitAccount().orElseThrow();
        holds.release(uow, dispatched.funded());
        settleExecution(
                uow, intent.id(), attempt.id(), wallet, intent.creditAccount(), amount,
                person, correlation, now);
        // The outcome audited like every rail's (the P7-TSK-011 gate's find): the card and
        // push rails record PaymentOutcomeApplied for each acting outcome, and a book
        // payment's money movement deserves the same record - under the PERSON, whose own
        // act moved the money, never a platform actor claimed for a resolver that does not
        // exist. The confirmation's audit says a dispatch happened; this one says what it
        // committed.
        appendOutcomeAudit(
                uow, intent.id(), attempt.id(), "BOOK_POSTED", PaymentAttemptStatus.EXECUTED,
                PaymentIntentStatus.SUCCEEDED, person, correlation, now);
    }

    /**
     * A judged, funded book attempt awaiting its settle (`P7-TSK-011`): the attempt born
     * {@code EXECUTED} and the hold that proved the wallet could fund it, carried between
     * the two halves of ONE transaction - never across a commit.
     */
    public record BookDispatch(PaymentAttempt attempt, com.finapp.ledger.HoldId funded) {
        public BookDispatch {
            java.util.Objects.requireNonNull(attempt, "attempt must not be null");
            java.util.Objects.requireNonNull(funded, "funded must not be null");
        }
    }

    /**
     * Both participating account rows {@code FOR UPDATE}, in {@code UUID.compareTo} order —
     * the transfer's `lockBothInFixedOrder`, at the book rail (`P4-TST-001`'s remedy; the
     * order need not be the database's, only agreed by every instance). Package-private:
     * {@code PaymentRefund}'s book arm takes the same pair before its payable hold, because
     * the book refund is the cycle's OTHER direction.
     */
    void lockPairInFixedOrder(
            Connection uow,
            com.finapp.ledger.LedgerAccountId debit,
            com.finapp.ledger.LedgerAccountId credit) {
        boolean debitFirst = debit.value().compareTo(credit.value()) <= 0;
        lockOrThrow(uow, debitFirst ? debit : credit);
        if (!debit.equals(credit)) {
            lockOrThrow(uow, debitFirst ? credit : debit);
        }
    }

    private void lockOrThrow(Connection uow, com.finapp.ledger.LedgerAccountId account) {
        ledgerAccounts
                .lockForUpdate(uow, account)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "account " + account + " resolved and then vanished -"
                                                + " an invariant is already broken"));
    }

    /** The locked row's liveness — a closed participant refuses with nothing written. */
    private void requireActive(Connection uow, com.finapp.ledger.LedgerAccountId account) {
        boolean active =
                ledgerAccounts
                        .lockForUpdate(uow, account)
                        .map(
                                row ->
                                        row.status()
                                                == com.finapp.ledger.LedgerAccountStatus.ACTIVE)
                        .orElse(false);
        if (!active) {
            throw new NoWalletForPaymentException();
        }
    }

    /**
     * The account facing the refund's debit side (`P7-TSK-010`/`P7-TSK-011`): the STORED
     * rail's declared clearing position, resolved through the chart — or, on the book
     * rail, the intent's own debit wallet, because the compensating movement returns the
     * money where it came from and nothing external stands between (ADR-0059 §§4/6).
     */
    private com.finapp.ledger.LedgerAccountId refundCounterpartOf(
            Connection uow, PaymentIntentId intentId, Refund refund) {
        RailId rail =
                attempts.findById(uow, refund.attemptId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a refund outcome is being applied to attempt "
                                                        + refund.attemptId()
                                                        + ", which no longer reads back"))
                        .rail();
        Optional<AccountPurpose> declared = rails.capabilitiesOf(rail).clearingPurpose();
        if (declared.isPresent()) {
            return chart.resolve(uow, declared.get(), refund.amount().currency()).id();
        }
        return intents.findById(uow, intentId)
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "a refund outcome's intent exists: V003's foreign key"
                                                + " holds the attempt to it"))
                .debitAccount()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "a book refund's intent carries its debit wallet:"
                                                + " V019's XOR holds it for every writer"
                                                + " (P7-TSK-011)"));
    }

    private AccountPurpose clearingPurposeOf(
            Connection uow, PaymentAttemptId attemptId) {
        RailId rail =
                attempts.findById(uow, attemptId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "an outcome is being applied to attempt "
                                                        + attemptId
                                                        + ", which no longer reads back"))
                        .rail();
        return rails.capabilitiesOf(rail)
                .clearingPurpose()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "rail '" + rail.value() + "' declares no clearing"
                                                + " position: a book rail's completion posts"
                                                + " directly, and resolving clearing for one"
                                                + " is a wiring fault (ADR-0059 section 4)"));
    }
}
