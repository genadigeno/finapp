package com.finapp.app.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.fx.FxEvidenceCipher;
import com.finapp.fx.FxProviderEvidenceStore;
import com.finapp.fx.JdbcFxProviderEvidenceStore;
import com.finapp.platform.security.KeyRing;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The retirement census over the real tables, as the application's own role (`P15-TSK-019`): every listed column is readable
 * by it, credit's stated exception really is refused, and a real row under a version is really found. Owed to the phase's one
 * database pass; written and walked here against the DDL.
 */
@Tag("database")
@DisplayName("the retirement census reads the real tables, as the application's own role (P15-TSK-019)")
class KeyRingRetirementGuardDatabaseTest {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";
    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    private final KeyRingRetirementGuard.JdbcRowCensus census =
            new KeyRingRetirementGuard.JdbcRowCensus(DatabaseRoles::application);

    private static byte[] key(int fill) {
        byte[] key = new byte[KeyRing.KEY_BYTES];
        Arrays.fill(key, (byte) fill);
        return key;
    }

    @Test
    @DisplayName("every listed column is read by the application role, a version nothing is written under answering no rows, and credit's excepted column is refused")
    void everyListedColumnIsReadableAndCreditsIsNot() throws SQLException {
        for (String column : KeyRingRetirementGuard.STAMPED_COLUMNS.values().stream().flatMap(java.util.List::stream).toList()) {
            if (KeyRingRetirementGuard.NOT_READABLE_BY_THE_APPLICATION.contains(column)) {
                assertThatThrownBy(() -> census.anyRowUnder(column, Set.of(2_147_000_000)))
                        .as("the guard's exception must really be a column the role cannot read: %s", column)
                        .isInstanceOf(SQLException.class)
                        .matches(e -> INSUFFICIENT_PRIVILEGE.equals(((SQLException) e).getSQLState()));
                continue;
            }
            assertThat(census.anyRowUnder(column, Set.of(2_147_000_000))).as("no row stands under a version never written: %s", column).isFalse();
        }
    }

    @Test
    @DisplayName("a real row under a version is found through the real column, and the guard refuses the retirement it would break")
    void aRealRowUnderAVersionIsFound() throws SQLException {
        int version = 2_146_999_001;
        KeyRing writer = KeyRing.single("fx evidence key", key(3), version);
        FxProviderEvidenceStore<Connection> store = new JdbcFxProviderEvidenceStore(new FxEvidenceCipher(writer, new SecureRandom()), IDS);
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            store.append(app, "fx-sim-a", "retirement-census-" + IDS.next(), FxProviderEvidenceStore.Kind.REQUEST,
                    "evidence".getBytes(StandardCharsets.UTF_8), Instant.parse("2026-11-09T10:00:00Z"));
            app.commit();
        }

        assertThat(census.anyRowUnder("fx.fx_provider_evidence.key_version", Set.of(version))).isTrue();
        assertThat(census.anyRowUnder("fx.fx_provider_evidence.key_version", Set.of(version + 1))).isFalse();

        KeyRing retiring = KeyRing.of("fx evidence key", Map.of(version + 1, key(4)), version + 1, Set.of(version));
        assertThatThrownBy(() -> KeyRingRetirementGuard.verify(Map.of("fxEvidenceKeyRing", retiring), census))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fx.fx_provider_evidence.key_version");
    }
}
