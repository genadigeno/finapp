package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The severity policy's determinism (`P8-TSK-010`, ADR-0069 §5): every base, every named
 * refinement, the high-value escalation at exactly the threshold and not one unit below
 * (owner decision O7's {@code ≥}), and the {@code CRITICAL} cap.
 */
@DisplayName("the severity policy (P8-TSK-010)")
class BreakSeverityTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final long THRESHOLD = 100_000L; // 1,000.00 EUR, rule set v1's seed.

    private static Severity assess(BreakType type, BreakCause cause, long minor) {
        return BreakSeverity.assess(
                type,
                cause,
                Optional.empty(),
                Optional.empty(),
                Money.ofPersisted(minor, EUR, 2),
                Optional.of(THRESHOLD));
    }

    @Test
    @DisplayName("every type's base severity is ADR-0069's table")
    void everyBaseIsTheTables() {
        assertThat(assess(BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE, 1))
                .isEqualTo(Severity.MEDIUM);
        assertThat(assess(BreakType.MISSING_INTERNAL, BreakCause.GRACE_EXPIRED, 1))
                .isEqualTo(Severity.HIGH);
        assertThat(assess(BreakType.UNKNOWN_EXTERNAL, BreakCause.GRACE_EXPIRED, 1))
                .isEqualTo(Severity.HIGH);
        assertThat(assess(BreakType.AMOUNT_MISMATCH, BreakCause.AMOUNT_DIFFERS, 1))
                .isEqualTo(Severity.HIGH);
        assertThat(assess(BreakType.CURRENCY_MISMATCH, BreakCause.CURRENCY_DIFFERS, 1))
                .isEqualTo(Severity.HIGH);
        assertThat(assess(BreakType.FEE_MISMATCH, BreakCause.FEE_BEYOND_TOLERANCE, 1))
                .isEqualTo(Severity.MEDIUM);
        assertThat(assess(BreakType.DUPLICATE_EXTERNAL, BreakCause.REPEATED_FINGERPRINT, 1))
                .isEqualTo(Severity.HIGH);
        assertThat(assess(BreakType.DUPLICATE_INTERNAL, BreakCause.KEY_COLLISION, 1))
                .isEqualTo(Severity.HIGH);
        assertThat(assess(BreakType.AMBIGUOUS_MATCH, BreakCause.MULTIPLE_CANDIDATES, 1))
                .isEqualTo(Severity.MEDIUM);
        assertThat(assess(BreakType.TIMING_DIFFERENCE, BreakCause.LATE_MATCH, 0))
                .isEqualTo(Severity.LOW);
        assertThat(assess(BreakType.REVERSAL_MISMATCH, BreakCause.DIRECTION_CONTRADICTED, 1))
                .isEqualTo(Severity.HIGH);
        assertThat(assess(BreakType.REFUND_MISMATCH, BreakCause.REFUND_CONTRADICTED, 1))
                .isEqualTo(Severity.CRITICAL);
        assertThat(assess(BreakType.SETTLEMENT_MISMATCH, BreakCause.REMITTANCE_DIFFERS, 1))
                .isEqualTo(Severity.HIGH);
        assertThat(assess(BreakType.PROCESSING_ERROR, BreakCause.ITEM_ERRORED, 1))
                .isEqualTo(Severity.CRITICAL);
    }

    @Test
    @DisplayName("the named refinements: OUTBOUND UNKNOWN_EXTERNAL, the payout and"
            + " remittance kinds, the statement causes")
    void theRefinementsHold() {
        assertThat(
                        BreakSeverity.assess(
                                BreakType.UNKNOWN_EXTERNAL,
                                BreakCause.GRACE_EXPIRED,
                                Optional.of(ExpectationDirection.OUTBOUND),
                                Optional.empty(),
                                Money.ofPersisted(1, EUR, 2),
                                Optional.of(THRESHOLD)))
                .as("money that LEFT with no owner is the loudest unknown")
                .isEqualTo(Severity.CRITICAL);
        assertThat(
                        BreakSeverity.assess(
                                BreakType.MISSING_EXTERNAL,
                                BreakCause.EXPECTATION_OVERDUE,
                                Optional.empty(),
                                Optional.of(ExpectationKind.MERCHANT_PAYOUT),
                                Money.ofPersisted(1, EUR, 2),
                                Optional.of(THRESHOLD)))
                .isEqualTo(Severity.HIGH);
        assertThat(
                        BreakSeverity.assess(
                                BreakType.MISSING_EXTERNAL,
                                BreakCause.EXPECTATION_OVERDUE,
                                Optional.empty(),
                                Optional.of(ExpectationKind.REMITTANCE),
                                Money.ofPersisted(1, EUR, 2),
                                Optional.of(THRESHOLD)))
                .isEqualTo(Severity.HIGH);
        assertThat(assess(BreakType.SETTLEMENT_MISMATCH, BreakCause.STATEMENT_GAP, 1))
                .isEqualTo(Severity.CRITICAL);
        assertThat(assess(BreakType.SETTLEMENT_MISMATCH, BreakCause.OPENING_BALANCE, 1))
                .isEqualTo(Severity.CRITICAL);
    }

    @Test
    @DisplayName("one level up at exactly high_value_minor and not one unit below - and"
            + " the grade caps at CRITICAL")
    void theThresholdIsInclusiveAndTheCapHolds() {
        assertThat(assess(BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE,
                        THRESHOLD - 1))
                .as("one unit below: the base stands")
                .isEqualTo(Severity.MEDIUM);
        assertThat(assess(BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE,
                        THRESHOLD))
                .as("at the threshold: one level up (>=, owner decision O7)")
                .isEqualTo(Severity.HIGH);
        assertThat(assess(BreakType.REFUND_MISMATCH, BreakCause.REFUND_CONTRADICTED,
                        THRESHOLD))
                .as("CRITICAL caps")
                .isEqualTo(Severity.CRITICAL);
        assertThat(
                        BreakSeverity.assess(
                                BreakType.MISSING_EXTERNAL,
                                BreakCause.EXPECTATION_OVERDUE,
                                Optional.empty(),
                                Optional.empty(),
                                Money.ofPersisted(THRESHOLD, EUR, 2),
                                Optional.empty()))
                .as("no threshold row escalates nothing rather than everything")
                .isEqualTo(Severity.MEDIUM);
    }

    @Test
    @DisplayName("a cause raises only its own types, and the suspense-owning list is"
            + " ADR-0070's")
    void theVocabularyDisciplinesHold() {
        assertThat(BreakCause.KEY_COLLISION.raisesAs())
                .containsExactly(BreakType.DUPLICATE_INTERNAL);
        assertThat(BreakCause.GRACE_EXPIRED.raisesAs())
                .containsExactlyInAnyOrder(
                        BreakType.MISSING_INTERNAL, BreakType.UNKNOWN_EXTERNAL);
        assertThat(BreakType.MISSING_EXTERNAL.mayOwnSuspense()).isFalse();
        assertThat(BreakType.FEE_MISMATCH.mayOwnSuspense()).isFalse();
        assertThat(BreakType.DUPLICATE_INTERNAL.mayOwnSuspense()).isFalse();
        assertThat(BreakType.TIMING_DIFFERENCE.mayOwnSuspense()).isFalse();
        assertThat(
                        java.util.Arrays.stream(BreakType.values())
                                .filter(BreakType::mayOwnSuspense)
                                .count())
                .as("ten of fourteen may own suspense (ADR-0070 section 2)")
                .isEqualTo(10);
    }
}
