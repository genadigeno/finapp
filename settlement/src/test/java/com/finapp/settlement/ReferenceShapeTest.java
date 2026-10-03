package com.finapp.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link ReferenceShape} (the Phase 8 → 9 transition, SEC-02): what a stored counterparty
 * reference must look like to be served back verbatim — the formats' opaque reference alphabet
 * with no instrument shape anywhere in it.
 */
@DisplayName("the shape a stored reference must have to be served (SEC-02)")
class ReferenceShapeTest {

    @ParameterizedTest(name = "a reference: {0}")
    @ValueSource(strings = {
        "PSPB-2026-09-25-01", "SCHEME-CYCLE-2026-09-29.1", "po_batch:42", "STMT-0001",
    })
    void referencesAreServed(String value) {
        assertThat(ReferenceShape.isReference(value)).isTrue();
    }

    @ParameterizedTest(name = "not a reference: {0}")
    @ValueSource(strings = {
        // A card number however written, and any digit run of card length.
        "4111-1111-1111-1111", "PAN4111111111111111", "PSPB-20260925000017",
        // An account identifier, contiguous or printed with dashes.
        "DE89370400440532013000", "GB82-WEST-1234-5698-7654-32",
        // NEW-SEC-2: the machine separators ':' and '_' group a digit run as a dash does.
        "4111:1111:1111:1111", "4111_1111_1111_1111", "PSPB_20260925000017",
        // Not the alphabet, or past the bound.
        "two words", "",
    })
    void anythingElseIsWithheld(String value) {
        assertThat(ReferenceShape.isReference(value))
                .as("withheld from the read, never served verbatim")
                .isFalse();
    }

    @Test
    @DisplayName("the 100-character bound, and null")
    void theBoundAndNull() {
        assertThat(ReferenceShape.isReference("R".repeat(100))).isTrue();
        assertThat(ReferenceShape.isReference("R".repeat(101))).isFalse();
        assertThat(ReferenceShape.isReference(null)).isFalse();
    }
}
