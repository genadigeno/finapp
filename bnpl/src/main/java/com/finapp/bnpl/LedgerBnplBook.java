package com.finapp.bnpl;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AsOf;
import com.finapp.ledger.AvailableBalance;
import com.finapp.ledger.BalanceDerivation;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingResult;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.PurposeBalances;
import com.finapp.ledger.ReversalCommand;
import com.finapp.ledger.ReversalService;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * {@link BnplBook} over the ledger's published API (`P12-TSK-013`; {@code INV-LED-04}): the posting command
 * ({@code PostingService} - validate, claim the key, record the entry, its lines, audit, outbox and projections, all on
 * the caller's transaction), the account store's row locks, the available balance under the caller's lock, the balance
 * derivation over journal lines (never {@code account_balance}, {@code INV-BAL-05}), the dated principal lines the accrual
 * base folds ({@code PurposeBalances}, `P12-TSK-017`), the chart, the reversal command and the journal's entry read (`P12-TSK-024`) and the obligation's
 * opener ({@link BnplAccounts}). No state of its own; code on every instance.
 */
@RequiredArgsConstructor
public final class LedgerBnplBook implements BnplBook {

    @NonNull private final BnplAccounts accounts;
    @NonNull private final LedgerAccountStore<Connection> store;
    @NonNull private final ChartOfAccounts<Connection> chart;
    @NonNull private final AvailableBalance<Connection> available;
    @NonNull private final BalanceDerivation<Connection> balances;
    @NonNull private final PostingService postings;
    @NonNull private final PurposeBalances<Connection> purposeBalances;
    @NonNull private final ReversalService reversals;
    @NonNull private final JournalEntryStore<Connection> journal;

    @Override
    public BnplLedgerAccounts openAccounts(Connection unitOfWork, BnplObligationId obligation, CurrencyCode currency) {
        return accounts.openFor(unitOfWork, obligation, currency);
    }

    @Override
    public List<LedgerAccount> accountsOf(Connection unitOfWork, BnplObligationId obligation) {
        Objects.requireNonNull(obligation, "obligation");
        return store.findAllOwned(unitOfWork, obligation.value());
    }

    @Override
    public LedgerAccountId operational(Connection unitOfWork, AccountPurpose purpose, CurrencyCode currency) {
        return chart.resolve(unitOfWork, purpose, currency).id();
    }

    @Override
    public Optional<LedgerAccountStatus> lockForUpdate(Connection unitOfWork, LedgerAccountId account) {
        return store.lockForUpdate(unitOfWork, account).map(LedgerAccount::status);
    }

    @Override
    public Money availableUnderLock(Connection unitOfWork, LedgerAccountId account) {
        return available.underLock(unitOfWork, account);
    }

    @Override
    public void lockBalancesInOrder(Connection unitOfWork, Collection<LedgerAccountId> accounts) {
        postings.lockBalancesInOrder(unitOfWork, accounts);
    }

    @Override
    public JournalEntryId post(Connection unitOfWork, PostingCommand command) {
        PostingResult result = postings.post(unitOfWork, command);
        if (result.replayed()) {
            throw new IllegalStateException("a BNPL entry exists for an id minted in this transaction (INV-IDEM-01)");
        }
        return result.entryId();
    }

    @Override
    public JournalEntryId reverse(Connection unitOfWork, ReversalCommand command) {
        PostingResult result = reversals.reverse(unitOfWork, command);
        if (result.replayed()) {
            throw new IllegalStateException("a BNPL reversal exists for an id decided in this transaction (INV-IDEM-01)");
        }
        return result.entryId();
    }

    @Override
    public List<JournalLine> entryLines(Connection unitOfWork, JournalEntryId entry) {
        Objects.requireNonNull(entry, "entry");
        return journal.findById(unitOfWork, entry).map(posted -> posted.entry().lines()).orElse(List.of());
    }

    @Override
    public Money balanceOf(Connection unitOfWork, LedgerAccount account) {
        return balances.derive(unitOfWork, account.id(), AsOf.latest()).settled();
    }

    @Override
    public List<PurposeBalances.DatedLine> datedLinesOn(Connection unitOfWork, Set<LedgerAccountId> accounts,
            LocalDate valueDateThrough) {
        return purposeBalances.datedLinesOn(unitOfWork, accounts, valueDateThrough);
    }
}
