package com.finapp.app.bnpl;

import com.finapp.app.api.DecimalText;
import com.finapp.bnpl.AllocationAnomaly;
import com.finapp.bnpl.BnplAgreementId;
import com.finapp.bnpl.BnplErrorCode;
import com.finapp.bnpl.BnplReasons;
import com.finapp.bnpl.BnplRepaymentReversal;
import com.finapp.bnpl.BnplRepaymentReversals;
import com.finapp.bnpl.BnplWaiver;
import com.finapp.bnpl.BnplWaivers;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the BNPL servicing corrections (`P12-TSK-024`; {@code PHASE_12_PLAN.md} sections 9 and 11,
 * {@code BNPL_LIFECYCLES.md} section 4.11; lending's {@code LoanServicingDesk} and {@code LoanWaiverDesk}): the repayment
 * reversal's and the fee waiver's proposal, approval and rejection - each keyed per principal
 * ({@code bnpl.servicing:<actor type>:<id>} - {@code CUSTOMER}-typed for an operator session today, as every operator door
 * of the platform), run in one transaction under the {@link IdempotentExecutor} claim, so a retried key replays the recorded
 * receipt and a refusal, which writes nothing, rolls the claim back with it. The four-eyes rule, the machines, the lock
 * order and the arbiters are the domain's and {@code bnpl V016}'s; nothing here decides them.
 *
 * <p><strong>Self-dealing</strong> and <strong>self-approval</strong> (the Phase 11 gate's R4) are the refusals recorded: a
 * {@code bnpl.SelfDealingRefused} or {@code bnpl.SelfApprovalRefused} {@code FAILED} record in a transaction of its own after
 * the act rolled back - so the attempt survives. Every other loser records nothing. An operator acts on any customer's
 * agreement by design, so an unknown or malformed identifier - of the agreement, the repayment, the fee or the proposal -
 * is the uniform {@code 404 bnpl.NotFound}. The meters are told after the commit, once; a replay counts nothing.
 */
@RequiredArgsConstructor
public final class BnplCorrectionDesk {

    static final String SCOPE = "bnpl.servicing:";

    @NonNull private final BnplRepaymentReversals reversals;
    @NonNull private final BnplWaivers waivers;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final BnplTransactions transactions;
    @NonNull private final BnplCorrectionMetrics metrics;

    /**
     * A repayment reversal as answered - and replayed: its machine, the repayment and the agreement version that repayment
     * was allocated under; once approved, F8's journal entry and its value date. Never a reason and never an amount.
     */
    public record BnplReversalView(
            String reversalId,
            String agreementId,
            String repaymentId,
            String status,
            int agreementVersion,
            String proposedAt,
            String decidedAt,
            String journalEntryId,
            String valueDate) {}

    /**
     * A fee waiver as answered - and replayed: its machine, the fee and its instalment, the amount (an exact decimal), the
     * agreement version; once approved, F7's journal entry and any closure it brought. Never a reason.
     */
    public record BnplWaiverView(
            String waiverId,
            String agreementId,
            String kind,
            String assessmentId,
            String instalmentId,
            String amount,
            String currency,
            String status,
            int agreementVersion,
            String proposedAt,
            String decidedAt,
            String journalEntryId,
            String closure) {}

    // ------------------------------------------------------------------ the reversal's acts

    /** Proposes the reversal of a repayment - a holder of {@code BNPL_SERVICE}; nothing is posted. */
    public BnplReversalView proposeReversal(String rawAgreementId, String rawRepaymentId, String idempotencyKey,
            String reason) {
        BnplAgreementId agreement = agreementId(rawAgreementId);
        UUID repayment = id(rawRepaymentId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return reversalKeyed(actor, correlation, idempotencyKey, BnplCorrectionMetrics.ReversalOutcome.PROPOSED,
                String.join("|", "reversal:propose", agreement.value().toString(), repayment.toString(),
                        String.valueOf(reason)),
                uow -> new ActedReversal(reversals.propose(uow, agreement, repayment, reason, actor, correlation),
                        false));
    }

    /** Approves a pending reversal - a holder of {@code BNPL_SERVICE_APPROVE} other than its proposer; posts F8. */
    public BnplReversalView approveReversal(String rawAgreementId, String rawRepaymentId, String idempotencyKey,
            BnplCorrectionController.BnplReversalDecisionBody body) {
        BnplAgreementId agreement = agreementId(rawAgreementId);
        UUID repayment = id(rawRepaymentId);
        UUID reversal = id(body.reversalId());
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return reversalKeyed(actor, correlation, idempotencyKey, BnplCorrectionMetrics.ReversalOutcome.APPROVED,
                String.join("|", "reversal:approve", agreement.value().toString(), repayment.toString(),
                        reversal.toString(), String.valueOf(body.reason())),
                uow -> decided(reversals.approve(uow, agreement, repayment, reversal, body.reason(), actor,
                        correlation)));
    }

    /** Rejects a pending reversal - a holder of {@code BNPL_SERVICE}, the proposer's withdrawal included. */
    public BnplReversalView rejectReversal(String rawAgreementId, String rawRepaymentId, String idempotencyKey,
            BnplCorrectionController.BnplReversalDecisionBody body) {
        BnplAgreementId agreement = agreementId(rawAgreementId);
        UUID repayment = id(rawRepaymentId);
        UUID reversal = id(body.reversalId());
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return reversalKeyed(actor, correlation, idempotencyKey, BnplCorrectionMetrics.ReversalOutcome.REJECTED,
                String.join("|", "reversal:reject", agreement.value().toString(), repayment.toString(),
                        reversal.toString(), String.valueOf(body.reason())),
                uow -> decided(reversals.reject(uow, agreement, repayment, reversal, body.reason(), actor,
                        correlation)));
    }

    // ------------------------------------------------------------------ the waiver's acts

    /** Proposes waiving an amount of one fee - a holder of {@code BNPL_SERVICE}; nothing is posted. */
    public BnplWaiverView proposeWaiver(String rawAgreementId, String idempotencyKey,
            BnplCorrectionController.BnplWaiverProposalBody body) {
        BnplAgreementId agreement = agreementId(rawAgreementId);
        UUID assessment = id(body.assessmentId());
        Money amount = amount(body.amount(), body.currency());
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return waiverKeyed(actor, correlation, idempotencyKey, BnplCorrectionMetrics.WaiverOutcome.PROPOSED,
                String.join("|", "waiver:propose", agreement.value().toString(), assessment.toString(),
                        Long.toString(amount.minorUnits()), Integer.toString(amount.scale()), amount.currency().code(),
                        String.valueOf(body.reason())),
                uow -> new ActedWaiver(waivers.propose(uow, agreement, assessment, amount, body.reason(), actor,
                        correlation), false, null));
    }

    /** Approves a pending waiver - a holder of {@code BNPL_SERVICE_APPROVE} other than its proposer; posts F7. */
    public BnplWaiverView approveWaiver(String rawAgreementId, String rawWaiverId, String idempotencyKey,
            BnplCorrectionController.BnplWaiverDecisionBody body) {
        BnplAgreementId agreement = agreementId(rawAgreementId);
        UUID waiver = id(rawWaiverId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return waiverKeyed(actor, correlation, idempotencyKey, BnplCorrectionMetrics.WaiverOutcome.APPROVED,
                String.join("|", "waiver:approve", agreement.value().toString(), waiver.toString(),
                        String.valueOf(body.reason())),
                uow -> decided(waivers.approve(uow, agreement, waiver, body.reason(), actor, correlation)));
    }

    /** Rejects a pending waiver - a holder of {@code BNPL_SERVICE}, the proposer's withdrawal included. */
    public BnplWaiverView rejectWaiver(String rawAgreementId, String rawWaiverId, String idempotencyKey,
            BnplCorrectionController.BnplWaiverDecisionBody body) {
        BnplAgreementId agreement = agreementId(rawAgreementId);
        UUID waiver = id(rawWaiverId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return waiverKeyed(actor, correlation, idempotencyKey, BnplCorrectionMetrics.WaiverOutcome.REJECTED,
                String.join("|", "waiver:reject", agreement.value().toString(), waiver.toString(),
                        String.valueOf(body.reason())),
                uow -> decided(waivers.reject(uow, agreement, waiver, body.reason(), actor, correlation)));
    }

    // ------------------------------------------------------------------ plumbing

    /** What a reversal act did: the reversal it left, and whether the same person's retry converged. */
    private record ActedReversal(BnplRepaymentReversal reversal, boolean replayed) {}

    /** What a waiver act did: the waiver it left, whether it converged, and the closure's reason if it closed. */
    private record ActedWaiver(BnplWaiver waiver, boolean replayed, String closure) {}

    private static ActedReversal decided(BnplRepaymentReversals.Decided decided) {
        return new ActedReversal(decided.reversal(), decided.replayed());
    }

    private static ActedWaiver decided(BnplWaivers.Decided decided) {
        return new ActedWaiver(decided.waiver(), decided.replayed(),
                decided.closure().map(closure -> closure.reason().name()).orElse(null));
    }

    /**
     * One keyed reversal act in one transaction - a lost response replays the stored receipt; a refusal stores nothing.
     * The meter is told after the commit, once; a refusal is counted by reason; a self-dealing or self-approval attempt is
     * recorded {@code FAILED} in a transaction of its own.
     */
    private BnplReversalView reversalKeyed(Actor actor, CorrelationId correlation, String idempotencyKey,
            BnplCorrectionMetrics.ReversalOutcome success, String fingerprinted,
            Function<Connection, ActedReversal> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        AtomicReference<ActedReversal> acted = new AtomicReference<>();
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                ActedReversal done = act.apply(uow);
                acted.set(done);
                return CommandResult.succeeded(stored(view(done.reversal())));
            }));
        } catch (BnplRepaymentReversals.SelfDealingRefused selfDealing) {
            transactions.inTransaction(uow -> {
                reversals.recordRefusal(uow, selfDealing, actor, correlation);
                return null;
            });
            metrics.record(BnplCorrectionMetrics.ReversalOutcome.SELF_DEALING_REFUSED);
            throw refused(BnplErrorCode.SELF_DEALING_REFUSED, selfDealing);
        } catch (BnplRepaymentReversals.SelfApprovalRefused self) {
            transactions.inTransaction(uow -> {
                reversals.recordRefusal(uow, self, actor, correlation);
                return null;
            });
            metrics.record(BnplCorrectionMetrics.ReversalOutcome.SELF_APPROVAL_REFUSED);
            throw refused(BnplErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (BnplRepaymentReversals.NotFound unknown) {
            throw notFound();
        } catch (BnplReasons.ReasonRequired reason) {
            throw reasonRequired();
        } catch (BnplRepaymentReversals.ObligationClosed closed) {
            metrics.record(BnplCorrectionMetrics.ReversalOutcome.OBLIGATION_CLOSED);
            throw refused(BnplErrorCode.OBLIGATION_CLOSED, closed);
        } catch (BnplRepaymentReversals.ProposalPending pending) {
            metrics.record(BnplCorrectionMetrics.ReversalOutcome.PROPOSAL_PENDING);
            throw refused(BnplErrorCode.PROPOSAL_PENDING, pending);
        } catch (BnplRepaymentReversals.ProposalNotPending decided) {
            metrics.record(BnplCorrectionMetrics.ReversalOutcome.PROPOSAL_NOT_PENDING);
            throw refused(BnplErrorCode.PROPOSAL_NOT_PENDING, decided);
        } catch (BnplRepaymentReversals.RepaymentNotReversible notReversible) {
            metrics.record(BnplCorrectionMetrics.ReversalOutcome.NOT_REVERSIBLE);
            throw refused(BnplErrorCode.REPAYMENT_NOT_REVERSIBLE, notReversible);
        } catch (BnplRepaymentReversals.CapitalUnavailable unavailable) {
            metrics.record(BnplCorrectionMetrics.ReversalOutcome.CAPITAL_UNAVAILABLE);
            throw refused(BnplErrorCode.CAPITAL_UNAVAILABLE, unavailable);
        } catch (BnplRepaymentReversals.WalletUnavailable unavailable) {
            metrics.record(BnplCorrectionMetrics.ReversalOutcome.WALLET_UNAVAILABLE);
            throw refused(BnplErrorCode.WALLET_UNAVAILABLE, unavailable);
        } catch (AllocationAnomaly anomaly) {
            throw anomaly;
        }
        if (outcome.executed() && acted.get() != null && !acted.get().replayed()) {
            metrics.record(success);
        }
        return replayedReversal(outcome.body()
                .orElseThrow(() -> new IllegalStateException("a servicing act stores its receipt")));
    }

    /** One keyed waiver act in one transaction - the reversal's plumbing, with the waiver's refusals. */
    private BnplWaiverView waiverKeyed(Actor actor, CorrelationId correlation, String idempotencyKey,
            BnplCorrectionMetrics.WaiverOutcome success, String fingerprinted, Function<Connection, ActedWaiver> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        AtomicReference<ActedWaiver> acted = new AtomicReference<>();
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                ActedWaiver done = act.apply(uow);
                acted.set(done);
                return CommandResult.succeeded(stored(view(done.waiver(), done.closure())));
            }));
        } catch (BnplRepaymentReversals.SelfDealingRefused selfDealing) {
            transactions.inTransaction(uow -> {
                waivers.recordRefusal(uow, selfDealing, actor, correlation);
                return null;
            });
            metrics.record(BnplCorrectionMetrics.WaiverOutcome.SELF_DEALING_REFUSED);
            throw refused(BnplErrorCode.SELF_DEALING_REFUSED, selfDealing);
        } catch (BnplWaivers.SelfApprovalRefused self) {
            transactions.inTransaction(uow -> {
                waivers.recordRefusal(uow, self, actor, correlation);
                return null;
            });
            metrics.record(BnplCorrectionMetrics.WaiverOutcome.SELF_APPROVAL_REFUSED);
            throw refused(BnplErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (BnplRepaymentReversals.NotFound unknown) {
            throw notFound();
        } catch (BnplReasons.ReasonRequired reason) {
            throw reasonRequired();
        } catch (BnplWaivers.AmountInvalid invalid) {
            throw invalid("the amount must be a positive amount in the fee's currency, at its minor units.");
        } catch (BnplWaivers.ObligationClosed closed) {
            metrics.record(BnplCorrectionMetrics.WaiverOutcome.OBLIGATION_CLOSED);
            throw refused(BnplErrorCode.OBLIGATION_CLOSED, closed);
        } catch (BnplWaivers.ProposalPending pending) {
            metrics.record(BnplCorrectionMetrics.WaiverOutcome.PROPOSAL_PENDING);
            throw refused(BnplErrorCode.PROPOSAL_PENDING, pending);
        } catch (BnplWaivers.ProposalNotPending decided) {
            metrics.record(BnplCorrectionMetrics.WaiverOutcome.PROPOSAL_NOT_PENDING);
            throw refused(BnplErrorCode.PROPOSAL_NOT_PENDING, decided);
        } catch (BnplWaivers.WaiverExceedsDue exceeds) {
            metrics.record(BnplCorrectionMetrics.WaiverOutcome.EXCEEDS_DUE);
            throw refused(BnplErrorCode.WAIVER_EXCEEDS_DUE, exceeds);
        } catch (AllocationAnomaly anomaly) {
            throw anomaly;
        }
        ActedWaiver done = acted.get();
        if (outcome.executed() && done != null && !done.replayed()) {
            metrics.record(success == BnplCorrectionMetrics.WaiverOutcome.APPROVED && done.closure() != null
                    ? BnplCorrectionMetrics.WaiverOutcome.CLOSED : success);
        }
        return replayedWaiver(outcome.body()
                .orElseThrow(() -> new IllegalStateException("a servicing act stores its receipt")));
    }

    // ------------------------------------------------------------------ the views and the receipts

    static BnplReversalView view(BnplRepaymentReversal reversal) {
        return new BnplReversalView(reversal.id().toString(), reversal.agreement().value().toString(),
                reversal.repayment().toString(),
                reversal.status().name(), reversal.agreementVersion(), reversal.proposedAt().toString(),
                reversal.decidedAt().map(Instant::toString).orElse(null),
                reversal.entry().map(entry -> entry.value().toString()).orElse(null),
                reversal.valueDate().map(LocalDate::toString).orElse(null));
    }

    static BnplWaiverView view(BnplWaiver waiver, String closure) {
        return new BnplWaiverView(waiver.id().toString(), waiver.agreement().value().toString(), waiver.kind().name(),
                waiver.assessment().toString(), waiver.instalment().toString(),
                waiver.amount().toBigDecimal().toPlainString(), waiver.amount().currency().code(), waiver.status().name(),
                waiver.agreementVersion(), waiver.proposedAt().toString(),
                waiver.decidedAt().map(Instant::toString).orElse(null),
                waiver.entry().map(entry -> entry.value().toString()).orElse(null), closure);
    }

    /** The receipt: the view's fields, '|'-separated, an absent one empty. */
    static StoredResponse stored(BnplReversalView view) {
        String body = String.join("|", view.reversalId(), view.agreementId(), view.repaymentId(), view.status(),
                Integer.toString(view.agreementVersion()), view.proposedAt(), nullable(view.decidedAt()),
                nullable(view.journalEntryId()), nullable(view.valueDate()));
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    static StoredResponse stored(BnplWaiverView view) {
        String body = String.join("|", view.waiverId(), view.agreementId(), view.kind(), view.assessmentId(),
                view.instalmentId(), view.amount(), view.currency(), view.status(),
                Integer.toString(view.agreementVersion()), view.proposedAt(), nullable(view.decidedAt()),
                nullable(view.journalEntryId()), nullable(view.closure()));
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    static BnplReversalView replayedReversal(byte[] body) {
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        return new BnplReversalView(fields[0], fields[1], fields[2], fields[3], Integer.parseInt(fields[4]), fields[5],
                orNull(fields[6]), orNull(fields[7]), orNull(fields[8]));
    }

    static BnplWaiverView replayedWaiver(byte[] body) {
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        return new BnplWaiverView(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6], fields[7],
                Integer.parseInt(fields[8]), fields[9], orNull(fields[10]), orNull(fields[11]), orNull(fields[12]));
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }

    private static String orNull(String field) {
        return field.isEmpty() ? null : field;
    }

    private static ApiException refused(BnplErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A BNPL servicing correction was refused", cause.getMessage() + ".");
    }

    private static ApiException reasonRequired() {
        return new ApiException(BnplErrorCode.REASON_REQUIRED, "A reason is required",
                "a reason of 1 to " + BnplReasons.BOUND + " characters, holding no card or account number, is required.");
    }

    private static ApiException notFound() {
        return new ApiException(BnplErrorCode.NOT_FOUND, "No BNPL agreement, repayment, fee or proposal matches the"
                + " request", "no such record.");
    }

    private static ApiException invalid(String detail) {
        return new ApiException(PlatformErrorCode.VALIDATION_FAILED, "The waiver was not valid", detail);
    }

    /** The body's amount in its named currency, exact at the currency's minor units. */
    static Money amount(String amount, String currency) {
        CurrencyCode code;
        try {
            code = CurrencyCode.of(currency);
        } catch (RuntimeException unknown) {
            throw invalid("the currency is not one this platform supports.");
        }
        try {
            return Money.of(DecimalText.parse(amount), code);
        } catch (RuntimeException malformed) {
            throw invalid("the amount must be an exact decimal at the currency's minor units.");
        }
    }

    /** An agreement id is a UUIDv7 (ADR-0013): any other identifier names no agreement - the uniform 404, never a 500. */
    private static BnplAgreementId agreementId(String raw) {
        try {
            return BnplAgreementId.of(id(raw));
        } catch (IllegalArgumentException notAnAgreementId) {
            throw notFound();
        }
    }

    private static UUID id(String raw) {
        if (raw == null) {
            throw notFound();
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a BNPL servicing correction runs inside a correlation"
                        + " scope"))
                .correlationId();
    }
}
