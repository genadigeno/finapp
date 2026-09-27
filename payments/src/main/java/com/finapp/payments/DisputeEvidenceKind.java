package com.finapp.payments;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What one piece of dispute evidence is (`P7-TSK-014`, ADR-0061 §7) — the closed set the
 * representment wire labels each document with, so the network's reviewer knows what it is
 * reading without the platform assembling a narrative (automated evidence assembly is out of
 * scope).
 *
 * <p>Closed deliberately, the {@code DocumentType} reasoning: a free-text label is a field the
 * responder would type customer details into. `V022`'s {@code CHECK} is generated from here
 * ({@link #sqlValueList()}), and {@code PaymentsMigrationTest} fails the build if they disagree.
 */
public enum DisputeEvidenceKind {

    /** The receipt or invoice the customer was given. */
    RECEIPT,

    /** Proof the goods or service were delivered — a carrier's record, a signed note. */
    PROOF_OF_DELIVERY,

    /** Correspondence with the customer about the purchase. */
    CUSTOMER_COMMUNICATION,

    /** The refund, cancellation or terms policy the customer accepted. */
    POLICY,

    /** Anything else the responder judges material. */
    OTHER;

    /** The quoted, comma-separated value list `V022`'s {@code CHECK} uses. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
