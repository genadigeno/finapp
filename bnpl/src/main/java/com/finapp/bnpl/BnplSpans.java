package com.finapp.bnpl;

/**
 * BNPL's span names (`P12-TSK-026`; {@code PHASE_12_PLAN.md} section 15: "spans per step ({@code BnplFlowScope}, no
 * attributes)"; lending's {@code LendingSpans}) - one per step of section 7.1 a BNPL flow passes through, each recorded
 * through the platform {@code Spans} port with no attribute at all, so no amount, rate, balance, DPD, party, merchant,
 * purchase, obligation or agreement id can reach the trace backend (section 11, {@code INV-AUD-02}).
 *
 * <p>The steps are linked by the correlation their flow stored: an application's (its offer carries it), and an
 * obligation's (the acceptance's, frozen on the purchase and the obligation). Every sweep leg runs in the stored
 * correlation of the row it steps - a dispute's deadline leg in its obligation's - and every door acting on an existing
 * application, offer, agreement, purchase or dispute records its span in that row's flow with the request's own
 * correlation as the cause, the act itself and every row it writes staying in the request's scope. The two doors that
 * begin a flow - the application and the acceptance - record theirs in the request's scope, which becomes the flow's.
 */
public final class BnplSpans {

    /** T-app - the customer's application door: begins the application's flow. */
    public static final String APPLY = "bnpl.application.submit";

    /** The customer withdraws their application. */
    public static final String WITHDRAW = "bnpl.application.withdraw";

    /** T-off - the origination sweep turns a decided application into its offer or closure. */
    public static final String OFFER = "bnpl.offer.make";

    /** The origination sweep expires an offer past its instant. */
    public static final String EXPIRE = "bnpl.offer.expire";

    /** The customer declines their offer. */
    public static final String DECLINE = "bnpl.offer.decline";

    /** T-pur - the customer's acceptance: the purchase, the merchant financing and the obligation; begins its flow. */
    public static final String ACCEPT = "bnpl.offer.accept";

    /** T-acc and T-svc - the servicing sweep's step on one obligation. */
    public static final String SERVICE = "bnpl.obligation.service";

    /** T-rep - the customer's repayment. */
    public static final String REPAY = "bnpl.obligation.repay";

    /** A payoff quote. */
    public static final String QUOTE = "bnpl.obligation.quote";

    /** T-pay - the payoff's execution. */
    public static final String PAYOFF = "bnpl.obligation.payoff";

    /** T-ref - a refund of the purchase, by the merchant or by an operator for it. */
    public static final String REFUND = "bnpl.purchase.refund";

    /** The customer opens a dispute of their purchase. */
    public static final String DISPUTE_OPEN = "bnpl.dispute.open";

    /** The customer withdraws their open dispute. */
    public static final String DISPUTE_WITHDRAW = "bnpl.dispute.withdraw";

    /** The merchant answers a dispute. */
    public static final String DISPUTE_RESPOND = "bnpl.dispute.respond";

    /** An operator's resolution act on a dispute - the resolution, its approval or its rejection. */
    public static final String DISPUTE_RESOLVE = "bnpl.dispute.resolve";

    /** The dispute deadline sweep's step on one dispute past its response deadline. */
    public static final String DISPUTE_LAPSE = "bnpl.dispute.lapse";

    /** T-rev / T-wvr - an operator's four-eyes correction act on an obligation. */
    public static final String CORRECT = "bnpl.obligation.correct";

    private BnplSpans() {}
}
