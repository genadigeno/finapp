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
import java.util.Objects;
import java.util.Optional;

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
public final class PaymentOutcomes {

    static final String AUTHORIZED_EVENT_TYPE = "payments.PaymentAuthorized";
    static final String CAPTURED_EVENT_TYPE = "payments.PaymentCaptured";
    static final String FAILED_EVENT_TYPE = "payments.PaymentFailed";
    static final String UNKNOWN_EVENT_TYPE = "payments.PaymentStateUnknown";

    private final PaymentIntentStore<Connection> intents;
    private final PaymentAttemptStore<Connection> attempts;
    private final RefundStore<Connection> refunds;
    private final com.finapp.ledger.HoldService holds;
    private final PostingService postings;
    private final ChartOfAccounts<Connection> chart;
    private final AuditWriter<Connection> audit;
    private final OutboxWriter<Connection> outbox;
    private final IdGenerator ids;
    private final Clock clock;

    public PaymentOutcomes(
            PaymentIntentStore<Connection> intents,
            PaymentAttemptStore<Connection> attempts,
            RefundStore<Connection> refunds,
            com.finapp.ledger.HoldService holds,
            PostingService postings,
            ChartOfAccounts<Connection> chart,
            AuditWriter<Connection> audit,
            OutboxWriter<Connection> outbox,
            IdGenerator ids,
            Clock clock) {
        this.intents = Objects.requireNonNull(intents, "intents must not be null");
        this.attempts = Objects.requireNonNull(attempts, "attempts must not be null");
        this.refunds = Objects.requireNonNull(refunds, "refunds must not be null");
        this.holds = Objects.requireNonNull(holds, "holds must not be null");
        this.postings = Objects.requireNonNull(postings, "postings must not be null");
        this.chart = Objects.requireNonNull(chart, "chart must not be null");
        this.audit = Objects.requireNonNull(audit, "audit must not be null");
        this.outbox = Objects.requireNonNull(outbox, "outbox must not be null");
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /** What committed (or was found committed by the loser of a harmless race). */
    public record Applied(PaymentIntentStatus intent, PaymentAttemptStatus attempt) {}

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
        switch (verdict) {
            case APPROVED -> {
                if (attempts.authorize(
                        uow, attemptId, from, providerReference.orElseThrow(), promisedAmount)) {
                    attempts.recordTransition(
                            uow, attemptId, from, PaymentAttemptStatus.AUTHORIZED, platform, now);
                    announce(uow, AUTHORIZED_EVENT_TYPE, intentId, "AUTHORIZED",
                            Optional.empty(), correlation, now);
                }
                committedAttempt = PaymentAttemptStatus.AUTHORIZED;
            }
            case DECLINED -> {
                committedAttempt =
                        failBoth(uow, intentId, attemptId, from, PaymentFailureReason.DECLINED,
                                correlation, platform, now);
                committedIntent = PaymentIntentStatus.FAILED;
            }
            case NOTHING_SENT -> {
                committedAttempt =
                        failBoth(uow, intentId, attemptId, from,
                                PaymentFailureReason.PROVIDER_UNAVAILABLE, correlation,
                                platform, now);
                committedIntent = PaymentIntentStatus.FAILED;
            }
            default -> {
                // INDETERMINATE: we do not know is the answer, and it commits (INV-LIFE-03).
                // Only a DISPATCHED source can become UNKNOWN - the conditional refuses the
                // rest, which is what makes an already-unknown attempt converge here.
                if (attempts.markAuthUnknown(uow, attemptId)) {
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
        return new Applied(committedIntent, committedAttempt);
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
        switch (verdict) {
            case APPROVED -> {
                if (attempts.capture(
                        uow, attemptId, from, providerReference.orElseThrow(), amount)) {
                    attempts.recordTransition(
                            uow, attemptId, from, PaymentAttemptStatus.CAPTURED, platform, now);

                    // THE POSTING - same connection, atomically with the transition
                    // (ADR-0048). DR clearing / CR wallet; no savepoint, deliberately: a
                    // posting failure fails this whole transaction loudly, whichever
                    // resolver carried the outcome.
                    LedgerAccount clearing =
                            chart.resolve(
                                    uow, AccountPurpose.SETTLEMENT_CLEARING, amount.currency());
                    LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
                    postings.post(
                            uow,
                            new PostingCommand(
                                    "payment-capture:" + attemptId.value(),
                                    today,
                                    today,
                                    attemptId.value().toString(),
                                    List.of(
                                            new JournalLine(
                                                    clearing.id(), Direction.DEBIT, amount),
                                            new JournalLine(
                                                    wallet, Direction.CREDIT, amount))));

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
                committedAttempt =
                        failBoth(uow, intentId, attemptId, from, PaymentFailureReason.DECLINED,
                                correlation, platform, now);
                committedIntent = PaymentIntentStatus.FAILED;
            }
            case NOTHING_SENT -> {
                committedAttempt =
                        failBoth(uow, intentId, attemptId, from,
                                PaymentFailureReason.PROVIDER_UNAVAILABLE, correlation,
                                platform, now);
                committedIntent = PaymentIntentStatus.FAILED;
            }
            default -> {
                // INDETERMINATE: CAPTURE_UNKNOWN commits with NOTHING POSTED (INV-LIFE-03) -
                // the posting arrives only with a resolved CAPTURED.
                if (attempts.markCaptureUnknown(uow, attemptId)) {
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
        return new Applied(committedIntent, committedAttempt);
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
        PaymentAttemptStatus committedAttempt =
                failBoth(uow, intentId, attemptId, from, PaymentFailureReason.NEVER_RECEIVED,
                        correlation, platform, now);
        appendOutcomeAudit(uow, intentId, attemptId, QueryAnswer.Verdict.UNRECOGNISED.name(),
                committedAttempt, PaymentIntentStatus.FAILED, platform, correlation, now);
        return new Applied(PaymentIntentStatus.FAILED, committedAttempt);
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
    public RefundStatus applyRefund(
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
        switch (verdict) {
            case APPROVED -> {
                if (refunds.complete(uow, refund.id(), from, providerReference.orElseThrow())) {
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
                    postings.post(
                            uow,
                            new PostingCommand(
                                    "payment-refund:" + refund.id().value(),
                                    today,
                                    today,
                                    refund.id().value().toString(),
                                    List.of(
                                            new JournalLine(
                                                    wallet, Direction.DEBIT, refund.amount()),
                                            new JournalLine(
                                                    clearing.id(),
                                                    Direction.CREDIT,
                                                    refund.amount()))));
                }
                committed = RefundStatus.COMPLETED;
            }
            case DECLINED, NOTHING_SENT -> {
                if (refunds.fail(uow, refund.id(), from)) {
                    refunds.recordTransition(
                            uow, refund.id(), from, RefundStatus.FAILED, platform, now);
                    // The customer's money is theirs again, and the freed budget is the sum
                    // bound's own arithmetic (a FAILED refund no longer counts).
                    holds.release(uow, refund.holdReference());
                }
                committed = RefundStatus.FAILED;
            }
            default -> {
                // INDETERMINATE: UNKNOWN commits and the HOLD STANDS - nothing released,
                // nothing posted, the parked money visible (INV-LIFE-03).
                if (refunds.markUnknown(uow, refund.id())) {
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
        return committed;
    }

    /** The attempt fails with its mapped reason from {@code from}, and the intent with it. */
    private PaymentAttemptStatus failBoth(
            Connection uow,
            PaymentIntentId intentId,
            PaymentAttemptId attemptId,
            PaymentAttemptStatus from,
            PaymentFailureReason reason,
            Correlation correlation,
            Actor platform,
            Instant now) {
        if (attempts.fail(uow, attemptId, from, reason)) {
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
        return PaymentAttemptStatus.FAILED;
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
