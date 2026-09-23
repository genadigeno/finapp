package com.finapp.app.checkout;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.payments.InstrumentToken;
import com.finapp.payments.PaymentParticipants;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * {@link PaymentParticipants} for a <strong>checkout</strong> payment (`P6-TSK-007`,
 * ADR-0050 §6) — the second wiring of the port, and the one that makes a merchant-bound
 * intent possible.
 *
 * <h2>The account a checkout payment credits is the MERCHANT's payable</h2>
 *
 * <p>A wallet top-up credits the paying customer's own wallet, and
 * {@code JdbcPaymentParticipants} resolves it from the caller's party. A checkout payment
 * credits the merchant the customer is buying from — a different question with a different
 * authoritative answer, asked of the same port because {@code PaymentCreation} needs exactly
 * one thing from it: <em>which ledger account does this capture credit, and in what currency
 * is the amount judged?</em>
 *
 * <p>So this is not a double and not a relaxation. The resolution is still authoritative — the
 * merchant's payable is read from the ledger, by the merchant the SESSION names, never from a
 * request — and the instrument half delegates unchanged, because the token's path is not this
 * class's business and substituting it would weaken a shipped guarantee.
 *
 * <h2>The naming debt this makes visible, stated rather than hidden</h2>
 *
 * <p>The intent records this account in {@code payment_intent.wallet_account_id}, whose own
 * comment already defines it as <em>where the capture will credit</em> — so the meaning is
 * right and the name is narrower than the meaning. Recorded in {@code CURRENT_STATE.md}
 * §Known Architectural Debt at {@code P6-TSK-005}, owned by this task's successor, and bounded
 * by the check that makes a mismatch loud: {@code MerchantSettlement} refuses a capture whose
 * credit account is not the pinned merchant's payable.
 */
@RequiredArgsConstructor
public final class CheckoutPaymentParticipants implements PaymentParticipants<Connection> {

    @NonNull private final LedgerAccountStore<Connection> ledgerAccounts;

    /** The real resolver, for the half this class does not answer. */
    @NonNull private final PaymentParticipants<Connection> instruments;

    /**
     * Whose payable this payment credits — taken from the SESSION, which is authoritative state,
     * never from the request.
     */
    @NonNull private final UUID merchantRef;

    /** The offer's currency, which the create command judges the amount against. */
    @NonNull private final CurrencyCode currency;

    /**
     * The merchant's payable, read authoritatively.
     *
     * <p>{@code callerPartyId} is deliberately unused: the account this capture credits does
     * not depend on who is paying. Empty when the merchant has no payable in this currency —
     * the one empty answer the port's contract promises, which the creating flow turns into
     * its own refusal rather than an oracle over merchants.
     */
    @Override
    public Optional<Wallet> walletOwnedBy(Connection unitOfWork, UUID callerPartyId) {
        return ledgerAccounts
                .findOwned(unitOfWork, merchantRef, AccountPurpose.MERCHANT_PAYABLE, currency)
                .map(this::asDestination);
    }

    /** Delegated unchanged: the instrument must still be the paying customer's own. */
    @Override
    public Optional<InstrumentToken> instrumentOwnedBy(
            Connection unitOfWork, UUID callerPartyId, UUID paymentMethodId) {
        return instruments.instrumentOwnedBy(unitOfWork, callerPartyId, paymentMethodId);
    }

    private Wallet asDestination(LedgerAccount payable) {
        // The customer identifier the intent records is the MERCHANT's, because that is whose
        // money this becomes. The paying customer is recorded on the intent's party_id.
        return new Wallet(merchantRef, payable.id(), currency);
    }
}
