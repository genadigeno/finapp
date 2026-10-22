package com.finapp.app.accounts;

import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.ProductType;
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

/**
 * A party's <em>existing</em> wallet in exactly one currency - the one resolution {@code app}'s wallet ports share (the
 * Phase 12 &rarr; 13 transition gate, A4-F7: lending's {@code AccountsLoanWallets.existingWallet} (`P11-TSK-018`) and
 * bnpl's {@code AccountsBnplWallets} (`P12-TSK-013`) each carried a copy of it). Its live customer - whatever the
 * customer's status, so a suspended customer's wallet is found and a suspended customer may still repay - its live
 * {@code WALLET} agreement, and that agreement's {@code CUSTOMER_WALLET} ledger account in exactly the currency asked
 * (ADR-0076 section 6: keyed by currency, never a fallback currency, never the first of the agreement's wallets). Never
 * opened: a repayment needs money already there, and a closure refunds to the wallet the obligation was repaid from.
 * Empty when any link is missing.
 *
 * <p>Read per call, in the caller's transaction, no cache: stateless beyond its three stores, so every instance answers
 * alike. Whether the account accepts a posting, and what it can spend, the caller judges under its own lock on the row.
 */
public final class ExistingWallets {

    private final PartyStore<Connection> parties;
    private final CustomerAccountStore<Connection> customerAccounts;
    private final LedgerAccountStore<Connection> ledgerAccounts;

    public ExistingWallets(PartyStore<Connection> parties, CustomerAccountStore<Connection> customerAccounts,
            LedgerAccountStore<Connection> ledgerAccounts) {
        this.parties = Objects.requireNonNull(parties, "parties is marked non-null but is null");
        this.customerAccounts = Objects.requireNonNull(customerAccounts,
                "customerAccounts is marked non-null but is null");
        this.ledgerAccounts = Objects.requireNonNull(ledgerAccounts, "ledgerAccounts is marked non-null but is null");
    }

    /** The party's existing {@code CUSTOMER_WALLET} account in exactly {@code currency}, or empty. */
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
