package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The session enums and the {@code CHECK} constraints that persist them are one definition
 * (`P1-TSK-013`).
 *
 * <h2>Why this exists, and why it did not until the completion gate</h2>
 *
 * <p>{@code P1-TSK-007} established the pattern — {@code CredentialMigrationTest} reconciles all
 * three credential enums with {@code V003} — and {@code V005} shipped <strong>without the
 * equivalent</strong>. Worse, its own comments claimed
 * <em>"IdentityEnumMigrationTest fails the build if they drift"</em>, and that test covers
 * {@link IdentityStatus} and nothing else. A documented claim about a test that does not cover the
 * thing it names is the defect the {@code P1-TSK-010} gate found in a javadoc, one layer down: the
 * next reader stops looking.
 *
 * <p>What could have drifted is not cosmetic. Adding a fourth {@link AssuranceLevel} without
 * touching the constraint gives a value the domain can produce and the database refuses — an
 * authentication that fails at the last write, after the derivation, for a reason no error message
 * would explain.
 */
@DisplayName("Session enums and their CHECK constraints agree (P1-TSK-013)")
class SessionMigrationTest {

    private static final String MIGRATION = "db/migration/identity/V005__create_session.sql";

    private static final String DATABASE_CLOCK =
            "db/migration/identity/V016__session_bounds_on_database_clock.sql";

    @Test
    @DisplayName("the assurance constraint lists exactly the levels the enum declares")
    void assuranceConstraintMatchesTheEnum() {
        assertThat(migration())
                .as("V005's CHECK must match AssuranceLevel exactly")
                .contains("CHECK (assurance IN (" + AssuranceLevel.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the status constraint lists exactly the statuses the enum declares")
    void statusConstraintMatchesTheEnum() {
        assertThat(migration())
                .as("V005's CHECK must match SessionStatus exactly")
                .contains("CHECK (status IN (" + SessionStatus.sqlValueList() + "))");
    }

    @Test
    @DisplayName("there is no EXPIRED status in the schema, because expiry is derived")
    void thereIsNoStoredExpiry() {
        // The modelling decision, asserted rather than left to the comment that explains it. A
        // stored EXPIRED needs a sweep to write it, and until that sweep runs the database says
        // ACTIVE about a session that is not.
        assertThat(migration()).doesNotContain("'EXPIRED'");
    }

    @Test
    @DisplayName("the token hash is unique, so a collision is refused rather than merely improbable")
    void theTokenHashIsUnique() {
        assertThat(migration())
                .contains("CREATE UNIQUE INDEX session_token_hash_is_unique");
    }

    @Test
    @DisplayName("the application role gets no DELETE")
    void theApplicationCannotDeleteASession() {
        // Withheld deliberately: a retention sweep is Phase 15's and runs as a different role.
        // Without DELETE the application cannot make a session disappear, so an investigation always
        // has the row.
        assertThat(migration())
                .contains("GRANT SELECT, INSERT, UPDATE ON identity.session TO finapp_app")
                .doesNotContain("DELETE ON identity.session");
    }

    @Test
    @DisplayName("the guard can actually read the migration")
    void theGuardIsNotVacuous() {
        // Without this, a moved or renamed file would make every assertion above pass over an empty
        // string - the failure this repository has met repeatedly.
        assertThat(migration()).contains("CREATE TABLE identity.session");
        assertThat(read(DATABASE_CLOCK)).contains("ALTER TABLE identity.session");
    }

    @Test
    @DisplayName("V016 compares the bounds with the database's own instant, never with issued_at")
    void theBoundsFollowTheDatabasesInstant() {
        // X-TSK-007. issued_at is business time and the bounds are the database's, so V005's check
        // compared two clocks. The rule it kept - a session is never written already expired -
        // survives on one clock. That it holds against a real row is SessionClockSkewDatabaseTest's.
        assertThat(read(DATABASE_CLOCK))
                .contains("DROP CONSTRAINT session_bounds_follow_issue")
                .contains(
                        "CHECK (idle_expires_at > live_from AND absolute_expires_at > live_from)");
    }

    @Test
    @DisplayName("live_from is the database's by default; business time still has no default")
    void onlyCoordinationTimeHasADefault() {
        // The DEFAULT is what lets an old-version INSERT, which names no live_from, land correctly
        // during a rolling deploy. Business time keeps P0-TSK-013's rule: no DEFAULT, because a
        // default would be the database quietly deciding a business fact.
        String v016 = read(DATABASE_CLOCK);
        assertThat(v016).contains("ALTER COLUMN live_from SET DEFAULT now()");
        assertThat(v016).doesNotContainPattern("(?i)(issued_at|revoked_at)\\s+SET\\s+DEFAULT");
    }

    /** From the classpath, as {@code CredentialMigrationTest} does - one idiom, not two. */
    private static String migration() {
        return read(MIGRATION);
    }

    private static String read(String path) {
        try (InputStream migration =
                SessionMigrationTest.class.getClassLoader().getResourceAsStream(path)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + path);
            }
            return new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
