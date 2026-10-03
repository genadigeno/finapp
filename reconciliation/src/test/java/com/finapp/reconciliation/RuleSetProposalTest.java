package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A proposed rule set version's pure judgement (`P8-TSK-022`, ADR-0068 §8): the domain rank of
 * `V002`'s member {@code CHECK}s as `V010` and `V012` regenerated them, judged before anything
 * is stored. The tolerance vocabulary first — an amount tolerance is unrepresentable
 * ({@code INV-REC-08}) whatever else is wrong — then one refusal per defect class, each naming
 * the defect and never echoing a supplied value.
 */
@DisplayName("a rule set proposal is judged before anything is stored (P8-TSK-022)")
class RuleSetProposalTest {

    private static final UUID PSP_SOURCE = UUID.fromString("01a0e2bc-8200-7001-8000-000000000001");
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");

    /** A well-formed PSP version shaped like rule set v1, every field overridable. */
    private static final class Draft {
        int fundingLagDays = 2;
        int gainMinAgeDays = 90;
        Map<ExpectationKind, Integer> lagDays =
                Map.of(ExpectationKind.CARD_CAPTURE, 3, ExpectationKind.CARD_REFUND, 3);
        List<RuleSetProposal.Rule> rules =
                new ArrayList<>(
                        List.of(
                                rule(1, ExternalLineType.CAPTURE, "PSP_CAPTURE_REF",
                                        ExpectationKind.CARD_CAPTURE, Cardinality.ONE_TO_ONE),
                                rule(2, ExternalLineType.REFUND, "PSP_REFUND_REF",
                                        ExpectationKind.CARD_REFUND, Cardinality.ONE_TO_ONE),
                                rule(3, ExternalLineType.PROCESSING_FEE, "ORIGINAL_REF", null,
                                        Cardinality.CHECK)));
        List<RuleSetProposal.Tolerance> tolerances =
                new ArrayList<>(List.of(dateWindow(2), feeBound("PROCESSING_FEE_PER_LINE", 2)));
        List<RuleSetProposal.FeeTerms> fees =
                new ArrayList<>(
                        List.of(fee(ExternalLineType.PROCESSING_FEE, EUR, "0.015000", 25, 2)));
        Map<CurrencyCode, Long> thresholds = Map.of(EUR, 100_000L, GBP, 100_000L);
        String reason = "Tighten the PSP's date window after the provider moved its cut-off";

        RuleSetProposal build() {
            return new RuleSetProposal(
                    PSP_SOURCE, fundingLagDays, gainMinAgeDays, lagDays, rules, tolerances, fees,
                    thresholds, reason);
        }
    }

    private static RuleSetProposal.Rule rule(
            int priority,
            ExternalLineType lineType,
            String keyKind,
            ExpectationKind kind,
            Cardinality cardinality) {
        return new RuleSetProposal.Rule(
                priority, lineType, Optional.ofNullable(keyKind), Optional.ofNullable(kind),
                cardinality, false, 48);
    }

    private static RuleSetProposal.Tolerance dateWindow(int days) {
        return new RuleSetProposal.Tolerance(
                "SETTLEMENT_DATE_DAYS", Optional.empty(), Optional.empty(), Optional.of(days));
    }

    private static RuleSetProposal.Tolerance feeBound(String comparison, long minor) {
        return new RuleSetProposal.Tolerance(
                comparison, Optional.of(EUR), Optional.of(minor), Optional.empty());
    }

    private static RuleSetProposal.FeeTerms fee(
            ExternalLineType lineType, CurrencyCode currency, String rate, long fixed, int scale) {
        return new RuleSetProposal.FeeTerms(
                lineType, currency, new BigDecimal(rate), fixed, scale, "HALF_UP");
    }

    private static void assertInvalid(Draft draft, String defect) {
        RuleSetProposal proposal = draft.build();
        assertThatThrownBy(proposal::validate)
                .isInstanceOf(RuleSetAdministration.RuleSetInvalid.class)
                .hasMessageContaining(defect);
    }

    // ----------------------------------------------------------------- the well-formed version

