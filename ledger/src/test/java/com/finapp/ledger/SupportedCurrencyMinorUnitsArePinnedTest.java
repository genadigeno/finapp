package com.finapp.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The minor units the platform posts and prices in are pinned (`P9-TSK-002`, ADR-0074 §9;
 * {@code INV-MON-05}). The build's JDK must agree with the pin, every postable currency must be
 * pinned, and the pin itself is written down here a second time, literally, so editing the map is
 * a visible decision rather than a quiet one. The running instance's JDK is checked again at
 * startup by {@code SupportedCurrencyMinorUnitsGuard}.
 */
@DisplayName("the supported currencies' minor units are pinned (P9-TSK-002)")
class SupportedCurrencyMinorUnitsArePinnedTest {

    @Test
    @DisplayName("EUR 2, GBP 2, USD 2, JPY 0, BHD 3 - exactly")
    void thePinIsExactly() {
        Map<CurrencyCode, Integer> expected = new LinkedHashMap<>();
        expected.put(CurrencyCode.of("EUR"), 2);
        expected.put(CurrencyCode.of("GBP"), 2);
        expected.put(CurrencyCode.of("USD"), 2);
        expected.put(CurrencyCode.of("JPY"), 0);
        expected.put(CurrencyCode.of("BHD"), 3);
        assertThat(SupportedCurrencies.PINNED_MINOR_UNITS).containsExactlyEntriesOf(expected);
    }

    @Test
    @DisplayName("the build's JDK agrees with every pin")
    void theJdkAgrees() {
        SupportedCurrencies.PINNED_MINOR_UNITS.forEach((currency, minorUnits) ->
                assertThat(currency.minorUnits())
                        .as("%s's minor units on this JDK", currency)
                        .isEqualTo(minorUnits));
    }

    @Test
    @DisplayName("every postable currency is pinned")
    void everyPostableCurrencyIsPinned() {
        assertThat(SupportedCurrencies.PINNED_MINOR_UNITS.keySet())
                .containsAll(SupportedCurrencies.ALL);
    }
}
