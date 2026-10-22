package com.finapp.app.lending;

import com.finapp.accounts.AccountNotActiveException;
import com.finapp.accounts.AccountOpening;
import com.finapp.accounts.AccountOpeningRefusedException;
import com.finapp.accounts.CustomerAccount;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.accounts.UnsupportedAccountCurrencyException;
import com.finapp.app.accounts.ExistingWallets;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.lending.LoanWallets;
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
 * Lending's {@link LoanWallets} over {@code accounts} (`P11-TSK-014`; ADR-0097 point 1, the
 * {@code PartyConversionParticipants} precedent): read per disbursement, in the caller's transaction, no cache. The
 * wallet is the customer's ONE live {@code WALLET} agreement's {@code CUSTOMER_WALLET} account in exactly the currency
 * asked - never a fallback currency. When the agreement holds none in the currency it is added through
 * {@link AccountOpening#addCurrency}, and when the customer holds no live agreement one is opened through
 * {@link AccountOpening#open} - accounts' doors, each the one way a wallet comes into existence (D28), each auditing and
 * announcing as it always does, under the disbursement's system actor and correlation.
 *
 * <p>A party with no active customer, an agreement not active, or an opening accounts refuses answers empty: the
 * disbursement treats it as a wallet that cannot be posted - retried until the deadline, the savepoint discarding
 * anything an attempt wrote. Whether the account accepts the posting is the ledger's to judge under its own lock.
 */
@RequiredArgsConstructor
public final class AccountsLoanWallets implements LoanWallets {

    @NonNull private final PartyStore<Connection> parties;
    @NonNull private final CustomerAccountStore<Connection> customerAccounts;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final AccountOpening accountOpening;

    @Override
    public Optional<LedgerAccountId> walletFor(Connection unitOfWork, UUID party, CurrencyCode currency) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(party, "party must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        Optional<UUID> customer = parties.findLiveCustomerFor(unitOfWork, PartyId.of(party))
                .filter(found -> found.status() == CustomerStatus.ACTIVE)
                .map(found -> found.id().value());
        if (customer.isEmpty()) {
            return Optional.empty();
        }
        try {
            Optional<CustomerAccount> agreement =
                    customerAccounts.findLive(unitOfWork, customer.get(), ProductType.WALLET);
            if (agreement.isPresent()) {
                Optional<LedgerAccount> existing = ledgerAccounts.findOwned(
                        unitOfWork, agreement.get().id().value(), AccountPurpose.CUSTOMER_WALLET, currency);
                if (existing.isPresent()) {
                    return existing.map(LedgerAccount::id);
                }
                return accountOpening.addCurrency(unitOfWork, party, agreement.get().id(), currency)
                        .map(added -> added.wallet().id());
            }
            accountOpening.open(unitOfWork, party, ProductType.WALLET, currency);
            return customerAccounts.findLive(unitOfWork, customer.get(), ProductType.WALLET)
                    .flatMap(opened -> ledgerAccounts.findOwned(
                            unitOfWork, opened.id().value(), AccountPurpose.CUSTOMER_WALLET, currency))
                    .map(LedgerAccount::id);
        } catch (AccountOpeningRefusedException | AccountNotActiveException | UnsupportedAccountCurrencyException refused) {
            return Optional.empty();
        }
    }

    /**
     * The party's existing wallet in exactly {@code currency} (`P11-TSK-018`): its live customer - whatever its status, so a
     * suspended customer's wallet is found and a suspended customer may still repay - its live {@code WALLET} agreement,
     * and that agreement's {@code CUSTOMER_WALLET} account. Never opened: a repayment needs money already there, and a
     * closure refunds to the wallet the loan was repaid from. Empty when any link is missing. The resolution is
     * {@link ExistingWallets}' - the one bnpl's {@code AccountsBnplWallets} reads too (the Phase 12 &rarr; 13 transition
     * gate, A4-F7); no behaviour changed.
     */
    @Override
    public Optional<LedgerAccountId> existingWallet(Connection unitOfWork, UUID party, CurrencyCode currency) {
        return new ExistingWallets(parties, customerAccounts, ledgerAccounts).existingWallet(unitOfWork, party, currency);
    }
}
