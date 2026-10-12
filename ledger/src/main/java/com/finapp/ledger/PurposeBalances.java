package com.finapp.ledger;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Balances across every account of some purposes in one currency, and one account's lines by entry
 * (`P11-TSK-004`, ADR-0096 sections 5 and 6) - generic reads in the ledger's own vocabulary, so the
 * lending capital's headroom and its proof can be derived from journal lines without the ledger
 * learning what a loan or a contribution is.
 *
 * <h2>One statement, never a sum of per-account reads</h2>
 *
 * <p>A billing moves principal from {@code LOAN_PRINCIPAL} to {@code LOAN_PRINCIPAL_DUE} of the same
 * loan in one entry. Read account by account under {@code READ COMMITTED}, a billing committing
 * between two reads would be seen on one side and not the other, and the principal outstanding would
 * read SHORT - a headroom read too high, an over-deployment. So {@link #netDebit} reads every line of
 * every account of the purposes in ONE statement: one snapshot, every entry wholly in or wholly out.
 *
 * <h2>Folded through {@link Money}, never a SQL {@code SUM}</h2>
 *
 * <p>{@link BalanceDerivation}'s recorded rule ({@code INV-MON-01}, {@code INV-MON-03},
 * {@code INV-MON-06}): the lines stream and fold, and an underivable history refuses loudly.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface PurposeBalances<T> {

    /**
     * DR − CR over every line of every account whose purpose is in {@code purposes} and whose
     * currency is {@code currency}, read in one statement; zero at the currency's scale when there
     * are none.
     */
    Money netDebit(T unitOfWork, Set<AccountPurpose> purposes, CurrencyCode currency);

    /** One account's lines, each with its entry - in entry order, read in one statement. */
    List<EntryLine> linesOn(T unitOfWork, LedgerAccountId account);

    /**
     * Every line of {@code accounts} whose entry's value date is on or before {@code valueDateThrough}, each with its
     * account and that value date - read in ONE statement, so an entry moving value between two of the accounts (a
     * billing from not-due to due) is wholly in or wholly out (`P11-TSK-016`). Ordered by value date, then entry. The
     * caller folds the lines through {@link Money}; a balance "at the end of a date" is then the fold of the lines
     * dated on or before it - the accrual base of a loan, derived from journal lines and never from the projection
     * ({@code INV-BAL-05}).
     */
    List<DatedLine> datedLinesOn(T unitOfWork, Set<LedgerAccountId> accounts, LocalDate valueDateThrough);

    /** A journal line as its account sees it: the entry it belongs to, its side and its amount. */
    record EntryLine(JournalEntryId entry, Direction direction, Money amount) {

        public EntryLine {
            Objects.requireNonNull(entry, "entry must not be null");
            Objects.requireNonNull(direction, "direction must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
        }

        /** Identifiers only - an amount in a log line is {@code INV-AUD-02}'s to refuse. */
        @Override
        public String toString() {
            return "EntryLine[" + entry.value() + ", " + direction + "]";
        }
    }

    /** A journal line with its account and its entry's value date (`P11-TSK-016`). */
    record DatedLine(
            JournalEntryId entry, LedgerAccountId account, LocalDate valueDate, Direction direction, Money amount) {

        public DatedLine {
            Objects.requireNonNull(entry, "entry must not be null");
            Objects.requireNonNull(account, "account must not be null");
            Objects.requireNonNull(valueDate, "valueDate must not be null");
            Objects.requireNonNull(direction, "direction must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
        }

        /** Identifiers and the date only - never the amount ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "DatedLine[" + entry.value() + ", " + valueDate + ", " + direction + "]";
        }
    }
}
