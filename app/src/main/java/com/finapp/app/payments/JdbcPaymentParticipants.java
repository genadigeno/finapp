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
import com.finapp.paymentmethods.PaymentMethodId;
import com.finapp.paymentmethods.PaymentMethodStatus;
import com.finapp.paymentmethods.PaymentMethodStore;
import com.finapp.payments.InstrumentToken;
import com.finapp.payments.PaymentParticipants;
import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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
public final class JdbcPaymentParticipants implements PaymentParticipants<Connection> {

    private final PartyStore<Connection> parties;
    private final CustomerAccountStore<Connection> products;
    private final LedgerAccountStore<Connection> ledgerAccounts;
    private final PaymentMethodStore<Connection> instruments;

    public JdbcPaymentParticipants(
            PartyStore<Connection> parties,
            CustomerAccountStore<Connection> products,
            LedgerAccountStore<Connection> ledgerAccounts,
            PaymentMethodStore<Connection> instruments) {
        this.parties = Objects.requireNonNull(parties, "parties must not be null");
        this.products = Objects.requireNonNull(products, "products must not be null");
        this.ledgerAccounts =
                Objects.requireNonNull(ledgerAccounts, "ledgerAccounts must not be null");
        this.instruments = Objects.requireNonNull(instruments, "instruments must not be null");
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

    @Override
    public Optional<InstrumentToken> instrumentOwnedBy(
            Connection unitOfWork, UUID callerPartyId, UUID paymentMethodId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(callerPartyId, "callerPartyId must not be null");
        Objects.requireNonNull(paymentMethodId, "paymentMethodId must not be null");
        return instruments
                .findOwned(unitOfWork, PaymentMethodId.of(paymentMethodId), callerPartyId)
                .filter(method -> method.status() == PaymentMethodStatus.ACTIVE)
                // The registered re-wrapping: off in one expression, wrapped again before it
                // travels (INV-PAY-02) - the SecretsAreUnwrappedInOnePlaceTest entry.
                .map(method -> InstrumentToken.of(method.token().expose()));
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
