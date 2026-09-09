package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The `party` and `identity` schemas, against a real PostgreSQL (`P1-TSK-005`).
 *
 * <h2>Why these assertions are here and not only in the aggregates</h2>
 *
 * <p>Two of the rules this task introduces <strong>cannot</strong> be enforced by an aggregate,
 * because they are rules <em>across</em> aggregates of the same type: at most one live customer
 * relationship per party, and a login identifier used once ever. An aggregate can only see itself,
 * so only the database can arbitrate either between two concurrent transactions — which under
 * ADR-0014 is the normal case rather than the exception.
 *
 * <p>The rest are the {@code CHECK} constraints and the grants. They are asserted here because a
 * constraint that exists in a migration file and a constraint that exists in the database are
 * different claims, and the migration having been applied is what makes the second one true.
 *
 * <p>Runs as {@code finapp_app}, deliberately: every assertion is then also a statement about what
 * the application role can and cannot do. A test that connected as the migrator would prove the
 * constraints and prove nothing about least privilege.
 */
@Tag("database")
@DisplayName("party and identity schemas (P1-TSK-005)")
class PartyAndIdentitySchemaDatabaseTest {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CHECK_VIOLATION = "23514";
    private static final String FOREIGN_KEY_VIOLATION = "23503";
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Test
    @DisplayName("a party, a customer and an identity are three rows in two schemas")
    void theThreeArePersistedSeparately() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            UUID party = insertParty(app, "PERSON", "Ada Lovelace");
            UUID customer = insertCustomer(app, party, "PENDING");
            UUID identity = insertIdentity(app, party, login(), "ACTIVE");

