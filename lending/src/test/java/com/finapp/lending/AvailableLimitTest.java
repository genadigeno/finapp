package com.finapp.lending;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The available limit's arithmetic (`P11-TSK-021`; ADR-0100 point 4, {@code INV-LND-14}): the limit less the drawn
 * principal, floored at zero, admitting a positive amount up to and including it - and nothing about where the drawn
 * principal comes from, which is the draw's lock and the ledger's lines ({@code CreditLineDrawDatabaseTest}).
 */
@DisplayName("the available limit (P11-TSK-021)")
class AvailableLimitTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");

    @Test
    @DisplayName("the limit less the drawn principal; zero - never negative - at or past the limit")
    void theLimitLessTheDrawnPrincipal() {
        assertThat(limit(1_000_00, 0).available()).isEqualTo(eur(1_000_00));
        assertThat(limit(1_000_00, 250_01).available()).isEqualTo(eur(749_99));
        assertThat(limit(1_000_00, 1_000_00).available()).isEqualTo(eur(0));
        assertThat(limit(1_000_00, 1_200_00).available()).as("a paydown reversed after a re-draw").isEqualTo(eur(0));
        assertThat(limit(1_000_00, -5_00).available()).as("a principal below zero - never expected - is still the formula's: a draw of it leaves the limit drawn")
                .isEqualTo(eur(1_005_00));
    }

    @Test
    @DisplayName("admits a positive amount up to and including the available limit, one minor unit more never")
    void admitsUpToTheAvailableLimit() {
        AvailableLimit limit = limit(1_000_00, 700_00);
        assertThat(limit.admits(eur(300_00))).isTrue();
        assertThat(limit.admits(eur(300_01))).isFalse();
        assertThat(limit.admits(eur(1))).isTrue();
        assertThat(limit.admits(eur(0))).isFalse();
        assertThat(limit.admits(eur(-1))).isFalse();
        assertThat(limit(1_000_00, 1_000_00).admits(eur(1))).isFalse();
        assertThat(limit(1_000_00, 1_500_00).admits(eur(1))).isFalse();
    }

    @Test
    @DisplayName("a limit is positive and the drawn principal in its currency and scale; neither figure is ever printed")
    void shapes() {
        assertThatIllegalArgumentException().isThrownBy(() -> limit(0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new AvailableLimit(eur(1_00), Money.ofPersisted(0, EUR, 3)));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new AvailableLimit(eur(1_00), Money.ofMinorUnits(0, CurrencyCode.of("USD"))));
        assertThat(limit(1_000_00, 1_00).toString()).isEqualTo("AvailableLimit[redacted]");
    }

    private static AvailableLimit limit(long limit, long drawn) {
        return new AvailableLimit(eur(limit), eur(drawn));
    }

    private static Money eur(long minor) {
        return Money.ofMinorUnits(minor, EUR);
    }
}
