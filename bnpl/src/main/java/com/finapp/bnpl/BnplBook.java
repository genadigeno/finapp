package com.finapp.bnpl;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PurposeBalances;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The ledger, as bnpl's money-moving acts command and read it (`P12-TSK-013`; {@code INV-LED-04}, {@code INV-BNPL-01},
 * ADR-0103) - bnpl's one seam over the ledger's published API, so every act's sequence of locks and postings is bnpl's
 * own code and observable without a database. {@link LedgerBnplBook} is the one implementation, over
 * {@code PostingService}, {@code LedgerAccountStore}, {@code AvailableBalance}, {@code BalanceDerivation} and the chart.
 * Every method runs on the caller's unit of work: what it posts commits or rolls back with bnpl's rows.
 *
 * <p>Nothing here decides: the order of the locks - (P) as one element in {@code UUID.compareTo} order, then (L8) - and
 * every judgement under them are the act's ({@link BnplAcceptances}).
 */
public interface BnplBook {

    /** The obligation's six accounts in {@code currency}, opened or converged on ({@link BnplAccounts#openFor}). */
    BnplLedgerAccounts openAccounts(Connection unitOfWork, BnplObligationId obligation, CurrencyCode currency);

    /** The obligation's accounts as they stand - empty when none was opened. Unlocked. */
    List<LedgerAccount> accountsOf(Connection unitOfWork, BnplObligationId obligation);

    /** The platform's operational account for {@code purpose} in {@code currency} - the chart's, seeded (ledger V027). */
    LedgerAccountId operational(Connection unitOfWork, AccountPurpose purpose, CurrencyCode currency);

    /** The account's row {@code FOR UPDATE}, its status as found under the lock, or empty when it does not exist. */
    Optional<LedgerAccountStatus> lockForUpdate(Connection unitOfWork, LedgerAccountId account);

    /** What {@code account} can spend - settled less its active holds - judged under the caller's row lock on it. */
    Money availableUnderLock(Connection unitOfWork, LedgerAccountId account);

    /** (L8) the projection rows of {@code accounts}, in the ledger's one order, before the first posting. */
    void lockBalancesInOrder(Connection unitOfWork, Collection<LedgerAccountId> accounts);

    /**
     * Posts {@code command}'s entry under its key - once.
     *
     * @throws IllegalStateException the key had already posted: every bnpl key names an id minted in this transaction,
     *     so a replay is a defect, never a second posting ({@code INV-IDEM-01})
     */
    JournalEntryId post(Connection unitOfWork, PostingCommand command);

    /** The settled balance of {@code account}, by its normal balance, derived from its committed journal lines. */
    Money balanceOf(Connection unitOfWork, LedgerAccount account);

    /**
     * Every line of {@code accounts} whose entry is valued on or before {@code valueDateThrough}, with its account and that
     * value date, ordered by value date - read in ONE statement, so an entry moving value between two of them (a billing
     * from not-due to due) is wholly in or wholly out ({@code PurposeBalances#datedLinesOn}; `P12-TSK-017`): the accrual
     * base at the end of a date is the fold of the principal accounts' lines dated on or before it - journal lines, never
     * the projection ({@code INV-BAL-05}).
     */
    List<PurposeBalances.DatedLine> datedLinesOn(Connection unitOfWork, Set<LedgerAccountId> accounts,
            LocalDate valueDateThrough);
}
