package com.finapp.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * `V003` and the code cannot drift (`P6-TSK-002`, the {@code P0-TSK-022} pattern): the status
 * {@code CHECK}s from {@link MerchantApiKeyStatus#sqlValueList()}, the live-key index
 * predicate from {@code sqlTerminalValueList()}, the trigger's one edge from
 * {@code permittedTransitions()} — and the claim the whole credential design rests on: that
 * <strong>no column here could hold a secret</strong>.
 */
@DisplayName("merchant api key migration reconciliation (P6-TSK-002)")
class MerchantApiKeyMigrationTest {

    private static final String MIGRATION =
            "db/migration/merchant/V003__create_merchant_api_key.sql";

    @Test
    @DisplayName("the status CHECKs are generated from the machine, on all three columns")
    void statusChecksMatchTheEnum() {
        String list = MerchantApiKeyStatus.sqlValueList();
        assertThat(migration())
                .contains("CHECK (status IN (" + list + "))")
                .contains("CHECK (from_status IN (" + list + "))")
                .contains("CHECK (to_status IN (" + list + "))");
    }

    @Test
    @DisplayName("the live-key index predicate is generated from the terminal list")
    void liveKeyPredicateMatchesTheTerminals() {
        assertThat(migration())
                .contains("CREATE INDEX merchant_api_key_live_by_merchant")
                .contains(
                        "WHERE status NOT IN ("
                                + MerchantApiKeyStatus.sqlTerminalValueList()
                                + ")");
    }

    @Test
    @DisplayName("the trigger's one edge is generated from the machine")
    void triggerEdgeMatchesTheMachine() {
        for (MerchantApiKeyStatus from : MerchantApiKeyStatus.values()) {
            if (from.isTerminal()) {
                assertThat(migration())
                        .as("a terminal state has no trigger edge (%s)", from)
                        .doesNotContain("OLD.status = '" + from.name() + "' AND NEW.status");
                continue;
            }
            String targets =
                    from.permittedTransitions().stream()
                            .map(to -> "'" + to.name() + "'")
                            .collect(Collectors.joining(", "));
            assertThat(migration())
                    .as("the trigger's %s edge set is the machine's", from)
                    .contains(
                            "(OLD.status = '" + from.name() + "' AND NEW.status IN (" + targets
                                    + "))");
        }
    }

    @Test
    @DisplayName("no column could hold a secret, and the hash's shape is constrained")
    void noColumnCouldHoldASecret() {
        // The register-level half of INV-IDN-01; the live-schema sweep in the database suite
        // holds the same claim against information_schema. Comments stripped: the migration's
        // own prose discusses the secret at length and must be allowed to.
        String definitionsOnly =
                migration()
                        .lines()
                        .map(line -> line.replaceFirst("--.*$", ""))
                        .collect(Collectors.joining("\n"));
        assertThat(definitionsOnly.toLowerCase())
                .doesNotContain("secret text")
                .doesNotContain("plaintext")
                .doesNotContain("api_key_value");
        assertThat(definitionsOnly)
                .as("the hash is length- and alphabet-bounded, so a plaintext secret would"
                        + " not fit quietly")
                .contains("CHECK (secret_hash ~ '^[A-Za-z0-9+/]{43}=$')");
    }

    @Test
    @DisplayName("one hash across the platform, and the grants are narrowed")
    void theUniquenessAndTheGrantsHold() {
        assertThat(migration())
                .contains("CREATE UNIQUE INDEX merchant_api_key_one_row_per_hash")
                .contains("GRANT SELECT, INSERT ON merchant.merchant_api_key TO finapp_app")
                .contains(
                        "GRANT UPDATE (status, revoked_at) ON merchant.merchant_api_key"
                                + " TO finapp_app")
                .contains(
                        "GRANT SELECT, INSERT ON merchant.merchant_api_key_event TO finapp_app")
                .doesNotContain("DELETE ON merchant.merchant_api_key");
    }

    @Test
    @DisplayName("the revocation reason is NOT NULL - the history cannot record an unreasoned act")
    void theReasonIsRequired() {
        assertThat(migration())
                .as("INV-AUD-03: revoking a counterparty's access is a security judgement")
                .contains("reason      text        NOT NULL");
    }

    @Test
    @DisplayName("the guard can actually read the migration")
    void theGuardIsNotVacuous() {
        assertThat(migration()).contains("CREATE TABLE merchant.merchant_api_key");
    }

    private static String migration() {
        try (InputStream migration =
                MerchantApiKeyMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
