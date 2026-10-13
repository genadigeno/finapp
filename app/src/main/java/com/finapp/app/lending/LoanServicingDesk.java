package com.finapp.app.lending;

import com.finapp.lending.AllocationAnomaly;
import com.finapp.lending.LendingAuditAction;
import com.finapp.lending.LendingCapitalAdministration;
import com.finapp.lending.LendingErrorCode;
import com.finapp.lending.LoanId;
import com.finapp.lending.LoanStore;
import com.finapp.lending.Loans;
import com.finapp.lending.Repayment;
import com.finapp.lending.RepaymentReversal;
import com.finapp.lending.RepaymentReversalStore;
import com.finapp.lending.RepaymentReversals;
import com.finapp.lending.RepaymentStore;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The boundary half of the loan servicing doors (`P11-TSK-020`; {@code PHASE_11_PLAN.md} sections 9 and 11, ADR-0099
 * points 1, 2, 4 and 8): the repayment reversal's proposal, approval and rejection - each keyed per principal
 * ({@code lending.servicing:<type>:<id>}), run in one transaction under the {@link IdempotentExecutor} claim, so a retried
 * key replays the recorded receipt and a refusal, which writes nothing, rolls the claim back with it - and the operator's
 * loan read, served from one {@code REPEATABLE READ} snapshot with its audit record ({@code lending.LoanRead}) in the same
 * transaction. The four-eyes rule, the machine, the lock order and the arbiters are the domain's and {@code lending V016}'s;
 * nothing here decides them.
 *
 * <p><strong>Self-dealing</strong> (the Phase 10 to 11 transition's R1) is the one refusal recorded: a
 * {@code lending.SelfDealingRefused} {@code FAILED} record in a transaction of its own after the act rolled back - so the
 * attempt survives, and a retry is recorded as the attempt it is. Every other loser records nothing.
 */
@RequiredArgsConstructor
public final class LoanServicingDesk {

    static final String SCOPE = "lending.servicing:";

    @NonNull private final RepaymentReversals reversals;
    @NonNull private final RepaymentReversalStore reversalStore;
    @NonNull private final Loans loans;
    @NonNull private final LoanStore loanStore;
    @NonNull private final RepaymentStore repayments;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final LendingTransactions transactions;
    @NonNull private final RepaymentReversalMetrics metrics;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;
    @NonNull private final com.finapp.lending.FeeAssessmentStore feeStore;
    @NonNull private final com.finapp.lending.WaiverStore waiverStore;
    @NonNull private final com.finapp.lending.LoanAmendmentStore amendmentStore;

    /**
     * A repayment reversal as answered - and replayed: its machine, the repayment it reverses and the agreement version
     * that repayment was allocated under; once approved, rule 10's journal entry. Never a reason (a person's prose, held
     * on the record and in the audit trail) and never an amount (the repayment's own read shows it).
     */
    public record ReversalView(
            String reversalId,
            String loanId,
            String repaymentId,
            String status,
            int agreementVersion,
            String proposedAt,
            String decidedAt,
            String journalEntryId) {}

    /**
     * A loan as a servicing agent reads it: the account as its customer sees it - state, agreement, balances derived from
     * journal lines - its repayments with their derived status (newest first, at most {@value RepaymentReversals#HISTORY_LIMIT})
     * and its reversals (newest first, likewise); its fees (oldest first) and its waivers (newest first, at most
     * {@value com.finapp.lending.Waivers#HISTORY_LIMIT}) (`P11-TSK-025`); its amendments (newest first, at most
     * {@value com.finapp.lending.LoanAmendments#HISTORY_LIMIT}, each state derived now) (`P11-TSK-027`). Never the party.
     */
    public record OperatorLoanView(
            LoanAccountDesk.LoanView loan,
            List<LoanRepaymentDesk.RepaymentView> repayments,
            List<ReversalView> reversals,
            List<LoanWaiverDesk.FeeView> fees,
            List<LoanWaiverDesk.WaiverView> waivers,
            List<LoanAmendmentDesk.AmendmentView> amendments) {}

    // ------------------------------------------------------------------ the reversal's acts

    /** Proposes the reversal of a repayment - a holder of {@code LOAN_SERVICE}; nothing is posted. */
    public ReversalView propose(String rawLoanId, String rawRepaymentId, String idempotencyKey, String reason) {
        LoanId loan = loanId(rawLoanId);
        UUID repayment = id(rawRepaymentId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, correlation, idempotencyKey, RepaymentReversalMetrics.Outcome.PROPOSED,
                String.join("|", "propose", loan.value().toString(), repayment.toString(), String.valueOf(reason)),
                uow -> new Acted(reversals.propose(uow, loan, repayment, reason, actor, correlation), false));
    }

    /** Approves a pending reversal - a holder of {@code LOAN_SERVICE_APPROVE} other than its proposer; posts rule 10. */
    public ReversalView approve(String rawLoanId, String rawRepaymentId, String idempotencyKey,
            LoanServicingController.ReversalDecisionRequest body) {
        LoanId loan = loanId(rawLoanId);
        UUID repayment = id(rawRepaymentId);
        UUID reversal = id(body.reversalId());
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, correlation, idempotencyKey, RepaymentReversalMetrics.Outcome.APPROVED,
                String.join("|", "approve", loan.value().toString(), repayment.toString(), reversal.toString(),
                        String.valueOf(body.reason())),
                uow -> decided(reversals.approve(uow, loan, repayment, reversal, body.reason(), actor, correlation)));
    }

    /** Rejects a pending reversal - a holder of {@code LOAN_SERVICE}, the proposer's withdrawal included. */
    public ReversalView reject(String rawLoanId, String rawRepaymentId, String idempotencyKey,
            LoanServicingController.ReversalDecisionRequest body) {
        LoanId loan = loanId(rawLoanId);
        UUID repayment = id(rawRepaymentId);
        UUID reversal = id(body.reversalId());
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return keyed(actor, correlation, idempotencyKey, RepaymentReversalMetrics.Outcome.REJECTED,
                String.join("|", "reject", loan.value().toString(), repayment.toString(), reversal.toString(),
                        String.valueOf(body.reason())),
                uow -> decided(reversals.reject(uow, loan, repayment, reversal, body.reason(), actor, correlation)));
    }

    // ------------------------------------------------------------------ the operator's read

    /**
     * Any loan, as a servicing agent reads it - one snapshot, its audit record ({@code lending.LoanRead}) committed with
     * it; an unknown or malformed id is the uniform {@code 404}, read and recorded as nothing.
     */
    public OperatorLoanView read(String rawLoanId) {
        LoanId loan = loanId(rawLoanId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return transactions.inTransaction(unitOfWork -> {
            try (Statement snapshot = unitOfWork.createStatement()) {
                snapshot.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
            } catch (SQLException failure) {
                throw new IllegalStateException("the loan read could not open its snapshot: " + failure.getSQLState());
            }
            UUID party = loanStore.partyOf(unitOfWork, loan).orElseThrow(LoanServicingDesk::notFound);
            Loans.Read read = loans.read(unitOfWork, loan, party).orElseThrow(LoanServicingDesk::notFound);
            List<Repayment> found = repayments.ownedBy(unitOfWork, loan, party, RepaymentReversals.HISTORY_LIMIT);
            Set<UUID> reversed = reversalStore.reversedAmong(unitOfWork, found.stream().map(Repayment::id).toList());
            List<LoanRepaymentDesk.RepaymentView> repaymentViews = new ArrayList<>();
            for (Repayment repayment : found) {
                repaymentViews.add(LoanRepaymentDesk.view(repayment, Optional.empty(), reversed.contains(repayment.id())));
            }
            List<ReversalView> reversalViews = reversalStore.ofLoan(unitOfWork, loan, RepaymentReversals.HISTORY_LIMIT)
                    .stream().map(LoanServicingDesk::view).toList();
            List<LoanWaiverDesk.FeeView> feeViews = feeStore.ofLoan(unitOfWork, loan).stream().map(LoanWaiverDesk::view)
                    .toList();
            List<LoanWaiverDesk.WaiverView> waiverViews = waiverStore.ofLoan(unitOfWork, loan,
                    com.finapp.lending.Waivers.HISTORY_LIMIT).stream().map(LoanWaiverDesk::view).toList();
            List<LoanAmendmentDesk.AmendmentView> amendmentViews = amendmentStore.ofLoan(unitOfWork, loan,
                    com.finapp.lending.LoanAmendments.HISTORY_LIMIT).stream().map(LoanAmendmentDesk::view).toList();
            audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, Instant.now(clock),
                    LendingAuditAction.LOAN_READ, "loan", loan.value().toString(), Optional.empty(),
                    AuditOutcome.SUCCEEDED, correlation, Optional.of("loan=" + loan.value() + ", repayments="
                            + repaymentViews.size() + ", reversals=" + reversalViews.size() + ", fees=" + feeViews.size()
                            + ", waivers=" + waiverViews.size() + ", amendments=" + amendmentViews.size())));
            return new OperatorLoanView(LoanAccountDesk.view(read), List.copyOf(repaymentViews), reversalViews, feeViews,
                    waiverViews, amendmentViews);
        });
    }

    // ------------------------------------------------------------------ plumbing

    /**
     * One keyed act in one transaction - a lost response replays the stored receipt; a refusal stores nothing. The act's
     * meter is told after the commit, once (a replay or a converged retry counts nothing); a refusal is counted by
     * reason; a self-dealing attempt is recorded {@code FAILED} in a transaction of its own.
     */
    private ReversalView keyed(
            Actor actor,
            CorrelationId correlation,
            String idempotencyKey,
            RepaymentReversalMetrics.Outcome success,
            String fingerprinted,
            Function<Connection, Acted> act) {
        IdempotencyKey key = new IdempotencyKey(SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint = RequestFingerprint.sha256(fingerprinted.getBytes(StandardCharsets.UTF_8));
        AtomicReference<Acted> acted = new AtomicReference<>();
        IdempotentExecutor.ExecutionOutcome outcome;
        try {
            outcome = transactions.inTransaction(unitOfWork -> executor.execute(unitOfWork, key, fingerprint, uow -> {
                Acted done = act.apply(uow);
                acted.set(done);
                return CommandResult.succeeded(stored(view(done.reversal())));
            }));
        } catch (RepaymentReversals.SelfDealingRefused selfDealing) {
            transactions.inTransaction(uow -> {
                reversals.recordRefusal(uow, selfDealing, actor, correlation);
                return null;
            });
            metrics.record(RepaymentReversalMetrics.Outcome.SELF_DEALING_REFUSED);
            throw refused(LendingErrorCode.SELF_DEALING_REFUSED, selfDealing);
        } catch (RepaymentReversals.LoanNotFound unknown) {
            throw notFound();
        } catch (LendingCapitalAdministration.ReasonRequired reason) {
            throw refused(LendingErrorCode.REASON_REQUIRED, reason);
        } catch (RepaymentReversals.SelfApprovalRefused self) {
            metrics.record(RepaymentReversalMetrics.Outcome.SELF_APPROVAL_REFUSED);
            throw refused(LendingErrorCode.SELF_APPROVAL_REFUSED, self);
        } catch (RepaymentReversals.LoanClosed closed) {
            metrics.record(RepaymentReversalMetrics.Outcome.LOAN_CLOSED);
            throw refused(LendingErrorCode.LOAN_CLOSED, closed);
        } catch (RepaymentReversals.ProposalPending pending) {
            metrics.record(RepaymentReversalMetrics.Outcome.PROPOSAL_PENDING);
            throw refused(LendingErrorCode.PROPOSAL_PENDING, pending);
        } catch (RepaymentReversals.ProposalNotPending decided) {
            metrics.record(RepaymentReversalMetrics.Outcome.PROPOSAL_NOT_PENDING);
            throw refused(LendingErrorCode.PROPOSAL_NOT_PENDING, decided);
        } catch (RepaymentReversals.RepaymentNotReversible notReversible) {
            metrics.record(RepaymentReversalMetrics.Outcome.NOT_REVERSIBLE);
            throw refused(LendingErrorCode.REPAYMENT_NOT_REVERSIBLE, notReversible);
        } catch (RepaymentReversals.CapitalUnavailable unavailable) {
            metrics.record(RepaymentReversalMetrics.Outcome.CAPITAL_UNAVAILABLE);
            throw refused(LendingErrorCode.CAPITAL_UNAVAILABLE, unavailable);
        } catch (RepaymentReversals.WalletUnavailable unavailable) {
            metrics.record(RepaymentReversalMetrics.Outcome.WALLET_UNAVAILABLE);
            throw refused(LendingErrorCode.WALLET_UNAVAILABLE, unavailable);
        } catch (AllocationAnomaly anomaly) {
            throw anomaly;
        }
        if (outcome.executed() && acted.get() != null && !acted.get().replayed()) {
            metrics.record(success);
        }
        return replayed(outcome.body().orElseThrow(() -> new IllegalStateException("a servicing act stores its receipt")));
    }

    /** What an act did: the reversal it left, and whether the same person's retry converged on their own decision. */
    private record Acted(RepaymentReversal reversal, boolean replayed) {}

    private static Acted decided(RepaymentReversals.Decided decided) {
        return new Acted(decided.reversal(), decided.replayed());
    }

    private static ReversalView view(RepaymentReversal reversal) {
        return new ReversalView(
                reversal.id().toString(),
                reversal.loan().value().toString(),
                reversal.repayment().toString(),
                reversal.status().name(),
                reversal.agreementVersion(),
                reversal.proposedAt().toString(),
                reversal.decidedAt().map(Instant::toString).orElse(null),
                reversal.journalEntry().map(entry -> entry.value().toString()).orElse(null));
    }

    /** The receipt: the view's fields, '|'-separated, an absent one empty. */
    private static StoredResponse stored(ReversalView view) {
        String body = String.join("|", view.reversalId(), view.loanId(), view.repaymentId(), view.status(),
                Integer.toString(view.agreementVersion()), view.proposedAt(), nullable(view.decidedAt()),
                nullable(view.journalEntryId()));
        return StoredResponse.of(body.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static ReversalView replayed(byte[] body) {
        String[] fields = new String(body, StandardCharsets.UTF_8).split("\\|", -1);
        return new ReversalView(fields[0], fields[1], fields[2], fields[3], Integer.parseInt(fields[4]), fields[5],
                fields[6].isEmpty() ? null : fields[6], fields[7].isEmpty() ? null : fields[7]);
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }

    private static ApiException refused(LendingErrorCode code, RuntimeException cause) {
        return new ApiException(code, "A loan servicing command was refused", cause.getMessage() + ".");
    }

    private static ApiException notFound() {
        return new ApiException(LendingErrorCode.NOT_FOUND, "No loan, repayment or reversal matches the request",
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
                .orElseThrow(() -> new IllegalStateException("a loan servicing command runs inside a correlation scope"))
                .correlationId();
    }
}
