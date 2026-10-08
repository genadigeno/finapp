package com.finapp.credit;

import static com.finapp.credit.AffordabilityTest.ABSENT;
import static com.finapp.credit.AffordabilityTest.loan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The scorecard's arithmetic and its table's shape (`P10-TSK-011`; {@code INV-CRD-05}): bands are {@code [lower,
 * upper)}, an absent value takes its declared band, code sets are disjoint, the sum is an integer - and a table that
 * could fail to score a value of its vocabulary is refused whole.
 */
@DisplayName("the scorecard's bands and arithmetic (P10-TSK-011)")
class ScorecardTest {

    private static final Scorecard V1 = RetailScorecardV1.scorecard();

    @Test
    @DisplayName("the golden snapshot scores 570: 500 + 40 + 30 - 20 + 10 + 10")
    void integerSums() {
        assertThat(V1.score(loan(1_000_000, 36, Map.of()))).isEqualTo(570);
    }

    @Test
    @DisplayName("a range holds its lower edge and not its upper: 549 / 550 / 649 / 650 / 749 / 750")
    void bandEdgesAreHalfOpen() {
        assertThat(scoreOf(549)).isEqualTo(530 - 60);
        assertThat(scoreOf(550)).isEqualTo(530);
        assertThat(scoreOf(649)).isEqualTo(530);
        assertThat(scoreOf(650)).isEqualTo(530 + 40);
        assertThat(scoreOf(749)).isEqualTo(530 + 40);
        assertThat(scoreOf(750)).isEqualTo(530 + 80);
        assertThat(scoreOf(Long.MIN_VALUE)).as("unbounded below").isEqualTo(530 - 60);
        assertThat(scoreOf(Long.MAX_VALUE)).as("unbounded above").isEqualTo(530 + 80);
    }

    @Test
    @DisplayName("an absent value takes its declared absent band - never a zero")
    void absentTakesItsBand() {
        assertThat(V1.score(loan(1_000_000, 36, Map.of(CreditAttributeCode.BUREAU_EXTERNAL_SCORE, ABSENT))))
                .isEqualTo(530 - 40);
        assertThat(V1.score(loan(1_000_000, 36, Map.of(CreditAttributeCode.BUREAU_DEFAULTS_72M,
                        new AttributeValue.IntegerValue(0)))))
                .as("the golden snapshot's absent defaults scored -20; present and zero scores +20").isEqualTo(610);
    }

    @Test
    @DisplayName("code sets: a boolean's true and false, a code's set - and a code no set holds is an error")
    void codeSets() {
        assertThat(V1.score(loan(1_000_000, 36, Map.of(CreditAttributeCode.BUREAU_INSOLVENCY_FLAG,
                new AttributeValue.BooleanValue(true))))).isEqualTo(570 - 10 - 200);
        Scorecard signal = new Scorecard(0, List.of(new Scorecard.AttributeBands(CreditAttributeCode.RISK_SIGNAL, -5,
                List.of(new Scorecard.Codes(Set.of("LOW"), 10), new Scorecard.Codes(Set.of("HIGH"), -10)))));
        assertThat(signal.score(loan(1_000_000, 36, Map.of(CreditAttributeCode.RISK_SIGNAL, new AttributeValue.CodeValue("LOW")))))
                .isEqualTo(10);
        assertThatExceptionOfType(Scorecard.UnscoredValue.class)
                .as("the golden snapshot's NOT_ASSESSED is in no set")
                .isThrownBy(() -> signal.score(loan(1_000_000, 36, Map.of())))
                .withMessageNotContaining("NOT_ASSESSED");
    }

    @Test
    @DisplayName("ranges that leave a gap, overlap, start bounded or end bounded are refused")
    void rangesAreContiguousAndCovering() {
        invalid(CreditAttributeCode.BUREAU_EXTERNAL_SCORE, List.of(new Scorecard.Range(null, 500L, 1), new Scorecard.Range(550L, null, 2)));
        invalid(CreditAttributeCode.BUREAU_EXTERNAL_SCORE, List.of(new Scorecard.Range(null, 600L, 1), new Scorecard.Range(550L, null, 2)));
        invalid(CreditAttributeCode.BUREAU_EXTERNAL_SCORE, List.of(new Scorecard.Range(0L, 550L, 1), new Scorecard.Range(550L, null, 2)));
        invalid(CreditAttributeCode.BUREAU_EXTERNAL_SCORE, List.of(new Scorecard.Range(null, 550L, 1), new Scorecard.Range(550L, 900L, 2)));
        invalid(CreditAttributeCode.BUREAU_EXTERNAL_SCORE, List.of(new Scorecard.Codes(Set.of("A"), 1)));
    }

    @Test
    @DisplayName("money, markers, overlapping sets, a half-covered boolean and a code banded twice are refused")
    void theVocabularyIsRespected() {
        invalid(CreditAttributeCode.DECLARED_MONTHLY_INCOME, List.of(new Scorecard.Range(null, null, 1)));
        invalid(CreditAttributeCode.SOURCE_UNAVAILABLE, List.of(new Scorecard.Codes(Set.of("BUREAU"), 1)));
        invalid(CreditAttributeCode.RISK_SIGNAL, List.of(new Scorecard.Codes(Set.of("LOW", "HIGH"), 1), new Scorecard.Codes(Set.of("HIGH"), 2)));
        invalid(CreditAttributeCode.RISK_SIGNAL, List.of(new Scorecard.Codes(Set.of("low"), 1)));
        invalid(CreditAttributeCode.BUREAU_INSOLVENCY_FLAG, List.of(new Scorecard.Codes(Set.of("true"), 1)));
        Scorecard.AttributeBands once = new Scorecard.AttributeBands(CreditAttributeCode.BUREAU_DEFAULTS_72M, 0,
                List.of(new Scorecard.Range(null, null, 1)));
        assertThatExceptionOfType(Scorecard.ScorecardInvalid.class).isThrownBy(() -> new Scorecard(0, List.of(once, once)));
        assertThatExceptionOfType(Scorecard.ScorecardInvalid.class).isThrownBy(() -> new Scorecard(0, List.of()));
        assertThatExceptionOfType(Scorecard.ScorecardInvalid.class).isThrownBy(() -> new Scorecard(100_001, List.of(once)));
    }

    private static int scoreOf(long external) {
        return V1.score(loan(1_000_000, 36, Map.of(CreditAttributeCode.BUREAU_EXTERNAL_SCORE, new AttributeValue.IntegerValue(external))));
    }

    private static void invalid(CreditAttributeCode code, List<Scorecard.Band> bands) {
        assertThatExceptionOfType(Scorecard.ScorecardInvalid.class)
                .as(code + " " + bands)
                .isThrownBy(() -> new Scorecard.AttributeBands(code, 0, bands));
    }
}