            assertThat(party).isNotEqualTo(customer).isNotEqualTo(identity);
            assertThat(count(app, "party.party", party)).isEqualTo(1);
            assertThat(count(app, "party.customer", customer)).isEqualTo(1);
            assertThat(count(app, "identity.identity", identity)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("a party may hold no customer relationship at all")
    void aPartyWithNoCustomer() throws SQLException {
        // The beneficial owner. Asserted in the schema because a NOT NULL customer column, or a
        // customer row created by a trigger, would make this unrepresentable without any Java
        // changing.
        try (Connection app = DatabaseRoles.application()) {
            UUID party = insertParty(app, "PERSON", "Grace Hopper");
            assertThat(count(app, "party.party", party)).isEqualTo(1);
            assertThat(customersOf(app, party)).isZero();
        }
    }

    @Test
    @DisplayName("a party may hold two identities, and only one live customer relationship")
    void theTwoUniquenessRulesDiffer() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            UUID party = insertParty(app, "PERSON", "Ada Lovelace");

            // Two logins for one party: permitted, and the case a merged model cannot represent.
            insertIdentity(app, party, login(), "ACTIVE");
            assertThatCode(() -> insertIdentity(app, party, login(), "ACTIVE"))
                    .as("a party may hold a retired login and its replacement")
                    .doesNotThrowAnyException();

            // Two LIVE relationships for one party: refused. This is the duplicate-customer defect
            // a retried registration would otherwise produce.
            insertCustomer(app, party, "ACTIVE");
            assertThatThrownBy(() -> insertCustomer(app, party, "PENDING"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(UNIQUE_VIOLATION);
        }
    }

    @Test
    @DisplayName("a closed relationship frees the party for a new one; a closed login never frees its identifier")
    void terminalStatesDifferInWhatTheyRelease() throws SQLException {
        // The asymmetry between the two uniqueness rules, asserted rather than described. Both are
        // deliberate and they point opposite ways: a relationship may be re-established, and a
        // retired login identifier must never be reissued, because it appears in somebody's audit
        // history and reissuing it would make every record naming it ambiguous.
        try (Connection app = DatabaseRoles.application()) {
            UUID party = insertParty(app, "PERSON", "Ada Lovelace");

            insertCustomer(app, party, "CLOSED");
            assertThatCode(() -> insertCustomer(app, party, "PENDING"))
                    .as("a closed relationship may be replaced by a new one")
                    .doesNotThrowAnyException();

            String retired = login();
            insertIdentity(app, party, retired, "CLOSED");
            assertThatThrownBy(() -> insertIdentity(app, party, retired, "ACTIVE"))
                    .as("a retired login identifier is never reissued")
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(UNIQUE_VIOLATION);
        }
    }

    @Test
    @DisplayName("an unknown status is refused by the database, not only by the enum")
    void unknownStatesAreRefused() throws SQLException {
        // The CHECK constraints, which exist for the writer that is not the aggregate: a migration,
        // an operator, or a future repository nobody has written yet.
        try (Connection app = DatabaseRoles.application()) {
            UUID party = insertParty(app, "PERSON", "Ada Lovelace");

            assertThatThrownBy(() -> insertCustomer(app, party, "REOPENED"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(CHECK_VIOLATION);

            assertThatThrownBy(() -> insertIdentity(app, party, login(), "PENDING"))
                    .as("PENDING is a Customer state and deliberately not an Identity state")
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(CHECK_VIOLATION);

            assertThatThrownBy(() -> insertParty(app, "ROBOT", "Nobody"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    @DisplayName("a login identifier outside the permitted charset is refused by the database too")
    void theLoginCharsetIsEnforcedInTheSchema() throws SQLException {
        // LoginIdentifier enforces this for anything going through the domain. The column enforces
        // it for everything else - which is the point of having both.
        try (Connection app = DatabaseRoles.application()) {
            UUID party = insertParty(app, "PERSON", "Ada Lovelace");

            assertThatThrownBy(() -> insertIdentity(app, party, "ada@example.com", "ACTIVE"))
                    .as("an email address is not a login identifier")
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    @DisplayName("a control character in a display name is refused by the database too")
    void theDisplayNameControlCharacterRuleIsEnforcedInTheSchema() throws SQLException {
        // Added by the `P1-TSK-006` gate (V003). PartyName enforces this for anything going through
        // the domain and the API boundary reports it as 422; the column enforces it for everything
        // else - a migration, an operator, a writer nobody has written yet.
        //
        // The constraint is deliberately NARROWER than PartyName, covering the C0/C1 control ranges
        // rather than five Unicode categories, because that is what a POSIX class expresses exactly.
        // Asserting the narrow part is the honest test; claiming parity would be the dishonest one.
        try (Connection app = DatabaseRoles.application()) {
            assertThatThrownBy(() -> insertParty(app, "PERSON", "Ada" + (char) 0x0A + "Lovelace"))
                    .as("a line feed in a RESTRICTED-PII column is a forged log line in waiting")
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(CHECK_VIOLATION);

            assertThatThrownBy(() -> insertParty(app, "PERSON", "Ada" + (char) 0x0D + "Lovelace"))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(CHECK_VIOLATION);
        }

        // The other half: a real name with an accent and a non-Latin script still stores, which is
        // what stops this constraint from having become the charset restriction PartyName refuses.
        try (Connection app = DatabaseRoles.application()) {
            assertThat(insertParty(app, "PERSON", "Ægir Þórsson 李雷")).isNotNull();
        }
    }

    @Test
    @DisplayName("customer references party by foreign key; identity does not")
    void theForeignKeyExistsWithinASchemaAndNotAcross() throws SQLException {
        // ADR-0029's boundary, asserted from both sides. Within `party` an FK is correct and
        // present; across the schema boundary there is none, so an identity CAN be written for a
        // party that does not exist.
        //
        // That is the cost of the decision, and it is asserted rather than hidden: what prevents it
        // is that the only code creating an identity creates the party in the same transaction
        // (P1-TSK-006), not the schema.
        try (Connection app = DatabaseRoles.application()) {
            UUID absent = UUID.randomUUID();

            assertThatThrownBy(() -> insertCustomer(app, absent, "PENDING"))
                    .as("a customer of a party that does not exist is refused")
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(FOREIGN_KEY_VIOLATION);

            assertThatCode(() -> insertIdentity(app, absent, login(), "ACTIVE"))
                    .as("no cross-schema foreign key: the database permits it, the domain does not")
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("the application role holds the DML these tables need, and no more")
    void leastPrivilegeIsEnforced() throws SQLException {
        // V008's principle, applied to the new tables. "Nothing more" is the part that does work:
        // a privilege granted just in case converts a design decision into an intention.
        try (Connection app = DatabaseRoles.application()) {
            UUID party = insertParty(app, "PERSON", "Ada Lovelace");
            UUID customer = insertCustomer(app, party, "PENDING");

            assertThatThrownBy(() -> execute(app, "DELETE FROM party.party WHERE id = ?", party))
                    .as("a party is never deleted")
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(INSUFFICIENT_PRIVILEGE);

            assertThatThrownBy(() -> execute(app, "DELETE FROM party.customer WHERE id = ?", customer))
                    .as("a relationship ends by becoming CLOSED, never by disappearing")
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(INSUFFICIENT_PRIVILEGE);

            // This assertion used to read "nothing about a party changes yet; the grant arrives
            // with the capability", and it was written to FAIL the day that stopped being true.
            // P1-TSK-030 is that day: PATCH /v1/me renames a Party, and it failed with SQLState
            // 42501 before a line of it had been reviewed - the privilege model working exactly as
            // P0-TSK-022 designed it.
            //
            // What replaces it is NARROWER than a plain UPDATE grant, and that is the point. V004
            // grants UPDATE (display_name) and nothing else, so a name is writable while `kind` and
            // `registered_at` are not: those are facts rather than fields - what a Party IS, and
            // when it came into existence - and neither has a legitimate writer.
            assertThatCode(
                            () ->
                                    execute(
                                            app,
                                            "UPDATE party.party SET display_name = 'x' WHERE id = ?",
                                            party))
                    .as("a person's own name is mutable data; the audit record carries the history")
                    .doesNotThrowAnyException();

            assertThatThrownBy(
                            () ->
                                    execute(
                                            app,
                                            "UPDATE party.party SET kind = 'ORGANISATION'"
                                                    + " WHERE id = ?",
                                            party))
                    .as("a party's kind is what it IS, and the column grant is what refuses this")
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(INSUFFICIENT_PRIVILEGE);

            assertThatThrownBy(
                            () ->
                                    execute(
                                            app,
                                            "UPDATE party.party SET registered_at = now()"
                                                    + " WHERE id = ?",
                                            party))
                    .as("when a party came into existence is not something the application may"
                            + " revise. A column-level grant is invisible in table_privileges"
                            + " (P0-TST-007), so this is where its narrowness is actually checked")
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(INSUFFICIENT_PRIVILEGE);

            // The transitions the aggregate performs must actually be writable.
            assertThatCode(
                            () ->
                                    execute(
                                            app,
                                            "UPDATE party.customer SET status = 'ACTIVE',"
                                                    + " status_changed_at = now() WHERE id = ?",
                                            customer))
                    .as("a customer's status legitimately changes")
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("a status change earlier than the opening is refused")
    void timestampsMustBeOrdered() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            UUID party = insertParty(app, "PERSON", "Ada Lovelace");

            assertThatThrownBy(
                            () -> {
                                try (PreparedStatement statement =
                                        app.prepareStatement(
                                                "INSERT INTO party.customer"
                                                    + " (id, party_id, status, opened_at, status_changed_at)"
                                                    + " VALUES (?, ?, 'PENDING', ?, ?)")) {
                                    statement.setObject(1, UUID.randomUUID());
                                    statement.setObject(2, party);
                                    statement.setTimestamp(
                                            3, Timestamp.from(Instant.parse("2026-09-04T10:00:00Z")));
                                    statement.setTimestamp(
                                            4, Timestamp.from(Instant.parse("2026-09-04T09:00:00Z")));
                                    statement.executeUpdate();
                                }
                            })
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(CHECK_VIOLATION);
        }
    }

    /**
     * The insert-then-update fixture shape is proven insensitive to a backwards clock correction
     * ({@code P1-TSK-031}).
     *
     * <p>The local container's clock runs fast and is corrected backwards, so PostgreSQL's
     * {@code now()} is not monotonic across two statements — a fixture that inserts a row at
     * {@code now()} and later moves its status with {@code status_changed_at = now()} reads the
     * clock twice with nothing ordering the second read after the first. Observed, not theorised:
     * {@code P1-TSK-025}'s gate failed on a status change 225 ms before its creation, and the
     * constraint that fired was <strong>right</strong>.
     *
     * <p>The remedy is back-dating the INSERT ({@code OutboxRelayTest.backDate}'s precedent), and
     * this test is what makes it load-bearing rather than a comment: the update simulates a
     * correction of thirty minutes — absurdly worse than the observed hundreds of milliseconds —
     * and must succeed against a back-dated row. The second half is the vacuity control: the same
     * update against a row written at plain {@code now()} must still be refused, so a pass proves
     * the back-dating carries the property rather than the constraint being dead.
     */
    @Test
    @DisplayName("a back-dated fixture survives a backwards clock correction, one at now() does not")
    void fixturesSurviveABackwardsClockCorrection() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            UUID party = insertParty(app, "PERSON", "Ada Lovelace");

            UUID backDated = UUID.randomUUID();
            try (PreparedStatement insert =
                    app.prepareStatement(
                            "INSERT INTO identity.identity"
                                + " (id, party_id, login_identifier, status, created_at,"
                                + " status_changed_at) VALUES (?, ?, ?, 'ACTIVE',"
                                + " now() - interval '1 hour', now() - interval '1 hour')")) {
                insert.setObject(1, backDated);
                insert.setObject(2, party);
                insert.setString(3, login());
                insert.executeUpdate();
            }
            assertThatCode(
                            () ->
                                    execute(
                                            app,
                                            "UPDATE identity.identity SET status = 'SUSPENDED',"
                                                    + " status_changed_at ="
                                                    + " now() - interval '30 minutes'"
                                                    + " WHERE id = ?",
                                            backDated))
                    .as("a clock corrected backwards between the two statements must not refuse"
                            + " the fixture")
                    .doesNotThrowAnyException();

            UUID atNow = insertIdentity(app, party, login(), "ACTIVE");
            assertThatThrownBy(
                            () ->
                                    execute(
                                            app,
                                            "UPDATE identity.identity SET status = 'SUSPENDED',"
                                                    + " status_changed_at ="
                                                    + " now() - interval '30 minutes'"
                                                    + " WHERE id = ?",
                                            atNow))
                    .as("the same update against a row written at now() is refused - the"
                            + " constraint is alive, so the back-dating is what carries the"
                            + " property")
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(CHECK_VIOLATION);
        }
    }

    // -----------------------------------------------------------------

    /**
     * A fresh identifier per call.
     *
     * <p>These tests share a database and commit, so a fixed identifier would make the second run
     * of the suite fail on the uniqueness rule the first run established — a test that passes once.
     */
    private static String login() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private static UUID insertParty(Connection connection, String kind, String name)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO party.party (id, kind, display_name, registered_at)"
                                + " VALUES (?, ?, ?, now())")) {
            statement.setObject(1, id);
            statement.setString(2, kind);
            statement.setString(3, name);
            statement.executeUpdate();
        }
        return id;
    }

    private static UUID insertCustomer(Connection connection, UUID partyId, String status)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement =
                connection.prepareStatement(
                        // Back-dated: a test later moves this row's status with an UPDATE that
                        // reads now() again, and the container's clock is corrected backwards
                        // between statements (P1-TSK-031). The ordering constraint is right and
                        // a fixture must not depend on two now() reads being ordered.
                        "INSERT INTO party.customer"
                                + " (id, party_id, status, opened_at, status_changed_at)"
                                + " VALUES (?, ?, ?, now() - interval '1 hour',"
                                + " now() - interval '1 hour')")) {
            statement.setObject(1, id);
            statement.setObject(2, partyId);
            statement.setString(3, status);
            statement.executeUpdate();
        }
        return id;
    }

    private static UUID insertIdentity(
            Connection connection, UUID partyId, String loginIdentifier, String status)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO identity.identity"
                                + " (id, party_id, login_identifier, status, created_at, status_changed_at)"
                                + " VALUES (?, ?, ?, ?, now(), now())")) {
            statement.setObject(1, id);
            statement.setObject(2, partyId);
            statement.setString(3, loginIdentifier);
            statement.setString(4, status);
            statement.executeUpdate();
        }
        return id;
    }

    private static void execute(Connection connection, String sql, UUID id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.executeUpdate();
        }
    }

    private static int count(Connection connection, String table, UUID id) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT count(*) FROM " + table + " WHERE id = ?")) {
            statement.setObject(1, id);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private static int customersOf(Connection connection, UUID partyId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT count(*) FROM party.customer WHERE party_id = ?")) {
            statement.setObject(1, partyId);
            try (var rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }
}
