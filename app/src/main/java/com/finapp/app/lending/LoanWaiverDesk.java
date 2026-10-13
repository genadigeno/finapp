package com.finapp.app.lending;

import com.finapp.app.api.DecimalText;
import com.finapp.lending.AllocationAnomaly;
import com.finapp.lending.FeeAssessment;
import com.finapp.lending.LendingCapitalAdministration;
import com.finapp.lending.LendingErrorCode;
import com.finapp.lending.LoanId;
import com.finapp.lending.RepaymentReversals;
import com.finapp.lending.Repayments;
import com.finapp.lending.Waiver;
import com.finapp.lending.WaiverKind;
import com.finapp.lending.Waivers;
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
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the waiver doors (`P11-TSK-025`; {@code PHASE_11_PLAN.md} sections 9 and 11, A14, ADR-0099 points
 * 1, 2 and 4): a fee or interest waiver's proposal, approval and rejection under four eyes - each keyed per principal
 * ({@code lending.servicing:<type>:<id>}, the servicing doors' one scope), run in one transaction under the
 * {@link IdempotentExecutor} claim, so a retried key replays the recorded receipt and a refusal, which writes nothing, rolls
 * the claim back with it. The four-eyes rule, the bound, the lock order and the arbiters are the domain's and
 * {@code lending V021}'s; nothing here decides them.
 *
 * <p><strong>Self-dealing</strong> is the one refusal recorded: a {@code lending.SelfDealingRefused} {@code FAILED} record in
 * a transaction of its own after the act rolled back. Every other loser records nothing.
 */
@RequiredArgsConstructor
public final class LoanWaiverDesk {

    @NonNull private final Waivers waivers;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final LendingTransactions transactions;
    @NonNull private final LoanWaiverMetrics metrics;

    /**
     * A waiver as answered - and replayed: its kind and machine, what it names (the fee assessment for a fee's kinds, the
     * billed item always), the amount it forgives in its currency, the agreement version at its proposal; once approved,
     * the journal entry it posted. Never a reason (a person's prose, held on the record and in the audit trail).
     */
    public record WaiverView(
            String waiverId,
            String loanId,
            String kind,
            String assessmentId,
            String dueItemId,
            String amount,
            String currency,
            String status,
            int agreementVersion,
            String proposedAt,
            String decidedAt,
            String journalEntryId) {}

    /** A fee as an operator reads it: the assessment, the item it is on, its kind, business date, amount and entry. */
    public record FeeView(
            String assessmentId,
            String dueItemId,
            String kind,
            String businessDate,
            String amount,
            String currency,
            int agreementVersion,
            String journalEntryId) {}

    // ------------------------------------------------------------------ the acts

    /** Proposes a waiver - a holder of {@code LOAN_SERVICE}; nothing is posted. */
    public WaiverView propose(String rawLoanId, String idempotencyKey, LoanServicingController.WaiverProposalRequest body) {
        LoanId loan = loanId(rawLoanId);
        WaiverKind kind = kind(body.kind());
        UUID target = id(body.targetId());
        Money amount = amount(body.amount(), body.currency());
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, correlation, idempotencyKey, Optional.of(kind), LoanWaiverMetrics.Outcome.PROPOSED,
                String.join("|", "propose", loan.value().toString(), kind.name(), target.toString(),
                        Long.toString(amount.minorUnits()), Integer.toString(amount.scale()), amount.currency().code(),
                        String.valueOf(body.reason())),
                uow -> new Acted(waivers.propose(uow, loan, kind, target, amount, body.reason(), actor, correlation),
                        false));
    }

    /** Approves a pending waiver - a holder of {@code LOAN_SERVICE_APPROVE} other than its proposer; posts it. */
    public WaiverView approve(String rawLoanId, String rawWaiverId, String idempotencyKey,
            LoanServicingController.WaiverDecisionRequest body) {
        LoanId loan = loanId(rawLoanId);
        UUID waiver = id(rawWaiverId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, correlation, idempotencyKey, Optional.empty(), LoanWaiverMetrics.Outcome.APPROVED,
                String.join("|", "approve", loan.value().toString(), waiver.toString(), String.valueOf(body.reason())),
                uow -> decided(waivers.approve(uow, loan, waiver, body.reason(), actor, correlation)));
    }

    /** Rejects a pending waiver - a holder of {@code LOAN_SERVICE}, the proposer's withdrawal included. */
    public WaiverView reject(String rawLoanId, String rawWaiverId, String idempotencyKey,
            LoanServicingController.WaiverDecisionRequest body) {
        LoanId loan = loanId(rawLoanId);
        UUID waiver = id(rawWaiverId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, correlation, idempotencyKey, Optional.empty(), LoanWaiverMetrics.Outcome.REJECTED,
                String.join("|", "reject", loan.value().toString(), waiver.toString(), String.valueOf(body.reason())),
                uow -> decided(waivers.reject(uow, loan, waiver, body.reason(), actor, correlation)));
    }

    // ------------------------------------------------------------------ the views

    static WaiverView view(Waiver waiver) {
        return new WaiverView(
                waiver.id().toString(),
                waiver.loan().value().toString(),
                waiver.kind().name(),
                waiver.assessment().map(UUID::toString).orElse(null),
                waiver.dueItem().value().toString(),
                waiver.amount().toBigDecimal().toPlainString(),
                waiver.amount().currency().code(),
                waiver.status().name(),
                waiver.agreementVersion(),
                waiver.proposedAt().toString(),
                waiver.decidedAt().map(Instant::toString).orElse(null),
                waiver.journalEntry().map(entry -> entry.value().toString()).orElse(null));
    }

    static FeeView view(FeeAssessment fee) {
        return new FeeView(
                fee.id().toString(),
                fee.dueItem().value().toString(),
                fee.kind().name(),
                fee.businessDate().toString(),
                fee.amount().toBigDecimal().toPlainString(),
                fee.amount().currency().code(),
                fee.agreementVersion(),
                fee.entry().value().toString());
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * One keyed act in one transaction - a lost response replays the stored receipt; a refusal stores nothing. The act's
     * meter is told after the commit, once (a replay or a converged retry counts nothing); a refusal is counted by reason
     * and the waiver's kind; a self-dealing attempt is recorded {@code FAILED} in a transaction of its own.
     */
    private WaiverView keyed(
            Actor actor,
            CorrelationId correlation,
            String idempotencyKey,
            Optional<WaiverKind> requested,
            LoanWaiverMetrics.Outcome success,
            String fingerprinted,
            Function<Connection, Acted> act) {
        IdempotencyKey key = new IdempotencyKey(LoanServicingDesk.SCOPE + actor.type().name() + ":" + actor.id(),
                idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        AtomicReference<Acted> acted = new AtomicReference<>();
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                Acted done = act.apply(uow);
                acted.set(done);
                return CommandResult.succeeded(stored(view(done.waiver())));
            }));
        } catch (RepaymentReversals.SelfDealingRefused selfDealing) {
            transactions.inTransaction(uow -> {
                waivers.recordRefusal(uow, selfDealing, actor, correlation);
                return null;
            });
            requested.ifPresentOrElse(kind -> metrics.record(kind, LoanWaiverMetrics.Outcome.SELF_DEALING_REFUSED),
                    metrics::recordSelfDealingOfUnknownKind);
            throw refused(LendingErrorCode.SELF_DEALING_REFUSED, selfDealing);
        } catch (Waivers.WaiverNotFound unknown) {
            throw notFound();
        } catch (LendingCapitalAdministration.ReasonRequired reason) {
            throw refused(LendingErrorCode.REASON_REQUIRED, reason);
        } catch (Repayments.AmountInvalid invalid) {
            throw invalid("the amount must be a positive amount in the loan's currency, at its minor units.");
        } catch (Waivers.WaiverExceedsDue exceeds) {
            metrics.record(exceeds.kind(), LoanWaiverMetrics.Outcome.EXCEEDS_DUE);
            throw refused(LendingErrorCode.WAIVER_EXCEEDS_DUE, exceeds);
        } catch (Waivers.SelfApprovalRefused self) {
            metrics.record(self.kind(), LoanWaiverMetrics.Outcome.SELF_APPROVAL_REFUSED);
            throw refused(LendingErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (Waivers.LoanClosed closed) {
            metrics.record(closed.kind(), LoanWaiverMetrics.Outcome.LOAN_CLOSED);
            throw refused(LendingErrorCode.LOAN_CLOSED, closed);
        } catch (Waivers.ProposalPending pending) {
            metrics.record(pending.kind(), LoanWaiverMetrics.Outcome.PROPOSAL_PENDING);
            throw refused(LendingErrorCode.PROPOSAL_PENDING, pending);
        } catch (Waivers.ProposalNotPending decided) {
            metrics.record(decided.kind(), LoanWaiverMetrics.Outcome.PROPOSAL_NOT_PENDING);
            throw refused(LendingErrorCode.PROPOSAL_NOT_PENDING, decided);
        } catch (AllocationAnomaly anomaly) {
            throw anomaly;
        }
        if (outcome.executed() && acted.get() != null && !acted.get().replayed()) {
            metrics.record(acted.get().waiver().kind(), success);
        }
        return replayed(outcome.body().orElseThrow(() -> new IllegalStateException("a waiver act stores its receipt")));
    }

    /** What an act did: the waiver it left, and whether the same person's retry converged on their own decision. */
    private record Acted(Waiver waiver, boolean replayed) {}

    private static Acted decided(Waivers.Decided decided) {
        return new Acted(decided.waiver(), decided.replayed());
    }

    /** The receipt: the view's fields, '|'-separated, an absent one empty. */
    private static StoredResponse stored(WaiverView view) {
        String body = String.join("|", view.waiverId(), view.loanId(), view.kind(), nullable(view.assessmentId()),
                view.dueItemId(), view.amount(), view.currency(), view.status(), Integer.toString(view.agreementVersion()),
                view.proposedAt(), nullable(view.decidedAt()), nullable(view.journalEntryId()));
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static WaiverView replayed(byte[] body) {
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        return new WaiverView(fields[0], fields[1], fields[2], fields[3].isEmpty() ? null : fields[3], fields[4],
                fields[5], fields[6], fields[7], Integer.parseInt(fields[8]), fields[9],
                fields[10].isEmpty() ? null : fields[10], fields[11].isEmpty() ? null : fields[11]);
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }

    private static WaiverKind kind(String raw) {
        if (raw != null) {
            for (WaiverKind kind : WaiverKind.values()) {
                if (kind.name().equals(raw.toUpperCase(Locale.ROOT))) {
                    return kind;
                }
            }
        }
        throw invalid("the kind must be one of FEE, FEE_REFUND or INTEREST.");
    }

    /** The body's amount in its named currency, exact at the currency's minor units - the loan's are the domain's. */
    private static Money amount(String rawAmount, String rawCurrency) {
        CurrencyCode currency;
        try {
            currency = CurrencyCode.of(rawCurrency);
        } catch (RuntimeException unknown) {
            throw invalid("the currency is not one this platform supports.");
        }
        try {
            // The shape first: an exponent never reaches Money.of's rescale.
            return Money.of(DecimalText.parse(rawAmount), currency);
        } catch (RuntimeException malformed) {
            throw invalid("the amount must be an exact decimal at the currency's minor units.");
        }
    }

    private static ApiException refused(LendingErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A loan servicing command was refused", cause.getMessage() + ".");
    }

    private static ApiException invalid(String detail) {
        return new ApiException(PlatformErrorCode.VALIDATION_FAILED, "The waiver was not valid", detail);
    }

    private static ApiException notFound() {
        return new ApiException(LendingErrorCode.NOT_FOUND, "No loan, fee, billed item or waiver matches the request",
                "no such record.");
    }

    /** A loan id is a UUIDv7 (ADR-0013): any other identifier names no loan - the uniform 404, never a 500. */
    private static LoanId loanId(String raw) {
        try {
            return LoanId.of(id(raw));
        } catch (IllegalArgumentException notALoanId) {
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
                .orElseThrow(() -> new IllegalStateException("a waiver command runs inside a correlation scope"))
                .correlationId();
    }
}
