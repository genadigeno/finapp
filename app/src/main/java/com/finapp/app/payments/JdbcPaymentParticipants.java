package com.finapp.app.payments;

import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.ProductType;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStatus;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.party.CustomerStatus;
import com.finapp.party.PartyId;
import com.finapp.party.PartyStore;
import com.finapp.paymentmethods.PaymentMethod;
import com.finapp.paymentmethods.PaymentMethodId;
import com.finapp.paymentmethods.PaymentMethodStatus;
import com.finapp.paymentmethods.PaymentMethodStore;
import com.finapp.payments.InstrumentToken;
import com.finapp.payments.PaymentParticipants;
import com.finapp.payments.ProviderReference;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * {@link PaymentParticipants} over the party, accounts, ledger and paymentmethods stores
 * (`P5-TSK-009`) — the composition root's half, because {@code payments} can see none of the
 * four modules that hold the answers (the {@code JdbcTransferParticipants} shape, one phase
 * over; the PCI half is <em>why</em> the port exists, {@code INV-PAY-02}).
 *
 * <p><strong>The wallet is the caller's, proven per decision</strong>: party → live
 * {@code ACTIVE} customer → live {@code WALLET} product → the product's {@code ACTIVE}
 * customer-wallet ledger account, whose currency the create command judges the amount against.
 * Unknown, not-yours, no-live-customer and closed are one empty answer.
 *
 * <p><strong>The instrument crosses re-wrapped, and this class is the registered
 * bridge</strong>: {@code TokenReference.expose()} → {@code InstrumentToken.of(…)}, one
 * statement, nothing held — a named entry in {@code SecretsAreUnwrappedInOnePlaceTest}, because
 * the token is unwrapped here for exactly one purpose (the provider wire, its one legitimate
 * destination) and any second unwrapping site is a review question.
 */
@RequiredArgsConstructor
public final class JdbcPaymentParticipants implements PaymentParticipants<Connection> {

    @NonNull private final PartyStore<Connection> parties;
    @NonNull private final CustomerAccountStore<Connection> products;
    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;
    @NonNull private final PaymentMethodStore<Connection> instruments;

