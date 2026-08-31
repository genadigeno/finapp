package com.finapp.sharedkernel.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class CurrencyCodeTest {

    @ParameterizedTest(name = "{0} has {1} minor units")
    @CsvSource({"USD, 2", "EUR, 2", "GBP, 2", "JPY, 0", "UGX, 0", "BHD, 3", "KWD, 3", "CLF, 4"})
    @DisplayName("reports the minor units of real currencies, including 0-, 3- and 4-decimal ones")
    void reportsMinorUnits(String code, int expectedMinorUnits) {
        assertThat(CurrencyCode.of(code).minorUnits()).isEqualTo(expectedMinorUnits);
    }

    @ParameterizedTest
    @ValueSource(strings = {"XXX", "XAU", "XDR"})
    @DisplayName("rejects codes that define no minor unit")
    void rejectsCurrenciesWithoutMinorUnits(String code) {
        // These are ISO 4217 codes but not currencies an exact amount can be denominated in.
        // Treating them as 0-decimal would make one gram of gold equal one thousandth of one.
        assertThatThrownBy(() -> CurrencyCode.of(code))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minor unit");
    }

    @ParameterizedTest
    @ValueSource(strings = {"usd", "Usd", "uSD"})
    @DisplayName("rejects lower-case rather than silently normalising it")
    void rejectsNonCanonicalCase(String code) {
        assertThatThrownBy(() -> CurrencyCode.of(code))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("three uppercase letters");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "US", "USDD", "US1", "US ", "12A"})
    @DisplayName("rejects anything that is not three uppercase letters")
    void rejectsMalformedCodes(String code) {
        assertThatThrownBy(() -> CurrencyCode.of(code)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("rejects a well-formed code that is not a known currency")
    void rejectsUnknownCurrency() {
        assertThatThrownBy(() -> CurrencyCode.of("ZZZ"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO 4217");
    }

    @Test
    @DisplayName("rejects null")
    void rejectsNull() {
        assertThatThrownBy(() -> CurrencyCode.of(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("is a value: equal codes are equal and hash alike")
    void hasValueSemantics() {
        assertThat(CurrencyCode.of("USD")).isEqualTo(CurrencyCode.of("USD"));
        assertThat(CurrencyCode.of("USD")).hasSameHashCodeAs(CurrencyCode.of("USD"));
        assertThat(CurrencyCode.of("USD")).isNotEqualTo(CurrencyCode.of("EUR"));
    }

    @Test
    @DisplayName("renders as the bare code")
    void rendersAsCode() {
        assertThat(CurrencyCode.of("USD")).hasToString("USD");
        assertThat(CurrencyCode.of("USD").code()).isEqualTo("USD");
    }
}
