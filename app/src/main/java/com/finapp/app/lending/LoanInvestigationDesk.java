package com.finapp.app.lending;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PurposeBalances;
import com.finapp.lending.LendingCapitalAdministration;
import com.finapp.lending.LendingErrorCode;
import com.finapp.lending.LoanExplanations;
import com.finapp.lending.LoanId;
import com.finapp.lending.LoanRecord;
import com.finapp.lending.LoanReplayProof;
import com.finapp.lending.LoanSubledgerProof;
import com.finapp.lending.RepaymentReversals;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The investigator's loan doors (`P11-TSK-029`; PHASE_11_PLAN.md section 9; behind {@code LENDING_INVESTIGATE}).
 *
 * <ul>
 *   <li><strong>The explanation</strong> - one {@code REPEATABLE READ} transaction: the self-dealing judgement, the loan's
 *       whole record and its subledger proof, and {@code lending.ExplanationRead} committed with them before the answer -
 *       no serving unrecorded. Reads, so no idempotency key: a repeated read is a second serving, recorded again.
 *   <li><strong>The replay</strong> - a blank or instrument-shaped reason refused before anything is read; the replay on
 *       one read-only {@code REPEATABLE READ} snapshot; then {@code lending.LoanReplayed} committed in a transaction of its
 *       own - only then is the verdict answered (the credit replay's precedent).
 * </ul>
 *
 * <p>An investigator of the loan's own party - or one the platform cannot place in a party - is {@code 403
 * lending.SelfDealingRefused}, the attempt recorded {@code FAILED} in a transaction of its own; an unknown or malformed loan
 * is the uniform {@code 404 lending.NotFound}. The explanation carries amounts and is never logged ({@code INV-AUD-02}).
 */
@RequiredArgsConstructor
public final class LoanInvestigationDesk {

    @NonNull private final LoanExplanations explanations;
    @NonNull private final LendingTransactions transactions;

    // ------------------------------------------------------------------ the views

    /** A loan explained from its rows alone. Amounts are decimal strings in the loan's currency. */
    public record LoanExplanationView(
            String loanId,
            String product,
            String kind,
            String status,
            String currency,
            String openedAt,
            String closedAt,
            String closureReason,
            String cancellationReason,
            List<ExplainedAgreementView> agreements,
            ExplainedDisbursementView disbursement,
            String accrualStart,
            ExplainedPayoutView payout,
            List<ExplainedScheduleView> schedules,
            List<ExplainedDrawView> draws,
            List<ExplainedAccrualView> accruals,
            List<ExplainedBillingView> billings,
            List<ExplainedStatementView> statements,
            List<ExplainedRepaymentView> repayments,
            List<ExplainedApplicationView> creditApplications,
            List<ExplainedReversalView> reversals,
            List<ExplainedFeeView> fees,
            List<ExplainedWaiverView> waivers,
            List<ExplainedAmendmentView> amendments,
            List<ExplainedReschedulingView> reschedulings,
            ExplainedClosureView closure,
            List<ExplainedPayoffView> payoffQuotes,
            List<ExplainedConditionView> conditions,
            List<ExplainedEntryView> journalEntries,
            List<ExplainedBalanceView> balances,
            String subledgerVerdict,
            List<String> subledgerFailures) {}

    /** An agreement version: its origin, terms, engines and canonical hash. */
    public record ExplainedAgreementView(int version, String agreementId, String origin, String originId, String termsVersionId,
            String amount, Integer termMonths, String nominalAnnualRate, int repaymentDay, Integer scheduleEngine,
            int accrualEngine, int allocationEngine, Integer statementEngine, String template, int templateVersion,
            int canonicalFormat, String termsSha256, String effectiveFrom) {}

    /** The disbursement. */
    public record ExplainedDisbursementView(String status, String valueDate, String journalEntryId) {}

    /** The external payout (path X). */
    public record ExplainedPayoutView(String status, String withdrawalId, String concludedAt) {}

    /** A schedule version and its instalments. */
    public record ExplainedScheduleView(int version, int agreementVersion, int engine, String principal, String instalment,
            String finalInstalment, String totalInterest, int termMonths, String startDate, int intendedDay,
            List<ExplainedInstalmentView> instalments) {}

    /** One instalment. */
    public record ExplainedInstalmentView(String instalmentId, int sequence, String periodStart, String dueDate, int days,
            String openingPrincipal, String interest, String principal, String amount, String closingPrincipal) {}

