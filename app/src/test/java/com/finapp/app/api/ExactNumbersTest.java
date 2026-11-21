package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.json.JsonMapper;

/** A request number given as a JSON number or as a decimal string, exactly either way (`P17-TSK-127`). */
@DisplayName("an exact request number arrives as a JSON number or a decimal string, and is never rounded (P17-TSK-127)")
class ExactNumbersTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The same field as Jackson binds it by default - the baseline the binding departs from. */
    record Plain(Long fixedAmountMinor) {}

    record Body(
            @JsonDeserialize(using = ExactNumbers.Decimal.class) BigDecimal rate,
            @JsonDeserialize(using = ExactNumbers.MinorUnits.class) Long fixedAmountMinor) {}

    @Test
    @DisplayName("a number and its decimal string bind to the same exact value")
    void bothFormsBindExactly() {
        Body number = JSON.readValue("{\"rate\":0.029,\"fixedAmountMinor\":250}", Body.class);
        Body text = JSON.readValue("{\"rate\":\"0.029\",\"fixedAmountMinor\":\"250\"}", Body.class);
        assertThat(number.rate()).isEqualTo(new BigDecimal("0.029"));
        assertThat(text.rate()).isEqualTo(new BigDecimal("0.029"));
        assertThat(number.fixedAmountMinor()).isEqualTo(250L);
        assertThat(text.fixedAmountMinor()).isEqualTo(250L);
        assertThat(JSON.readValue("{\"rate\":\"0.123456789\"}", Body.class).rate()).isEqualTo(new BigDecimal("0.123456789"));
        assertThat(JSON.readValue("{\"fixedAmountMinor\":1500.0}", Body.class).fixedAmountMinor()).isEqualTo(1500L);
        assertThat(JSON.readValue("{\"rate\":null,\"fixedAmountMinor\":null}", Body.class))
                .isEqualTo(new Body(null, null));
    }

    @Test
    @DisplayName("why the minor units are bound at all: by default a fraction of a minor unit is silently truncated")
    void theDefaultTruncates() {
        assertThat(JSON.readValue("{\"fixedAmountMinor\":1.5}", Plain.class).fixedAmountMinor()).isEqualTo(1L);
        assertThatThrownBy(() -> JSON.readValue("{\"fixedAmountMinor\":1.5}", Body.class)).isInstanceOf(DatabindException.class);
    }

    @Test
    @DisplayName("a string outside the shape, a fraction of a minor unit and any other JSON type are malformed input")
    void anythingElseIsRefused() {
        for (String refused : List.of("{\"rate\":\"1E+400000000\"}", "{\"rate\":\"-0.01\"}", "{\"rate\":\"0,029\"}",
                "{\"rate\":\"\"}", "{\"rate\":true}", "{\"rate\":{}}", "{\"fixedAmountMinor\":2.5}",
                "{\"fixedAmountMinor\":\"2.5\"}", "{\"fixedAmountMinor\":\"-1\"}", "{\"fixedAmountMinor\":\"1234567890123456789\"}",
                "{\"fixedAmountMinor\":1E+30}", "{\"fixedAmountMinor\":[]}")) {
            assertThatThrownBy(() -> JSON.readValue(refused, Body.class)).as(refused).isInstanceOf(DatabindException.class);
        }
    }
}
