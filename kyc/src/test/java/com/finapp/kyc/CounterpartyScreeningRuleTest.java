package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.kyc.CounterpartyScreeningVocabulary.Decision;
import com.finapp.kyc.CounterpartyScreeningVocabulary.PayeeVerdict;
import com.finapp.kyc.CounterpartyScreeningVocabulary.ReasonCode;
import com.finapp.kyc.CounterpartyScreeningVocabulary.ReviewReason;
import com.finapp.kyc.CounterpartyScreeningVocabulary.Verdict;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * kyc's rule over a provider's verdict and the payee check (`P9-TSK-016`; {@code INV-KYC-01},
 * {@code INV-KYC-04} extended): only a provider {@code CLEAR} beside a payee {@code MATCH} clears; a hit
 * or an indeterminate answer never auto-clears and never auto-rejects; an unverified payee is handled
 * as a hit; and the machine has exactly ADR-0081's edges.
 */
@DisplayName("the counterparty screening rule and machine (P9-TSK-016)")
class CounterpartyScreeningRuleTest {

    @Test
    @DisplayName("a provider CLEAR beside a payee MATCH is the only automatic clearance")
    void onlyClearWithAMatchClears() {
        assertThat(CounterpartyScreenings.route(Verdict.CLEAR, PayeeVerdict.MATCH))
                .isEqualTo(new CounterpartyScreenings.Routed(CounterpartyScreeningStatus.CLEAR, Optional.empty()));
        for (Verdict verdict : Verdict.values()) {
            for (PayeeVerdict payee : PayeeVerdict.values()) {
                boolean clears = CounterpartyScreenings.route(verdict, payee).status().clears();
                assertThat(clears).as(verdict + " beside " + payee)
                        .isEqualTo(verdict == Verdict.CLEAR && payee == PayeeVerdict.MATCH);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = PayeeVerdict.class, names = {"NO_MATCH", "UNAVAILABLE"})
    @DisplayName("a provider CLEAR beside an unverified payee goes IN_REVIEW, PAYEE_UNVERIFIED")
    void anUnverifiedPayeeMeetsAPerson(PayeeVerdict payee) {
        assertThat(CounterpartyScreenings.route(Verdict.CLEAR, payee)).isEqualTo(new CounterpartyScreenings.Routed(
                CounterpartyScreeningStatus.IN_REVIEW, Optional.of(ReviewReason.PAYEE_UNVERIFIED)));
    }

    @ParameterizedTest
    @EnumSource(PayeeVerdict.class)
    @DisplayName("a hit or an indeterminate answer goes IN_REVIEW - never CLEAR, never BLOCKED")
    void aHitMeetsAPerson(PayeeVerdict payee) {
        assertThat(CounterpartyScreenings.route(Verdict.HIT, payee).status()).isEqualTo(CounterpartyScreeningStatus.IN_REVIEW);
        assertThat(CounterpartyScreenings.route(Verdict.HIT, payee).reviewReason()).contains(ReviewReason.HIT);
        assertThat(CounterpartyScreenings.route(Verdict.INDETERMINATE, payee).reviewReason()).contains(ReviewReason.INDETERMINATE);
        for (Verdict verdict : Verdict.values()) {
            assertThat(CounterpartyScreenings.route(verdict, payee).status())
                    .as("the machine never decides a person's outcome")
                    .isNotIn(CounterpartyScreeningStatus.RELEASED, CounterpartyScreeningStatus.BLOCKED);
        }
    }

    @Test
    @DisplayName("an unavailable provider decides nothing but the retry")
    void unavailableIsRetried() {
        assertThat(CounterpartyScreenings.route(Verdict.UNAVAILABLE, PayeeVerdict.MATCH).status())
                .isEqualTo(CounterpartyScreeningStatus.UNAVAILABLE);
        assertThat(CounterpartyScreenings.backoff(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(CounterpartyScreenings.backoff(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(CounterpartyScreenings.backoff(30)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    @DisplayName("the machine has exactly ADR-0081's edges")
    void theMachine() {
        assertThat(CounterpartyScreeningStatus.REQUESTED.canMoveTo(CounterpartyScreeningStatus.CLEAR)).isTrue();
        assertThat(CounterpartyScreeningStatus.UNAVAILABLE.canMoveTo(CounterpartyScreeningStatus.IN_REVIEW)).isTrue();
        assertThat(CounterpartyScreeningStatus.IN_REVIEW.canMoveTo(CounterpartyScreeningStatus.RELEASED)).isTrue();
        assertThat(CounterpartyScreeningStatus.IN_REVIEW.canMoveTo(CounterpartyScreeningStatus.CLEAR)).isFalse();
        assertThat(CounterpartyScreeningStatus.REQUESTED.canMoveTo(CounterpartyScreeningStatus.RELEASED)).isFalse();
        assertThat(CounterpartyScreeningStatus.REQUESTED.canMoveTo(CounterpartyScreeningStatus.BLOCKED)).isFalse();
        for (CounterpartyScreeningStatus terminal : new CounterpartyScreeningStatus[] {
                CounterpartyScreeningStatus.CLEAR, CounterpartyScreeningStatus.RELEASED, CounterpartyScreeningStatus.BLOCKED}) {
            for (CounterpartyScreeningStatus target : CounterpartyScreeningStatus.values()) {
                assertThat(terminal.canMoveTo(target)).as(terminal + " -> " + target).isFalse();
            }
        }
    }

    @Test
    @DisplayName("a reason code justifies only its own decision")
    void reasonCodesJustifyTheirDecision() {
        assertThat(ReasonCode.FALSE_POSITIVE.justifies(Decision.RELEASE)).isTrue();
        assertThat(ReasonCode.PAYEE_CONFIRMED.justifies(Decision.RELEASE)).isTrue();
        assertThat(ReasonCode.TRUE_MATCH.justifies(Decision.RELEASE)).isFalse();
        assertThat(ReasonCode.INSUFFICIENT_INFORMATION.justifies(Decision.BLOCK)).isTrue();
        assertThat(ReasonCode.FALSE_POSITIVE.justifies(Decision.BLOCK)).isFalse();
    }
}
