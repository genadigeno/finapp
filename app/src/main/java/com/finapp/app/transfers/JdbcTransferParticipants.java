package com.finapp.app.transfers;

import com.finapp.accounts.CustomerAccount;
import com.finapp.accounts.CustomerAccountId;
import com.finapp.accounts.CustomerAccountStatus;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.WalletAccounts;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.party.CustomerStatus;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.transfers.TransferParticipants;
import java.sql.Connection;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

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
@RequiredArgsConstructor
public final class JdbcTransferParticipants implements TransferParticipants<Connection> {

    @NonNull private final PartyStore<Connection> parties;
    @NonNull private final CustomerAccountStore<Connection> products;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;

    @Override
    public Optional<Source> sourceOwnedBy(
            Connection unitOfWork,
            UUID callerPartyId,
            UUID sourceProductRef,
            CurrencyCode currency) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(callerPartyId, "callerPartyId must not be null");
        Objects.requireNonNull(sourceProductRef, "sourceProductRef must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
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
                                walletOf(unitOfWork, product.id().value(), currency)
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
    public Optional<Side> destination(
            Connection unitOfWork, UUID destinationProductRef, CurrencyCode currency) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(destinationProductRef, "destinationProductRef must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        return walletOf(unitOfWork, destinationProductRef, currency)
                .map(wallet -> side(wallet, true));
    }

    @Override
    public boolean destinationExists(Connection unitOfWork, UUID destinationProductRef) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(destinationProductRef, "destinationProductRef must not be null");
        return WalletAccounts.firstOpened(
                        ledgerAccounts.findAllOwned(unitOfWork, destinationProductRef))
                .isPresent();
    }

    /**
     * The product's wallet in {@code currency}, else its first-opened wallet (`P9-TSK-004`):
     * the one resolution rule, {@link WalletAccounts#resolve}, so no flow picks among a
     * multi-currency product's wallets by accident.
     */
    private Optional<LedgerAccount> walletOf(
            Connection unitOfWork, UUID productRef, CurrencyCode currency) {
        return WalletAccounts.resolve(
                ledgerAccounts.findAllOwned(unitOfWork, productRef), currency);
    }

    private static Side side(LedgerAccount wallet, boolean productActive) {
        return new Side(
                wallet.id(),
                wallet.currency(),
                productActive && wallet.status() == LedgerAccountStatus.ACTIVE);
    }
}
