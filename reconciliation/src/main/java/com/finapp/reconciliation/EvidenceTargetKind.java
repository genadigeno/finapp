package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * What a {@code break_evidence_link} points at (`P8-TSK-010`, ADR-0069 §7) — identifiers
 * only, never a URL or content: a break reaches its evidence by the stored identifier chain
 * ({@code INV-REC-01}), and raw file content is read only through settlement's audited
 * content reads. The producer route is `P8-TSK-014`'s; the table and its discipline are
 * this task's.
 */
public enum EvidenceTargetKind {

    /** A settlement file id. */
    SETTLEMENT_FILE,

    /** A settlement batch id. */
    SETTLEMENT_BATCH,

    /** A canonical settlement line id. */
    SETTLEMENT_LINE,

    /** A ledger journal entry id. */
    JOURNAL_ENTRY,

    /** A payments or merchant operation reference. */
    OPERATION,

    /** A {@code payments.provider_evidence} id. */
    PROVIDER_EVIDENCE,

    /** A reconciliation run id. */
    RUN,

    /** A match decision id (`P8-TSK-011`). */
    DECISION;

    /** The `V004` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
