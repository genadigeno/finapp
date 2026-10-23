package com.finapp.payments;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What more a {@code FAILED(RISK_REFUSED)} withdrawal records about how it failed (`P13-TSK-009`, payments
 * {@code V033}; {@code PHASE_13_PLAN.md} §5, §14) - present only where the reason alone would leave two facts
 * indistinguishable on the row. A refusal at the judgement is told apart by never having been {@code HELD}; a denial by
 * its {@code held_resolution_key} (the door's key); the expiry by this detail. Internal: the customer reads
 * {@code RISK_REFUSED} and never the detail ({@code INV-RSK-08}).
 */
public enum WithdrawalFailureDetail {

    /**
     * The held withdrawal reached its {@code held_until} on the database clock with nobody having released or denied
     * it: failed by payments' expiry leg on {@code WithdrawalResolutionSchedule}, the hold released, the risk case left
     * open.
     */
    HOLD_EXPIRED;

    /** The values as a SQL literal list — {@code V033}'s {@code CHECK} is generated from this. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
