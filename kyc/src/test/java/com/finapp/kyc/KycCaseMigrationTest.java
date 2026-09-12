package com.finapp.kyc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link KycCaseStatus} and the schema artefacts generated from it are one definition
 * (`P2-TSK-005`) — the {@code RoleAssignmentMigrationTest} idiom, with <strong>two</strong>
 * generated lists rather than one: the status {@code CHECK} from {@code sqlValueList()} and the
 * one-open-case index predicate from {@code sqlTerminalValueList()}.
 *
 * <p>The second is the one that would drift most expensively: a state added to the machine
 * without a decision about whether it frees the open-case slot either lets a customer hold two
 * open cases (the duplicate-case defect `INV-KYC-03` names) or blocks their successor case
 * forever — and both arrive silently without this reconciliation.
 */
@DisplayName("KycCaseStatus and its schema agree (P2-TSK-005)")
class KycCaseMigrationTest {

    private static final String MIGRATION = "db/migration/kyc/V002__create_kyc_case.sql";

    /** V008 (`P2-TSK-015`): the kind, the grant narrowing and the ownership graph. */
    private static final String MIGRATION_V008 =
            "db/migration/kyc/V008__create_beneficial_owner.sql";

    @Test
    @DisplayName("the status constraint lists exactly the states the machine declares")
    void statusConstraintMatchesTheEnum() {
        assertThat(migration())
                .contains("CHECK (status IN (" + KycCaseStatus.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the one-open-case predicate is exactly the machine's terminal set")
    void openCasePredicateMatchesTheTerminalSet() {
        assertThat(migration())
                .as("'terminal' and 'frees the open-case slot' must be one definition")
                .contains("WHERE status NOT IN (" + KycCaseStatus.sqlTerminalValueList() + ")");
    }

    @Test
    @DisplayName("the policy-version bound matches the domain type")
    void policyVersionBoundMatchesTheType() {
        assertThat(migration())
                .contains("length(policy_version) BETWEEN 1 AND " + KycPolicyVersion.MAX_LENGTH);
    }

    @Test
    @DisplayName("no cross-schema foreign key, and the application role gets no DELETE")
    void theBoundaryAndTheGrantsHold() {
        assertThat(migration())
                .as("the customer is referenced by value; an FK across schemas is coupling"
                        + " neither Gradle nor ArchUnit can see (ADR-0029)")
                .doesNotContain("REFERENCES party.");
        assertThat(migration())
                .contains("GRANT SELECT, INSERT, UPDATE ON kyc.kyc_case TO finapp_app")
                .doesNotContain("DELETE ON kyc.kyc_case");
    }

    @Test
    @DisplayName("the case-kind constraint lists exactly the kinds the enum declares")
    void caseKindConstraintMatchesTheEnum() {
        assertThat(migration(MIGRATION_V008))
                .contains("CHECK (case_kind IN (" + KycCaseKind.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the control-role constraint lists exactly the roles the enum declares")
    void controlRoleConstraintMatchesTheEnum() {
        assertThat(migration(MIGRATION_V008))
                .contains("CHECK (control_role IN (" + ControlRole.sqlValueList() + "))");
    }

    @Test
    @DisplayName("V008 narrows the case grant and keeps the owner rows append-only")
    void v008GrantsAreNarrow() {
        // The V002 grant above is pinned HISTORY (an applied migration cannot be edited); the
        // EFFECTIVE privilege is V008's narrowing - kind, customer, policy and opened_at become
        // unwritable by the application role, because a KYB -> KYC flip would disarm the
        // ownership gate. The per-column denial proof runs against the live schema in
        // KybCaseDatabaseTest; this pins the artefact that creates it.
        assertThat(migration(MIGRATION_V008))
                .contains("REVOKE UPDATE ON kyc.kyc_case FROM finapp_app")
                .contains("GRANT UPDATE (status, status_changed_at) ON kyc.kyc_case TO finapp_app")
                .contains("GRANT SELECT, INSERT ON kyc.beneficial_owner TO finapp_app")
                .doesNotContain("UPDATE ON kyc.beneficial_owner")
                .doesNotContain("DELETE ON kyc.beneficial_owner");
    }

    @Test
    @DisplayName("the graph is kind-bound at the schema: owner rows KYB-only, verifications KYC-only")
    void theCompositeForeignKeysBindTheKinds() {
        assertThat(migration(MIGRATION_V008))
                .contains("CHECK (case_kind = 'KYB')")
                .contains("CHECK (verification_case_kind = 'KYC')")
                .contains("FOREIGN KEY (case_id, case_kind) REFERENCES kyc.kyc_case (id, case_kind)")
                .contains(
                        "FOREIGN KEY (verification_case_id, verification_case_kind)");
    }

    @Test
    @DisplayName("the guard can actually read the migration")
    void theGuardIsNotVacuous() {
        // Without this a moved or renamed file makes every assertion above pass over an empty
        // string - and the doesNotContain assertions would pass most convincingly of all.
        assertThat(migration()).contains("CREATE TABLE kyc.kyc_case");
        assertThat(migration(MIGRATION_V008)).contains("CREATE TABLE kyc.beneficial_owner");
    }

    /** From the classpath, the sibling migration tests' idiom. */
    private static String migration() {
        return migration(MIGRATION);
    }

    private static String migration(String resource) {
        try (InputStream migration =
                KycCaseMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + resource);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
