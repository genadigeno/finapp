package com.finapp.bnpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * bnpl's view of the capital headroom (`P12-TSK-005`; ADR-0104 decisions 1 and 3; {@code INV-BNPL-10}): the gross
 * admitted up to the last unit and refused one past it, a headroom below zero admitting nothing, a judgement only in the
 * headroom's own currency and only of a positive principal, and no amount in its string form ({@code INV-AUD-02}). The
 * port's adapter - the lock, lending's figure - is {@code app}'s {@code LendingBnplCapitalTest}.
 */
@DisplayName("bnpl's capital headroom: the gross admitted to the last unit, never across currencies (P12-TSK-005)")
class BnplHeadroomTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");

    @Test
    @DisplayName("a purchase's gross is admitted up to the headroom exactly and refused one minor unit past it")
    void theGrossIsAdmittedToTheLastUnit() {
        BnplHeadroom headroom = new BnplHeadroom(EUR, Money.ofMinorUnits(500_00, EUR));
        assertThat(headroom.admits(Money.ofMinorUnits(500_00, EUR))).isTrue();
        assertThat(headroom.admits(Money.ofMinorUnits(500_01, EUR))).isFalse();
        assertThat(headroom.admits(Money.ofMinorUnits(1, EUR))).isTrue();
    }

    @Test
    @DisplayName("no headroom, or headroom below zero after a withdrawn recognition, admits nothing")
    void noHeadroomAdmitsNothing() {
        assertThat(new BnplHeadroom(EUR, Money.zero(EUR)).admits(Money.ofMinorUnits(1, EUR))).isFalse();
        assertThat(new BnplHeadroom(EUR, Money.ofMinorUnits(-10_00, EUR)).admits(Money.ofMinorUnits(1, EUR))).isFalse();
    }

    @Test
    @DisplayName("a headroom is its currency's; a principal of another currency, or not positive, is refused")
    void oneCurrencyAndAPositivePrincipal() {
        assertThatThrownBy(() -> new BnplHeadroom(EUR, Money.ofMinorUnits(1_00, GBP)))
                .isInstanceOf(IllegalArgumentException.class);
        BnplHeadroom headroom = new BnplHeadroom(EUR, Money.ofMinorUnits(500_00, EUR));
        assertThatThrownBy(() -> headroom.admits(Money.ofMinorUnits(1_00, GBP)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> headroom.admits(Money.zero(EUR))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> headroom.admits(Money.ofMinorUnits(-1, EUR))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("its string form names the currency and never the amount")
    void noAmountInItsStringForm() {
        assertThat(new BnplHeadroom(EUR, Money.ofMinorUnits(123_45, EUR)).toString())
                .isEqualTo("BnplHeadroom[EUR]")
                .doesNotContain("123");
    }
}
