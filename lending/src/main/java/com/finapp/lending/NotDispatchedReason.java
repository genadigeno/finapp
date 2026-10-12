package com.finapp.lending;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Why payments did not take a payout's withdrawal (`P11-TSK-015`; ADR-0097 point 4) - met while dispatching, so the payout
 * moves {@code DISPATCHING -> NOT_DISPATCHED}, the hold released and the funds free in the borrower's wallet. Each is
 * payments' answer, recorded in lending's vocabulary - never an account identifier ({@code INV-RAIL-03}).
 */
public enum NotDispatchedReason {

    /** Payments' routing found no rail able to carry it (its refusal recorded under the instruction's key). */
    NO_ELIGIBLE_RAIL,

    /** The payout method is no longer the borrower's own active bank account. */
    DESTINATION_UNAVAILABLE,

    /** The borrower has no active customer record payments can withdraw for. */
    CUSTOMER_UNAVAILABLE;

    /** The values as a SQL literal list - {@code lending V009}'s {@code CHECK}. */
    public static String sqlValueList() {
        return Arrays.stream(values()).map(value -> "'" + value.name() + "'").collect(Collectors.joining(", "));
    }
}