    /** A draw. */
    public record ExplainedDrawView(String drawId, String amount, String valueDate, int agreementVersion, String journalEntryId) {}

    /** A day's accrual. */
    public record ExplainedAccrualView(String date, String periodStart, String periodEnd, String base, String cumulativeExact,
            String posted, int agreementVersion, int engine, String journalEntryId) {}

    /** A billing. */
    public record ExplainedBillingView(String billingId, String kind, int sequence, String periodStart, String dueDate,
            String interestDue, String principalDue, int scheduleVersion, int agreementVersion, String journalEntryId) {}

    /** A statement. */
    public record ExplainedStatementView(String statementId, String cycleStart, String cycleEnd, String dueDate,
            String interestBilled, String feesCarried, String principalBilled, String minimumPayment, int agreementVersion,
            String journalEntryId) {}

    /** A repayment and its allocation. */
    public record ExplainedRepaymentView(String repaymentId, String kind, String amount, String valueDate, int agreementVersion,
            int allocationEngine, String journalEntryId, List<ExplainedAllocationView> allocations) {}

    /** One allocation line. */
    public record ExplainedAllocationView(int ordinal, String target, String billedItemId, String component, String amount) {}

    /** A credit-balance application and its lines. */
    public record ExplainedApplicationView(String applicationId, String billedItemId, String amount, int agreementVersion,
            String journalEntryId, List<ExplainedAllocationView> allocations) {}

    /** A repayment reversal. */
    public record ExplainedReversalView(String reversalId, String repaymentId, String status, String decidedAt,
            String journalEntryId) {}

    /** A fee. */
    public record ExplainedFeeView(String feeId, String billedItemId, String kind, String businessDate, String amount,
            String journalEntryId) {}

    /** A waiver. */
    public record ExplainedWaiverView(String waiverId, String kind, String billedItemId, String feeId, String amount, String outcome,
            String decidedAt, String journalEntryId) {}

    /** An amendment. */
    public record ExplainedAmendmentView(String amendmentId, int amendsVersion, String nominalAnnualRate, int remainingInstalments,
            boolean rescheduleArrears, String outcome, String decidedAt) {}

    /** Arrears re-scheduled. */
    public record ExplainedReschedulingView(String amendmentId, String billedItemId, String principal, String journalEntryId) {}

    /** The closure. */
    public record ExplainedClosureView(String reason, String refund, String refundJournalEntryId, String repaymentId) {}

    /** A payoff quote. */
    public record ExplainedPayoffView(String quoteId, String businessDate, String goodThrough, String outstanding,
            boolean executed) {}

    /** A condition change. */
    public record ExplainedConditionView(String businessDate, String kind, int daysPastDue, String bucket, boolean defaulted) {}

    /** A journal entry on the loan's accounts, the row that names it, and its lines there. */
    public record ExplainedEntryView(String journalEntryId, String valueDate, String explainedBy, String explainedById,
            List<ExplainedLineView> lines) {}

    /** One line of an entry on one of the loan's accounts. */
    public record ExplainedLineView(String account, String direction, String amount) {}

    /** One of the six accounts: the ledger's balance, the rows' figure, and whether they agree. */
    public record ExplainedBalanceView(String account, String ledger, String rows, boolean explained) {}

    /** A replay's verdict: what diverged, by kind and row - never a figure. */
    public record LoanReplayView(String loanId, String verdict, int replayed, List<ExplainedDivergenceView> divergences) {}

    /** One diverged fact. */
    public record ExplainedDivergenceView(String check, String subject) {}

    // ------------------------------------------------------------------ the doors

    /** {@code rawId} explained, audited. */
    public LoanExplanationView explain(String rawId) {
        LoanId loan = loanId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        LoanExplanations.Explanation explanation;
        try {
            explanation = transactions.inTransaction(unitOfWork -> {
                repeatableRead(unitOfWork);
                return explanations.explain(unitOfWork, loan, actor, correlation);
            });
        } catch (RepaymentReversals.SelfDealingRefused selfDealing) {
            throw refusedAndRecorded(selfDealing, actor, correlation);
        } catch (LoanExplanations.LoanNotFound unknown) {
            throw notFound();
        }
        return view(explanation);
    }

