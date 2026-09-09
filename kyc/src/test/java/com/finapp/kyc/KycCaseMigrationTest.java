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
    @DisplayName("the guard can actually read the migration")
    void theGuardIsNotVacuous() {
        // Without this a moved or renamed file makes every assertion above pass over an empty
        // string - and the doesNotContain assertions would pass most convincingly of all.
        assertThat(migration()).contains("CREATE TABLE kyc.kyc_case");
    }

    /** From the classpath, the sibling migration tests' idiom. */
    private static String migration() {
        try (InputStream migration =
                KycCaseMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
