package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What one evaluation concluded (`P8-TSK-011`, ADR-0068 §5) — stated whole (the `V002`
 * precedent): `P8-TSK-011` produces {@code MATCHED}, {@code UNMATCHED}, {@code PARKED} and
 * {@code ERRORED}; {@code CHECKED} and {@code OFFSET} arrive with the fee check and the
 * correction (`P8-TSK-012`) and stay inert until then. The outcome mirrors the item's final
 * disposition in the deciding transaction — an over-payment that allocates AND parks is
 * {@code PARKED}, the louder fact.
 */
public enum DecisionOutcome {

    /** Fully allocated (an under-payment's item is fully allocated too). */
    MATCHED,

    /** A fee line verified against the pinned schedule (`P8-TSK-012`). */
    CHECKED,

    /** A correction offset a parked excess (`P8-TSK-012`). */
    OFFSET,

    /** Left waiting: a grace class, or a cardinality whose engine has not landed. */
    UNMATCHED,

    /** A definitive class: the remainder parked with its break in this transaction. */
    PARKED,

    /** The engine threw on this item; the remainder parked, the chunk carried on. */
    ERRORED;

    /** The `V005` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
