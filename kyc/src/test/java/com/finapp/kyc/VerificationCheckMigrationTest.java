package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The check schema and the code that writes it are one definition (`P2-TSK-009`) — the
 * {@code KycCaseMigrationTest} idiom, with the one-in-flight index predicate as the sharper of
 * the generated artefacts: a status added without a decision about whether it holds the
 * in-flight slot either lets two identical questions fly at once or blocks a type's retry
 * forever, both silent without this reconciliation.
 */
@DisplayName("the verification-check schema and the code agree (P2-TSK-009)")
class VerificationCheckMigrationTest {

    private static final String MIGRATION = "db/migration/kyc/V004__create_verification_check.sql";

    @Test
    @DisplayName("the type and status constraints list exactly what the enums declare")
    void constraintsMatchTheEnums() {
        assertThat(migration())
                .contains("CHECK (check_type IN (" + CheckType.sqlValueList() + "))")
                .contains("CHECK (status IN (" + CheckStatus.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the one-in-flight predicate is exactly the machine's terminal set")
    void inFlightPredicateMatchesTheTerminalSet() {
        assertThat(migration())
                .as("'terminal' and 'frees the in-flight slot' must be one definition")
                .contains("WHERE status NOT IN (" + CheckStatus.sqlTerminalValueList() + ")");
    }

    @Test
    @DisplayName("evidence carries the kyc_document at-rest constraints and its bound")
    void evidenceConstraintsMatchTheTypes() {
        assertThat(migration())
                .contains("octet_length(content_nonce) = " + DocumentCipher.NONCE_BYTES)
                .contains("octet_length(checksum_sha256) = " + KycDocument.CHECKSUM_BYTES)
                .contains("BETWEEN 1 AND " + DocumentBytes.MAX_BYTES)
                .contains(
                        "octet_length(content_ciphertext) = content_length + "
                                + DocumentCipher.TAG_BYTES);
    }

    @Test
    @DisplayName("checks are updatable, evidence is append-only, and every FK stays in-schema")
    void privilegesAndBoundaryHold() {
        assertThat(migration())
                .contains("GRANT SELECT, INSERT, UPDATE ON kyc.verification_check TO finapp_app")
                .doesNotContain("DELETE ON kyc.verification_check");
        assertThat(migration())
                .as("INV-HIST-02: the application role may never edit or delete evidence")
                .contains("GRANT SELECT, INSERT ON kyc.verification_evidence TO finapp_app")
                .doesNotContain("UPDATE ON kyc.verification_evidence")
                .doesNotContain("DELETE ON kyc.verification_evidence");
        assertThat(migration())
                .contains("REFERENCES kyc.kyc_case (id)")
                .contains("REFERENCES kyc.verification_check (id)")
                .doesNotContain("REFERENCES party.")
                .doesNotContain("REFERENCES identity.");
    }

    @Test
    @DisplayName("the guard can actually read the migration")
    void theGuardIsNotVacuous() {
        assertThat(migration()).contains("CREATE TABLE kyc.verification_check");
    }

    /** From the classpath, the sibling migration tests' idiom. */
    private static String migration() {
        try (InputStream migration =
                VerificationCheckMigrationTest.class
                        .getClassLoader()
                        .getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
