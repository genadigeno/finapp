package com.finapp.payments;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Which way the money moves relative to the platform (`P7-TSK-003`, ADR-0060 §1): a routing
 * rule's first matcher. A pay-in funds the platform's books from outside (the card top-up,
 * the checkout payment); a pay-out sends the platform's money outward (the withdrawal, the
 * return payment — `P7-TSK-009`'s callers).
 *
 * <p>Deliberately not the ledger's debit/credit vocabulary: direction here is the payment's
 * relationship to the outside world, decided before any posting exists.
 */
public enum PaymentDirection {
    PAY_IN,
    PAY_OUT;

    /** The values as a SQL literal list — `V013`'s `CHECK`s are generated from this. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
