package com.finapp.sharedkernel.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CountryCode - ISO 3166-1 alpha-2, no coercion (P9-TSK-002)")
class CountryCodeTest {

    @Test
    @DisplayName("admits real alpha-2 codes and refuses lower case, alpha-3 and invented codes")
    void onlyRealUppercaseAlpha2() {
        assertThat(CountryCode.of("DE").code()).isEqualTo("DE");
        assertThat(CountryCode.of("JP")).isEqualTo(new CountryCode("JP"));
        assertThatIllegalArgumentException().isThrownBy(() -> CountryCode.of("de"));
        assertThatIllegalArgumentException().isThrownBy(() -> CountryCode.of("DEU"));
        assertThatIllegalArgumentException().isThrownBy(() -> CountryCode.of("QQ"));
        assertThatIllegalArgumentException().isThrownBy(() -> CountryCode.of(""));
        assertThatNullPointerException().isThrownBy(() -> CountryCode.of(null));
    }
}
