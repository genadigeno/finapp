package com.finapp.app.transfers;

import com.finapp.accounts.CustomerAccount;
import com.finapp.accounts.CustomerAccountId;
import com.finapp.accounts.CustomerAccountStatus;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.party.CustomerStatus;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.transfers.TransferParticipants;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link TransferParticipants} over the party, accounts and ledger stores (`P4-TSK-005`) —
 * the composition root's half, because {@code transfers} can see none of the three modules
 * that hold the answers (the {@code VerifiedAccountHolder} shape, one port over).
 *
 * <p><strong>The source is the caller's, proven per decision</strong>: party → live
 * {@code ACTIVE} customer ({@code INV-KYC-05}'s projection, consumed never recomputed) →
 * {@code findOwnedBy} with the ownership predicate in the statement (`P3-TSK-013`) → the
 * product's wallet. Unknown, not-yours and no-live-customer are one empty answer.
 *
 * <p><strong>The destination is resolved through the ledger alone.</strong> Its wallet's own
 * status answers postability — `P3-TSK-014` closes the product and its ledger accounts in one
 * transaction, so the ledger row's status is the product's, recorded here as today-exact —
 * and no unowned {@code accounts} read needs to exist, so the disclosure question a
 * counterparty lookup would raise never arises. Postability is judged per side:
 * <strong>both</strong> the product agreement (source) and the wallet row must be
 * {@code ACTIVE}.
 */
public final class JdbcTransferParticipants implements TransferParticipants<Connection> {

    private final PartyStore<Connection> parties;
    private final CustomerAccountStore<Connection> products;
    private final LedgerAccountStore<Connection> ledgerAccounts;

    public JdbcTransferParticipants(
            PartyStore<Connection> parties,
            CustomerAccountStore<Connection> products,
            LedgerAccountStore<Connection> ledgerAccounts) {
        this.parties = Objects.requireNonNull(parties, "parties must not be null");
        this.products = Objects.requireNonNull(products, "products must not be null");
        this.ledgerAccounts =
                Objects.requireNonNull(ledgerAccounts, "ledgerAccounts must not be null");
    }

    @Override
    public Optional<Source> sourceOwnedBy(
            Connection unitOfWork, UUID callerPartyId, UUID sourceProductRef) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(callerPartyId, "callerPartyId must not be null");
        Objects.requireNonNull(sourceProductRef, "sourceProductRef must not be null");
        return parties
                .findLiveCustomerFor(unitOfWork, PartyId.of(callerPartyId))
                .filter(customer -> customer.status() == CustomerStatus.ACTIVE)
                .flatMap(
                        customer ->
                                products.findOwnedBy(
                                        unitOfWork,
                                        CustomerAccountId.of(sourceProductRef),
                                        customer.id().value()))
                .flatMap(
                        product ->
                                walletOf(unitOfWork, product.id().value())
                                        .map(
                                                wallet ->
                                                        new Source(
                                                                product.customerId(),
                                                                side(
                                                                        wallet,
                                                                        product.status()
                                                                                == CustomerAccountStatus
                                                                                        .ACTIVE))));
    }

    @Override
    public Optional<Side> destination(Connection unitOfWork, UUID destinationProductRef) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(destinationProductRef, "destinationProductRef must not be null");
        return walletOf(unitOfWork, destinationProductRef).map(wallet -> side(wallet, true));
    }

    /** The product's single wallet today; multi-currency products are Phase 9's seam. */
    private Optional<LedgerAccount> walletOf(Connection unitOfWork, UUID productRef) {
        List<LedgerAccount> owned = ledgerAccounts.findAllOwned(unitOfWork, productRef);
        return owned.stream()
                .filter(account -> account.purpose() == AccountPurpose.CUSTOMER_WALLET)
                .findFirst();
    }

    private static Side side(LedgerAccount wallet, boolean productActive) {
        return new Side(
                wallet.id(),
                wallet.currency(),
                productActive && wallet.status() == LedgerAccountStatus.ACTIVE);
    }
}
