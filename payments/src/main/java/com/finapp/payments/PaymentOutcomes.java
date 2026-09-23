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
@RequiredArgsConstructor
public final class PaymentOutcomes {

    static final String AUTHORIZED_EVENT_TYPE = "payments.PaymentAuthorized";
    static final String CAPTURED_EVENT_TYPE = "payments.PaymentCaptured";
    static final String FAILED_EVENT_TYPE = "payments.PaymentFailed";
    static final String UNKNOWN_EVENT_TYPE = "payments.PaymentStateUnknown";
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
            PaymentIntentStatus intent, PaymentAttemptStatus attempt, boolean acting) {}

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

        appendOutcomeAudit(uow, intentId, attemptId, verdict.name(), committedAttempt,
                committedIntent, platform, correlation, now);
        return new Applied(committedIntent, committedAttempt, acting);
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
                                    uow, AccountPurpose.SETTLEMENT_CLEARING, amount.currency());
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

        appendOutcomeAudit(uow, intentId, attemptId, verdict.name(), committedAttempt,
                committedIntent, platform, correlation, now);
        return new Applied(committedIntent, committedAttempt, acting);
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
        appendOutcomeAudit(uow, intentId, attemptId, QueryAnswer.Verdict.UNRECOGNISED.name(),
                failed.status(), PaymentIntentStatus.FAILED, platform, correlation, now);
        return new Applied(PaymentIntentStatus.FAILED, failed.status(), failed.acting());
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
                    // structurally unable to post twice.
                    LedgerAccount clearing =
                            chart.resolve(
                                    uow,
                                    AccountPurpose.SETTLEMENT_CLEARING,
                                    refund.amount().currency());
                    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));

                    // WHAT HAD ALREADY BEEN RETURNED, EXCLUDING THIS REFUND (`P6-TSK-014`).
                    // The completed sum is read AFTER this refund's own conditional fired, so
                    // it includes this one; the composer is handed the total BEFORE it,
                    // because the difference of two cumulative allocations is the whole of
                    // the proportional arithmetic and neither side of that subtraction may
                    // be guessed. Valid under the command's FOR UPDATE on the attempt row.
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
                                                    clearing.id(),
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
                    // The customer's money is theirs again, and the freed budget is the sum
                    // bound's own arithmetic (a FAILED refund no longer counts).
                    holds.release(uow, refund.holdReference());
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
        return new RefundApplied(committed, acting);
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
}
