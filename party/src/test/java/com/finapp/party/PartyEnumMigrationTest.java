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

    @Test
    @DisplayName("the kind constraint lists exactly the kinds the enum declares")
    void kindsAgree() {
        assertThat(readMigration())
                .as("V002's CHECK must match PartyKind exactly")
                .contains("CHECK (kind IN (" + PartyKind.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the status constraint lists exactly the statuses the enum declares")
    void statusesAgree() {
        assertThat(readMigration())
                .as("V002's CHECK must match CustomerStatus exactly")
                .contains("CHECK (status IN (" + CustomerStatus.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the terminal state is the one the partial index treats as terminal")
    void theTerminalStateMatchesTheIndex() {
        // The partial unique index says "at most one live relationship per party" by excluding
        // WHERE status <> 'CLOSED'. That literal is the enum's terminal state, and if a second
        // terminal state were added the index would silently keep letting a party hold one row in
        // each - two dead relationships counting as live.
        //
        // Derived rather than hardcoded, so adding a terminal state fails here rather than in a
        // duplicate-customer incident.
        long terminals =
                java.util.Arrays.stream(CustomerStatus.values())
                        .filter(CustomerStatus::isTerminal)
                        .count();
        assertThat(terminals)
                .as("the partial index encodes exactly one terminal state; see V002")
                .isEqualTo(1);

        assertThat(readMigration())
                .contains("WHERE status <> '" + CustomerStatus.CLOSED.name() + "'");
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
    }

    private static String readMigration() {
        try (InputStream stream =
                PartyEnumMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (stream == null) {
                throw new IllegalStateException(MIGRATION + " is not on the test classpath");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + MIGRATION, e);
        }
    }
}
