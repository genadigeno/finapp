package com.finapp.reconciliation;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;
import java.util.Optional;

/**
 * The one seat of the severity rule (`P8-TSK-010`, ADR-0069 §5) — pure, so ten raisers and
 * every replay grade one discrepancy identically from stored data alone: the base by type,
 * refined by direction ({@code UNKNOWN_EXTERNAL} OUTBOUND), by expectation kind
 * ({@code MISSING_EXTERNAL} on {@code MERCHANT_PAYOUT} or {@code REMITTANCE}) or by cause
 * ({@code SETTLEMENT_MISMATCH}'s statement causes); one level up when the value at issue is
 * at or above the pinned per-currency {@code high_value_minor} (owner decision O7 — {@code ≥},
 * held by the exactly-at-threshold test); capped at {@code CRITICAL}. The ageing bands are
 * the sweep's escalations (`P8-TSK-013`), applied forward-only on the stored row — at raise
 * the age is zero and no band has been crossed.
 */
public final class BreakSeverity {

    private BreakSeverity() {}

    /**
     * @param direction the subject's direction, where its type refines by it
     * @param expectationKind the subject expectation's kind, where its type refines by it
     * @param highValueMinor the pinned rule set's {@code high_value_minor} for the value's
     *     currency — empty when the rule set names no threshold for it, which escalates
     *     nothing rather than everything
     */
    public static Severity assess(
            BreakType type,
            BreakCause cause,
            Optional<ExpectationDirection> direction,
            Optional<ExpectationKind> expectationKind,
            Money valueAtIssue,
            Optional<Long> highValueMinor) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(cause, "cause must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(expectationKind, "expectationKind must not be null");
        Objects.requireNonNull(valueAtIssue, "valueAtIssue must not be null");
        Objects.requireNonNull(highValueMinor, "highValueMinor must not be null");

        Severity severity = base(type, cause, direction, expectationKind);
        if (highValueMinor.isPresent()
                && valueAtIssue.minorUnits() >= highValueMinor.get()) {
            severity = severity.oneUp();
        }
        return severity;
    }

    private static Severity base(
            BreakType type,
            BreakCause cause,
            Optional<ExpectationDirection> direction,
            Optional<ExpectationKind> expectationKind) {
        return switch (type) {
            case MISSING_EXTERNAL ->
                    expectationKind
                                    .filter(
                                            kind ->
                                                    kind == ExpectationKind.MERCHANT_PAYOUT
                                                            || kind
                                                                    == ExpectationKind
                                                                            .REMITTANCE)
                                    .isPresent()
                            ? Severity.HIGH
                            : Severity.MEDIUM;
            case UNKNOWN_EXTERNAL ->
                    direction.filter(d -> d == ExpectationDirection.OUTBOUND).isPresent()
                            ? Severity.CRITICAL
                            : Severity.HIGH;
            case SETTLEMENT_MISMATCH ->
                    cause == BreakCause.STATEMENT_GAP || cause == BreakCause.OPENING_BALANCE
                            ? Severity.CRITICAL
                            : Severity.HIGH;
            case MISSING_INTERNAL,
                            AMOUNT_MISMATCH,
                            CURRENCY_MISMATCH,
                            DUPLICATE_EXTERNAL,
                            DUPLICATE_INTERNAL,
                            REVERSAL_MISMATCH ->
                    Severity.HIGH;
            case FEE_MISMATCH, AMBIGUOUS_MATCH -> Severity.MEDIUM;
            case TIMING_DIFFERENCE -> Severity.LOW;
            case REFUND_MISMATCH, PROCESSING_ERROR -> Severity.CRITICAL;
        };
    }
}
