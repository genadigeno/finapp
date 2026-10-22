package com.finapp.bnpl;

import com.finapp.creditmath.AllocationComponent;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PurposeBalances;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The subledger proof - the three-way proof's customer leg (`P12-TSK-025`; {@code PHASE_12_PLAN.md} section 12.12 point 2;
 * lending's {@code LoanSubledgerProof}; {@code INV-BNPL-01}, {@code INV-BAL-02}, {@code INV-LED-04}, {@code INV-BNPL-09}):
 * per obligation, each of its six ledger balances - folded from zero over its journal lines, never the projection -
 * equals what bnpl's immutable rows say it must be. The ledger is the authority, the rows explain it; a difference is an
 * alert, never a correction.
 *
 * <p><strong>The six identities</strong>, each on the account's normal side ({@code G} the financing's gross; "paid" every
 * repayment's due-item lines, "negated" an approved reversal's negating rows of them, "applied" the credit-balance
 * applications' lines; a refund's figures are its own columns and adjustments):
 * <ul>
 *   <li>{@code BNPL_PRINCIPAL} = G - billed principal - unbilled principal a refund cancelled;
 *   <li>{@code BNPL_PRINCIPAL_DUE} = billed principal - paid + negated - applied - billed principal a refund cancelled;
 *   <li>{@code BNPL_INTEREST_ACCRUED} = Σ accruals' posted - billed interest - accrued interest a refund released;
 *   <li>{@code BNPL_INTEREST_DUE} = billed interest - paid + negated - applied - due interest a refund released;
 *   <li>{@code BNPL_FEES_DUE} = assessed - paid + negated - applied - released by a refund (OQ-14) - released by a
 *       reversal it re-opened on an instalment a refund cancelled whole (the Phase 12 transition gate's A7-3) - approved
 *       waivers;
 *   <li>{@code BNPL_CREDIT_BALANCE} = held by repayments - their negated holds + a refund's excess held (OQ-13) -
 *       applied - refunded at the closure.
 * </ul>
 * And: every journal entry on the obligation's accounts is named by one of its rows (a raw posting no row explains fails
 * the obligation even where amounts net); the six accounts are exactly the obligation's, one each in its currency; no
 * balance on the wrong side ("no due account negative"); a {@code CLOSED} obligation's six balances zero; and no late fee
 * earns the platform less than nothing (the Phase 11 gate's R3) - each assessment less its approved waivers and the
 * refunds' and the reversals' releases on its instalment is never negative.
 *
 * <p>Pure: a function of one {@link BnplRecord} read in one {@code REPEATABLE READ} snapshot - the same verdict on every
 * instance for the same snapshot, whatever commits concurrently. Amounts fold through {@link Money}; nothing is repaired.
 */
public final class BnplSubledgerProof {

    /** The six per-obligation purposes, in the plan's order. */
    public static final List<AccountPurpose> PURPOSES = List.of(AccountPurpose.BNPL_PRINCIPAL,
            AccountPurpose.BNPL_PRINCIPAL_DUE, AccountPurpose.BNPL_INTEREST_ACCRUED, AccountPurpose.BNPL_INTEREST_DUE,
            AccountPurpose.BNPL_FEES_DUE, AccountPurpose.BNPL_CREDIT_BALANCE);

    /** One account's identity: the ledger's balance and the rows' figure, both on its normal side. */
    public record Figure(AccountPurpose purpose, Money ledger, Money rows) {

        public Figure {
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(ledger, "ledger");
            Objects.requireNonNull(rows, "rows");
        }

        /** The rows explain the ledger exactly. */
        public boolean holds() {
            return ledger.minorUnits() == rows.minorUnits() && ledger.scale() == rows.scale();
        }

        /** The purpose and the verdict - never an amount ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "Figure[" + purpose + ", " + (holds() ? "holds" : "differs") + "]";
        }
    }

    /** One obligation's verdict: each identity and the rules beside them. */
    public record Verdict(
            BnplObligationId obligation,
            BnplProduct product,
            BnplObligationStatus status,
            List<Figure> figures,
            boolean accountsComplete,
            int unexplainedEntries,
            boolean noneNegative,
            boolean terminalZero,
            boolean feeIncomeNonNegative) {

        public Verdict {
            Objects.requireNonNull(obligation, "obligation");
            Objects.requireNonNull(product, "product");
            Objects.requireNonNull(status, "status");
            figures = List.copyOf(figures);
        }

        /** Every identity and every rule holds. */
        public boolean holds() {
            return accountsComplete && unexplainedEntries == 0 && noneNegative && terminalZero && feeIncomeNonNegative
                    && figures.stream().allMatch(Figure::holds);
        }

        /** The figure of {@code purpose}. */
        public Figure figure(AccountPurpose purpose) {
            return figures.stream().filter(figure -> figure.purpose() == purpose).findFirst().orElseThrow();
        }

        /** What fails, by name - never an amount. */
        public List<String> failures() {
            List<String> failures = new ArrayList<>();
            figures.stream().filter(figure -> !figure.holds()).forEach(figure -> failures.add(figure.purpose().name()));
            if (!accountsComplete) {
                failures.add("ACCOUNTS");
            }
            if (unexplainedEntries > 0) {
                failures.add("UNEXPLAINED_ENTRIES");
            }
            if (!noneNegative) {
                failures.add("NEGATIVE");
            }
            if (!terminalZero) {
                failures.add("TERMINAL_NOT_ZERO");
            }
            if (!feeIncomeNonNegative) {
                failures.add("FEE_INCOME_NEGATIVE");
            }
            return List.copyOf(failures);
        }

        @Override
        public String toString() {
            return "Verdict[" + obligation.value() + ", " + (holds() ? "HOLDS" : "FAILS " + failures()) + "]";
        }
    }

    /** {@code record}'s verdict. */
    public Verdict prove(BnplRecord record) {
        Objects.requireNonNull(record, "record");
        Money zero = record.zero();
        Map<AccountPurpose, Money> ledger = ledger(record);
        boolean complete = record.ledgerAccounts().size() == PURPOSES.size();
        for (AccountPurpose purpose : PURPOSES) {
            complete &= record.ledgerAccounts().stream().filter(account -> account.purpose() == purpose
                    && account.currency().equals(record.obligation().currency())).count() == 1;
        }
        Map<AccountPurpose, Money> rows = rows(record);
        List<Figure> figures = new ArrayList<>();
        boolean noneNegative = true;
        boolean allZero = true;
        for (AccountPurpose purpose : PURPOSES) {
            Money balance = ledger.getOrDefault(purpose, zero);
            figures.add(new Figure(purpose, balance, rows.get(purpose)));
            noneNegative &= !balance.isNegative();
            allZero &= balance.isZero();
        }
        boolean terminal = record.obligation().status() == BnplObligationStatus.CLOSED;
        return new Verdict(record.id(), record.obligation().product(), record.obligation().status(), figures, complete,
                unexplained(record), noneNegative, !terminal || allZero, feeIncomeNonNegative(record));
    }

    // ------------------------------------------------------------------ the ledger's side

    /** Each purpose's balance on its normal side, DR - CR of every line of the obligation's own account from zero. */
    static Map<AccountPurpose, Money> ledger(BnplRecord record) {
        Money zero = record.zero();
        Map<LedgerAccountId, Money> netDebit = new HashMap<>();
        for (PurposeBalances.DatedLine line : record.journalLines()) {
            Money signed = line.direction() == Direction.DEBIT ? line.amount() : line.amount().negated();
            netDebit.merge(line.account(), signed, Money::plus);
        }
        Map<AccountPurpose, Money> ledger = new EnumMap<>(AccountPurpose.class);
        for (AccountPurpose purpose : PURPOSES) {
            Money balance = record.account(purpose).map(account -> netDebit.getOrDefault(account.id(), zero)).orElse(zero);
            ledger.put(purpose, purpose == AccountPurpose.BNPL_CREDIT_BALANCE ? balance.negated() : balance);
        }
        return ledger;
    }

    // ------------------------------------------------------------------ the rows' side

    /** What the rows say each account holds, on its normal side. */
    static Map<AccountPurpose, Money> rows(BnplRecord record) {
        Money zero = record.zero();
        Money principal = record.financing().split().gross();
        Money principalDue = zero;
        Money accrued = zero;
        Money interestDue = zero;
        Money feesDue = zero;
        Money credit = zero;
        for (BnplRecord.Billing billing : record.billings()) {
            principal = principal.minus(billing.row().principal());
            principalDue = principalDue.plus(billing.row().principal());
            accrued = accrued.minus(billing.row().interest());
            interestDue = interestDue.plus(billing.row().interest());
        }
        for (BnplRecord.Stamped<BnplInterestAccrual> accrual : record.accruals()) {
            accrued = accrued.plus(accrual.row().accrual().posted());
        }
        for (BnplRecord.Stamped<BnplFeeAssessment> fee : record.fees()) {
            feesDue = feesDue.plus(fee.row().amount());
        }
        for (BnplWaiver waiver : record.waivers()) {
            if (waiver.status() == BnplCorrectionStatus.APPROVED) {
                feesDue = feesDue.minus(waiver.amount());
            }
        }
        for (BnplRecord.Stamped<BnplRefund> stamped : record.refunds()) {
            BnplRefund refund = stamped.row();
            principal = principal.minus(refund.cancelledUnbilled());
            principalDue = principalDue.minus(refund.cancelledBilled());
            accrued = accrued.minus(refund.interestAccruedReleased());
            interestDue = interestDue.minus(refund.interestDueReleased());
            for (BnplRefund.Adjustment adjustment : refund.adjustments()) {
                feesDue = feesDue.minus(adjustment.lateFeeReleased());
            }
            if (refund.excessTo() == BnplRefund.ExcessTo.CREDIT_BALANCE) {
                credit = credit.plus(refund.excess());
            }
        }
        for (BnplRecord.Stamped<BnplCreditBalanceApplication> stamped : record.applications()) {
            credit = credit.minus(stamped.row().amount());
            for (BnplCreditBalanceApplication.Line line : stamped.row().lines()) {
                switch (line.component()) {
                    case FEES -> feesDue = feesDue.minus(line.amount());
                    case INTEREST -> interestDue = interestDue.minus(line.amount());
                    case PRINCIPAL -> principalDue = principalDue.minus(line.amount());
                }
            }
        }
        // A reversal's release of a late fee it re-opened on an instalment a refund cancelled whole (A7-3, bnpl V019).
        for (BnplRecord.Reversal reversal : record.reversals()) {
            for (BnplRecord.FeeRelease release : reversal.releases()) {
                feesDue = feesDue.minus(release.amount());
            }
        }
        // Paid, and re-opened by an approved reversal's negating rows (F8: the exact swap of F3's credits).
        Map<UUID, Money> negated = negated(record);
        for (BnplRecord.Stamped<BnplPurchaseStore.Repayment> stamped : record.repayments()) {
            for (BnplPurchaseStore.Allocation line : stamped.row().allocations()) {
                Money net = line.amount().minus(negated.getOrDefault(line.id(), zero));
                switch (line.target()) {
                    case "CREDIT_BALANCE" -> credit = credit.plus(net);
                    case "DUE_ITEM" -> {
                        switch (AllocationComponent.valueOf(line.component().orElseThrow())) {
                            case FEES -> feesDue = feesDue.minus(net);
                            case INTEREST -> interestDue = interestDue.minus(net);
                            case PRINCIPAL -> principalDue = principalDue.minus(net);
                        }
                    }
                    default -> principal = principal.minus(net); // NOT_DUE_PRINCIPAL: refused by V010's CHECK
                }
            }
        }
        if (record.closure().isPresent()) {
            credit = credit.minus(record.closure().get().row().refund());
        }
        Map<AccountPurpose, Money> rows = new EnumMap<>(AccountPurpose.class);
        rows.put(AccountPurpose.BNPL_PRINCIPAL, principal);
        rows.put(AccountPurpose.BNPL_PRINCIPAL_DUE, principalDue);
        rows.put(AccountPurpose.BNPL_INTEREST_ACCRUED, accrued);
        rows.put(AccountPurpose.BNPL_INTEREST_DUE, interestDue);
        rows.put(AccountPurpose.BNPL_FEES_DUE, feesDue);
        rows.put(AccountPurpose.BNPL_CREDIT_BALANCE, credit);
        return rows;
    }

    /** What every approved reversal's negating rows negated, by allocation row. */
    static Map<UUID, Money> negated(BnplRecord record) {
        Map<UUID, Money> negated = new HashMap<>();
        for (BnplRecord.Reversal reversal : record.reversals()) {
            if (reversal.row().status() != BnplCorrectionStatus.APPROVED) {
                continue;
            }
            reversal.negations().forEach(negation -> negated.merge(negation.allocation(), negation.amount(), Money::plus));
        }
        return negated;
    }

    /**
     * Whether every late fee's income is zero or more (R3): its assessment less the approved waivers of it and the refunds'
     * releases on its instalment (one {@code LATE} fee per instalment, {@code UNIQUE (instalment_id, kind)}).
     */
    static boolean feeIncomeNonNegative(BnplRecord record) {
        Map<UUID, Money> income = new HashMap<>();
        Map<UUID, UUID> assessmentOf = new HashMap<>();
        for (BnplRecord.Stamped<BnplFeeAssessment> fee : record.fees()) {
            income.merge(fee.row().id(), fee.row().amount(), Money::plus);
            assessmentOf.put(fee.row().instalment(), fee.row().id());
        }
        for (BnplWaiver waiver : record.waivers()) {
            if (waiver.status() == BnplCorrectionStatus.APPROVED) {
                income.merge(waiver.assessment(), waiver.amount().negated(), Money::plus);
            }
        }
        for (BnplRecord.Stamped<BnplRefund> refund : record.refunds()) {
            for (BnplRefund.Adjustment adjustment : refund.row().adjustments()) {
                if (adjustment.lateFeeReleased().isPositive()) {
                    UUID assessment = assessmentOf.get(adjustment.instalment());
                    if (assessment == null) {
                        return false; // a release of a fee no assessment of the instalment carries
                    }
                    income.merge(assessment, adjustment.lateFeeReleased().negated(), Money::plus);
                }
            }
        }
        for (BnplRecord.Reversal reversal : record.reversals()) {
            for (BnplRecord.FeeRelease release : reversal.releases()) {
                UUID assessment = assessmentOf.get(release.instalment());
                if (assessment == null) {
                    return false; // a release of a fee no assessment of the instalment carries
                }
                income.merge(assessment, release.amount().negated(), Money::plus);
            }
        }
        return income.values().stream().noneMatch(Money::isNegative);
    }

    // ------------------------------------------------------------------ every entry named

    /** The journal entries on the obligation's accounts no row of the obligation names. */
    static int unexplained(BnplRecord record) {
        Set<JournalEntryId> named = namedEntries(record).keySet();
        Set<JournalEntryId> seen = new HashSet<>();
        int unexplained = 0;
        for (PurposeBalances.DatedLine line : record.journalLines()) {
            if (seen.add(line.entry()) && !named.contains(line.entry())) {
                unexplained++;
            }
        }
        return unexplained;
    }

    /** The row that names a journal entry: its kind, its id and the instant it was written at. */
    public record Namer(String kind, UUID row, Instant at) {

        public Namer {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(row, "row");
            Objects.requireNonNull(at, "at");
        }
    }

    /** Every journal entry a row of the obligation names, with that row. */
    public static Map<JournalEntryId, Namer> namedEntries(BnplRecord record) {
        Map<JournalEntryId, Namer> named = new HashMap<>();
        named.put(record.financing().entry(), new Namer("FINANCING", record.purchase().id().value(),
                record.financedAt()));
        record.billings().forEach(billing -> billing.row().entry().ifPresent(entry -> named.put(entry,
                new Namer("BILLING", billing.row().id(), billing.at()))));
        record.accruals().forEach(accrual -> accrual.row().entry().ifPresent(entry -> named.put(entry,
                new Namer("ACCRUAL", accrual.row().id(), accrual.at()))));
        record.applications().forEach(application -> named.put(application.row().entry(),
                new Namer("CREDIT_APPLICATION", application.row().id(), application.at())));
        record.repayments().forEach(repayment -> named.put(repayment.row().entry(),
                new Namer("REPAYMENT", repayment.row().id(), repayment.at())));
        record.refunds().forEach(refund -> named.put(refund.row().entry(),
                new Namer("REFUND", refund.row().id(), refund.at())));
        record.fees().forEach(fee -> named.put(fee.row().entry(), new Namer("FEE", fee.row().id(), fee.at())));
        record.reversals().forEach(reversal -> reversal.row().entry().ifPresent(entry -> named.put(entry,
                new Namer("REPAYMENT_REVERSAL", reversal.row().id(),
                        reversal.row().decidedAt().orElse(reversal.row().proposedAt())))));
        record.reversals().forEach(reversal -> reversal.releases().forEach(release -> named.put(release.entry(),
                new Namer("REVERSAL_FEE_RELEASE", reversal.row().id(),
                        reversal.row().decidedAt().orElse(reversal.row().proposedAt())))));
        record.waivers().forEach(waiver -> waiver.entry().ifPresent(entry -> named.put(entry,
                new Namer("WAIVER", waiver.id(), waiver.decidedAt().orElse(waiver.proposedAt())))));
        record.closure().ifPresent(closure -> closure.row().refundEntry().ifPresent(entry -> named.put(entry,
                new Namer("CLOSURE_REFUND", closure.row().id(), closure.at()))));
        return named;
    }
}
