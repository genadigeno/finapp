package com.finapp.payments;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a payment's participants from authoritative state ({@code P5-TSK-009}) — the port
 * the shipped javadocs promised: {@code payments} can see neither the wallet's modules nor the
 * PCI module, so the composition root implements this over the party, accounts, ledger and
 * paymentmethods stores (the {@code TransferParticipants} shape, one phase over).
 *
 * <p><strong>Ownership is the resolution</strong>: both answers are empty for unknown,
 * not-the-caller's and not-live alike — one empty answer, so the surface can keep its one 404
 * and never become an oracle over other people's instruments ({@code INV-IDN-07}'s reasoning,
 * ADR-0031).
 */
public interface PaymentParticipants<T> {

    /**
     * The caller's wallet: the party's live {@code ACTIVE} customer's live wallet product's
     * ledger account, with the currency the create command judges the amount against — the
     * command's authoritative resolution, never a request's claim.
     */
    Optional<Wallet> walletOwnedBy(T unitOfWork, UUID callerPartyId);

    /**
     * The caller's instrument, resolved to the token the provider wire presents — present only
     * for a live ({@code ACTIVE}) instrument of the caller's. The token crosses this port
     * wrapped ({@link InstrumentToken}) and comes off only on the wire ({@code INV-PAY-02}).
     */
    Optional<InstrumentToken> instrumentOwnedBy(
            T unitOfWork, UUID callerPartyId, UUID paymentMethodId);

    /** The wallet's owner, account and currency — what the intent records and judges. */
    record Wallet(UUID customerId, LedgerAccountId account, CurrencyCode currency) {}
}
