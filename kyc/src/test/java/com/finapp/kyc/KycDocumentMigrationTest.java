package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The document schema and the code that writes it are one definition (`P2-TSK-008`) — the
 * {@code KycCaseMigrationTest} idiom: every value the migration hard-codes is generated from, or
 * reconciled against, the type that owns it, so a constant moved in code without its constraint
 * fails the build rather than failing at the last write.
 */
@DisplayName("the document schema and the code agree (P2-TSK-008)")
class KycDocumentMigrationTest {

    private static final String MIGRATION = "db/migration/kyc/V003__create_kyc_document.sql";

    @Test
    @DisplayName("the type constraints list exactly what the enums declare")
    void typeConstraintsMatchTheEnums() {
        assertThat(migration())
                .contains("CHECK (document_type IN (" + DocumentType.sqlValueList() + "))")
                .contains("CHECK (content_type IN (" + DocumentContentType.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the size bound is DocumentBytes' bound, and the tag arithmetic is the cipher's")
    void boundsMatchTheTypes() {
        assertThat(migration())
                .contains("BETWEEN 1 AND " + DocumentBytes.MAX_BYTES)
                .contains("octet_length(content_nonce) = " + DocumentCipher.NONCE_BYTES)
                .contains("octet_length(checksum_sha256) = " + KycDocument.CHECKSUM_BYTES)
                .contains(
                        "octet_length(content_ciphertext) = content_length + "
                                + DocumentCipher.TAG_BYTES);
    }

    @Test
    @DisplayName("evidence is append-only at the privilege level, and the FK stays in-schema")
    void privilegesAndBoundaryHold() {
        assertThat(migration())
                .as("INV-HIST-02 / INV-KYC-06: the application role may never edit or delete"
                        + " evidence")
                .contains("GRANT SELECT, INSERT ON kyc.kyc_document TO finapp_app")
                .doesNotContain("UPDATE ON kyc.kyc_document")
                .doesNotContain("DELETE ON kyc.kyc_document");
        assertThat(migration())
                .as("the case FK is within this module's own schema, which ADR-0029 permits;"
                        + " a cross-schema reference is what it forbids")
                .contains("REFERENCES kyc.kyc_case (id)")
                .doesNotContain("REFERENCES party.")
                .doesNotContain("REFERENCES identity.");
    }

    @Test
    @DisplayName("the convergence index exists on (case_id, checksum_sha256)")
    void theConvergenceIndexExists() {
        assertThat(migration())
                .as("content-addressed convergence is the idempotency mechanism; without the"
                        + " unique index a retry appends a second copy of the same evidence")
                .contains("CREATE UNIQUE INDEX kyc_document_one_per_case_and_checksum")
                .contains("ON kyc.kyc_document (case_id, checksum_sha256)");
    }

    @Test
    @DisplayName("the guard can actually read the migration")
    void theGuardIsNotVacuous() {
        assertThat(migration()).contains("CREATE TABLE kyc.kyc_document");
    }

    /** From the classpath, the sibling migration tests' idiom. */
    private static String migration() {
        try (InputStream migration =
                KycDocumentMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
