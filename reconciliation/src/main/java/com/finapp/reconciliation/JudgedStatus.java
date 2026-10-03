package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The item's state a decision judged (`P8-TSK-022`, `V012`): a run's chunk judges a
 * {@code PENDING} line, grace and rematch a waiting {@code UNMATCHED} one, a rematch or a
 * reprocess a {@code PARKED} one — whose judged value is its parked remainder, not its amount.
 * Replay needs it: a parked item is allocated whole or not at all.
 */
public enum JudgedStatus {
    PENDING,
    UNMATCHED,
    PARKED;

    /** The item status a leg moves the item out of, named as its judgement. */
    public static JudgedStatus ofItemStatus(String status) {
        return valueOf(status);
    }

    /** The `V012` {@code CHECK}'s value list — reconciled by the migration test. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
