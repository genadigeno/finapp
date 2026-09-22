package com.finapp.checkout;

import com.finapp.platform.audit.AuditableAction;

/**
 * The checkout module's auditable actions ({@code AUDITABLE_ACTIONS.md}), arriving with the
 * commands whose designs fix their meaning (`P6-TSK-007`, `P6-TSK-008`) — the deliberately-few
 * licence.
 *
 * <h2>Four need no reason and one does, and the split is the whole of {@code INV-AUD-03}</h2>
 *
 * <p>Creating, confirming, producing an order and expiring are somebody — or nobody — doing
 * the ordinary thing the surface exists for: a merchant making an offer, a customer paying, a
 * capture landing, a deadline passing. Demanding a reason for those would make it a field
 * callers fill with noise, which is worse than not asking; and for the expiry there is not even
 * anybody to ask, because the sweeper acts as the platform.
 *
 * <p>{@link #CHECKOUT_SESSION_ABANDONED} is the exception, and it earns it: a merchant
 * withdrawing an offer it already made is a <em>judgement about somebody else's purchase</em> —
 * the suspension and revocation shape. The customer looking at the page finds their checkout
 * gone, and the trail must be able to say why.
 */
public enum CheckoutAuditAction implements AuditableAction {

    /**
     * A merchant opened a checkout session. The record names the session and the merchant by
     * identifier — <strong>never the token</strong> ({@code INV-AUD-02}, {@code INV-IDN-01}),
     * and never the line summary, which says what one person is buying.
     */
    CHECKOUT_SESSION_CREATED(
            "checkout.CheckoutSessionCreated",
            "A merchant opened a checkout session; the record names the session and the"
                    + " merchant by identifier, never the token and never what was bought.",
            false),

    /**
     * A customer confirmed a session and the platform opened its payment. The record names the
     * session and the payment intent by identifier — the link an auditor follows from a
     * purchase to the money that paid for it.
     */
    CHECKOUT_SESSION_CONFIRMED(
            "checkout.CheckoutSessionConfirmed",
            "A customer confirmed a checkout session; the record names the session and the"
                    + " payment intent by identifier.",
            false),

    /**
     * A capture landed and the commercial fact exists. The record names the order, the session
     * and the <strong>journal entry that paid for it</strong> — which is what closes the
     * traceable chain: order → entry → ADR-0050 §3's four lines → the merchant's payable.
     */
    ORDER_CREATED(
            "checkout.OrderCreated",
            "A capture completed a checkout session and produced its order; the record names"
                    + " the order, the session and the journal entry that paid for it.",
            false),

    /**
     * A deadline passed and the sweeper ended the offer. The record names the session and the
     * state it came from — {@code OPEN} (nobody paid) and {@code PAYMENT_PENDING} (a payment
     * that never landed) are different operational facts and an auditor must be able to tell
     * them apart.
     *
     * <p>The actor is the platform: a scheduled expiry has no person at all, which is the
     * cleanest case of the `P5-TSK-009` attribution reasoning.
     */
    CHECKOUT_SESSION_EXPIRED(
            "checkout.CheckoutSessionExpired",
            "The expiry sweeper ended a checkout session whose offer had run out; the record"
                    + " names the session and the state it expired from.",
            false),

    /**
     * A merchant withdrew its own offer before anybody paid for it. <strong>The one checkout
     * action that requires a reason</strong> — see the type javadoc.
     *
     * <p>It cannot reach a session whose payment is in flight: the machine has no
     * {@code PAYMENT_PENDING → ABANDONED} edge, which is exactly the guard that stops a
     * merchant withdrawing an offer whose money is already moving ({@code INV-MER-06}'s
     * neighbour).
     */
    CHECKOUT_SESSION_ABANDONED(
            "checkout.CheckoutSessionAbandoned",
            "A merchant withdrew a checkout session before it was paid; the record names the"
                    + " session and the merchant, and the reason is required.",
            true);

    private final String code;
    private final String description;
    private final boolean requiresReason;

    CheckoutAuditAction(String code, String description, boolean requiresReason) {
        this.code = code;
        this.description = description;
        this.requiresReason = requiresReason;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public boolean requiresReason() {
        return requiresReason;
    }
}
