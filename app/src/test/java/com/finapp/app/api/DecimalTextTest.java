package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The request decimal's shape, judged before it is ever a {@link BigDecimal} (the Phase 9 to 10 transition gate). */
@DisplayName("a request decimal is plain before it is parsed (the Phase 9 to 10 transition)")
class DecimalTextTest {

    @Test
    @DisplayName("plain decimals parse exactly; exponents, signs, grouping and overlong digits are refused within 200 ms")
    void theShapeDecides() {
        for (String plain : List.of("0", "100.00", "0.003500", "7500000", "20000.000", "999999999999999.999999999")) {
            assertThat(DecimalText.parse(plain)).isEqualByComparingTo(new BigDecimal(plain));
        }
        for (String refused : List.of("1E+400000000", "1E-400000000", "1e5", "-1.00", "+1.00", "1,000.00", " 1", "1.",
                ".5", "1.0000000001", "1234567890123456", "NaN", "", "0x10")) {
            assertTimeoutPreemptively(Duration.ofMillis(200), () -> {
                assertThat(DecimalText.plain(refused)).as(refused).isFalse();
                assertThatThrownBy(() -> DecimalText.parse(refused)).isInstanceOf(NumberFormatException.class);
            });
        }
        assertThat(DecimalText.plain(null)).isFalse();
    }
}