    /** The wallet's ledger account, share-locked: postable only while {@code ACTIVE}. */
    @Override
    public boolean creditable(Connection unitOfWork, com.finapp.ledger.LedgerAccountId account) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(account, "account must not be null");
        return ledgerAccounts
                .lockForShare(unitOfWork, account)
                .map(row -> row.status() == com.finapp.ledger.LedgerAccountStatus.ACTIVE)
                .orElse(false);
    }

    @Override
    public Optional<Wallet> walletOwnedBy(Connection unitOfWork, UUID callerPartyId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(callerPartyId, "callerPartyId must not be null");
        return parties
                .findLiveCustomerFor(unitOfWork, PartyId.of(callerPartyId))
                .filter(customer -> customer.status() == CustomerStatus.ACTIVE)
                .flatMap(
                        customer ->
                                products.findLive(
                                        unitOfWork, customer.id().value(), ProductType.WALLET))
                .flatMap(
                        product ->
                                walletAccountOf(unitOfWork, product.id().value())
                                        .map(
                                                account ->
                                                        new Wallet(
                                                                product.customerId(),
                                                                account.id(),
                                                                account.currency())));
    }

    /**
     * The payer's own wallet (`P7-TSK-011`): on this wiring, exactly
     * {@link #walletOwnedBy}'s answer — the top-up flow's credit side IS the payer's
     * wallet. The distinct port method exists for the checkout wiring, whose
     * {@code walletOwnedBy} deliberately answers the merchant's payable.
     */
    @Override
    public Optional<Wallet> payerWalletOwnedBy(Connection unitOfWork, UUID callerPartyId) {
        return walletOwnedBy(unitOfWork, callerPartyId);
    }

    @Override
    public Optional<InstrumentToken> instrumentOwnedBy(
            Connection unitOfWork, UUID callerPartyId, UUID paymentMethodId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(callerPartyId, "callerPartyId must not be null");
        Objects.requireNonNull(paymentMethodId, "paymentMethodId must not be null");
        // The port's contract folds MALFORMED with unknown and not-yours (one empty answer),
        // and a well-formed UUID that is not a v7 is malformed here: no platform identifier
        // is ever a v4 (ADR-0013), so it names nothing - never our 500 (P5-TSK-011).
        PaymentMethodId identifier;
        try {
            identifier = PaymentMethodId.of(paymentMethodId);
        } catch (IllegalArgumentException notAPlatformIdentifier) {
            return Optional.empty();
        }
        return instruments
                .findOwned(unitOfWork, identifier, callerPartyId)
                .filter(method -> method.status() == PaymentMethodStatus.ACTIVE)
                // A BANK_ACCOUNT instrument has no token, so it resolves to no chargeable
                // instrument HERE - this bridge is the card confirm's (P7-TSK-007: the push
                // flows present the destination reference at their own doors, P7-TSK-008).
                .flatMap(PaymentMethod::token)
                // The registered re-wrapping: off in one expression, wrapped again before it
                // travels (INV-PAY-02) - the SecretsAreUnwrappedInOnePlaceTest entry.
                .map(token -> InstrumentToken.of(token.expose()));
    }

    @Override
    public Optional<ProviderReference> bankDestinationOwnedBy(
            Connection unitOfWork, UUID callerPartyId, UUID paymentMethodId) {
        PaymentMethodId identifier;
        try {
            identifier = PaymentMethodId.of(paymentMethodId);
        } catch (IllegalArgumentException notAPlatformIdentifier) {
            return Optional.empty();
        }
        return instruments
                .findOwned(unitOfWork, identifier, callerPartyId)
                .filter(method -> method.status() == PaymentMethodStatus.ACTIVE)
                // The BANK_ACCOUNT arm of the same bridge (P7-TSK-008): a card resolves to
                // no push destination, exactly as a bank account resolves to no card token.
                .flatMap(PaymentMethod::destination)
                // The second registered re-wrapping across the PCI boundary: off in one
                // expression, wrapped again before it travels (INV-RAIL-03) - the same
                // SecretsAreUnwrappedInOnePlaceTest entry, its claim widened to both
                // references.
                .map(destination -> new ProviderReference(destination.expose()));
    }

    @Override
    public Optional<com.finapp.payments.InstrumentKind> instrumentKindOwnedBy(
            Connection unitOfWork, UUID callerPartyId, UUID paymentMethodId) {
        PaymentMethodId identifier;
        try {
            identifier = PaymentMethodId.of(paymentMethodId);
        } catch (IllegalArgumentException notAPlatformIdentifier) {
            return Optional.empty();
        }
        // The instrument registry's kind, mapped onto the routing vocabulary (P7-TSK-009):
        // no secret crosses here - which dispatch a confirmation performs is a
        // classification, not a credential.
        return instruments
                .findOwned(unitOfWork, identifier, callerPartyId)
                .filter(method -> method.status() == PaymentMethodStatus.ACTIVE)
                .map(
                        method ->
                                switch (method.kind()) {
                                    case CARD_TOKEN ->
                                            com.finapp.payments.InstrumentKind.CARD_TOKEN;
                                    case BANK_ACCOUNT ->
                                            com.finapp.payments.InstrumentKind.BANK_ACCOUNT;
                                });
    }

    /** The product's live customer-wallet account; the transfers precedent's read. */
    private Optional<LedgerAccount> walletAccountOf(Connection unitOfWork, UUID productRef) {
        List<LedgerAccount> owned = ledgerAccounts.findAllOwned(unitOfWork, productRef);
        return owned.stream()
                .filter(account -> account.purpose() == AccountPurpose.CUSTOMER_WALLET)
                .filter(account -> account.status() == LedgerAccountStatus.ACTIVE)
                .findFirst();
    }
}
