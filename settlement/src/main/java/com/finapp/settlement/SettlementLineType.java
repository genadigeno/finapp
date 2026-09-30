package com.finapp.settlement;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The closed set of canonical settlement line types (`P8-TSK-008`, ADR-0065 §2) — the
 * platform's vocabulary, never a provider's ({@code INV-PAY-03}: a provider code like the
 * simulated PSP's {@code SALE} lives only inside its format adapter).
 *
 * <p><strong>An unknown provider type is degraded, never dropped</strong> ({@code INV-REC-02}):
 * it becomes {@link #OTHER_IN} or {@link #OTHER_OUT} by its direction — and never a success
 * type, so nothing a counterparty invents can silently claim to be a capture. It survives
 * parsing to become a typed break at matching (`P8-TSK-011`), which is where a human sees it.
 */
public enum SettlementLineType {

    /** The counterparty reports our capture settled — allocates, posts nothing (ADR-0065 §2). */
    CAPTURE,

    /** Our refund, reported completed. */
    REFUND,

    /** A chargeback the network took. */
    CHARGEBACK,

    /** A chargeback returned — the dispute was won. */
    CHARGEBACK_REVERSAL,

    /** The network's dispute fee — posted at its stage, so this line allocates, never posts. */
    DISPUTE_FEE,

    /**
     * The counterparty's own fee for processing — the one thing hop 1 will post
     * (`P8-TSK-009`, ADR-0065 §2). Also what a gross-plus-fee source line's fee half becomes,
     * carrying {@code ORIGINAL_REF} to the transaction it rode in on.
     */
    PROCESSING_FEE,

    /** A correction the counterparty issues as a NEW line in a later batch — never an edit. */
    COUNTERPARTY_ADJUSTMENT,

    /** An inbound line no mapping knows — kept, typed as a break at matching, never a success. */
    OTHER_IN,

    /** An outbound line no mapping knows. */
    OTHER_OUT;

    /** The `V003` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
