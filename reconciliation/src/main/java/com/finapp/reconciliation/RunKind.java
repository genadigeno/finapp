package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Why a reconciliation run exists (`P8-TSK-009`, §5.3): an accepted batch's own run, or a
 * person's keyed re-resolution of residual items (`P8-TSK-014`). The kind decides the
 * identity arbiter: one {@code BATCH} run per batch and per (source, sequence); one open
 * {@code REPROCESS} per source.
 */
public enum RunKind {
    BATCH,
    REPROCESS;

    /** The `V003` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
