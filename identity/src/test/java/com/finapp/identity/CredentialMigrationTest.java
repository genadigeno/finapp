package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The credential enums and the {@code CHECK} constraints that persist them are one definition
 * (`P1-TSK-007`, the {@code P0-TSK-022} pattern).
 *
 * <p>Hermetic on purpose: drift introduced by adding an enum constant must fail in
 * {@code ./gradlew build} rather than waiting for somebody to run the database tier.
 */
@DisplayName("Credential enums and their CHECK constraints agree (P1-TSK-007)")
class CredentialMigrationTest {

    private static final String MIGRATION = "db/migration/identity/V003__create_credential.sql";

    @Test
    @DisplayName("the type constraint lists exactly the types the enum declares")
    void typesAgree() {
        assertThat(readMigration())
                .contains("CHECK (type IN (" + CredentialType.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the algorithm constraint lists exactly the algorithms the enum declares")
    void algorithmsAgree() {
        assertThat(readMigration())
                .contains("CHECK (algorithm IN (" + CredentialAlgorithm.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the status constraint lists exactly the statuses the enum declares")
    void statusesAgree() {
        assertThat(readMigration())
                .contains("CHECK (status IN (" + CredentialStatus.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the encoded-form constraint uses the algorithm's own prefix")
    void theEncodedFormConstraintMatchesTheAlgorithm() {
        // INV-IDN-01's database half. If CredentialAlgorithm's prefix ever changed and the
        // constraint did not, the column would start accepting values the domain rejects - and the
        // column is the stronger of the two mechanisms, so it is the one that must not drift.
        assertThat(readMigration())
                .contains(
                        "derivation LIKE '" + CredentialAlgorithm.ARGON2ID.derivationPrefix() + "%'");
    }

    @Test
    @DisplayName("the active-credential index is partial, unlike the login identifier's")
    void theActiveIndexIsPartial() {
        // The contrast is the decision, and it points the opposite way to the index one file over.
        // A superseded credential MUST free the slot - replacing a password is the ordinary thing a
        // person does - while a retired login identifier must NOT free its name. Asserted, because
        // making these two "consistent" is exactly the tidying somebody would do.
        String statement = statementStartingWith("CREATE UNIQUE INDEX credential_one_active_per_identity_and_type");

        assertThat(statement)
                .as("a superseded credential must free the slot for its replacement")
                .contains("WHERE status = 'ACTIVE'");
    }

    @Test
    @DisplayName("the strength index exists, which is what makes the parameters worth duplicating")
    void theStrengthIndexExists() {
        // ADR-0032 Option D's entire justification. Without this index, storing the parameters as
        // columns as well as inside the encoded form is duplication that buys nothing.
        assertThat(statementStartingWith("CREATE INDEX credential_by_strength"))
                .contains("algorithm, memory_kib, iterations, parallelism");
    }

    @Test
    @DisplayName("a trigger enforces that a credential is superseded and never edited")
    void theAppendOnlyTriggerExists() {
        // A CHECK sees only the row being written, so it cannot express "this column did not
        // change". The application role holds UPDATE because superseding needs it, so without the
        // trigger the grant would be wider than the intent.
        String migration = readMigration();

        assertThat(migration).contains("CREATE TRIGGER credential_is_append_only");
        assertThat(migration).contains("BEFORE UPDATE ON identity.credential");
        for (String frozen :
                new String[] {
                    "NEW.identity_id", "NEW.type", "NEW.algorithm", "NEW.memory_kib",
                    "NEW.iterations", "NEW.parallelism", "NEW.derivation", "NEW.created_at"
                }) {
            assertThat(migration).as("%s must be frozen by the trigger", frozen).contains(frozen);
        }
    }

    @Test
    @DisplayName("the application role gets no DELETE")
    void theRoleCannotDelete() {
        // A superseded credential is the evidence that protection changed. Destroying it destroys
        // the only record of when.
        assertThat(readMigration())
                .contains("GRANT SELECT, INSERT, UPDATE ON identity.credential TO finapp_app;")
                .doesNotContain("DELETE ON identity.credential");
    }

    @Test
    @DisplayName("the guard can actually read the migration")
    void theGuardIsNotVacuous() {
        // Every assertion above is a `contains`, and `contains` on an empty string fails - but a
        // missing resource would throw here rather than reporting a drift, so this says which.
        assertThat(readMigration()).contains("CREATE TABLE identity.credential");
    }

    // -----------------------------------------------------------------

    private static String statementStartingWith(String prefix) {
        String migration = readMigration();
        int start = migration.indexOf(prefix);
        assertThat(start).as("%s must exist", prefix).isNotNegative();
        return migration.substring(start, migration.indexOf(';', start));
    }

    private static String readMigration() {
        try (InputStream migration =
                CredentialMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + MIGRATION);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
