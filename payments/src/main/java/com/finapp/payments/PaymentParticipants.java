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

    /**
     * Whether the account a confirmed payment's capture will credit can still receive it —
     * read {@code FOR SHARE}, so a racing CLOSURE of the account serialises with this read (the
     * Phase 6 → 7 transition). True only while the ledger account is {@code ACTIVE}.
     *
     * <p>Without it, a customer could close the wallet an open top-up credits, then confirm: the
     * provider authorised and captured the card, the ledger refused the capture's posting, the
     * capture's transaction rolled back, and the payment sat {@code CAPTURE_DISPATCHED} for good
     * with the customer charged and nothing booked. The confirmation now asks here before it
     * dispatches, and account closing refuses while a payment is in flight to the account.
     */
    boolean creditable(T unitOfWork, LedgerAccountId account);

    /**
     * The caller's BANK_ACCOUNT instrument, resolved to the opaque destination the push
     * wire presents (`P7-TSK-008`, ADR-0062 §6) — present only for a live instrument of the
     * caller's whose kind is the bank account. The reference crosses the PCI boundary at
     * the implementing bridge's one registered re-wrap ({@code INV-RAIL-03}), exactly as
     * the card token does at {@link #instrumentOwnedBy}.
     */
    Optional<ProviderReference> bankDestinationOwnedBy(
            T unitOfWork, UUID callerPartyId, UUID paymentMethodId);

    /**
     * The caller's live instrument, resolved to the routing vocabulary's kind
     * (`P7-TSK-009`): which dispatch the confirmation performs and which routing input it
     * judges are the INSTRUMENT's facts, read authoritatively at the act — never a
     * request's claim. Empty folds unknown, another's, detached and malformed into the one
     * refusal, exactly as the token and destination reads do.
     */
    Optional<InstrumentKind> instrumentKindOwnedBy(
            T unitOfWork, UUID callerPartyId, UUID paymentMethodId);

    /** The wallet's owner, account and currency — what the intent records and judges. */
    record Wallet(UUID customerId, LedgerAccountId account, CurrencyCode currency) {}
}
