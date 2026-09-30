package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The eight template-bound ways a break closes (`P8-TSK-012`, ADR-0071 §2) — stated whole
 * now, the closed vocabulary `V007` regenerates from. `P8-TSK-012` produces only
 * {@code EVIDENCED}, and `V006`'s {@code CHECK} deliberately admits only it (the plan's §8
 * slicing: the person kinds arrive with `P8-TSK-015`'s door and `V007`, the batch subject
 * with `-023`'s `V009`) — a narrowed rank, not a narrowed vocabulary.
 */
public enum ResolutionKind {

    /** Any break explained by a zero-residual allocation or offset — the platform's only. */
    EVIDENCED,

    /** A zero-value observation accepted (`P8-TSK-015`). */
    ACKNOWLEDGE,

    /** An INBOUND remainder or DEBIT item absorbed to RECONCILIATION_LOSSES (`-015`). */
    WRITE_OFF,

    /** A CREDIT item or OUTBOUND remainder attributed to a named account (`-015`). */
    TRANSFER_TO_ACCOUNT,

    /** A CREDIT and a DEBIT item of equal amount netted, no posting (`-015`). */
    OFFSET_SUSPENSE,

    /** An aged CREDIT item recognised as income, where its type admits it (`-015`). */
    RECOGNISE_GAIN,

    /** A person chooses the ambiguous candidate the engine refused to guess (`-015`). */
    MANUAL_MATCH,

    /** An accepted batch reversed whole (`P8-TSK-023`). */
    REPUDIATE_BATCH;

    /** The value list `V007` regenerates into the widened {@code CHECK}. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
