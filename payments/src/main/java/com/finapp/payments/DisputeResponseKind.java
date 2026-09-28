package com.finapp.payments;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The platform's two answers to a chargeback (`P7-TSK-014`, ADR-0061 §7) — one aggregate,
 * because acceptance needs every protocol representment has: keyed, dispatched before the call,
 * resolvable by query, one live answer per dispute. The network's reply — {@code REPRESENTED},
 * {@code ACCEPTED}, {@code WON}, {@code LOST} — still arrives by notification; neither answer
 * moves the dispute's stage.
 */
public enum DisputeResponseKind {

    /** Contest the chargeback with the dispute's evidence set. */
    REPRESENTMENT,

    /** Concede the chargeback rather than contest it — the network then states ACCEPTED. */
    ACCEPTANCE;

    /** Whether this answer carries evidence: a representment always, an acceptance never. */
    public boolean carriesEvidence() {
        return this == REPRESENTMENT;
    }

    /** The quoted, comma-separated value list `V022`'s {@code CHECK} uses. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
