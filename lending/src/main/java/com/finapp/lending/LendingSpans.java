package com.finapp.lending;

/**
 * Lending's span names (`P11-TSK-030`; PHASE_11_PLAN.md section 15: "spans per step linked by the account's
 * correlation") - one per step of section 7.1 a flow passes through, each recorded through the platform {@code Spans}
 * port with no attribute at all, so no amount, rate, balance, limit, DPD, party or loan id can reach the trace backend
 * (section 11, {@code INV-AUD-02}). The steps are linked by the correlation their flow stored: an application's (its
 * offer carries it) and an account's (the acceptance's, frozen on the loan): every sweep leg runs in the stored
 * correlation of the row it steps, and every door acting on an existing application, offer or account records its span in
 * that row's correlation with the request's own correlation as the cause - the act itself, and every row it writes,
 * staying in the request's scope. The two doors that begin a flow - the application and the acceptance - record theirs in
 * the request's scope, which becomes the flow's.
 */
public final class LendingSpans {

    /** T-app - the customer's application door: begins the application's flow. */
    public static final String APPLY = "lending.application.submit";

    /** The customer withdraws their application. */
    public static final String WITHDRAW = "lending.application.withdraw";

    /** T-off - the origination sweep turns a decided application into its offer or closure. */
    public static final String OFFER = "lending.offer.make";

    /** The origination sweep expires an offer past its instant. */
    public static final String EXPIRE = "lending.offer.expire";

    /** The customer declines their offer. */
    public static final String DECLINE = "lending.offer.decline";

    /** T-acc - the customer's acceptance: begins the account's flow. */
    public static final String ACCEPT = "lending.offer.accept";

    /** T-dis - the disbursement sweep's step on one loan. */
    public static final String DISBURSE = "lending.loan.disburse";

    /** T-pyo / T-pyr - the payout sweep's step on one payout. */
    public static final String PAYOUT = "lending.payout.step";

    /** T-svc - the servicing sweep's step on one account. */
    public static final String SERVICE = "lending.loan.service";

    /** T-drw - a line's draw. */
    public static final String DRAW = "lending.loan.draw";

    /** T-rep - the customer's repayment. */
    public static final String REPAY = "lending.loan.repay";

    /** T-rep's partial prepayment with recalculation. */
    public static final String PREPAY = "lending.loan.prepay";

    /** A payoff quote. */
    public static final String QUOTE = "lending.loan.quote";

    /** T-pay - the payoff's execution. */
    public static final String PAYOFF = "lending.loan.payoff";

    /** T-clo - the customer's closure request of a line. */
    public static final String CLOSE = "lending.loan.close";

    /** The customer accepts an approved amendment. */
    public static final String AMEND = "lending.amendment.accept";

    /** T-rev / T-wvr / T-amd - an operator's four-eyes correction act on an account. */
    public static final String CORRECT = "lending.loan.correct";

    private LendingSpans() {}
}