    /** {@code rawId} replayed with {@code reason}, the verdict answered once recorded. */
    public LoanReplayView replay(String rawId, String reason) {
        LoanId loan = loanId(rawId);
        try {
            // The domain's one rule for a reason: present, bounded, never a card or account number's shape.
            LoanExplanations.reasoned(reason);
        } catch (LendingCapitalAdministration.ReasonRequired refused) {
            throw new ApiException(LendingErrorCode.REASON_REQUIRED, "A loan replay without a usable reason",
                    refused.getMessage() + ".");
        }
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        LoanReplayProof.Replay replay;
        try {
            replay = transactions.inSnapshot(snapshot -> explanations.replay(snapshot, loan, actor));
        } catch (RepaymentReversals.SelfDealingRefused selfDealing) {
            throw refusedAndRecorded(selfDealing, actor, correlation);
        } catch (LoanExplanations.LoanNotFound unknown) {
            throw notFound();
        }
        transactions.inTransaction(unitOfWork -> {
            explanations.recordReplay(unitOfWork, replay, reason, actor, correlation);
            return null;
        });
        return new LoanReplayView(replay.loan().value().toString(), replay.verdict().name(), replay.replayed(),
                replay.findings().stream().map(finding -> new ExplainedDivergenceView(finding.check().name(),
                        finding.subject().toString())).toList());
    }

    // ------------------------------------------------------------------ the explanation's view

