package com.finapp.app.money;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.finapp.sharedkernel.money.CurrencyCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("the startup guard refuses a JDK that moved a pinned currency's minor units"
        + " (P9-TSK-002)")
class SupportedCurrencyMinorUnitsGuardTest {

    @Test
    @DisplayName("the running JDK agrees, so the instance starts")
    void theRealJdkPasses() {
        assertThatCode(() -> SupportedCurrencyMinorUnitsGuard.verify(CurrencyCode::minorUnits))
                .doesNotThrowAnyException();
        assertThatCode(SupportedCurrencyMinorUnitsGuard::new).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a planted drift - a JDK that gave the yen two decimals - refuses startup, naming"
            + " the currency")
    void aPlantedDriftRefuses() {
        assertThatIllegalStateException()
                .isThrownBy(() -> SupportedCurrencyMinorUnitsGuard.verify(
                        currency -> currency.code().equals("JPY") ? 2 : currency.minorUnits()))
                .withMessageContaining("JPY is pinned to 0 minor units, the running JDK says 2")
                .withMessageContaining("INV-MON-05");
    }
}
