package com.finapp.sharedkernel.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ExchangeRate - a directed price with no inversion and one rounding (P9-TSK-002)")
class ExchangeRateTest {

    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final CurrencyCode JPY = CurrencyCode.of("JPY");

    private static ExchangeRate rate(CurrencyCode s, CurrencyCode d, String value) {
        return ExchangeRate.of(s, d, new BigDecimal(value));
    }

    @Test
    @DisplayName("refuses a non-positive value, one currency on both sides, and anything a"
            + " NUMERIC(20,10) column would have to round - refused, never rounded")
    void refusesWhatAColumnWouldRound() {
        assertThatIllegalArgumentException().isThrownBy(() -> rate(EUR, USD, "0"));
        assertThatIllegalArgumentException().isThrownBy(() -> rate(EUR, USD, "-1.085024"));
        assertThatIllegalArgumentException().isThrownBy(() -> rate(EUR, EUR, "1"));
        assertThatIllegalArgumentException()
                .as("eleven decimals is the provider's defect")
                .isThrownBy(() -> rate(EUR, USD, "1.08502400001"));
        assertThatIllegalArgumentException()
                .as("twenty-one digits")
                .isThrownBy(() -> rate(EUR, USD, "12345678901.0000000001"));
        assertThat(rate(EUR, USD, "1234567890.0000000001").value().precision())
                .as("the widest rate the column holds is admitted").isEqualTo(20);
        assertThat(ExchangeRate.MAX_SCALE).isEqualTo(10);
        assertThat(ExchangeRate.MAX_PRECISION).isEqualTo(20);
    }

    @Test
    @DisplayName("equality is numeric and the hash agrees")
    void equalityIsNumeric() {
        assertThat(rate(EUR, USD, "1.085024")).isEqualTo(rate(EUR, USD, "1.0850240000"))
                .hasSameHashCodeAs(rate(EUR, USD, "1.0850240000"));
        assertThat(rate(EUR, USD, "1.085024")).isNotEqualTo(rate(USD, EUR, "1.085024"));
    }

    @Test
    @DisplayName("exactProduct rounds nothing; convert rounds once, under the named policy")
    void productAndConvert() {
        ExchangeRate rp = rate(EUR, USD, "1.085024");
        Money thousand = Money.of(new BigDecimal("1000.00"), EUR);
        assertThat(rp.exactProduct(thousand)).isEqualByComparingTo("1085.0240000");
        assertThat(rp.convert(thousand, RoundingPolicy.HALF_EVEN))
                .isEqualTo(Money.of(new BigDecimal("1085.02"), USD));
        assertThat(rate(EUR, USD, "1.079598").convert(thousand, RoundingPolicy.HALF_EVEN))
                .isEqualTo(Money.of(new BigDecimal("1079.60"), USD));
        assertThat(rate(USD, JPY, "149.124829")
                        .convert(Money.of(new BigDecimal("250.00"), USD), RoundingPolicy.HALF_EVEN))
                .as("into a zero-minor-unit currency")
                .isEqualTo(Money.of(new BigDecimal("37281"), JPY));
        assertThatExceptionOfType(CurrencyMismatchException.class)
                .isThrownBy(() -> rp.exactProduct(Money.of(BigDecimal.TEN, USD)));
    }

    @Test
    @DisplayName("sourceFor is ONE exactly-rounded division - never an inversion then a product")
    void sourceForIsOneDivision() {
        ExchangeRate rc = rate(EUR, USD, "1.079598");
        Money thousandUsd = Money.of(new BigDecimal("1000.00"), USD);
        assertThat(rc.sourceFor(thousandUsd, RoundingPolicy.HALF_EVEN))
                .as("1000 ÷ 1.079598 = 926.2707…")
                .isEqualTo(Money.of(new BigDecimal("926.27"), EUR));
        // The rounding is the division's, once: an inverted rate at ten decimals, then a product,
        // lands on a different minor unit for some amounts - the reason the type offers no
        // inversion at all.
        Money d = Money.of(new BigDecimal("4999.99"), USD);
        BigDecimal invertedFirst =
                BigDecimal.ONE.divide(rc.value(), 6, java.math.RoundingMode.HALF_EVEN)
                        .multiply(d.toBigDecimal())
                        .setScale(2, java.math.RoundingMode.HALF_EVEN);
        assertThat(invertedFirst).as("1 ÷ 1.079598 at six decimals, then the product")
                .isEqualByComparingTo("4631.35");
        assertThat(rc.sourceFor(d, RoundingPolicy.HALF_EVEN).toBigDecimal())
                .as("4999.99 ÷ 1.079598 = 4631.3445…, rounded once")
                .isEqualByComparingTo("4631.34");
        assertThatExceptionOfType(CurrencyMismatchException.class)
                .isThrownBy(() -> rc.sourceFor(Money.of(BigDecimal.TEN, EUR), RoundingPolicy.HALF_EVEN));
    }

    @Test
    @DisplayName("marginAgainst is the exact difference, same direction only")
    void marginAgainstSameDirection() {
        assertThat(rate(EUR, USD, "1.085024").marginAgainst(rate(EUR, USD, "1.079598")))
                .isEqualByComparingTo("0.005426");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> rate(EUR, USD, "1.085024").marginAgainst(rate(USD, EUR, "0.92")));
    }

    @Test
    @DisplayName("the type offers no inversion and no cross rate")
    void noInversionNoCross() {
        assertThat(Arrays.stream(ExchangeRate.class.getDeclaredMethods())
                        .map(Method::getName)
                        .map(name -> name.toLowerCase(Locale.ROOT)))
                .noneMatch(name -> name.contains("inver") || name.contains("recipro")
                        || name.contains("cross") || name.contains("flip"));
    }
}
