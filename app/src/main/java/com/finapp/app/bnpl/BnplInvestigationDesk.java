package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplAgreement;
import com.finapp.bnpl.BnplAgreementId;
import com.finapp.bnpl.BnplErrorCode;
import com.finapp.bnpl.BnplExplanations;
import com.finapp.bnpl.BnplReasons;
import com.finapp.bnpl.BnplRecord;
import com.finapp.bnpl.BnplRepaymentReversals;
import com.finapp.bnpl.BnplReplayProof;
import com.finapp.bnpl.BnplSubledgerProof;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PurposeBalances;
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
import java.time.LocalDate;
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
 * The investigator's BNPL agreement doors (`P12-TSK-025`; {@code PHASE_12_PLAN.md} section 9; behind
 * {@code BNPL_INVESTIGATE}; lending's {@code LoanInvestigationDesk}).
 *
 * <ul>
 *   <li><strong>The read and the explanation</strong> - one {@code REPEATABLE READ} transaction: the self-dealing
 *       judgement, the agreement's whole record and its subledger proof, and {@code bnpl.AgreementRead} or
 *       {@code bnpl.ExplanationRead} committed with them before the answer - no serving unrecorded. Reads, so no
 *       idempotency key: a repeated read is a second serving, recorded again.
 *   <li><strong>The replay</strong> - a blank or instrument-shaped reason refused before anything is read; the replay on one
 *       read-only {@code REPEATABLE READ} snapshot; then {@code bnpl.Replayed} committed in a transaction of its own - only
 *       then is the verdict answered.
 * </ul>
 *
 * <p>An investigator of the agreement's own party - or one the platform cannot place in a party - is {@code 403
 * bnpl.SelfDealingRefused}, the attempt recorded {@code FAILED} in a transaction of its own; an unknown or malformed
 * agreement is the uniform {@code 404 bnpl.NotFound}. The explanation carries amounts and is never logged
 * ({@code INV-AUD-02}).
 */
@RequiredArgsConstructor
public final class BnplInvestigationDesk {

    @NonNull private final BnplExplanations explanations;
    @NonNull private final BnplTransactions transactions;

    // ------------------------------------------------------------------ the views

    /** An agreement as an investigator reads it: its latest version, its obligation and its purchase. */
    public record BnplAgreementReadView(String agreementId, int agreementVersion, String obligationId, String purchaseId,
            String merchantId, String product, String plan, String status, String closureReason, String currency,
            String purchaseDate, String openedAt, String termsVersionId, String termsSha256, String gross, String fee,
            String net, String subledgerVerdict) {}

    /** An agreement explained from its rows alone. Amounts are decimal strings in its currency. */
    public record BnplExplanationView(
            String agreementId,
            String obligationId,
            String purchaseId,
            String merchantId,
            String product,
            String plan,
            String status,
            String closureReason,
            String currency,
            String purchaseDate,
            String openedAt,
            String closedAt,
            BnplExplainedFinancingView financing,
            List<BnplExplainedAgreementView> agreements,
            List<BnplExplainedScheduleView> schedules,
            List<BnplExplainedAccrualView> accruals,
            List<BnplExplainedBillingView> billings,
            List<BnplExplainedApplicationView> creditApplications,
            List<BnplExplainedRepaymentView> repayments,
            List<BnplExplainedCollectionView> collectionAttempts,
            List<BnplExplainedRefundView> refunds,
            List<BnplExplainedDisputeView> disputes,
            List<BnplExplainedConditionView> conditions,
            List<BnplExplainedFeeView> fees,
            List<BnplExplainedWaiverView> waivers,
            List<BnplExplainedReversalView> reversals,
            List<BnplExplainedPayoffView> payoffQuotes,
            BnplExplainedPayoffExecutionView payoffExecution,
            BnplExplainedClosureView closure,
            List<BnplExplainedEntryView> journalEntries,
            List<BnplExplainedBalanceView> balances,
            String subledgerVerdict,
            List<String> subledgerFailures) {}

    /** The merchant's financing: gross, fee, net, the fee schedule version and the entry. */
    public record BnplExplainedFinancingView(String gross, String fee, String net, String feeScheduleVersionId,
            String financedAt, String journalEntryId) {}

