package com.finapp.reconciliation;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * The closed reason codes a resolution carries (`P8-TSK-012`, ADR-0071 §5; the plan's
 * §12.6 list, closed at both ranks). `P8-TSK-012` produces only
 * {@code EVIDENCE_RECEIVED} — the platform's, refused to any person by `V006`'s
 * {@code EVIDENCED} `CHECK`; the allowed subset per kind is `P8-TSK-015`'s door and
 * `V007`'s pairing `CHECK`.
 */
public enum ResolutionReasonCode {

    /** The counterparty's own correction or claw-back explained the break — platform only. */
    EVIDENCE_RECEIVED,

    COUNTERPARTY_ERROR_CONFIRMED,
    INTERNAL_PROCESSING_ERROR,
    DUPLICATE_BY_COUNTERPARTY,
    FUNDS_ATTRIBUTED,
    UNATTRIBUTABLE_AGED,
    TIMING_CONFIRMED,
    FEE_ACCEPTED_AS_CHARGED,
    FEE_RECOVERED,
    AMBIGUITY_RESOLVED_BY_EVIDENCE,
    IMMATERIAL_DIFFERENCE,
    LOSS_ACCEPTED,
    EVIDENCE_REPUDIATED;

    /** The value list of every member (`V006`'s migration test reads it). */
    public static String sqlValueList() {
        return sqlValueList(java.util.EnumSet.allOf(ResolutionReasonCode.class));
    }

    /** The codes `V007` admits: every code but the repudiation's (`V012`, `-023`). */
    public static java.util.Set<ResolutionReasonCode> admittedByV007() {
        return java.util.EnumSet.complementOf(java.util.EnumSet.of(EVIDENCE_REPUDIATED));
    }

    /** The value list of {@code codes}, in declaration order. */
    public static String sqlValueList(java.util.Set<ResolutionReasonCode> codes) {
        return Arrays.stream(values())
                .filter(codes::contains)
                .map(value -> "'" + value.name() + "'")
                .collect(Collectors.joining(", "));
    }
}
