package com.finapp.app.bnpl;

import com.finapp.accounts.CustomerAccountStore;
import com.finapp.app.accounts.ExistingWallets;
import com.finapp.bnpl.BnplWallets;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.party.PartyStore;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;

/**
 * bnpl's {@link BnplWallets} over {@code accounts} (`P12-TSK-013`; owner decision B6; lending's
 * {@code AccountsLoanWallets.existingWallet} shape): the party's live customer, its live {@code WALLET} agreement and that
 * agreement's {@code CUSTOMER_WALLET} ledger account in exactly the currency asked - never opened, never a fallback
 * currency. Empty when any link is missing: pay-in-4's purchase then cannot collect instalment 1 and refuses
 * {@code 422 bnpl.InsufficientFunds}, nothing committed. Read per acceptance, in its transaction, no cache; whether the
 * account accepts the posting and what it can spend the purchase judges under its own (P) lock on the row.
 *
 * <p>The resolution itself is {@link ExistingWallets}' - the one lending's {@code AccountsLoanWallets.existingWallet}
 * reads too (the Phase 12 &rarr; 13 transition gate, A4-F7: this class carried a copy of it); no behaviour changed.
 */
final class AccountsBnplWallets implements BnplWallets {

    private final ExistingWallets wallets;

    AccountsBnplWallets(PartyStore<Connection> parties, CustomerAccountStore<Connection> customerAccounts,
            LedgerAccountStore<Connection> ledgerAccounts) {
        this.wallets = new ExistingWallets(parties, customerAccounts, ledgerAccounts);
    }

    @Override
    public Optional<LedgerAccountId> existingWallet(Connection unitOfWork, UUID party, CurrencyCode currency) {
        return wallets.existingWallet(unitOfWork, party, currency);
    }
}
