package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The decision schema and the code that writes it are one definition (`P2-TSK-013`) — the
 * {@code ReviewTaskMigrationTest} idiom. The sharpest artefacts here are the <strong>total</strong>
 * one-per-case index (a partial one would re-admit a second decision for a decided case, and
 * "may this party transact?" must have one answer forever — {@code INV-KYC-03}'s ambiguity, at
 * the record that matters most) and the grants being {@code SELECT, INSERT} and nothing else —
 * append-only at {@code DB-PRIVILEGE} is {@code INV-KYC-02}'s own enforcement rank.
 */
@DisplayName("the decision schema and the code agree (P2-TSK-013)")
class DecisionMigrationTest {

    private static final String MIGRATION = "db/migration/kyc/V007__create_kyc_decision.sql";

    @Test
    @DisplayName("the outcome and basis constraints list exactly what the enums declare")
    void constraintsMatchTheEnums() {
        assertThat(statements())
                .contains("CHECK (outcome IN (" + DecisionOutcome.sqlValueList() + "))")
                .contains("CHECK (decision_basis IN (" + DecisionBasis.sqlValueList() + "))");
    }

    @Test
    @DisplayName("one decision per case is TOTAL - a decided case never admits a second")
    void onePerCaseIsTotal() {
        String migration = migration();
        int index = migration.indexOf("CREATE UNIQUE INDEX kyc_decision_one_per_case");
        assertThat(index).as("the defence-in-depth index must exist").isNotNegative();

        String statement = migration.substring(index, migration.indexOf(';', index));
        assertThat(statement)
                .contains("ON kyc.kyc_decision (case_id)")
                .as("a WHERE would make it partial - changed circumstances are a NEW case"
                        + " (INV-LIFE-04), never a second decision on the old one")
                .doesNotContain("WHERE");
    }

    @Test
    @DisplayName("both tables grant SELECT and INSERT and nothing else")
    void appendOnlyAtThePrivilegeLevel() {
        String statements = statements();
        assertThat(statements)
                .contains("GRANT SELECT, INSERT ON kyc.kyc_decision TO finapp_app")
                .contains("GRANT SELECT, INSERT ON kyc.kyc_decision_check TO finapp_app");
        // No UPDATE grant at all is why - unlike review_task - no freeze trigger is needed:
        // the privilege IS the immutability (the audit_record model), and the behavioural
        // proof against a live database is the acceptance suite's.
        assertThat(statements)
                .doesNotContain("UPDATE ON kyc.kyc_decision")
                .doesNotContain("DELETE ON kyc.kyc_decision")
                .doesNotContain("TRIGGER");
    }

    @Test
    @DisplayName("the actor coherence CHECK mirrors the domain's constructor invariant")
    void actorCoherenceIsInTheSchema() {
        assertThat(statements())
                .contains("CHECK ((decision_basis = 'REVIEWER') = (decided_by IS NOT NULL))");
    }

    @Test
    @DisplayName("the reason bound is the audit record's, so the last write cannot refuse")
    void reasonBoundMatchesTheAuditRecord() {
        assertThat(statements())
                .contains(
                        "BETWEEN 1 AND " + com.finapp.platform.audit.AuditRecord.MAX_REASON_LENGTH);
    }

    @Test
    @DisplayName("every FK stays in-schema")
    void boundaryHolds() {
        String statements = statements();
        assertThat(statements)
                .contains("REFERENCES kyc.kyc_case (id)")
                .contains("REFERENCES kyc.verification_check (id)")
                .contains("REFERENCES kyc.kyc_decision (id)")
                .doesNotContain("REFERENCES party.")
                .doesNotContain("REFERENCES identity.");
    }

    @Test
    @DisplayName("the guard can actually read the migration")
    void theGuardIsNotVacuous() {
        assertThat(statements())
                .contains("CREATE TABLE kyc.kyc_decision")
                .contains("CREATE TABLE kyc.kyc_decision_check");
    }

    // -----------------------------------------------------------------

    /**
     * The migration's statements, comments stripped — the `P2-TSK-012` lesson learned in this
     * suite's sibling: a {@code doesNotContain} over the whole file matches prose, and this
     * migration's own header narrates the constraints it deliberately lacks.
     */
    private static String statements() {
        return migration()
                .lines()
                .filter(line -> !line.stripLeading().startsWith("--"))
                .collect(Collectors.joining("\n"));
    }

    private static String migration() {
        try (InputStream migration =
                DecisionMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException(
                        "Migration not on the test classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
