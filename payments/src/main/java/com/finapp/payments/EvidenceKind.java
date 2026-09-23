package com.finapp.payments;

/**
 * What kind of provider bytes an evidence row retains ({@code PAYMENT_LIFECYCLES.md} §6,
 * {@code INV-HIST-02}) — arriving with the first writer ({@code P5-TSK-009}), exactly as
 * {@code V005}'s header promised; the migration's {@code CHECK} is reconciled against
 * {@link #sqlValueList()} from that arrival on.
 *
 * <p>{@code REQUEST} exists for the day an outbound payload is captured at the wire; today the
 * port's answer types carry <em>received</em> bytes only (ADR-0049), so the writers of this
 * phase produce {@code RESPONSE}, {@code QUERY_RESULT} and {@code WEBHOOK} rows — the recorded
 * finding for the phase audit, not a silent narrowing.
 */
public enum EvidenceKind {
    REQUEST,
    RESPONSE,
    QUERY_RESULT,
    WEBHOOK;

    /** The kinds as a SQL literal list — {@code V005}'s {@code CHECK}, one definition. */
    public static String sqlValueList() {
        return java.util.Arrays.stream(values())
                .map(kind -> "'" + kind.name() + "'")
                .collect(java.util.stream.Collectors.joining(", "));
    }
}
