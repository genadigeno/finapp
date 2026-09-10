package com.finapp.party;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PartyKind}, {@link CustomerStatus} and the {@code CHECK} constraints that persist them are
 * one definition (the {@code P0-TSK-022} pattern).
 *
 * <p>Hermetic on purpose. A database test proves the same agreement against a real server, but runs
 * only under {@code databaseTest}; drift introduced by adding an enum constant would sit undetected
 * until somebody remembered to run it. This runs in {@code ./gradlew build}, so the definitions
 * cannot separate for longer than one compile.
 *
 * <p><strong>The failure it prevents is quiet in a specific way.</strong> A new
 * {@code CustomerStatus} would compile, pass every unit test — including the lifecycle sweep, which
 * derives its expectations from the enum — and then fail at run time on the first relationship that
 * reached the new state, as a constraint violation on a write. The state machine would be correct
 * and the database would refuse to record it.
 */
@DisplayName("Party enums and their CHECK constraints agree (P1-TSK-005)")
class PartyEnumMigrationTest {

    private static final String MIGRATION = "db/migration/party/V002__create_party_and_customer.sql";

    /** The migration that most recently replaced the status constraint and the index (P2-TSK-014). */
    private static final String LATEST =
            "db/migration/party/V005__customer_rejection_and_status_grant_narrowing.sql";

    @Test
    @DisplayName("the kind constraint lists exactly the kinds the enum declares")
    void kindsAgree() {
        assertThat(readMigration())
                .as("V002's CHECK must match PartyKind exactly")
                .contains("CHECK (kind IN (" + PartyKind.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the LATEST status constraint lists exactly the statuses the enum declares")
    void statusesAgree() {
        // Applied migrations are history (ADR-0011): the constraint moved by REPLACEMENT when
        // REJECTED arrived (V005), so the enum reconciles against the latest definition - the
        // RoleAssignmentMigrationTest lesson, met here the day this file's original
        // one-terminal assertion was built to break.
        assertThat(read(LATEST))
                .as("the latest CHECK must match CustomerStatus exactly")
                .contains("CHECK (status IN (" + CustomerStatus.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the LATEST index predicate excludes exactly the enum's terminal states")
    void theTerminalStatesMatchTheIndex() {
        // The partial unique index says "at most one live relationship per party" by excluding
        // the terminal states. Derived from sqlTerminalValueList() so "terminal" and "frees
        // the slot" stay one definition: a third terminal state added without a new index
        // migration fails here rather than in a duplicate-customer incident - or, in the other
        // direction, as a refused party blocked from re-onboarding forever.
        assertThat(read(LATEST))
                .contains(
                        "WHERE status NOT IN (" + CustomerStatus.sqlTerminalValueList() + ")");
    }

    @Test
    @DisplayName("history keeps its shape: V002's original literals are pinned")
    void historyKeepsItsShape() {
        // V002 cannot be edited (ADR-0011), and its original one-terminal forms staying
        // exactly as applied is its own claim - separate from the latest definitions above.
        String original = readMigration();
        assertThat(original)
                .contains("CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'CLOSED'))")
                .contains("WHERE status <> 'CLOSED'");
    }

    @Test
    @DisplayName("the name bound in the column matches the bound the value object enforces")
    void theNameBoundAgrees() {
        // A column narrower than the type would reject a value the domain accepted, at write time,
        // after the caller was told it was valid. A column wider would let something in through any
        // other writer.
        assertThat(readMigration())
                .as("the CHECK must use PartyName.MAX_LENGTH")
                .contains("length(display_name) BETWEEN 1 AND " + PartyName.MAX_LENGTH);
    }

    @Test
    @DisplayName("the guard can actually read the migration")
    void theMigrationIsReadable() {
        // Without this, a renamed or moved file would make every assertion above pass over an
        // exception nobody sees - or, worse, over an empty string.
        assertThat(readMigration()).contains("CREATE TABLE party.party");
        assertThat(read(LATEST)).contains("ALTER TABLE party.customer");
    }

    private static String readMigration() {
        return read(MIGRATION);
    }

    private static String read(String resource) {
        try (InputStream stream =
                PartyEnumMigrationTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException(resource + " is not on the test classpath");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + resource, e);
        }
    }
}