    /** An agreement version: its origin, engines, template and canonical hashes. */
    public record BnplExplainedAgreementView(int version, String refundId, int scheduleVersion, String termsVersionId,
            String scheduleEngine, String accrualEngine, String allocationEngine, String refundAllocationEngine,
            String feeScheduleVersionId, String template, int templateVersion, int canonicalFormat, String termsSha256,
            String documentSha256, String createdAt) {}

    /** A schedule version and its instalments. */
    public record BnplExplainedScheduleView(int version, String refundId, String engine, String principal, String startDate,
            List<BnplExplainedInstalmentView> instalments) {}

    /** One instalment. */
    public record BnplExplainedInstalmentView(String instalmentId, int sequence, String dueDate, String principal,
            String interest, String amount) {}

    /** A day's accrual. */
    public record BnplExplainedAccrualView(String date, String periodStart, String periodEnd, String base,
            String cumulativeExact, String posted, int agreementVersion, String engine, String journalEntryId) {}

    /** A billing. */
    public record BnplExplainedBillingView(String billingId, String instalmentId, int sequence, String periodStart,
            String dueDate, String businessDate, String principal, String interest, int scheduleVersion,
            int agreementVersion, String payoffExecutionId, String journalEntryId) {}

    /** A credit-balance application and its lines. */
    public record BnplExplainedApplicationView(String applicationId, String billingId, Integer catchUp, String amount,
            String valueDate, int agreementVersion, String journalEntryId, List<BnplExplainedAllocationView> lines) {}

    /** A repayment and its allocation. */
    public record BnplExplainedRepaymentView(String repaymentId, String kind, String amount, String valueDate,
            int agreementVersion, String allocationEngine, String journalEntryId, boolean reversed,
            List<BnplExplainedAllocationView> allocations) {}

    /** One allocation line. */
    public record BnplExplainedAllocationView(int ordinal, String target, String item, String component, String amount) {}

    /** A collection attempt. */
    public record BnplExplainedCollectionView(String attemptId, String instalmentId, String attemptDate, String outcome,
            String due, String collected, String repaymentId) {}

    /** A refund and its adjustments. */
    public record BnplExplainedRefundView(String refundId, String door, String amount, String refundedBefore,
            String feeReturned, String payableDebit, String cancelledUnbilled, String cancelledBilled, String excess,
            String excessTo, String interestAccruedReleased, String interestDueReleased, int agreementVersion,
            int scheduleVersion, String valueDate, String journalEntryId, List<BnplExplainedAdjustmentView> adjustments) {}

    /** One refund adjustment. */
    public record BnplExplainedAdjustmentView(int sequence, String instalmentId, String kind, String principalCancelled,
            String interestReleased, String lateFeeReleased, boolean whole) {}

    /** A dispute and the instalments it holds. */
    public record BnplExplainedDisputeView(String disputeId, String status, String amount, String openedOn,
            String resolvedOn, String refundId, List<String> heldDueDates) {}

    /** A condition change. */
    public record BnplExplainedConditionView(String businessDate, String kind, int daysPastDue, String bucket,
            boolean defaulted) {}

    /** A late fee. */
    public record BnplExplainedFeeView(String assessmentId, String instalmentId, String kind, String businessDate,
            String amount, String journalEntryId) {}

    /** A waiver. */
    public record BnplExplainedWaiverView(String waiverId, String assessmentId, String amount, String status,
            String decidedAt, String journalEntryId) {}

    /** A repayment reversal. */
    public record BnplExplainedReversalView(String reversalId, String repaymentId, String status, String valueDate,
            String decidedAt, String journalEntryId) {}

    /** A payoff quote. */
    public record BnplExplainedPayoffView(String quoteId, String businessDate, String goodThrough, String amount) {}

    /** The payoff's execution. */
    public record BnplExplainedPayoffExecutionView(String executionId, String quoteId, String businessDate,
            String valueDate) {}

    /** The closure. */
    public record BnplExplainedClosureView(String closureReason, String refund, String refundJournalEntryId) {}

