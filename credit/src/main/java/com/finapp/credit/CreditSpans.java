package com.finapp.credit;

/**
 * The credit decision's span names (`P10-TSK-020`; PHASE_10_PLAN.md section 15): the five legs of one request -
 * submission, collection, freeze, evaluation and decision - each recorded through the platform {@code Spans} port with
 * no attribute at all, so no amount, score, attribute value or party can reach the trace backend ({@code INV-CRD-02},
 * {@code INV-AUD-02}). The legs are linked by the request's own correlation: the submission's span runs in the request's
 * scope, and every later leg - taken by a sweeper on any instance - runs in a scope carrying the correlation the request
 * stored at submission, its own step's correlation as the cause.
 */
public final class CreditSpans {

    /** The customer's submission door. */
    public static final String SUBMIT = "credit.request.submit";

    /** One provider pull for a source kind. */
    public static final String COLLECT = "credit.data.collect";

    /** The snapshot's freeze from the collected records. */
    public static final String FREEZE = "credit.snapshot.freeze";

    /** The assessment and the policy's evaluation over the frozen snapshot. */
    public static final String EVALUATE = "credit.policy.evaluate";

    /** The platform's deciding transaction. */
    public static final String DECIDE = "credit.decision.decide";

    private CreditSpans() {}
}
