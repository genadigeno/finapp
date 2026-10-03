package com.finapp.platform.money;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.sharedkernel.money.ExchangeRate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RateColumns - one column type, generated from the type's bounds (P9-TSK-002)")
class RateColumnsTest {

    @Test
    @DisplayName("the DDL is NUMERIC(MAX_PRECISION, MAX_SCALE) - NUMERIC(20,10)")
    void ddlIsGeneratedFromTheConstants() {
        assertThat(RateColumns.ddl())
                .isEqualTo("NUMERIC(" + ExchangeRate.MAX_PRECISION + "," + ExchangeRate.MAX_SCALE + ")")
                .isEqualTo("NUMERIC(20,10)");
    }
}