    /** A journal entry on the obligation's accounts, the row that names it, and its lines there. */
    public record BnplExplainedEntryView(String journalEntryId, String valueDate, String explainedBy, String explainedById,
            List<BnplExplainedLineView> lines) {}

    /** One line of an entry on one of the obligation's accounts. */
    public record BnplExplainedLineView(String account, String direction, String amount) {}

    /** One of the six accounts: the ledger's balance, the rows' figure, and whether they agree. */
    public record BnplExplainedBalanceView(String account, String ledger, String rows, boolean explained) {}

    /** A replay's verdict: what diverged, by kind and row - never a figure. */
    public record BnplReplayView(String agreementId, String obligationId, String verdict, int replayed,
            List<BnplDivergenceView> divergences) {}

    /** One diverged fact. */
    public record BnplDivergenceView(String check, String subject) {}

    // ------------------------------------------------------------------ the doors

    /** {@code rawId} read, audited. */
    public BnplAgreementReadView read(String rawId) {
        BnplExplanations.Explanation explanation = explained(rawId, true);
        BnplRecord record = explanation.record();
        BnplAgreement latest = record.agreements().get(record.agreements().size() - 1).row();
        return new BnplAgreementReadView(latest.id().value().toString(), latest.version(), record.id().value().toString(),
                record.purchase().id().value().toString(), record.purchase().merchant().toString(),
                record.obligation().product().name(), record.obligation().plan().name(),
                record.obligation().status().name(), record.obligation().closureReason().map(Enum::name).orElse(null),
                record.obligation().currency().code(), record.obligation().purchaseDate().toString(),
                record.obligation().openedAt().toString(), latest.termsVersionId().value().toString(),
                latest.termsSha256Hex(), decimal(record.financing().split().gross()),
                decimal(record.financing().split().fee()), decimal(record.financing().split().net()),
                explanation.subledger().holds() ? "HOLDS" : "FAILS");
    }

    /** {@code rawId} explained, audited. */
    public BnplExplanationView explain(String rawId) {
        return view(explained(rawId, false));
    }

    /** {@code rawId} replayed with {@code reason}, the verdict answered once recorded. */
    public BnplReplayView replay(String rawId, String reason) {
        BnplAgreementId agreement = agreementId(rawId);
        try {
            BnplExplanations.reasoned(reason);
        } catch (BnplReasons.ReasonRequired refused) {
            throw new ApiException(BnplErrorCode.REASON_REQUIRED, "A BNPL replay without a usable reason",
                    refused.getMessage() + ".");
        }
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        BnplReplayProof.Replay replay;
        try {
            replay = transactions.inSnapshot(snapshot -> explanations.replay(snapshot, agreement, actor));
        } catch (BnplRepaymentReversals.SelfDealingRefused selfDealing) {
            throw refusedAndRecorded(selfDealing, actor, correlation);
        } catch (BnplExplanations.AgreementNotFound unknown) {
            throw notFound();
        }
        transactions.inTransaction(unitOfWork -> {
            explanations.recordReplay(unitOfWork, agreement, replay, reason, actor, correlation);
            return null;
        });
        return new BnplReplayView(agreement.value().toString(), replay.obligation().value().toString(),
                replay.verdict().name(), replay.replayed(), replay.findings().stream().map(finding ->
                        new BnplDivergenceView(finding.check().name(), finding.subject().toString())).toList());
    }

    private BnplExplanations.Explanation explained(String rawId, boolean readOnly) {
        BnplAgreementId agreement = agreementId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        try {
            return transactions.inTransaction(unitOfWork -> {
                repeatableRead(unitOfWork);
                return readOnly ? explanations.read(unitOfWork, agreement, actor, correlation)
                        : explanations.explain(unitOfWork, agreement, actor, correlation);
            });
        } catch (BnplRepaymentReversals.SelfDealingRefused selfDealing) {
            throw refusedAndRecorded(selfDealing, actor, correlation);
        } catch (BnplExplanations.AgreementNotFound unknown) {
            throw notFound();
        }
    }

    // ------------------------------------------------------------------ the explanation's view