    private static LoanExplanationView view(LoanExplanations.Explanation explanation) {
        LoanRecord record = explanation.record();
        LoanRecord.Account loan = record.loan();
        List<ExplainedAgreementView> agreements = record.agreements().stream().map(agreement -> new ExplainedAgreementView(
                agreement.version(), agreement.id().toString(),
                agreement.offer().isPresent() ? "OFFER" : agreement.amendment().isPresent() ? "AMENDMENT" : "PREPAYMENT",
                agreement.offer().or(agreement::amendment).or(agreement::prepayment).map(UUID::toString).orElse(null),
                agreement.termsVersionId().toString(),
                decimal(Money.ofPersisted(agreement.amountMinor(), loan.currency(), agreement.scale())),
                agreement.termMonths().orElse(null), agreement.nominalAnnualRate(), agreement.repaymentDay(),
                agreement.scheduleEngine().orElse(null), agreement.accrualEngine(), agreement.allocationEngine(),
                agreement.statementEngine().orElse(null), agreement.templateId(), agreement.templateVersion(),
                agreement.canonicalFormat(), HexFormat.of().formatHex(agreement.termsSha256()),
                agreement.effectiveFrom().toString())).toList();
        ExplainedDisbursementView disbursement = record.disbursement().map(found -> new ExplainedDisbursementView(found.status(),
                found.valueDate().map(Object::toString).orElse(null), entry(found.entry()))).orElse(null);
        ExplainedPayoutView payout = record.payout().map(found -> new ExplainedPayoutView(found.status(),
                found.withdrawal().map(UUID::toString).orElse(null),
                found.concludedAt().map(Instant::toString).orElse(null))).orElse(null);
        List<ExplainedScheduleView> schedules = record.schedules().stream().map(found -> new ExplainedScheduleView(found.version(),
                found.agreementVersion(), found.engine(), decimal(found.principal()), decimal(found.instalment()),
                decimal(found.finalInstalment()), decimal(found.totalInterest()), found.termMonths(),
                found.startDate().toString(), found.intendedDay(), found.instalments().stream().map(instalment ->
                        new ExplainedInstalmentView(instalment.id().toString(), instalment.sequence(),
                                instalment.periodStart().toString(), instalment.dueDate().toString(), instalment.days(),
                                decimal(instalment.opening()), decimal(instalment.interest()),
                                decimal(instalment.principal()), decimal(instalment.amount()),
                                decimal(instalment.closing()))).toList())).toList();
        List<ExplainedDrawView> draws = record.draws().stream().map(draw -> new ExplainedDrawView(draw.id().toString(),
                decimal(draw.amount()), draw.valueDate().toString(), draw.agreementVersion(), draw.entry().value()
                        .toString())).toList();
        List<ExplainedAccrualView> accruals = record.accruals().stream().map(accrual -> new ExplainedAccrualView(accrual.date().toString(),
                accrual.periodStart().toString(), accrual.periodEnd().toString(), decimal(accrual.base()),
                accrual.cumulativeExact().toPlainString(), decimal(accrual.posted()), accrual.agreementVersion(),
                accrual.engine(), entry(accrual.entry()))).toList();
        List<ExplainedBillingView> billings = record.billings().stream().map(billing -> new ExplainedBillingView(billing.id().toString(),
                billing.kind(), billing.sequence(), billing.periodStart().toString(), billing.dueDate().toString(),
                decimal(billing.interestDue()), decimal(billing.principalDue()), billing.scheduleVersion(),
                billing.agreementVersion(), entry(billing.entry()))).toList();
        List<ExplainedStatementView> statements = record.statements().stream().map(statement -> new ExplainedStatementView(
                statement.id().toString(), statement.cycleStart().toString(), statement.cycleEnd().toString(),
                statement.dueDate().toString(), decimal(statement.interestBilled()), decimal(statement.feesCarried()),
                decimal(statement.principalBilled()), decimal(statement.minimumPayment()), statement.agreementVersion(),
                entry(statement.entry()))).toList();
        List<ExplainedRepaymentView> repayments = record.repayments().stream().map(payment -> new ExplainedRepaymentView(
                payment.id().toString(), payment.kind().name(), decimal(payment.amount()), payment.valueDate().toString(),
                payment.agreementVersion(), payment.allocationEngine(), payment.entry().value().toString(),
                payment.lines().stream().map(line -> new ExplainedAllocationView(line.ordinal(), line.target().name(),
                        line.billingId().map(UUID::toString).orElse(null),
                        line.component().map(Enum::name).orElse(null), decimal(line.amount()))).toList())).toList();
        List<ExplainedApplicationView> applications = record.applications().stream().map(application -> new ExplainedApplicationView(
                application.id().toString(), application.billingId().toString(), decimal(application.amount()),
                application.agreementVersion(), application.entry().value().toString(), application.lines().stream()
                        .map(line -> new ExplainedAllocationView(line.ordinal(), "DUE_ITEM", line.billingId().toString(),
                                line.component().name(), decimal(line.amount()))).toList())).toList();
        List<ExplainedReversalView> reversals = record.reversals().stream().map(reversal -> new ExplainedReversalView(
                reversal.id().toString(), reversal.repaymentId().toString(), reversal.status(),
                reversal.decidedAt().map(Instant::toString).orElse(null), entry(reversal.entry()))).toList();
        List<ExplainedFeeView> fees = record.fees().stream().map(fee -> new ExplainedFeeView(fee.id().toString(),
                fee.dueItemId().toString(), fee.kind(), fee.businessDate().toString(), decimal(fee.amount()),
                fee.entry().value().toString())).toList();
        List<ExplainedWaiverView> waivers = record.waivers().stream().map(waiver -> new ExplainedWaiverView(waiver.id().toString(),
                waiver.kind().name(), waiver.dueItemId().toString(), waiver.feeAssessmentId().map(UUID::toString)
                        .orElse(null), decimal(waiver.amount()), waiver.outcome().orElse(null),
                waiver.decidedAt().map(Instant::toString).orElse(null), entry(waiver.entry()))).toList();
        List<ExplainedAmendmentView> amendments = record.amendments().stream().map(amendment -> new ExplainedAmendmentView(
                amendment.id().toString(), amendment.amendsVersion(), amendment.nominalAnnualRate(),
                amendment.remainingInstalments(), amendment.rescheduleArrears(), amendment.outcome().orElse(null),
                amendment.decidedAt().map(Instant::toString).orElse(null))).toList();
        List<ExplainedReschedulingView> reschedulings = record.reschedulings().stream().map(rescheduling -> new ExplainedReschedulingView(
                rescheduling.amendmentId().toString(), rescheduling.billingId().toString(),
                decimal(rescheduling.principal()), rescheduling.entry().value().toString())).toList();
        ExplainedClosureView closure = record.closure().map(found -> new ExplainedClosureView(found.reason(), decimal(found.refund()),
                entry(found.refundEntry()), found.repaymentId().map(UUID::toString).orElse(null))).orElse(null);
        List<ExplainedPayoffView> quotes = record.payoffQuotes().stream().map(quote -> new ExplainedPayoffView(quote.id().toString(),
                quote.businessDate().toString(), quote.goodThrough().toString(), decimal(quote.outstanding()),
                record.payoffExecutions().stream().anyMatch(execution -> execution.quoteId().equals(quote.id()))))
                .toList();
        List<ExplainedConditionView> conditions = record.conditions().stream().map(condition -> new ExplainedConditionView(
                condition.businessDate().toString(), condition.kind(), condition.daysPastDue(), condition.bucket(),
                condition.defaulted())).toList();

        // Every journal entry on the loan's accounts, with the row that names it (none: unexplained) and its lines there.
        Map<LedgerAccountId, AccountPurpose> purposes = new HashMap<>();
        record.ledgerAccounts().forEach(account -> purposes.put(account.id(), account.purpose()));
        Map<JournalEntryId, LoanSubledgerProof.Namer> named = LoanSubledgerProof.namedEntries(record);
        Map<JournalEntryId, List<PurposeBalances.DatedLine>> byEntry = new LinkedHashMap<>();
        record.journalLines().forEach(line -> byEntry.computeIfAbsent(line.entry(), key -> new ArrayList<>()).add(line));
        List<ExplainedEntryView> entries = new ArrayList<>();
        byEntry.forEach((id, lines) -> {
            Optional<LoanSubledgerProof.Namer> namer = Optional.ofNullable(named.get(id));
            entries.add(new ExplainedEntryView(id.value().toString(), lines.get(0).valueDate().toString(),
                    namer.map(LoanSubledgerProof.Namer::kind).orElse("UNEXPLAINED"),
                    namer.map(found -> found.row().toString()).orElse(null),
                    lines.stream().map(line -> new ExplainedLineView(String.valueOf(purposes.get(line.account())),
                            line.direction().name(), decimal(line.amount()))).toList()));
        });
        LoanSubledgerProof.Verdict verdict = explanation.subledger();
        List<ExplainedBalanceView> balances = verdict.figures().stream().map(figure -> new ExplainedBalanceView(figure.purpose().name(),
                decimal(figure.ledger()), decimal(figure.rows()), figure.holds())).toList();

        return new LoanExplanationView(loan.id().value().toString(), loan.product().name(), loan.kind().name(),
                loan.status().name(), loan.currency().code(), loan.openedAt().toString(),
                loan.closedAt().map(Instant::toString).orElse(null), loan.closureReason().orElse(null),
                loan.cancellationReason().orElse(null), agreements, disbursement,
                record.accrualStart().map(start -> start.startDate().toString()).orElse(null), payout, schedules, draws,
                accruals, billings, statements, repayments, applications, reversals, fees, waivers, amendments,
                reschedulings, closure, quotes, conditions, List.copyOf(entries), balances,
                verdict.holds() ? "HOLDS" : "FAILS", verdict.failures());
    }

