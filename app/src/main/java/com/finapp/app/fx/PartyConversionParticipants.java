package com.finapp.app.fx;

import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.accounts.WalletAccounts;
import com.finapp.fx.ConversionParticipants;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.party.CustomerStatus;
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
 * {@code fx}'s {@link ConversionParticipants} over the party projection and {@code accounts}
 * (`P9-TSK-008`, `-009`; the {@code VerifiedAccountHolder} precedent): read per decision, in the
 * caller's transaction, no cache. The wallets are the customer's ONE live WALLET agreement's - the
 * schema's {@code customer_account_one_live_per_customer_product} makes it unique - resolved by
 * exactly the currency asked, never a fallback; a missing destination opens through
 * {@link WalletAccounts#openIfAbsent}, the one door that brings a wallet into existence (D28).
 */
@RequiredArgsConstructor
public final class PartyConversionParticipants implements ConversionParticipants {

    @NonNull private final PartyStore<Connection> parties;
    @NonNull private final CustomerAccountStore<Connection> customerAccounts;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final WalletAccounts wallets;

    @Override
    public Optional<UUID> activeCustomer(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");
        return parties
                .findLiveCustomerFor(unitOfWork, PartyId.of(partyId))
                .filter(customer -> customer.status() == CustomerStatus.ACTIVE)
                .map(customer -> customer.id().value());
    }

    @Override
    public Optional<LedgerAccountId> wallet(Connection unitOfWork, UUID customerId, CurrencyCode currency) {
        Objects.requireNonNull(currency, "currency must not be null");
        return customerAccounts.findLive(unitOfWork, customerId, ProductType.WALLET)
                .flatMap(product -> ledgerAccounts.findOwned(
                        unitOfWork, product.id().value(), AccountPurpose.CUSTOMER_WALLET, currency))
                .map(LedgerAccount::id);
    }

    @Override
    public Optional<LedgerAccountId> openIfAbsent(Connection unitOfWork, UUID customerId, CurrencyCode currency) {
        Objects.requireNonNull(currency, "currency must not be null");
        return customerAccounts.findLive(unitOfWork, customerId, ProductType.WALLET)
                .flatMap(product -> wallets.openIfAbsent(unitOfWork, customerId, product.id(), currency))
                .map(opened -> opened.wallet().id());
    }
}