    static BnplExplanationView view(BnplExplanations.Explanation explanation) {
        BnplRecord record = explanation.record();
        Map<UUID, Boolean> reversed = new HashMap<>();
        record.reversals().forEach(reversal -> reversed.put(reversal.row().repayment(),
                reversal.row().status() == com.finapp.bnpl.BnplCorrectionStatus.APPROVED));
        BnplExplainedFinancingView financing = new BnplExplainedFinancingView(decimal(record.financing().split().gross()),
                decimal(record.financing().split().fee()), decimal(record.financing().split().net()),
                record.financing().split().feeScheduleVersionId().toString(), record.financedAt().toString(),
                record.financing().entry().value().toString());
        List<BnplExplainedAgreementView> agreements = record.agreements().stream().map(version -> {
            BnplAgreement row = version.row();
            return new BnplExplainedAgreementView(row.version(), version.refund().map(UUID::toString).orElse(null),
                    version.scheduleVersion(), row.termsVersionId().value().toString(), row.scheduleEngine(),
                    row.accrualEngine().orElse(null), row.allocationEngine(), row.refundAllocationEngine(),
                    row.feeScheduleVersion().toString(), row.template().id(), row.template().version(),
                    row.canonicalFormat(), row.termsSha256Hex(), HexFormat.of().formatHex(row.documentSha256()),
                    row.createdAt().toString());
        }).toList();
        List<BnplExplainedScheduleView> schedules = record.schedules().stream().map(version ->
                new BnplExplainedScheduleView(version.row().version(), version.refund().map(UUID::toString).orElse(null),
                        version.row().engine(), decimal(version.row().principal()), version.row().start().toString(),
                        version.row().instalments().stream().map(instalment -> new BnplExplainedInstalmentView(
                                instalment.id().toString(), instalment.sequence(), instalment.dueDate().toString(),
                                decimal(instalment.principal()), decimal(instalment.interest()),
                                decimal(instalment.amount()))).toList())).toList();
        List<BnplExplainedAccrualView> accruals = record.accruals().stream().map(stamped -> {
            var row = stamped.row();
            return new BnplExplainedAccrualView(row.accrual().date().toString(),
                    row.accrual().period().start().toString(), row.accrual().period().endExclusive().toString(),
                    decimal(row.accrual().base()), row.accrual().cumulativeExact().toPlainString(),
                    decimal(row.accrual().posted()), row.agreementVersion(), row.accrual().engine().name(),
                    entry(row.entry()));
        }).toList();
        List<BnplExplainedBillingView> billings = record.billings().stream().map(billing -> new BnplExplainedBillingView(
                billing.row().id().toString(), billing.row().instalment().toString(), billing.row().sequence(),
                billing.row().periodStart().toString(), billing.row().dueDate().toString(),
                billing.businessDate().toString(), decimal(billing.row().principal()), decimal(billing.row().interest()),
                billing.row().scheduleVersion(), billing.row().agreementVersion(),
                billing.payoffExecution().map(UUID::toString).orElse(null), entry(billing.row().entry()))).toList();
        List<BnplExplainedApplicationView> applications = record.applications().stream().map(stamped -> {
            var row = stamped.row();
            List<BnplExplainedAllocationView> lines = new ArrayList<>();
            for (int i = 0; i < row.lines().size(); i++) {
                var line = row.lines().get(i);
                lines.add(new BnplExplainedAllocationView(i + 1, "DUE_ITEM", line.billing().toString(),
                        line.component().name(), decimal(line.amount())));
            }
            return new BnplExplainedApplicationView(row.id().toString(), row.billing().map(UUID::toString).orElse(null),
                    row.catchUp().orElse(null), decimal(row.amount()), row.valueDate().toString(), row.agreementVersion(),
                    row.entry().value().toString(), lines);
        }).toList();
        List<BnplExplainedRepaymentView> repayments = record.repayments().stream().map(stamped -> {
            var row = stamped.row();
            return new BnplExplainedRepaymentView(row.id().toString(), row.kind(), decimal(row.amount()),
                    row.valueDate().toString(), row.agreementVersion(), row.allocationEngine(),
                    row.entry().value().toString(), reversed.getOrDefault(row.id(), false),
                    row.allocations().stream().map(line -> new BnplExplainedAllocationView(line.sequence(),
                            line.target(), line.instalment().map(UUID::toString).orElse(null),
                            line.component().orElse(null), decimal(line.amount()))).toList());
        }).toList();
        List<BnplExplainedCollectionView> attempts = record.attempts().stream().map(stamped -> {
            var row = stamped.row();
            return new BnplExplainedCollectionView(row.id().toString(), row.instalment().toString(),
                    row.attemptDate().toString(), row.outcome().name(), decimal(row.due()), decimal(row.collected()),
                    row.repayment().map(UUID::toString).orElse(null));
        }).toList();
        List<BnplExplainedRefundView> refunds = record.refunds().stream().map(stamped -> {
            var row = stamped.row();
            return new BnplExplainedRefundView(row.id().toString(), row.door().name(), decimal(row.amount()),
                    decimal(row.refundedBefore()), decimal(row.feeReturned()), decimal(row.payableDebit()),
                    decimal(row.cancelledUnbilled()), decimal(row.cancelledBilled()), decimal(row.excess()),
                    row.excessTo().name(), decimal(row.interestAccruedReleased()), decimal(row.interestDueReleased()),
                    row.agreementVersion(), row.scheduleVersion(), row.valueDate().toString(),
                    row.entry().value().toString(), row.adjustments().stream().map(adjustment ->
                            new BnplExplainedAdjustmentView(adjustment.sequence(), adjustment.instalment().toString(),
                                    adjustment.kind().name(), decimal(adjustment.principalCancelled()),
                                    decimal(adjustment.interestReleased()), decimal(adjustment.lateFeeReleased()),
                                    adjustment.whole())).toList());
        }).toList();
        List<BnplExplainedDisputeView> disputes = record.disputes().stream().map(dispute -> new BnplExplainedDisputeView(
                dispute.id().value().toString(), dispute.status().name(), decimal(dispute.amount()),
                dispute.openedOn().map(LocalDate::toString).orElse(null),
                dispute.resolvedOn().map(LocalDate::toString).orElse(null),
                dispute.refund().map(UUID::toString).orElse(null),
                dispute.holds().stream().map(held -> held.dueDate().toString()).toList())).toList();
        List<BnplExplainedConditionView> conditions = record.conditions().stream().map(stamped ->
                new BnplExplainedConditionView(stamped.row().businessDate().toString(), stamped.row().kind().name(),
                        stamped.row().state().daysPastDue(), stamped.row().state().bucketName(),
                        stamped.row().state().defaulted())).toList();
        List<BnplExplainedFeeView> fees = record.fees().stream().map(stamped -> new BnplExplainedFeeView(
                stamped.row().id().toString(), stamped.row().instalment().toString(), stamped.row().kind().name(),
                stamped.row().businessDate().toString(), decimal(stamped.row().amount()),
                stamped.row().entry().value().toString())).toList();
        List<BnplExplainedWaiverView> waivers = record.waivers().stream().map(waiver -> new BnplExplainedWaiverView(
                waiver.id().toString(), waiver.assessment().toString(), decimal(waiver.amount()), waiver.status().name(),
                waiver.decidedAt().map(Instant::toString).orElse(null), entry(waiver.entry()))).toList();
        List<BnplExplainedReversalView> reversals = record.reversals().stream().map(reversal ->
                new BnplExplainedReversalView(reversal.row().id().toString(), reversal.row().repayment().toString(),
                        reversal.row().status().name(), reversal.row().valueDate().map(LocalDate::toString).orElse(null),
                        reversal.row().decidedAt().map(Instant::toString).orElse(null), entry(reversal.row().entry())))
                .toList();
        List<BnplExplainedPayoffView> quotes = record.quotes().stream().map(stamped -> new BnplExplainedPayoffView(
                stamped.row().id().toString(), stamped.row().businessDate().toString(),
                stamped.row().goodThrough().toString(), decimal(stamped.row().amount()))).toList();
        BnplExplainedPayoffExecutionView execution = record.execution().map(stamped ->
                new BnplExplainedPayoffExecutionView(stamped.row().id().toString(), stamped.row().quote().toString(),
                        stamped.row().businessDate().toString(), stamped.row().valueDate().toString())).orElse(null);
        BnplExplainedClosureView closure = record.closure().map(stamped -> new BnplExplainedClosureView(
                stamped.row().reason().name(), decimal(stamped.row().refund()), entry(stamped.row().refundEntry())))
                .orElse(null);

        // Every journal entry on the obligation's accounts, with the row that names it (none: unexplained) and its lines.
        Map<LedgerAccountId, AccountPurpose> purposes = new HashMap<>();
        record.ledgerAccounts().forEach(account -> purposes.put(account.id(), account.purpose()));
        Map<JournalEntryId, BnplSubledgerProof.Namer> named = BnplSubledgerProof.namedEntries(record);
        Map<JournalEntryId, List<PurposeBalances.DatedLine>> byEntry = new LinkedHashMap<>();
        record.journalLines().forEach(line -> byEntry.computeIfAbsent(line.entry(), key -> new ArrayList<>()).add(line));
        List<BnplExplainedEntryView> entries = new ArrayList<>();
        byEntry.forEach((id, lines) -> {
            Optional<BnplSubledgerProof.Namer> namer = Optional.ofNullable(named.get(id));
            entries.add(new BnplExplainedEntryView(id.value().toString(), lines.get(0).valueDate().toString(),
                    namer.map(BnplSubledgerProof.Namer::kind).orElse("UNEXPLAINED"),
                    namer.map(found -> found.row().toString()).orElse(null),
                    lines.stream().map(line -> new BnplExplainedLineView(String.valueOf(purposes.get(line.account())),
                            line.direction().name(), decimal(line.amount()))).toList()));
        });
        BnplSubledgerProof.Verdict verdict = explanation.subledger();
        List<BnplExplainedBalanceView> balances = verdict.figures().stream().map(figure -> new BnplExplainedBalanceView(
                figure.purpose().name(), decimal(figure.ledger()), decimal(figure.rows()), figure.holds())).toList();

        return new BnplExplanationView(record.obligation().agreement().value().toString(), record.id().value().toString(),
                record.purchase().id().value().toString(), record.purchase().merchant().toString(),
                record.obligation().product().name(), record.obligation().plan().name(),
                record.obligation().status().name(), record.obligation().closureReason().map(Enum::name).orElse(null),
                record.obligation().currency().code(), record.obligation().purchaseDate().toString(),
                record.obligation().openedAt().toString(), record.closedAt().map(Instant::toString).orElse(null),
                financing, agreements, schedules, accruals, billings, applications, repayments, attempts, refunds,
                disputes, conditions, fees, waivers, reversals, quotes, execution, closure, List.copyOf(entries), balances,
                verdict.holds() ? "HOLDS" : "FAILS", verdict.failures());
    }

    // ------------------------------------------------------------------ plumbing

    /** The refusal recorded FAILED in a transaction of its own, then answered {@code 403}. */
    private ApiException refusedAndRecorded(BnplRepaymentReversals.SelfDealingRefused selfDealing, Actor actor,
            CorrelationId correlation) {
        transactions.inTransaction(unitOfWork -> {
            explanations.recordRefusal(unitOfWork, selfDealing, actor, correlation);
            return null;
        });
        return new ApiException(BnplErrorCode.SELF_DEALING_REFUSED, "A BNPL investigation was refused",
                "an investigator never reads an agreement of their own party.");
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
        return new ApiException(BnplErrorCode.NOT_FOUND, "No BNPL agreement matches the request", "no such record.");
    }

    /** An agreement id is a UUIDv7 (ADR-0013): any other identifier names no agreement - the uniform 404, never a 500. */
    private static BnplAgreementId agreementId(String raw) {
        if (raw == null) {
            throw notFound();
        }
        try {
            return BnplAgreementId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException notAnAgreementId) {
            throw notFound();
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a BNPL investigation runs inside a correlation scope"))
                .correlationId();
    }
}
