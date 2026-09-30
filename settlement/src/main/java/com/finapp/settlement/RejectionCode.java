package com.finapp.settlement;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Why a settlement file was rejected (`P8-TSK-008`, ADR-0066 §9) — the file row's one-word
 * verdict, carried by {@code settlement.SettlementFileRejected} and by the
 * {@code finapp.settlement.file.rejected} outcome tag.
 *
 * <p>The five content codes leave up to 100 {@code ingestion_error} rows naming line, code
 * and field — never a value. {@link #CONFLICTING_BATCH} and {@link #DECLINED} are verdicts
 * about the file as a whole, and leave none.
 */
public enum RejectionCode {

    /** A record or field the format cannot read as declared — the counterparty's defect. */
    MALFORMED(true),

    /** The trailer's count or net disagrees with the `Money` fold of the details. */
    CONTROL_TOTAL_MISMATCH(true),

    /** A currency the platform cannot represent. */
    UNKNOWN_CURRENCY(true),

    /** An amount carrying more decimals than its currency's scale. */
    SCALE_MISMATCH(true),

    /** The header names a format or version this source does not deliver. */
    UNSUPPORTED_FORMAT(true),

    /**
     * A live batch already stands for this (source, batch reference, currency) — the second
     * declaration is refused and RETAINED, recoverable by readmission once the standing batch
     * is repudiated (`P8-TSK-022`, `P8-TSK-023`).
     */
    CONFLICTING_BATCH(false),

    /** A person's reasoned judgement, not our validation — never readmitted (ADR-0066 §8). */
    DECLINED(false),

    /**
     * The source retired between receipt and acceptance (`P8-TSK-009`, §5.1): its identity
     * stands, its door closed, and the parsed evidence is rejected RETAINED — a re-opened
     * source is a NEW source whose re-issue arrives through its own door.
     */
    SOURCE_RETIRED(false);

    private final boolean leavesErrorRows;

    RejectionCode(boolean leavesErrorRows) {
        this.leavesErrorRows = leavesErrorRows;
    }

    /** Whether this verdict is substantiated by per-line {@code ingestion_error} rows. */
    public boolean leavesErrorRows() {
        return leavesErrorRows;
    }

    /** The `V003` {@code CHECK}'s value list for {@code file.rejection_code}. */
    public static String sqlValueList() {
        return Arrays.stream(values())
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }

    /** The `V003` {@code CHECK}'s value list for {@code ingestion_error.error_code}. */
    public static String sqlErrorRowList() {
        return Arrays.stream(values())
                .filter(RejectionCode::leavesErrorRows)
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
