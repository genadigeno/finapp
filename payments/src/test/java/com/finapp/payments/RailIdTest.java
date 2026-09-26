package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rail id's shape (`P7-TSK-001`, ADR-0059): a bounded lowercase token, because the value
 * becomes a constrained column, a bounded meter tag and an audit-summary fragment.
 */
@DisplayName("RailId (P7-TSK-001)")
class RailIdTest {

    @Test
    @DisplayName("the declared shapes are accepted, with value semantics")
    void acceptsTheDeclaredShapes() {
        assertThat(RailId.of("card").value()).isEqualTo("card");
        assertThat(RailId.of("sepa-instant")).isEqualTo(new RailId("sepa-instant"));
        assertThat(RailId.of("a2345678901234567890123456789012").value()).hasSize(32);
    }

    @Test
    @DisplayName("null, empty, case, leading digits and dashes, foreign characters and"
            + " over-length are refused without echoing the value")
    void refusesEverythingElse() {
        assertThatThrownBy(() -> RailId.of(null)).isInstanceOf(NullPointerException.class);
        for (String bad : new String[] {
                "", "Card", "CARD", "1card", "-card", "card_rail", "card rail",
                "a23456789012345678901234567890123"}) {
            assertThatThrownBy(() -> RailId.of(bad))
                    .as("'%s' must be refused", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("[a-z0-9-]");
        }
        assertThatThrownBy(() -> RailId.of("Card"))
                .as("the refusal never echoes the offered value")
                .hasMessageNotContaining("Card");
    }
}
