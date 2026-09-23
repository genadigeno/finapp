package com.finapp.checkout;

import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The permanent commercial fact a <em>paid</em> checkout produces (`P6-TSK-006`, ADR-0053 §1
 * and §6).
 *
 * <h2>No status column, deliberately</h2>
 *
 * <p>An order that exists is paid — it is created in the completion's own transaction and never
 * before, so there is no unpaid order to model. Its only other quality, refund standing, is
 * <strong>derived from the payment's refund rows at read time</strong> and never stored
 * (ADR-0053 §6, the ADR-0045 derivation discipline): a stored {@code REFUNDED} flag would be a
 * second authority beside the rows that actually moved the money, and two authorities drift.
 *
 * <p>So this aggregate has no machine, and that is not laziness — it is the {@code FeeSchedule}
 * precedent: a status column with one producible value is a state machine nobody earns, and
 * modelling one would invite a writer to move it.
 *
 * <h2>Append-only, at three ranks</h2>
 *
 * <p>The application role holds {@code SELECT, INSERT} and nothing else; `V002`'s trigger
 * refuses every {@code UPDATE} and {@code DELETE} for every writer, the migrator included; and
 * {@link OrderStore} has no method that could express a change. An order is evidence, and
 * evidence that can be edited is worth less than none.
 *
 * <h2>The chain it closes</h2>
 *
 * <p>{@link #capturedEntryRef} is the journal entry that paid for this order, by value. That is
 * what makes a merchant's statement traceable end to end — order → entry → the four lines
 * ADR-0050 §3 posts — without this module being able to see {@code ledger} at all. Checkout has
 * {@code platform} on its classpath and nothing else, so the reference is a {@code UUID} rather
 * than a typed identifier: the isolation showing up in the type system.
 *
 * <h2>Fulfilment is not here</h2>
 *
 * <p>Shipped, delivered, returned — the merchant's business, outside the platform's books
 * (ADR-0053 §6). The platform records that money changed hands, not what was done afterwards.
 */
public final class Order {

    private final OrderId id;
    private final CheckoutSessionId sessionRef;
    private final UUID merchantRef;
    private final Money amount;
    private final UUID capturedEntryRef;
    private final Instant createdAt;

    private Order(
            OrderId id,
            CheckoutSessionId sessionRef,
            UUID merchantRef,
            Money amount,
            UUID capturedEntryRef,
            Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.sessionRef = Objects.requireNonNull(sessionRef, "sessionRef must not be null");
        this.merchantRef = Objects.requireNonNull(merchantRef, "merchantRef must not be null");
        this.amount = Objects.requireNonNull(amount, "amount must not be null");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("an order's amount must be positive");
        }
        this.capturedEntryRef =
                Objects.requireNonNull(capturedEntryRef, "capturedEntryRef must not be null");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    }

    /**
     * The fact, created from the session whose payment captured.
     *
     * <p>Takes the session rather than loose fields so the order cannot disagree with the offer
     * it came from: the merchant and the amount are the session's, not a caller's recollection
     * of them.
     */
    public static Order paid(
            IdGenerator ids, Clock clock, CheckoutSession session, UUID capturedEntryRef) {
        Objects.requireNonNull(session, "session must not be null");
        return new Order(
                OrderId.next(ids),
                session.id(),
                session.merchantRef(),
                session.amount(),
                capturedEntryRef,
                Instant.now(clock));
    }

    /** Reconstitutes from storage. */
    public static Order rehydrate(
            OrderId id,
            CheckoutSessionId sessionRef,
            UUID merchantRef,
            Money amount,
            UUID capturedEntryRef,
            Instant createdAt) {
        return new Order(id, sessionRef, merchantRef, amount, capturedEntryRef, createdAt);
    }

    public OrderId id() {
        return id;
    }

    /** One order per session, total — held by a unique index, not by a read-then-write. */
    public CheckoutSessionId sessionRef() {
        return sessionRef;
    }

    public UUID merchantRef() {
        return merchantRef;
    }

    public Money amount() {
        return amount;
    }

    /** The journal entry that paid for this order — the traceable chain's last link. */
    public UUID capturedEntryRef() {
        return capturedEntryRef;
    }

    public Instant createdAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Order other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** Identifiers only — no amount ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "Order[" + id + ", session=" + sessionRef + "]";
    }
}
