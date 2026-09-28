package com.finapp.payments;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Why the payer disputes the payment, as the platform's own category (`P7-TSK-012`, ADR-0061
 * §6) — never the network's reason code, which stops at the webhook door with the rest of the
 * PSP's vocabulary ({@code INV-PAY-03}) and rests verbatim in the retained evidence.
 *
 * <p>The four categories are the card networks' own dispute groupings (the Visa Claims
 * Resolution groups; Mastercard's chargeback categories), so every real reason code has one
 * honest home. {@link #UNCATEGORISED} is the total mapping's default branch: a code the door
 * cannot place is <em>indeterminate</em>, never guessed into a category — the reason decides no
 * money, so an unknown one never blocks the stage it arrived with.
 */
public enum DisputeReason {

    /** The payer says they did not make or recognise the payment. */
    FRAUD,

    /** The payment was not properly authorized. */
    AUTHORIZATION,

    /** Something went wrong in processing: a duplicate, a wrong amount, a late presentment. */
    PROCESSING_ERROR,

    /** A disagreement about what was bought: not received, not as described, a credit not
     * processed, a cancelled subscription still charged. */
    CONSUMER_DISPUTE,

    /** The network's code maps to none of the above — recorded as unknown, never guessed. */
    UNCATEGORISED;

    /** The values as a SQL literal list — `V020`'s {@code CHECK} is generated from this. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
