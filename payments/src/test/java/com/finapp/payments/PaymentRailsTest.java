package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rails directory (`P7-TSK-001`, ADR-0059): the stored rail's key back to declared
 * capabilities, with both faults loud — a duplicate declaration and a stored rail no build
 * declares are wiring faults, never domain refusals.
 */
@DisplayName("PaymentRails (P7-TSK-001)")
class PaymentRailsTest {

    @Test
    @DisplayName("a declared rail's capabilities come back; an undeclared one is a named"
            + " wiring fault")
    void looksUpTheDeclaration() {
        PaymentRails rails = PaymentRails.of(List.of(SimulatedCardPspAdapter.RAIL));

        assertThat(rails.capabilitiesOf(SimulatedCardPspAdapter.RAIL.id()))
                .isSameAs(SimulatedCardPspAdapter.RAIL.capabilities());

        // The failure an outcome transaction must hit BEFORE a line posts to a guessed
        // clearing account: a build deployed against rows whose rail it no longer declares.
        assertThatThrownBy(() -> rails.capabilitiesOf(RailId.of("sepa-instant")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sepa-instant")
                .hasMessageContaining("wiring fault");
    }

    @Test
    @DisplayName("two declarations of one name are refused at construction")
    void refusesADuplicateDeclaration() {
        assertThatThrownBy(() ->
                        PaymentRails.of(List.of(
                                SimulatedCardPspAdapter.RAIL,
                                new PaymentRail(
                                        RailId.of("card"),
                                        SimulatedCardPspAdapter.RAIL.capabilities()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("card");
        assertThatThrownBy(() -> PaymentRails.of(null))
                .isInstanceOf(NullPointerException.class);
    }
}
