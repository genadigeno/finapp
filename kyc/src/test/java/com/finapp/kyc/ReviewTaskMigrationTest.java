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
    @DisplayName("V005's grants were SELECT and INSERT only - the UPDATE arrived with V006")
    void grantsArriveWithTheCapability() {
        // V005 is applied history (ADR-0011): its original grant keeps its shape, and the
        // resolution UPDATE arrives in the migration that creates the columns it writes.
        assertThat(migration())
                .contains("GRANT SELECT, INSERT ON kyc.review_task TO finapp_app")
                .doesNotContain("UPDATE ON kyc.review_task")
                .doesNotContain("DELETE ON kyc.review_task");
    }

    @Test
    @DisplayName("every FK stays in-schema, and V005 carries no resolution column (history)")
    void boundaryAndDeferralHold() {
        assertThat(migration())
                .contains("REFERENCES kyc.kyc_case (id)")
                .contains("REFERENCES kyc.verification_check (id)")
                .doesNotContain("REFERENCES party.")
                .doesNotContain("REFERENCES identity.");
        // V005 deferred the columns to the capability that populates them; V006 is that
        // arrival, and this pins the history's shape (the RoleAssignmentMigrationTest lesson:
        // an applied migration cannot be edited, so the deferral stays visible in V005).
        assertThat(migration())
                .doesNotContain("resolved_by")
                .doesNotContain("resolution_reason");
    }

    // -----------------------------------------------------------------
    // V006 - the resolution (P2-TSK-012)

    @Test
    @DisplayName("V006: a resolved task without its who/when/why is unrepresentable")
    void resolutionIsCoherent() {
        assertThat(resolutionMigration())
                .contains("(status = 'RESOLVED') = (resolved_by IS NOT NULL)")
                .contains("(status = 'RESOLVED') = (resolved_at IS NOT NULL)")
                .contains("(status = 'RESOLVED') = (resolution_reason IS NOT NULL)");
    }

    @Test
    @DisplayName("V006: the reason bound is the audit record's, so the last write cannot refuse")
    void reasonBoundMatchesTheAuditRecord() {
        // Three copies of one number - the request boundary, this CHECK, and
        // AuditRecord.MAX_REASON_LENGTH - each held to the record's by a test, because a sink
        // narrower than the boundary turns a caller's accepted reason into a 500 at the last
        // write (the SuspensionRequest reasoning).
        assertThat(resolutionMigration())
                .contains(
                        "BETWEEN 1 AND "
                                + com.finapp.platform.audit.AuditRecord.MAX_REASON_LENGTH);
    }

    @Test
    @DisplayName("V006: RESOLVED rows freeze whole, and the UPDATE grant names its four columns")
    void resolutionIsFinal() {
        String migration = resolutionMigration();
        assertThat(migration)
                .contains("CREATE TRIGGER review_task_resolution_is_final")
                .contains("BEFORE UPDATE ON kyc.review_task")
                // The one-way transition, in the trigger's own words: terminal stays terminal
                // at the schema level, whatever any future grant permits (INV-LIFE-04).
                .contains("OLD.status <> 'OPEN' OR NEW.status <> 'RESOLVED'");
        assertThat(migration)
                .contains(
                        "GRANT UPDATE (status, resolved_by, resolved_at, resolution_reason)")
                .as("column-level to NARROW (the V004 precedent): identity columns stay"
                        + " unwritable by the application role")
                .doesNotContain("GRANT UPDATE ON kyc.review_task");
    }

    @Test
    @DisplayName("V006: no ordering constraint between two app-clock reads, deliberately")
    void noFragileOrderingConstraint() {
        // resolved_at and opened_at are written by different transactions from a clock that is
        // corrected backwards (P1-TSK-031); correctness never depends on their ordering, so a
        // constraint would buy flaky refusals and no property. Asserted over the STATEMENTS,
        // never the whole file: the migration's own header comment names the rejected
        // constraint, and a doesNotContain over source text matches prose (the P1-TSK-021
        // string-literals-only lesson, met here by this test's first version).
        String statements =
                resolutionMigration()
                        .lines()
                        .filter(line -> !line.stripLeading().startsWith("--"))
                        .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(statements).doesNotContain("resolved_at >= opened_at");
        // The vacuity control a negative assertion needs: the stripped text still holds the
        // statements, so the doesNotContain is running over something.
        assertThat(statements).contains("ALTER TABLE kyc.review_task");
    }

    @Test
    @DisplayName("the guard can actually read both migrations")
    void theGuardIsNotVacuous() {
        assertThat(migration()).contains("CREATE TABLE kyc.review_task");
        assertThat(resolutionMigration()).contains("ALTER TABLE kyc.review_task");
    }

    /** From the classpath, the sibling migration tests' idiom. */
    private static String migration() {
        return read(MIGRATION);
    }

    private static String resolutionMigration() {
        return read("db/migration/kyc/V006__add_review_task_resolution.sql");
    }

    private static String read(String resource) {
        try (InputStream migration =
                ReviewTaskMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + resource);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
