package com.finapp.app.bnpl;

import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.bnpl.BnplWallets;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * bnpl's {@link BnplWallets} over {@code accounts} (`P12-TSK-013`; owner decision B6; lending's
 * {@code AccountsLoanWallets.existingWallet} shape): the party's live customer, its live {@code WALLET} agreement and that
 * agreement's {@code CUSTOMER_WALLET} ledger account in exactly the currency asked - never opened, never a fallback
 * currency. Empty when any link is missing: pay-in-4's purchase then cannot collect instalment 1 and refuses
 * {@code 422 bnpl.InsufficientFunds}, nothing committed. Read per acceptance, in its transaction, no cache; whether the
 * account accepts the posting and what it can spend the purchase judges under its own (P) lock on the row.
 */
@RequiredArgsConstructor
final class AccountsBnplWallets implements BnplWallets {

    @NonNull private final PartyStore<Connection> parties;
    @NonNull private final CustomerAccountStore<Connection> customerAccounts;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;

    @Override
    public Optional<LedgerAccountId> existingWallet(Connection unitOfWork, UUID party, CurrencyCode currency) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(party, "party must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        return parties.findLiveCustomerFor(unitOfWork, PartyId.of(party))
                .flatMap(found -> customerAccounts.findLive(unitOfWork, found.id().value(), ProductType.WALLET))
                .flatMap(agreement -> ledgerAccounts.findOwned(
                        unitOfWork, agreement.id().value(), AccountPurpose.CUSTOMER_WALLET, currency))
                .map(LedgerAccount::id);
    }
}
