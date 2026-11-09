package com.finapp.app.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("a migration version compares as a version, never as text (P15-TSK-005)")
class SchemaVersionTest {

    @ParameterizedTest(name = "{0} is below {1}")
    @CsvSource({"9,10", "009,10", "2,10", "1.9,1.10", "1.9,2", "1,1.1", "9.9,10.0", "99,100", "013,14"})
    void comparesAsAVersion(String lower, String higher) {
        assertThat(SchemaVersion.parse(lower).compareTo(SchemaVersion.parse(higher))).isNegative();
        assertThat(SchemaVersion.parse(higher).compareTo(SchemaVersion.parse(lower))).isPositive();
    }

    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource({"033,33", "1.0,1", "1.0.0,1", "001,1", "1_2,1.2", "0,0.0"})
    void theSameVersionWrittenTwoWaysIsOne(String one, String other) {
        assertThat(SchemaVersion.parse(one)).isEqualByComparingTo(SchemaVersion.parse(other));
        assertThat(SchemaVersion.parse(one)).isEqualTo(SchemaVersion.parse(other));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "1.", ".1", "1..2", "1.a", "-1", " 1"})
    @DisplayName("what is not a version is refused, never guessed at")
    void anythingElseIsRefused(String text) {
        assertThatIllegalArgumentException().isThrownBy(() -> SchemaVersion.parse(text));
    }

    @Test
    @DisplayName("null is not a version")
    void nullIsRefused() {
        assertThatIllegalArgumentException().isThrownBy(() -> SchemaVersion.parse(null));
    }

    @Test
    @DisplayName("a version beyond a long still compares")
    void aVersionBeyondALongCompares() {
        assertThat(SchemaVersion.parse("20261109101530123456789").compareTo(SchemaVersion.parse("20261109101530123456788"))).isPositive();
    }
}
