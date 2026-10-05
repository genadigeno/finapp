package com.finapp.crossborder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.money.CountryCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import com.finapp.sharedkernel.money.RoundingPolicy;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The corridor's value types and the proposal's judgements, without a database (`P9-TSK-015`,
 * ADR-0080 section 4; {@code INV-MON-01}, {@code INV-MON-03}): the fee is in S at S's scale, the
 * maximum in D at D's scale, the margin a bounded fraction, the durations whole hours, the key stable;
 * and a proposal names only rails the build declares covering its destination, and only data the
 * platform holds.
 */
@DisplayName("corridor terms and the proposal's judgements (P9-TSK-015)")
class CorridorTermsTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final CorridorKey EUR_USD_US = new CorridorKey(EUR, USD, CountryCode.of("US"));

    @Test
    @DisplayName("the key is S-D-CC, parsed back exactly; a corridor converts; a malformed code is refused")
    void theKey() {
        assertThat(EUR_USD_US.code()).isEqualTo("EUR-USD-US");
        assertThat(CorridorKey.parse("EUR-USD-US")).isEqualTo(EUR_USD_US);
        assertThatThrownBy(() -> new CorridorKey(EUR, EUR, CountryCode.of("DE")))
                .hasMessageContaining("converts");
        for (String malformed : List.of("EUR-USD", "eur-usd-us", "EUR-USD-USA", "EUR_USD_US", "EUR-USD-XX")) {
            assertThatThrownBy(() -> CorridorKey.parse(malformed)).as(malformed).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("O7's terms construct; a fee in D, a fee at the wrong scale, a negative fee, a margin of one or"
            + " seven decimals, a maximum in S or of zero, and a non-whole-hour duration are each refused")
    void theTerms() {
        assertThatCode(CorridorPolicyFixtures::eurUsdUs).doesNotThrowAnyException();
        record Bend(String what, Money fee, BigDecimal margin, Money maximum, Duration validity) {}
        Money fee = Money.of(new BigDecimal("2.50"), EUR);
        Money maximum = Money.of(new BigDecimal("10000.00"), USD);
        for (Bend bend : List.of(
                new Bend("a fee in D", Money.of(new BigDecimal("2.50"), USD), BigDecimal.ZERO, maximum, Duration.ofDays(7)),
                new Bend("a negative fee", Money.of(new BigDecimal("-2.50"), EUR), BigDecimal.ZERO, maximum, Duration.ofDays(7)),
                new Bend("a margin of one", fee, BigDecimal.ONE, maximum, Duration.ofDays(7)),
                new Bend("a margin of seven decimals", fee, new BigDecimal("0.0000001"), maximum, Duration.ofDays(7)),
                new Bend("a negative margin", fee, new BigDecimal("-0.001"), maximum, Duration.ofDays(7)),
                new Bend("a maximum in S", fee, BigDecimal.ZERO, Money.of(new BigDecimal("10000.00"), EUR), Duration.ofDays(7)),
                new Bend("a zero maximum", fee, BigDecimal.ZERO, Money.of(new BigDecimal("0.00"), USD), Duration.ofDays(7)),
                new Bend("a validity of 90 minutes", fee, BigDecimal.ZERO, maximum, Duration.ofMinutes(90)),
                new Bend("a validity past 720 hours", fee, BigDecimal.ZERO, maximum, Duration.ofHours(721)))) {
            assertThatThrownBy(() -> new CorridorTerms(
                            EUR_USD_US, List.of("corridor-sim-a"), bend.fee(), bend.margin(), RoundingPolicy.HALF_EVEN,
                            bend.maximum(), bend.validity(), Duration.ofDays(1), Set.of()))
                    .as(bend.what())
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new CorridorTerms(EUR_USD_US, List.of("corridor-sim-a", "corridor-sim-a"), fee,
                        BigDecimal.ZERO, RoundingPolicy.HALF_EVEN, maximum, Duration.ofDays(7), Duration.ofDays(1), Set.of()))
                .as("a rail named twice")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(CorridorPolicyFixtures.eurUsdUs().feeMargin())
                .as("canonical at the column's scale, so terms read back equal the terms proposed")
                .isEqualTo(new BigDecimal("0.000000"));
    }

    @Test
    @DisplayName("a proposal names only declared rails covering (country, D), only held data, each corridor once,"
            + " and a screened reason")
    void theProposalIsJudged() {
        CorridorDirectory directory = CorridorPolicyFixtures.DIRECTORY;
        assertThatCode(() -> CorridorPolicyFixtures.proposal("O7's first corridor").validate(directory))
                .doesNotThrowAnyException();
        CorridorTerms eurUsdUs = CorridorPolicyFixtures.eurUsdUs();
        assertThatThrownBy(() -> new CorridorPolicyProposal(List.of(new CorridorTerms(eurUsdUs.key(),
                                List.of("corridor-sim-b"), eurUsdUs.feeFixed(), eurUsdUs.feeMargin(), eurUsdUs.feeRounding(),
                                eurUsdUs.maximum(), eurUsdUs.screeningValidity(), eurUsdUs.deliveryEstimate(),
                                eurUsdUs.requiredData())), "an undeclared rail").validate(directory))
                .isInstanceOf(CorridorPolicyAdministration.RailNotDeclared.class);
        assertThatThrownBy(() -> new CorridorPolicyProposal(
                        List.of(CorridorPolicyFixtures.terms("EUR", "USD", "JP", "2.50", "10000.00")), "not covered")
                        .validate(directory))
                .isInstanceOf(CorridorPolicyAdministration.RailNotDeclared.class);
        assertThatThrownBy(() -> new CorridorPolicyProposal(List.of(new CorridorTerms(eurUsdUs.key(), eurUsdUs.rails(),
                                eurUsdUs.feeFixed(), eurUsdUs.feeMargin(), eurUsdUs.feeRounding(), eurUsdUs.maximum(),
                                eurUsdUs.screeningValidity(), eurUsdUs.deliveryEstimate(),
                                Set.of(RequiredData.PAYMENT_PURPOSE))), "a purpose code").validate(directory))
                .isInstanceOf(CorridorPolicyAdministration.RequiredDataUnsatisfiable.class);
        assertThatThrownBy(() -> new CorridorPolicyProposal(List.of(eurUsdUs, eurUsdUs), "twice").validate(directory))
                .isInstanceOf(CorridorPolicyAdministration.CorridorPolicyInvalid.class);
        assertThatThrownBy(() -> new CorridorPolicyProposal(List.of(), "empty").validate(directory))
                .isInstanceOf(CorridorPolicyAdministration.CorridorPolicyInvalid.class);
        assertThatThrownBy(() -> CorridorPolicyFixtures.proposal("card 4111 1111 1111 1111").validate(directory))
                .isInstanceOf(CorridorPolicyAdministration.CorridorPolicyInvalid.class);
        assertThat(RequiredData.HELD).containsExactlyInAnyOrder(RequiredData.BENEFICIARY_NAME, RequiredData.ENTITY_TYPE);
    }

    @Test
    @DisplayName("the version machine's edges are the lifecycle document's")
    void theMachine() {
        assertThat(CorridorPolicyStatus.PROPOSED.canMoveTo(CorridorPolicyStatus.ACTIVE)).isTrue();
        assertThat(CorridorPolicyStatus.PROPOSED.canMoveTo(CorridorPolicyStatus.REJECTED)).isTrue();
        assertThat(CorridorPolicyStatus.ACTIVE.canMoveTo(CorridorPolicyStatus.RETIRED)).isTrue();
        assertThat(CorridorPolicyStatus.ACTIVE.canMoveTo(CorridorPolicyStatus.REJECTED)).isFalse();
        assertThat(CorridorPolicyStatus.PROPOSED.canMoveTo(CorridorPolicyStatus.RETIRED)).isFalse();
        assertThat(CorridorPolicyStatus.RETIRED.canMoveTo(CorridorPolicyStatus.ACTIVE)).isFalse();
        assertThat(CorridorPolicyStatus.REJECTED.canMoveTo(CorridorPolicyStatus.ACTIVE)).isFalse();
    }
}