    @Test
    @DisplayName("a well-formed version validates")
    void aWellFormedVersionValidates() {
        assertThatCode(() -> new Draft().build().validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a rate's trailing zeros are not precision: 0.0150000 is the stored 0.015000")
    void trailingZerosAreNotPrecision() {
        Draft draft = new Draft();
        draft.fees = List.of(fee(ExternalLineType.PROCESSING_FEE, EUR, "0.0150000", 25, 2));
        assertThatCode(() -> draft.build().validate()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the copies are defensive and deterministic: lags in kind order")
    void theCopiesAreDefensive() {
        Draft draft = new Draft();
        draft.lagDays = Map.of(ExpectationKind.CARD_REFUND, 3, ExpectationKind.CARD_CAPTURE, 3);
        RuleSetProposal proposal = draft.build();
        assertThat(proposal.lagDays().keySet())
                .containsExactly(ExpectationKind.CARD_CAPTURE, ExpectationKind.CARD_REFUND);
        assertThatThrownBy(() -> proposal.rules().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ----------------------------------------------------------------- INV-REC-08 first

    @Test
    @DisplayName("an amount tolerance is ToleranceNotPermitted, judged before every other defect")
    void anAmountToleranceIsNotPermittedFirst() {
        Draft draft = new Draft();
        draft.tolerances = List.of(dateWindow(2), feeBound("PRINCIPAL_AMOUNT", 100));
        // Other defects at once: the tolerance refusal is its own answer.
        draft.reason = " ";
        draft.rules = List.of();
        draft.fundingLagDays = -1;
        RuleSetProposal proposal = draft.build();
        assertThatThrownBy(proposal::validate)
                .isInstanceOf(RuleSetAdministration.ToleranceNotPermitted.class)
                .hasMessageContaining("INV-REC-08")
                .hasMessageNotContaining("PRINCIPAL_AMOUNT");
    }

    // ----------------------------------------------------------------- the reason

    @Nested
    @DisplayName("the reason")
    class TheReason {

        @Test
        @DisplayName("is required")
        void isRequired() {
            Draft draft = new Draft();
            draft.reason = "   ";
            assertInvalid(draft, "reason must be 1..1000 characters");
        }

        @Test
        @DisplayName("is bounded at 1000 characters")
        void isBounded() {
            Draft draft = new Draft();
            draft.reason = "x".repeat(1001);
            assertInvalid(draft, "reason must be 1..1000 characters");
        }

        @Test
        @DisplayName("never holds a card-number shape, and is never echoed")
        void neverHoldsACardNumber() {
            Draft draft = new Draft();
            draft.reason = "seen on 4111111111111111 in the report";
            RuleSetProposal proposal = draft.build();
            assertThatThrownBy(proposal::validate)
                    .isInstanceOf(RuleSetAdministration.RuleSetInvalid.class)
                    .hasMessageContaining("card-number or bank-account shape")
                    .hasMessageNotContaining("4111");
        }
    }

    // ----------------------------------------------------------------- the dating magnitudes

    @Nested
    @DisplayName("the dating magnitudes are counted, never negative")
    class TheDatingMagnitudes {

        @Test
        void fundingLagDays() {
            Draft draft = new Draft();
            draft.fundingLagDays = -1;
            assertInvalid(draft, "funding_lag_days must not be negative");
        }

        @Test
        void gainMinAgeDays() {
            Draft draft = new Draft();
            draft.gainMinAgeDays = -1;
            assertInvalid(draft, "gain_min_age_days must not be negative");
        }

        @Test
        void aKindsLag() {
            Draft draft = new Draft();
            draft.lagDays = Map.of(ExpectationKind.CARD_CAPTURE, -1);
            assertInvalid(draft, "the lag for CARD_CAPTURE must not be negative");
        }
    }

    // ----------------------------------------------------------------- the rules

    @Nested
    @DisplayName("the rules")
    class TheRules {

        @Test
        @DisplayName("a version holds at least one")
        void atLeastOne() {
            Draft draft = new Draft();
            draft.rules = List.of();
            assertInvalid(draft, "at least one matching rule");
        }

        @Test
        @DisplayName("a priority is at least 1")
        void priorityPositive() {
            Draft draft = new Draft();
            draft.rules.set(0, rule(0, ExternalLineType.CAPTURE, "PSP_CAPTURE_REF",
                    ExpectationKind.CARD_CAPTURE, Cardinality.ONE_TO_ONE));
            assertInvalid(draft, "priority is at least 1");
        }

        @Test
        @DisplayName("a priority appears once")
        void priorityUnique() {
            Draft draft = new Draft();
            draft.rules.set(1, rule(1, ExternalLineType.REFUND, "PSP_REFUND_REF",
                    ExpectationKind.CARD_REFUND, Cardinality.ONE_TO_ONE));
            assertInvalid(draft, "priority appears once");
        }

        @Test
        @DisplayName("a key kind is the rule vocabulary's, and an unknown one is never echoed")
        void keyKindInVocabulary() {
            Draft draft = new Draft();
            draft.rules.set(0, rule(1, ExternalLineType.CAPTURE, "UNHEARD_OF_REF_KIND",
                    ExpectationKind.CARD_CAPTURE, Cardinality.ONE_TO_ONE));
            RuleSetProposal proposal = draft.build();
            assertThatThrownBy(proposal::validate)
                    .isInstanceOf(RuleSetAdministration.RuleSetInvalid.class)
                    .hasMessageContaining("outside the rule vocabulary")
                    .hasMessageNotContaining("UNHEARD_OF_REF_KIND");
        }

        @Test
        @DisplayName("a line type is one a rule may name (V010's rule_line_type)")
        void lineTypeInVocabulary() {
            Draft draft = new Draft();
            draft.rules.set(0, rule(1, ExternalLineType.OTHER_IN, null, null,
                    Cardinality.ONE_TO_ONE));
            assertInvalid(draft, "OTHER_IN is not a line type a matching rule may name");
        }

        @Test
        @DisplayName("a ONE_TO_ONE rule never keys on the line side's ORIGINAL_REF")
        void oneToOneNeverKeysOnOriginalRef() {
            Draft draft = new Draft();
            draft.rules.set(0, rule(1, ExternalLineType.CAPTURE, "ORIGINAL_REF",
                    ExpectationKind.CARD_CAPTURE, Cardinality.ONE_TO_ONE));
            assertInvalid(draft, "ORIGINAL_REF is read only by CHECK and CORRECTION rules");
        }

        @Test
        @DisplayName("an operation-anchored rule names the kind it reaches")
        void anchoredNamesItsKind() {
            Draft draft = new Draft();
            draft.rules.set(0, new RuleSetProposal.Rule(
                    1, ExternalLineType.CAPTURE, Optional.of("PSP_CAPTURE_REF"),
                    Optional.empty(), Cardinality.ONE_TO_ONE, true, 48));
            assertInvalid(draft, "names the expectation kind it reaches");
        }

        @Test
        @DisplayName("grace hours are counted, never negative")
        void graceCounted() {
            Draft draft = new Draft();
            draft.rules.set(0, new RuleSetProposal.Rule(
                    1, ExternalLineType.CAPTURE, Optional.of("PSP_CAPTURE_REF"),
                    Optional.of(ExpectationKind.CARD_CAPTURE), Cardinality.ONE_TO_ONE, false,
                    -1));
            assertInvalid(draft, "grace_hours must not be negative");
        }
    }

    // ----------------------------------------------------------------- the tolerances

    @Nested
    @DisplayName("the tolerances")
    class TheTolerances {

        @Test
        @DisplayName("a date window is days of at least zero, with no currency and no amount")
        void dateWindowShape() {
            Draft draft = new Draft();
            draft.tolerances = List.of(new RuleSetProposal.Tolerance(
                    "SETTLEMENT_DATE_DAYS", Optional.of(EUR), Optional.empty(),
                    Optional.of(2)));
            assertInvalid(draft, "a SETTLEMENT_DATE_DAYS tolerance is a count of days");
        }

        @Test
        @DisplayName("a negative date window is refused")
        void dateWindowCounted() {
            Draft draft = new Draft();
            draft.tolerances = List.of(dateWindow(-1));
            assertInvalid(draft, "a SETTLEMENT_DATE_DAYS tolerance is a count of days");
        }

        @Test
        @DisplayName("a fee bound is minor units of at least zero in a currency, with no days")
        void feeBoundShape() {
            Draft draft = new Draft();
            draft.tolerances = List.of(new RuleSetProposal.Tolerance(
                    "PROCESSING_FEE_PER_BATCH", Optional.of(EUR), Optional.of(50L),
                    Optional.of(1)));
            assertInvalid(draft, "a PROCESSING_FEE_PER_BATCH tolerance is a bound");
        }

        @Test
        @DisplayName("a negative fee bound is refused")
        void feeBoundCounted() {
            Draft draft = new Draft();
            draft.tolerances = List.of(feeBound("PROCESSING_FEE_PER_LINE", -1));
            assertInvalid(draft, "a PROCESSING_FEE_PER_LINE tolerance is a bound");
        }

        @Test
        @DisplayName("each (comparison, currency) appears once - two date windows included")
        void oncePerComparisonAndCurrency() {
            Draft draft = new Draft();
            draft.tolerances = List.of(dateWindow(2), dateWindow(3));
            assertInvalid(draft, "each SETTLEMENT_DATE_DAYS tolerance appears once");
        }
    }

    // ----------------------------------------------------------------- the fee schedules

    @Nested
    @DisplayName("the fee schedules")
    class TheFeeSchedules {

        @Test
        @DisplayName("only the four fee lines are priced")
        void onlyFeeLines() {
            Draft draft = new Draft();
            draft.fees = List.of(fee(ExternalLineType.CAPTURE, EUR, "0.015000", 25, 2));
            assertInvalid(draft, "CAPTURE carries no fee schedule");
        }

        @Test
        @DisplayName("a rate is never negative")
        void rateCounted() {
            Draft draft = new Draft();
            draft.fees = List.of(fee(ExternalLineType.PROCESSING_FEE, EUR, "-0.01", 25, 2));
            assertInvalid(draft, "rate must not be negative");
        }

        @Test
        @DisplayName("a rate carries at most six decimal places - never rounded silently")
        void rateScale() {
            Draft draft = new Draft();
            draft.fees = List.of(fee(ExternalLineType.PROCESSING_FEE, EUR, "0.0150001", 25, 2));
            assertInvalid(draft, "at most six decimal places");
        }

        @Test
        @DisplayName("a rate is below 10")
        void rateCeiling() {
            Draft draft = new Draft();
            draft.fees = List.of(fee(ExternalLineType.PROCESSING_FEE, EUR, "10", 25, 2));
            assertInvalid(draft, "rate is below 10");
        }

        @Test
        @DisplayName("a fixed part is never negative")
        void fixedCounted() {
            Draft draft = new Draft();
            draft.fees = List.of(fee(ExternalLineType.SCHEME_FEE, EUR, "0", -1, 2));
            assertInvalid(draft, "fixed part must not be negative");
        }

        @Test
        @DisplayName("a scale is 0..9")
        void scaleBounded() {
            Draft draft = new Draft();
            draft.fees = List.of(fee(ExternalLineType.BANK_FEE, EUR, "0", 50, 10));
            assertInvalid(draft, "scale is 0..9");
        }

        @Test
        @DisplayName("the rounding is the named HALF_UP")
        void roundingNamed() {
            Draft draft = new Draft();
            draft.fees = List.of(new RuleSetProposal.FeeTerms(
                    ExternalLineType.PAYOUT_FEE, EUR, BigDecimal.ZERO, 25, 2, "HALF_EVEN"));
            assertInvalid(draft, "names the HALF_UP rounding policy");
        }

        @Test
        @DisplayName("each (line type, currency) appears once")
        void oncePerLineAndCurrency() {
            Draft draft = new Draft();
            draft.fees = List.of(
                    fee(ExternalLineType.PROCESSING_FEE, EUR, "0.015000", 25, 2),
                    fee(ExternalLineType.PROCESSING_FEE, EUR, "0.020000", 25, 2));
            assertInvalid(draft, "each PROCESSING_FEE fee schedule appears once");
        }
    }

    // ----------------------------------------------------------------- the thresholds

    @Test
    @DisplayName("a high-value threshold is never negative")
    void thresholdCounted() {
        Draft draft = new Draft();
        draft.thresholds = Map.of(EUR, -1L);
        assertInvalid(draft, "the high-value threshold for EUR must not be negative");
    }
}
