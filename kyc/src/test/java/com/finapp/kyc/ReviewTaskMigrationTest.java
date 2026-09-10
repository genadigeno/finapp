package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The review-task schema and the code that writes it are one definition (`P2-TSK-010`) — the
 * {@code VerificationCheckMigrationTest} idiom, with the <strong>totality</strong> of the
 * one-per-check index as the sharper artefact: partial would quietly re-admit a second task for
 * a resolved check, and "what did the reviewer decide about this question?" must have at most
 * one answer for a decision to reference ({@code INV-KYC-02}).
 */
@DisplayName("the review-task schema and the code agree (P2-TSK-010)")
class ReviewTaskMigrationTest {

    private static final String MIGRATION = "db/migration/kyc/V005__create_review_task.sql";

    @Test
    @DisplayName("the status constraint lists exactly what the enum declares")
    void constraintMatchesTheEnum() {
        assertThat(migration())
                .contains("CHECK (status IN (" + ReviewTaskStatus.sqlValueList() + "))");
    }

    @Test
    @DisplayName("one task per check is TOTAL - resolution never frees the slot")
    void onePerCheckIsTotal() {
        String migration = migration();
        int index = migration.indexOf("CREATE UNIQUE INDEX review_task_one_per_check");
        assertThat(index).as("the arbiter index must exist").isNotNegative();

        String statement = migration.substring(index, migration.indexOf(';', index));
        assertThat(statement)
                .contains("ON kyc.review_task (check_id)")
                .as("a WHERE would make it partial, and a resolved check could grow a second"
                        + " task - changed circumstances are a NEW check, never a second task on"
                        + " the old one")
                .doesNotContain("WHERE");
    }

    @Test
    @DisplayName("the grants are SELECT and INSERT only - the resolution UPDATE is P2-TSK-012's")
    void grantsArriveWithTheCapability() {
        assertThat(migration())
                .contains("GRANT SELECT, INSERT ON kyc.review_task TO finapp_app")
                .doesNotContain("UPDATE ON kyc.review_task")
                .doesNotContain("DELETE ON kyc.review_task");
    }

    @Test
    @DisplayName("every FK stays in-schema, and no resolution column exists yet")
    void boundaryAndDeferralHold() {
        assertThat(migration())
                .contains("REFERENCES kyc.kyc_case (id)")
                .contains("REFERENCES kyc.verification_check (id)")
                .doesNotContain("REFERENCES party.")
                .doesNotContain("REFERENCES identity.");
        // Columns nothing populates are the schema drift this repository refuses (P0-TSK-017's
        // precedent); the resolution's shape is P2-TSK-012's design to take.
        assertThat(migration())
                .doesNotContain("resolved_by")
                .doesNotContain("resolution_reason");
    }

    @Test
    @DisplayName("the guard can actually read the migration")
    void theGuardIsNotVacuous() {
        assertThat(migration()).contains("CREATE TABLE kyc.review_task");
    }

    /** From the classpath, the sibling migration tests' idiom. */
    private static String migration() {
        try (InputStream migration =
                ReviewTaskMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
