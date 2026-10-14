package com.finapp.app.telemetry;

import com.finapp.lending.LoanProduct;
import java.time.Duration;
import java.util.Arrays;

/**
 * The objectives Phase 11's lending alerts are drawn against (`P11-TSK-030`; PHASE_11_PLAN.md section 15) - declared once,
 * here, so the rules file and the guards reading it cannot drift apart ({@code AlertRulesResolveTest#theLendingRulesResolve}
 * and {@code LendingAlertRulesTest} hold each rule's threshold equal to these). The {@code CreditObjectives} shape.
 *
 * <ul>
 *   <li><strong>The disbursement deadline</strong>: the longest any product lets a {@code PENDING} disbursement wait
 *       ({@link LoanProduct#disbursementDeadline()}, 24 hours for both today) - every terms version names its own deadline
 *       within that ceiling, and the sweep cancels a loan whose wallet could not be posted by it. A pending disbursement
 *       older than the ceiling means the sweep is not acting. Path X counts here too until its wallet posting (T-dis);
 *       the payout that follows is the payout objective's.
 *   <li><strong>The payout outcome objective</strong>: a loan payout - payments' own withdrawal on the instant rail - is
 *       concluded within thirty minutes of its birth: the payout sweep claims it within its poll, the instant rail answers
 *       within seconds, and payments' resolution sweep inquires a withdrawal still {@code DISPATCHED} after its ten-minute
 *       bound ({@code finapp.payments.withdrawal.sweeper.dispatched-age}), so thirty minutes leaves the claim, the dispatch
 *       and two resolution passes. Past it the borrower's funds are held and their interest waits on the platform's
 *       ambiguity (A30).
 *   <li><strong>The accrual lag</strong>: no account in servicing leaves an elapsed business date unaccrued for more than a
 *       day past its end - the servicing sweep services every account once each business date, so a day's lag means a
 *       whole business date was missed.
 * </ul>
 */
public final class LendingObjectives {

    /** The oldest unconcluded payout's age, from its birth. */
    public static final Duration PAYOUT_OUTCOME = Duration.ofMinutes(30);

    /** Seconds since the end of the oldest elapsed business date an account in servicing has not accrued. */
    public static final Duration ACCRUAL_LAG = Duration.ofDays(1);

    private LendingObjectives() {}

    /** The longest disbursement deadline any product's terms version may name. */
    public static Duration disbursementDeadline() {
        return Arrays.stream(LoanProduct.values())
                .map(LoanProduct::disbursementDeadline)
                .max(Duration::compareTo)
                .orElseThrow();
    }
}
