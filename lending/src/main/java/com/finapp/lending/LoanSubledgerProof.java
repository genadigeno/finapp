package com.finapp.lending;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.PurposeBalances;
import com.finapp.sharedkernel.money.Money;
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
 * The subledger proof (`P11-TSK-029`; PHASE_11_PLAN.md sections 12.8 "three outstanding numbers, one authority" and 13.1;
 * ADR-0096 point 3; {@code INV-LND-01}, {@code INV-BAL-02}, {@code INV-LED-04}): per loan account, each of its six ledger
 * balances - derived from zero over its journal lines, never the projection - equals what lending's immutable rows say it
 * must be. The ledger is the authority; the rows explain it; a difference is an alert, never a correction.
 *
 * <p><strong>The six identities</strong> (each on the account's normal side; reversals are the approved reversals'
 * negating rows, payers the repayments' and the credit-balance applications' lines):
 * <ul>
 *   <li>{@code LOAN_PRINCIPAL} = disbursed (an instalment loan's agreement principal, once its disbursement posted) + drawn
 *       (a line's draws) - billed principal (billings, statements) + re-scheduled (rule 13, `-027`) - paid down
 *       ({@code NOT_DUE_PRINCIPAL} lines: a line's paydowns and an instalment loan's prepayments, `-023`, `-028`) +
 *       reversed paydowns;
 *   <li>{@code LOAN_PRINCIPAL_DUE} = billed principal - re-scheduled - paid + reversed;
 *   <li>{@code LOAN_INTEREST_ACCRUED} = Σ accruals' posted - billed interest - waived from accrued (none: as built every
 *       interest waiver releases a billed item's due interest, `-025`);
 *   <li>{@code LOAN_INTEREST_DUE} = billed interest - interest waived - paid + reversed;
 *   <li>{@code LOAN_FEES_DUE} = assessed - fee waived - paid + reversed;
 *   <li>{@code LOAN_CREDIT_BALANCE} = held (the repayments' {@code CREDIT_BALANCE} lines) + fees refunded (rule 8a) -
 *       reversed held - applied - refunded at closure.
 * </ul>
 * And: every journal entry on the loan's accounts is named by one of its rows (a raw posting no row explains fails the
 * account even where amounts happen to net); the six accounts are exactly the loan's, one each in its currency; no balance
 * is on the wrong side ("no due account negative"); a {@code CLOSED} or {@code CANCELLED} account's six balances are zero;
 * and no fee earns the platform less than nothing (the Phase 11 gate's R3): each assessment's income - assessed less its
 * approved fee waivers and refunds, the {@code LOAN_FEE_INCOME} lines its rows name - is never negative. The income account
 * is the currency's, shared, so its lines are judged through the rows that name them; a fee forgiven twice (refunded, the
 * repayment that paid it reversed, then waived) fails {@code FEE_INCOME_NEGATIVE} even where every one of the six balances
 * is explained.
 *
 * <p>Pure: a function of one {@link LoanRecord}, read in one {@code REPEATABLE READ} snapshot by the caller - the same verdict
 * on every instance for the same snapshot, whatever commits concurrently. Amounts fold through {@link Money}
 * ({@code INV-MON-01}); nothing here writes, and nothing is repaired.
 */
public final class LoanSubledgerProof {

    /** The six per-account purposes, in the plan's order. */
    public static final List<AccountPurpose> PURPOSES = LoanAccounts.purposes();

    /** One account's identity: the ledger's balance and the rows' figure, both on the account's normal side. */
    public record Figure(AccountPurpose purpose, Money ledger, Money rows) {

        public Figure {
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(ledger, "ledger");
            Objects.requireNonNull(rows, "rows");
        }

        /** The rows explain the ledger exactly. */
        public boolean holds() {
            return ledger.minorUnits() == rows.minorUnits();
        }

        /** The purpose and the verdict - never an amount ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "Figure[" + purpose + ", " + (holds() ? "holds" : "differs") + "]";
        }
    }

    /** One account's verdict: each identity, and the four rules beside them. */
    public record Verdict(
            LoanId loan,
            LoanKind kind,
            LoanStatus status,
            List<Figure> figures,
            boolean accountsComplete,
            int unexplainedEntries,
            boolean noneNegative,
            boolean terminalZero,
            boolean feeIncomeNonNegative) {

        public Verdict {
            Objects.requireNonNull(loan, "loan");
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
            return "Verdict[" + loan.value() + ", " + (holds() ? "HOLDS" : "FAILS " + failures()) + "]";
        }
    }

    /** {@code record}'s verdict. */
    public Verdict prove(LoanRecord record) {
        Objects.requireNonNull(record, "record");
        Money zero = record.zero();

        // The ledger: DR - CR per account from zero, every line of the loan's own accounts (INV-BAL-02).
        Map<LedgerAccountId, Money> netDebit = new HashMap<>();
        for (PurposeBalances.DatedLine line : record.journalLines()) {
            Money signed = line.direction() == Direction.DEBIT ? line.amount() : line.amount().negated();
            netDebit.merge(line.account(), signed, Money::plus);
        }
        Map<AccountPurpose, Money> ledger = new EnumMap<>(AccountPurpose.class);
        boolean complete = record.ledgerAccounts().size() == PURPOSES.size();
        for (AccountPurpose purpose : PURPOSES) {
            long matching = record.ledgerAccounts().stream()
                    .filter(account -> account.purpose() == purpose && account.currency().equals(record.loan().currency()))
                    .count();
            complete &= matching == 1;
            Money balance = record.account(purpose).map(account -> netDebit.getOrDefault(account.id(), zero)).orElse(zero);
            ledger.put(purpose, purpose == AccountPurpose.LOAN_CREDIT_BALANCE ? balance.negated() : balance);
        }

        Map<AccountPurpose, Money> rows = rows(record);
        List<Figure> figures = new ArrayList<>();
        boolean noneNegative = true;
        boolean allZero = true;
        for (AccountPurpose purpose : PURPOSES) {
            Money balance = ledger.get(purpose);
            figures.add(new Figure(purpose, balance, rows.get(purpose)));
            noneNegative &= !balance.isNegative();
            allZero &= balance.isZero();
        }
        boolean terminal = record.loan().status() == LoanStatus.CLOSED || record.loan().status() == LoanStatus.CANCELLED;
        return new Verdict(record.id(), record.loan().kind(), record.loan().status(), figures, complete,
                unexplained(record), noneNegative, !terminal || allZero, feeIncomeNonNegative(record));
    }

    /**
     * Whether every fee assessment's income is zero or more: assessed (rule 4's credit to {@code LOAN_FEE_INCOME}) less its
     * approved fee waivers (rule 8) and refunds (rule 8a), each a debit of it.
     */
    static boolean feeIncomeNonNegative(LoanRecord record) {
        Map<UUID, Money> income = new HashMap<>();
        for (LoanRecord.Fee fee : record.fees()) {
            income.merge(fee.id(), fee.amount(), Money::plus);
        }
        for (LoanRecord.WaiverRow waiver : record.waivers()) {
            if (waiver.approved() && waiver.kind() != WaiverKind.INTEREST) {
                waiver.feeAssessmentId().ifPresent(fee -> income.merge(fee, waiver.amount().negated(), Money::plus));
            }
        }
        return income.values().stream().noneMatch(Money::isNegative);
    }

    // ------------------------------------------------------------------ the rows' side

    /** What the rows say each account holds, on its normal side. */
    static Map<AccountPurpose, Money> rows(LoanRecord record) {
        Money zero = record.zero();
        Money principal = zero;
        Money principalDue = zero;
        Money accrued = zero;
        Money interestDue = zero;
        Money feesDue = zero;
        Money credit = zero;

        // Disbursed or drawn (rule 1).
        if (record.loan().kind() == LoanKind.INSTALMENT
                && record.disbursement().filter(found -> "POSTED".equals(found.status())).isPresent()
                && !record.agreements().isEmpty()) {
            principal = principal.plus(record.money(record.agreements().get(0).amountMinor()));
        }
        for (LoanRecord.Draw draw : record.draws()) {
            principal = principal.plus(draw.amount());
        }
        // Accrued (rule 2), billed (rule 3).
        for (LoanRecord.Accrual accrual : record.accruals()) {
            accrued = accrued.plus(accrual.posted());
        }
        for (LoanRecord.Billing billing : record.billings()) {
            principal = principal.minus(billing.principalDue());
            principalDue = principalDue.plus(billing.principalDue());
            accrued = accrued.minus(billing.interestDue());
            interestDue = interestDue.plus(billing.interestDue());
        }
        for (LoanRecord.StatementRow statement : record.statements()) {
            principal = principal.minus(statement.principalBilled());
            principalDue = principalDue.plus(statement.principalBilled());
            accrued = accrued.minus(statement.interestBilled());
            interestDue = interestDue.plus(statement.interestBilled());
        }
        // Assessed (rule 4).
        for (LoanRecord.Fee fee : record.fees()) {
            feesDue = feesDue.plus(fee.amount());
        }
        // Waived (rules 8, 8a, 9) - approved decisions only.
        for (LoanRecord.WaiverRow waiver : record.waivers()) {
            if (!waiver.approved()) {
                continue;
            }
            switch (waiver.kind()) {
                case FEE -> feesDue = feesDue.minus(waiver.amount());
                case INTEREST -> interestDue = interestDue.minus(waiver.amount());
                case FEE_REFUND -> credit = credit.plus(waiver.amount());
            }
        }
        // Re-scheduled (rule 13).
        for (LoanRecord.Rescheduling rescheduling : record.reschedulings()) {
            principal = principal.plus(rescheduling.principal());
            principalDue = principalDue.minus(rescheduling.principal());
        }
        // Applied (rule 6).
        for (LoanRecord.Application application : record.applications()) {
            credit = credit.minus(application.amount());
            for (LoanRecord.ApplicationLine line : application.lines()) {
                switch (line.component()) {
                    case FEES -> feesDue = feesDue.minus(line.amount());
                    case INTEREST -> interestDue = interestDue.minus(line.amount());
                    case PRINCIPAL -> principalDue = principalDue.minus(line.amount());
                }
            }
        }
        // Paid (rule 5) and reversed (rule 10): an approved reversal's negating rows re-open exactly what its lines paid.
        Map<UUID, LoanRecord.PaymentLine> lines = new HashMap<>();
        for (LoanRecord.Payment payment : record.repayments()) {
            for (LoanRecord.PaymentLine line : payment.lines()) {
                lines.put(line.id(), line);
            }
        }
        List<Signed> paid = new ArrayList<>();
        record.repayments().forEach(payment -> payment.lines().forEach(line -> paid.add(new Signed(line, false))));
        for (LoanRecord.Reversal reversal : record.reversals()) {
            if (!"APPROVED".equals(reversal.status())) {
                continue;
            }
            for (LoanRecord.Negation negation : reversal.negations()) {
                LoanRecord.PaymentLine line = lines.get(negation.allocationId());
                if (line != null) {
                    paid.add(new Signed(new LoanRecord.PaymentLine(line.id(), line.ordinal(), line.target(),
                            line.billingId(), line.component(), negation.amount()), true));
                }
            }
        }
        for (Signed signed : paid) {
            Money amount = signed.reversed() ? signed.line().amount().negated() : signed.line().amount();
            switch (signed.line().target()) {
                case NOT_DUE_PRINCIPAL -> principal = principal.minus(amount);
                case CREDIT_BALANCE -> credit = credit.plus(amount);
                case DUE_ITEM -> {
                    switch (signed.line().component().orElseThrow()) {
                        case FEES -> feesDue = feesDue.minus(amount);
                        case INTEREST -> interestDue = interestDue.minus(amount);
                        case PRINCIPAL -> principalDue = principalDue.minus(amount);
                    }
                }
            }
        }
        // Refunded at closure.
        if (record.closure().isPresent()) {
            credit = credit.minus(record.closure().get().refund());
        }

        Map<AccountPurpose, Money> rows = new EnumMap<>(AccountPurpose.class);
        rows.put(AccountPurpose.LOAN_PRINCIPAL, principal);
        rows.put(AccountPurpose.LOAN_PRINCIPAL_DUE, principalDue);
        rows.put(AccountPurpose.LOAN_INTEREST_ACCRUED, accrued);
        rows.put(AccountPurpose.LOAN_INTEREST_DUE, interestDue);
        rows.put(AccountPurpose.LOAN_FEES_DUE, feesDue);
        rows.put(AccountPurpose.LOAN_CREDIT_BALANCE, credit);
        return rows;
    }

    private record Signed(LoanRecord.PaymentLine line, boolean reversed) {}

    // ------------------------------------------------------------------ every entry named

    /** The journal entries on the loan's accounts no row of the loan names. */
    static int unexplained(LoanRecord record) {
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

    /** The row that names a journal entry: its kind, its id and its place in the loan's history. */
    public record Namer(String kind, UUID row, Stamp stamp) {

        public Namer {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(row, "row");
            Objects.requireNonNull(stamp, "stamp");
        }
    }

    /**
     * A fact's place in its loan's history: its {@code recorded_order} (lending {@code V025}) - drawn under the loan's
     * row lock, so clock-independent - then, for facts written before {@code V025} ({@link LoanRecord#UNORDERED}), its
     * stamp.
     */
    public record Stamp(long order, java.time.Instant at) implements Comparable<Stamp> {

        public Stamp {
            Objects.requireNonNull(at, "at");
        }

        @Override
        public int compareTo(Stamp other) {
            int byOrder = Long.compare(order, other.order);
            return byOrder != 0 ? byOrder : at.compareTo(other.at);
        }

        /** Whether this fact was written before {@code other}. */
        public boolean before(Stamp other) {
            return compareTo(other) < 0;
        }
    }

    /** Every journal entry a row of the loan names, with that row (its kind, id and instant). */
    public static Map<JournalEntryId, Namer> namedEntries(LoanRecord record) {
        Map<JournalEntryId, Namer> named = new HashMap<>();
        record.disbursement().ifPresent(found -> found.entry().ifPresent(entry -> named.put(entry,
                new Namer("DISBURSEMENT", found.id(), new Stamp(LoanRecord.UNORDERED, found.decidedAt().orElse(found.createdAt()))))));
        record.draws().forEach(draw -> named.put(draw.entry(), new Namer("DRAW", draw.id(), new Stamp(draw.order(), draw.createdAt()))));
        record.accruals().forEach(accrual -> accrual.entry().ifPresent(entry -> named.put(entry,
                new Namer("ACCRUAL", accrual.id(), new Stamp(accrual.order(), accrual.createdAt())))));
        record.billings().forEach(billing -> billing.entry().ifPresent(entry -> named.put(entry,
                new Namer("BILLING", billing.id(), new Stamp(billing.order(), billing.createdAt())))));
        record.statements().forEach(statement -> statement.entry().ifPresent(entry -> named.put(entry,
                new Namer("STATEMENT", statement.id(), new Stamp(statement.order(), statement.createdAt())))));
        record.applications().forEach(application -> named.put(application.entry(),
                new Namer("CREDIT_APPLICATION", application.id(), new Stamp(application.order(), application.createdAt()))));
        record.repayments().forEach(payment -> named.put(payment.entry(),
                new Namer("REPAYMENT", payment.id(), new Stamp(payment.order(), payment.createdAt()))));
        record.reversals().forEach(reversal -> reversal.entry().ifPresent(entry -> named.put(entry,
                new Namer("REPAYMENT_REVERSAL", reversal.id(),
                new Stamp(reversal.order(), reversal.decidedAt().orElse(reversal.proposedAt()))))));
        record.fees().forEach(fee -> named.put(fee.entry(), new Namer("FEE", fee.id(), new Stamp(fee.order(), fee.createdAt()))));
        record.waivers().forEach(waiver -> waiver.entry().ifPresent(entry -> named.put(entry,
                new Namer("WAIVER", waiver.id(),
                new Stamp(waiver.order(), waiver.decidedAt().orElse(waiver.proposedAt()))))));
        record.reschedulings().forEach(rescheduling -> named.put(rescheduling.entry(),
                new Namer("RESCHEDULING", rescheduling.id(), new Stamp(rescheduling.order(), rescheduling.createdAt()))));
        record.closure().ifPresent(closure -> closure.refundEntry().ifPresent(entry -> named.put(entry,
                new Namer("CLOSURE_REFUND", closure.id(), new Stamp(closure.order(), closure.createdAt())))));
        return named;
    }
}
