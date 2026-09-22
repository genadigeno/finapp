package com.finapp.checkout;

import com.finapp.platform.audit.AuditableAction;

/**
 * The checkout module's auditable actions ({@code AUDITABLE_ACTIONS.md}), arriving with the
 * commands whose designs fix their meaning (`P6-TSK-007`) — the deliberately-few licence. The
 * expiry sweep's action arrives with `P6-TSK-008`.
 *
 * <p><strong>None requires a reason</strong>, and the absence is uniform for one reason: every
 * act here is somebody doing the ordinary thing the surface exists for — a merchant making an
 * offer, a customer paying, a capture landing. {@code INV-AUD-03} asks for a reason where a
 * <em>judgement</em> is made about somebody else (a suspension, a revocation, a repricing), and
 * demanding one here would make it a field callers fill with noise, which is worse than not
 * asking.
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
            false);

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
