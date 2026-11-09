package com.finapp.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.security.KeyRing;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The retirement rule (`P15-TSK-019`, ADR-0123 point 4): a version is retired only when no row stands under it. Hermetic over
 * a planted census; {@code KeyRingRetirementGuardDatabaseTest} runs the real census over the real tables.
 */
@DisplayName("a retired key version with rows under it refuses the replica (P15-TSK-019)")
class KeyRingRetirementGuardTest {

    private static byte[] key(int fill) {
        byte[] key = new byte[KeyRing.KEY_BYTES];
        Arrays.fill(key, (byte) fill);
        return key;
    }

    private static KeyRing retiring(int retiredVersion) {
        return KeyRing.of("test key", Map.of(2, key(2)), 2, Set.of(retiredVersion));
    }

    private static KeyRing unretired() {
        return KeyRing.of("test key", Map.of(1, key(1)), 1, Set.of());
    }

    private final List<String> asked = new ArrayList<>();

    private KeyRingRetirementGuard.RowCensus censusOf(boolean rowsRemain) {
        return (column, versions) -> {
            asked.add(column + versions);
            return rowsRemain;
        };
    }

    @Test
    @DisplayName("with nothing retired, no table is read at all: the check costs nothing until a retirement is declared")
    void withNothingRetiredNothingIsRead() {
        KeyRingRetirementGuard.verify(Map.of("documentKeyRing", unretired()), censusOf(true));
        assertThat(asked).isEmpty();
    }

    @Test
    @DisplayName("a retired version with rows under it refuses to start, naming the column")
    void aRetiredVersionWithRowsRefuses() {
        assertThatThrownBy(() -> KeyRingRetirementGuard.verify(Map.of("fxEvidenceKeyRing", retiring(1)), censusOf(true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fx.fx_provider_evidence.key_version")
                .hasMessageContaining("[1]");
    }

    @Test
    @DisplayName("a retired version with no rows under it is admitted, and every stamped column of the ring is asked about")
    void aRetiredVersionWithNoRowsIsAdmitted() {
        assertThatCode(() -> KeyRingRetirementGuard.verify(Map.of("documentKeyRing", retiring(1)), censusOf(false)))
                .doesNotThrowAnyException();
        assertThat(asked).containsExactly(
                "kyc.kyc_document.key_version[1]",
                "kyc.verification_evidence.key_version[1]",
                "kyc.counterparty_screening.subject_key_version[1]",
                "kyc.counterparty_screening_attempt.evidence_key_version[1]");
    }

    @Test
    @DisplayName("a census that fails refuses to start: a retirement nobody could verify is not admitted")
    void aFailedCensusRefuses() {
        KeyRingRetirementGuard.RowCensus failing = (column, versions) -> {
            throw new SQLException("the database is down", "08006");
        };
        assertThatThrownBy(() -> KeyRingRetirementGuard.verify(Map.of("fxEvidenceKeyRing", retiring(1)), failing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("could not be established")
                .hasMessageContaining("08006")
                .hasMessageNotContaining("the database is down");
    }

    @Test
    @DisplayName("a ring with no declared columns refuses a retirement rather than treating absence as proof")
    void aRingWithUnknownColumnsRefuses() {
        assertThatThrownBy(() -> KeyRingRetirementGuard.verify(Map.of("aRingNobodyDeclared", retiring(1)), censusOf(false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("columns are not known");
    }

    @Test
    @DisplayName("credit's evidence key can never be retired from here: the application's role cannot read that version column, so it is refused without being asked")
    void creditsRingRefusesEveryRetirement() {
        assertThatThrownBy(() -> KeyRingRetirementGuard.verify(Map.of("creditEvidenceKeyRing", retiring(1)), censusOf(false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credit.credit_evidence.key_version")
                .hasMessageContaining("cannot be read");
        assertThat(asked).as("the census is not asked about a column it could not read").isEmpty();
    }
}
