package com.finapp.lending;

import com.finapp.ledger.Direction;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.Money;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * What a write-off takes (`P14-TSK-021`; ADR-0096 rule 14): the whole of each of the loan's five receivables, each on its
 * normal side, as read under the loan's row lock - and the {@code revision} that read saw: how many journal lines stand on the
 * loan's six accounts. Lines are append-only, so a revision only rises: a repayment and its reversal leave the figures where
 * they were and the revision higher, which is why it is compared and not the figures alone.
 *
 * <p>Never partial: {@link #lines} credits every receivable that holds anything by exactly what it holds, each to zero, against
 * one debit of the expense - balanced in the loan's one currency by construction. The credit balance is not here: it is money
 * held for the customer, a liability, and an account holding one is refused (its servicing step applies it first).
 *
 * <p>Pure, and it prints no figure ({@code INV-AUD-02}).
 */
public record LoanWriteOffPosition(
        Money principal, Money principalDue, Money interestAccrued, Money interestDue, Money feesDue, long revision) {

    public LoanWriteOffPosition {
        Objects.requireNonNull(principal, "principal must not be null");
        Objects.requireNonNull(principalDue, "principalDue must not be null");
        Objects.requireNonNull(interestAccrued, "interestAccrued must not be null");
        Objects.requireNonNull(interestDue, "interestDue must not be null");
        Objects.requireNonNull(feesDue, "feesDue must not be null");
        for (Money figure : List.of(principal, principalDue, interestAccrued, interestDue, feesDue)) {
            if (figure.isNegative() || !figure.currency().equals(principal.currency()) || figure.scale() != principal.scale()) {
                throw new IllegalArgumentException("a write-off's figures are never negative, in one currency and scale");
            }
        }
        if (revision < 0) {
            throw new IllegalArgumentException("a revision counts journal lines");
        }
    }

    /**
     * All five. A sum of five balances the ledger already holds as {@code bigint} can leave a {@code long} only when the
     * books are already impossible.
     *
     * @throws com.finapp.sharedkernel.money.MonetaryOverflowException the total leaves the amount the platform can hold
     */
    public Money total() {
        return principalTotal().plus(interestAccrued).plus(interestDue).plus(feesDue);
    }

    /** The principal part, not yet due and due: what capital funded (A29), and all the headroom ever counts. */
    public Money principalTotal() {
        return principal.plus(principalDue);
    }

    /** Whether every figure and the revision are {@code other}'s. */
    public boolean sameAs(LoanWriteOffPosition other) {
        return equals(other);
    }

    /**
     * Rule 14: {@code DR LOAN_WRITE_OFF_EXPENSE} the total, {@code CR} each receivable holding anything by exactly that.
     *
     * @throws IllegalStateException nothing to write off: no entry is built for it
     */
    List<JournalLine> lines(LedgerAccountId expense, LoanReceivables.Accounts accounts) {
        Objects.requireNonNull(expense, "expense");
        Objects.requireNonNull(accounts, "accounts");
        Money total = total();
        if (!total.isPositive()) {
            throw new IllegalStateException("a write-off takes something");
        }
        List<JournalLine> lines = new ArrayList<>();
        lines.add(new JournalLine(expense, Direction.DEBIT, total));
        credit(lines, accounts.principal(), principal);
        credit(lines, accounts.principalDue(), principalDue);
        credit(lines, accounts.interestAccrued(), interestAccrued);
        credit(lines, accounts.interestDue(), interestDue);
        credit(lines, accounts.feesDue(), feesDue);
        return List.copyOf(lines);
    }

    private static void credit(List<JournalLine> lines, LedgerAccountId account, Money figure) {
        if (figure.isPositive()) {
            lines.add(new JournalLine(account, Direction.CREDIT, figure));
        }
    }

    /** The revision only - never a figure ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "LoanWriteOffPosition[revision=" + revision + "]";
    }
}
