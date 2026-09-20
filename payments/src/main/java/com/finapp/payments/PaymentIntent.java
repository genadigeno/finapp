package com.finapp.payments;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The PaymentIntent: the customer-facing objective of a wallet top-up ({@code P5-TSK-006},
 * ADR-0045) — <em>"fund my wallet with this amount from this instrument"</em> — the record of
 * what the customer asked and how it ended. It is never the try (the {@code PaymentAttempt}
 * owns every provider interaction and reference, {@code P5-TSK-007}), never the money (the
 * ledger's first touch is the capture's posting, ADR-0048, commanded by {@code P5-TSK-010}),
 * and never the refund state (the refund rows are the record; views join them — ADR-0045).
 *
 * <p><strong>The party, customer and instrument are raw {@code UUID}s, deliberately.</strong>
 * {@code party} owns the typed party and customer identifiers, and {@code paymentmethods} owns
 * the instrument's — and this module can see none of them: the first two by the established
 * boundary (the {@code Transfer.customerId} precedent), the third because {@code payments} has
 * <em>no edge at all</em> to the PCI module ({@code INV-PAY-02}, M7 — the instrument resolves
 * through a port {@code app} implements, {@code P5-TSK-009}). Two owner references because the
 * intent joins two ownerships: the instrument is the <em>party's</em> ({@code P5-TSK-004}'s
 * one-live (party, token)) and the wallet product is the <em>customer's</em>
 * ({@code P3-TSK-012}). The wallet reference is typed ({@link LedgerAccountId}) because the
 * {@code payments -> ledger} edge exists for exactly this.
 *
 * <p><strong>One constructor holds every invariant, and every path shares it</strong> — birth,
 * the four doors and {@link #rehydrate} — so a corrupt row is refused on read-back as defence
 * in depth ahead of the schema's {@code CHECK}s ({@code P5-TSK-008}). The intent's coherence is
 * <strong>status-independent, and that is a design fact rather than a thin aggregate</strong>:
 * every status-dependent payload lives where its fact lives — the mapped failure reason on the
 * attempt ({@code PAYMENT_LIFECYCLES.md} §2), the capture's posting evidence on the attempt
 * (idempotency key {@code payment-capture:} + the attempt id), refund totals on the refund
 * rows — ADR-0045's one-fact-one-place applied to this aggregate's field set. What the
 * constructor holds: presence of every field in every state, and strict amount positivity (a
 * zero top-up asserts nothing and a negative one is a credit wearing a debit's clothes —
 * {@code P3-TSK-004}'s argument; never a committed outcome, so the boundary 422s it and this
 * refusal is defence in depth).
 *
 * <p><strong>Deliberately no {@code statusChangedAt} and no per-transition stamps.</strong>
 * Transition instants are the append-only history table's evidence ({@code P5-TSK-008}), and
 * no intent transition carries a payload, so nothing but {@code status} ever changes after
 * birth — which is what lets {@code P5-TSK-008} narrow the {@code UPDATE} grant to that one
 * column. Only birth reads the clock, and it is injected.
 *
 * <p><strong>A class rather than a record, and the choice is load-bearing</strong>: a record's
 * generated {@code toString} prints every component, {@link Money}'s rendering includes the
 * amount, and payment amounts are {@code RESTRICTED-FINANCIAL} ({@code INV-AUD-02}) — the
 * {@code Transfer} form, kept for the same reason.
 *
 * <p>Transition doors are per-outcome and all route through the same machine check
 * ({@code INV-LIFE-02}'s one door). Production callers: {@link #confirm} is
 * {@code P5-TSK-009}'s (in the transaction that dispatches the attempt), {@link #cancel} is
 * {@code P5-TSK-011}'s, {@link #succeed} and {@link #fail} are the outcome transaction's
 * ({@code P5-TSK-010}/{@code -014}) — the {@code CustomerAccount.moveTo} precedent, shipped
 * with the machine and exercised by the exhaustive sweep until they arrive.
 */
public final class PaymentIntent {

    private final PaymentIntentId id;
    private final UUID partyId;
    private final UUID customerId;
    private final UUID paymentMethodId;
    private final LedgerAccountId walletAccount;
    private final Money amount;
    private final PaymentIntentStatus status;
    private final Instant createdAt;

    private PaymentIntent(
            PaymentIntentId id,
            UUID partyId,
            UUID customerId,
            UUID paymentMethodId,
            LedgerAccountId walletAccount,
            Money amount,
            PaymentIntentStatus status,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.partyId = Objects.requireNonNull(partyId, "partyId must not be null");
        this.customerId = Objects.requireNonNull(customerId, "customerId must not be null");
        this.paymentMethodId =
                Objects.requireNonNull(paymentMethodId, "paymentMethodId must not be null");
        this.walletAccount =
                Objects.requireNonNull(walletAccount, "walletAccount must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");

        // A zero amount asserts nothing and a negative one is a credit wearing a debit's
        // clothes (P3-TSK-004). Never a committed outcome — the boundary 422s it — so this is
        // defence in depth against the writer that never passed the boundary. The message names
        // the fact and the currency, NEVER the value: it reaches logs, and payment amounts are
        // RESTRICTED-FINANCIAL (INV-AUD-02).
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "a payment intent amount must be strictly positive; refused a non-positive"
                            + " amount in " + amount.currency());
        }
    }

    /**
     * A new intent, born {@code REQUIRES_CONFIRMATION} — the durable state creation commits
     * (ADR-0045: confirmation is a separate act, and that window is what gives
     * {@code CANCELLED} its producer). Birth is the only door to it.
     */
    public static PaymentIntent create(
            IdGenerator ids,
            Clock clock,
            UUID partyId,
            UUID customerId,
            UUID paymentMethodId,
            LedgerAccountId walletAccount,
            Money amount) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        return new PaymentIntent(
                PaymentIntentId.next(ids),
                partyId,
                customerId,
                paymentMethodId,
                walletAccount,
                amount,
                PaymentIntentStatus.REQUIRES_CONFIRMATION,
                Instant.now(clock));
    }

    /**
     * A row read back from storage, through the same constructor — so a corrupt row is refused
     * on read-back, ahead of the schema's own {@code CHECK}s.
     */
    public static PaymentIntent rehydrate(
            PaymentIntentId id,
            UUID partyId,
            UUID customerId,
            UUID paymentMethodId,
            LedgerAccountId walletAccount,
            Money amount,
            PaymentIntentStatus status,
            Instant createdAt) {
        return new PaymentIntent(
                id, partyId, customerId, paymentMethodId, walletAccount, amount, status,
                createdAt);
    }

    /** Confirmed: {@code PROCESSING}, the outcome now a third party's (ADR-0046 dispatches). */
    public PaymentIntent confirm() {
        return moved(PaymentIntentStatus.PROCESSING);
    }

    /** The customer's own withdrawal, only from {@code REQUIRES_CONFIRMATION}. */
    public PaymentIntent cancel() {
        return moved(PaymentIntentStatus.CANCELLED);
    }

    /** The attempt captured; the posting commits beside this transition ({@code P5-TSK-010}). */
    public PaymentIntent succeed() {
        return moved(PaymentIntentStatus.SUCCEEDED);
    }

    /** The attempt failed; the mapped reason is the attempt's fact, not this row's. */
    public PaymentIntent fail() {
        return moved(PaymentIntentStatus.FAILED);
    }

    /** The machine's one check ({@code INV-LIFE-02}), whichever door the transition arrives by. */
    private PaymentIntent moved(PaymentIntentStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalPaymentIntentTransitionException(id, status, target);
        }
        return new PaymentIntent(
                id, partyId, customerId, paymentMethodId, walletAccount, amount, target,
                createdAt);
    }

    public PaymentIntentId id() {
        return id;
    }

    /** The instrument's owner — the party whose payment method this intent draws on. */
    public UUID partyId() {
        return partyId;
    }

    /** The wallet product's owner. */
    public UUID customerId() {
        return customerId;
    }

    /**
     * The instrument reference: the {@code paymentmethods} row's identifier, never the token
     * and never anything reconstructable ({@code INV-PAY-02}).
     */
    public UUID paymentMethodId() {
        return paymentMethodId;
    }

    /** The wallet's ledger account — where the capture will credit (ADR-0048). */
    public LedgerAccountId walletAccount() {
        return walletAccount;
    }

    public Money amount() {
        return amount;
    }

    public PaymentIntentStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
