package com.finapp.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link IdentityStatus} and the {@code CHECK} constraint that persists it are one definition (the
 * {@code P0-TSK-022} pattern), and the login identifier's rules are stated once.
 *
 * <p>Hermetic, for the reason {@code PartyEnumMigrationTest} records: drift introduced by adding an
 * enum constant must fail in {@code ./gradlew build} rather than waiting for somebody to run the
 * database tier.
 */
@DisplayName("Identity enums and their CHECK constraints agree (P1-TSK-005)")
class IdentityEnumMigrationTest {

    private static final String MIGRATION = "db/migration/identity/V002__create_identity.sql";

    @Test
    @DisplayName("the status constraint lists exactly the statuses the enum declares")
    void statusesAgree() {
        assertThat(readMigration())
                .as("V002's CHECK must match IdentityStatus exactly")
                .contains("CHECK (status IN (" + IdentityStatus.sqlValueList() + "))");
    }

    @Test
    @DisplayName("the login identifier's bounds in the column match the value object's")
    void theLoginIdentifierBoundsAgree() {
        assertThat(readMigration())
                .as("the CHECK must use LoginIdentifier's own bounds")
                .contains(
                        "length(login_identifier) BETWEEN "
                                + LoginIdentifier.MIN_LENGTH
                                + " AND "
                                + LoginIdentifier.MAX_LENGTH);
    }

    @Test
    @DisplayName("the login identifier is unique across ALL identities, not only live ones")
    void uniquenessIsNotPartial() {
        // The difference from party.customer's partial index, and it is a decision rather than an
        // inconsistency. A closed relationship may be replaced; a retired login identifier must not
        // become available again, because reissuing it would let a new person authenticate with a
        // name that appears in somebody else's audit history.
        //
        // Asserted by the absence of a WHERE clause on this index, because a partial index here
        // would be the "consistent" change somebody makes while tidying.
        String migration = readMigration();
        int index = migration.indexOf("CREATE UNIQUE INDEX identity_login_identifier_is_unique");
        assertThat(index).as("the unique index must exist").isNotNegative();

        String statement = migration.substring(index, migration.indexOf(';', index));
        assertThat(statement)
                .as("uniqueness must cover closed identities too; see the comment in V002")
                .doesNotContain("WHERE");
    }

    @Test
    @DisplayName("the party reference carries no foreign key across the schema boundary")
    void thePartyReferenceIsByValue() {
        // ADR-0029's boundary, asserted in the schema rather than only in prose. An FK here is the
        // change that would look like an improvement - "referential integrity!" - and would turn
        // extracting a module into a data migration.
        String migration = readMigration();
        int table = migration.indexOf("CREATE TABLE identity.identity");
        String definition = migration.substring(table, migration.indexOf(");", table));

        assertThat(definition)
                .as("identity.identity must hold party_id by value, with no REFERENCES clause")
                .contains("party_id")
                .doesNotContain("REFERENCES");
    }

    @Test
    @DisplayName("the guard can actually read the migration")
    void theMigrationIsReadable() {
        assertThat(readMigration()).contains("CREATE TABLE identity.identity");
    }

    private static String readMigration() {
        try (InputStream stream =
                IdentityEnumMigrationTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            if (stream == null) {
                throw new IllegalStateException(MIGRATION + " is not on the test classpath");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + MIGRATION, e);
        }
    }
}