    // ------------------------------------------------------------------ plumbing

    /** The refusal recorded FAILED in a transaction of its own, then answered {@code 403}. */
    private ApiException refusedAndRecorded(RepaymentReversals.SelfDealingRefused selfDealing, Actor actor,
            CorrelationId correlation) {
        transactions.inTransaction(unitOfWork -> {
            explanations.recordRefusal(unitOfWork, selfDealing, actor, correlation);
            return null;
        });
        return new ApiException(LendingErrorCode.SELF_DEALING_REFUSED, "A loan investigation was refused",
                selfDealing.getMessage() + ".");
    }

    private static void repeatableRead(Connection unitOfWork) {
        try (Statement snapshot = unitOfWork.createStatement()) {
            snapshot.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
        } catch (SQLException failure) {
            throw new IllegalStateException("the explanation could not open its snapshot: " + failure.getSQLState());
        }
    }

    private static String decimal(Money amount) {
        return amount.toBigDecimal().toPlainString();
    }

    private static String entry(Optional<JournalEntryId> entry) {
        return entry.map(found -> found.value().toString()).orElse(null);
    }

    private static ApiException notFound() {
        return new ApiException(LendingErrorCode.NOT_FOUND, "No loan matches the request", "no such record.");
    }

    /** A loan id is a UUIDv7 (ADR-0013): any other identifier names no loan - the uniform 404, never a 500. */
    private static LoanId loanId(String raw) {
        if (raw == null) {
            throw notFound();
        }
        try {
            return LoanId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException notALoanId) {
            throw notFound();
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a loan investigation runs inside a correlation scope"))
                .correlationId();
    }
}
