package com.finapp.app.telemetry;

import java.time.Duration;

/**
 * The objectives Phase 12's BNPL alerts are drawn against (`P12-TSK-026`; {@code PHASE_12_PLAN.md} section 15) - declared
 * once, here, so {@code infra/prometheus/rules/bnpl.yml} and the guards reading it cannot drift apart
 * ({@code BnplAlertRulesTest} and {@code AlertRulesResolveTest#theBnplRulesResolve} hold each rule's threshold equal to
 * these). {@link LendingObjectives}' shape.
 *
 * <ul>
 *   <li><strong>The accrual lag</strong>: no {@code ACTIVE} pay-in-N obligation leaves an elapsed date of its accrual span
 *       unaccrued for more than a day past its end ({@code P12-TSK-017}'s hand-off,
 *       {@code BnplAccrualMetrics#LAG_ALERT}, held equal by {@code BnplAlertRulesTest}) - the servicing sweep runs T-acc
 *       before every step, so a day's lag means a whole business date was missed.
 *   <li><strong>The decision wait</strong> (the Phase 11 completion gate's A5-03 applied forward): no BNPL application
 *       waits {@code AWAITING_DECISION} longer than the longest credit request validity
 *       ({@link CreditObjectives#requestValidity()}, the BNPL products' among them) and an hour - credit closes a request
 *       at its validity at the latest and the origination step then closes the application undecided, so the hour covers
 *       both sweeps' polls.
 *   <li><strong>The negative payable's grace</strong>: a merchant payable below zero - legal under the amended
 *       {@code INV-MER-07} bound (a refund after payout, B7) and recovered from later captures and financings before any
 *       payout ({@code INV-MER-05}) - is a ticket once it has stood an hour, never a page: it is a debt to collect, not
 *       a defect.
 * </ul>
 */
public final class BnplObjectives {

    /** Seconds since the end of the oldest elapsed date an ACTIVE pay-in-N obligation has not accrued. */
    public static final Duration ACCRUAL_LAG = Duration.ofDays(1);

    /** How long a negative merchant payable stands before it is ticketed. */
    public static final Duration NEGATIVE_PAYABLE_GRACE = Duration.ofHours(1);

    private BnplObjectives() {}

    /** The longest an application may wait {@code AWAITING_DECISION} before the origination sweep is judged stalled. */
    public static Duration awaitingDecision() {
        return CreditObjectives.requestValidity().plus(Duration.ofHours(1));
    }
}
