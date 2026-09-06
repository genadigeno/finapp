package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.identity.Argon2PasswordDeriver;
import com.finapp.identity.Credential;
import com.finapp.identity.CredentialAlgorithm;
import com.finapp.identity.CredentialStatus;
import com.finapp.identity.CredentialType;
import com.finapp.identity.DerivationParameters;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcCredentialStore;
import com.finapp.identity.PasswordDeriver;
import com.finapp.identity.RawPassword;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * What the database itself refuses to store in {@code identity.credential} (`P1-TSK-007`).
 *
 * <h2>Why these are database tests rather than domain tests</h2>
 *
 * <p>The domain refuses the same things, and the domain is not the only writer. A migration, an
 * operator, or code nobody has written yet reaches this table without passing through
 * {@code Credential} - and {@code INV-IDN-01} protects the platform's most consequential secret, so
 * it gets the strongest mechanism in the catalogue rather than the most convenient one.
 */
@Tag("database")
@DisplayName("credential schema (P1-TSK-007)")
class CredentialSchemaDatabaseTest {

    private static final String CHECK_VIOLATION = "23514";

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    /** Cheap parameters: these tests are about the schema, not about Argon2's cost. */
    private static final PasswordDeriver DERIVER =
            new Argon2PasswordDeriver(new DerivationParameters(1024, 1, 1));

    @Test
    @DisplayName("A PLAINTEXT PASSWORD CANNOT BE STORED IN THE DERIVATION COLUMN")
    void aPlaintextCannotBeStored() throws SQLException {
        // INV-IDN-01, at DB-CONSTRAINT. This is the single most important assertion in the task:
        // it is not "the code does not do that", it is "the column will not hold it" - which
        // survives a future writer, a migration and an operator.
        try (Connection app = DatabaseRoles.application()) {
            UUID identity = givenAnIdentity(app);

            assertThatThrownBy(() -> insertRaw(app, identity, "hunter2", "ARGON2ID"))
                    .as("a plaintext must not be storable, whoever writes it")
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(CHECK_VIOLATION);

            assertThatThrownBy(
                            () ->
                                    insertRaw(
                                            app,
                                            identity,
                                            "$2a$10$abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRS",
                                            "ARGON2ID"))
                    .as("another algorithm's encoded form is not this algorithm's")
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    @DisplayName("a real derivation stores, so the constraint above is not simply refusing everything")
    void arealDerivationStores() throws SQLException {
        // The other half. Without it, a constraint of `CHECK (false)` would pass every assertion
        // above and break the platform.
        try (Connection app = DatabaseRoles.application()) {
            UUID identity = givenAnIdentity(app);

            assertThatCode(() -> new JdbcCredentialStore().insert(app, credentialFor(identity)))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("the parameters are NOT NULL, because a credential nobody can assess is worthless")
    void theParametersAreMandatory() throws SQLException {
        // INV-IDN-02. Enforced at DB-CONSTRAINT rather than only in the record, because a
        // credential whose cost factors are unknown can never be found by an upgrade campaign.
        try (Connection app = DatabaseRoles.application()) {
            UUID identity = givenAnIdentity(app);

            for (String column : new String[] {"algorithm", "memory_kib", "iterations", "parallelism"}) {
                assertThatThrownBy(() -> insertWithNull(app, identity, column))
                        .as("%s must not be nullable", column)
                        .isInstanceOf(SQLException.class)
                        .extracting(e -> ((SQLException) e).getSQLState())
                        .isEqualTo("23502");
            }
        }
    }

    @Test
    @DisplayName("zero cost factors are refused: a credential must not look parameterised and be free")
    void costFactorsMustBePositive() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            UUID identity = givenAnIdentity(app);

            assertThatThrownBy(() -> insertRaw(app, identity, derivation(), "ARGON2ID", 0, 2, 1))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    @DisplayName("one identity may hold only one ACTIVE credential of a type")
    void onlyOneActiveCredential() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            UUID identity = givenAnIdentity(app);
            JdbcCredentialStore store = new JdbcCredentialStore();

            store.insert(app, credentialFor(identity));

            assertThatThrownBy(() -> store.insert(app, credentialFor(identity)))
                    .isInstanceOf(com.finapp.identity.ActiveCredentialAlreadyExistsException.class);
        }
    }

    @Test
    @DisplayName("a superseded credential frees the slot; a retired login identifier never does")
    void supersedingFreesTheSlot() throws SQLException {
        // The asymmetry with identity_login_identifier_is_unique, exercised rather than asserted in
        // a comment. Replacing a password is the ordinary thing a person does; reissuing a login
        // identifier would make somebody else's audit history ambiguous.
        try (Connection app = DatabaseRoles.application()) {
            UUID identity = givenAnIdentity(app);
            JdbcCredentialStore store = new JdbcCredentialStore();

            Credential first = credentialFor(identity);
            store.insert(app, first);
            assertThat(store.supersede(app, first.id(), Instant.now(CLOCK))).isTrue();

            assertThatCode(() -> store.insert(app, credentialFor(identity)))
                    .as("the replacement may now be active")
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("superseding an already-superseded credential reports it rather than pretending")
    void supersedingTwiceReportsFalse() throws SQLException {
        // The conditional UPDATE ... WHERE status = 'ACTIVE' affecting zero rows IS the outcome.
        // Two instances issuing this is the ordinary case under ADR-0014, and the loser must be
        // told it lost rather than carrying on to insert a replacement somebody else already made.
        try (Connection app = DatabaseRoles.application()) {
            UUID identity = givenAnIdentity(app);
            JdbcCredentialStore store = new JdbcCredentialStore();
            Credential credential = credentialFor(identity);
            store.insert(app, credential);

            assertThat(store.supersede(app, credential.id(), Instant.now(CLOCK))).isTrue();
            assertThat(store.supersede(app, credential.id(), Instant.now(CLOCK)))
                    .as("the second call did not perform the transition")
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a derivation cannot be rewritten in place, whatever the application role attempts")
    void theDerivationIsFrozen() throws SQLException {
        // The trigger. The application role holds UPDATE because superseding needs it, so without
        // this the grant would be wider than the intent - and rewriting a derivation in place is
        // how the evidence of when protection changed disappears.
        try (Connection app = DatabaseRoles.application()) {
            UUID identity = givenAnIdentity(app);
            Credential credential = credentialFor(identity);
            new JdbcCredentialStore().insert(app, credential);

            assertThatThrownBy(
                            () ->
                                    execute(
                                            app,
                                            "UPDATE identity.credential SET derivation = ? WHERE id = ?",
                                            derivation(),
                                            credential.id().value()))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    @DisplayName("a credential cannot be un-superseded, and cannot move to another identity")
    void theTriggerRefusesEveryOtherChange() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            UUID identity = givenAnIdentity(app);
            UUID other = givenAnIdentity(app);
            JdbcCredentialStore store = new JdbcCredentialStore();
            Credential credential = credentialFor(identity);
            store.insert(app, credential);
            store.supersede(app, credential.id(), Instant.now(CLOCK));

            assertThatThrownBy(
                            () ->
                                    execute(
                                            app,
                                            "UPDATE identity.credential SET status = 'ACTIVE',"
                                                    + " superseded_at = NULL WHERE id = ?",
                                            credential.id().value()))
                    .as("SUPERSEDED is terminal in the schema, not only in the enum")
                    .isInstanceOf(SQLException.class);

            assertThatThrownBy(
                            () ->
                                    execute(
                                            app,
                                            "UPDATE identity.credential SET identity_id = ? WHERE id = ?",
                                            other,
                                            credential.id().value()))
                    .as("a credential belongs to the identity it was created for, permanently")
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("status and superseded_at must agree, in both directions")
    void statusAndTimestampMustAgree() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            UUID identity = givenAnIdentity(app);

            assertThatThrownBy(
                            () ->
                                    execute(
                                            app,
                                            "INSERT INTO identity.credential (id, identity_id, type,"
                                                + " algorithm, memory_kib, iterations, parallelism,"
                                                + " derivation, status, created_at, superseded_at)"
                                                + " VALUES (?, ?, 'PASSWORD', 'ARGON2ID', 1024, 1, 1,"
                                                + " ?, 'SUPERSEDED', now(), NULL)",
                                            UUID.randomUUID(),
                                            identity,
                                            derivation()))
                    .isInstanceOf(SQLException.class)
                    .extracting(e -> ((SQLException) e).getSQLState())
                    .isEqualTo(CHECK_VIOLATION);
        }
    }

    @Test
    @DisplayName("the credential round-trips, parameters and all")
    void itRoundTrips() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            UUID identity = givenAnIdentity(app);
            Credential written = credentialFor(identity);
            JdbcCredentialStore store = new JdbcCredentialStore();
            store.insert(app, written);

            Credential read =
                    store.findActive(app, IdentityId.of(identity), CredentialType.PASSWORD)
                            .orElseThrow();

            assertThat(read.id()).isEqualTo(written.id());
            assertThat(read.parameters()).isEqualTo(written.parameters());
            assertThat(read.algorithm()).isEqualTo(CredentialAlgorithm.ARGON2ID);
            assertThat(read.status()).isEqualTo(CredentialStatus.ACTIVE);
            assertThat(read.credentialDerivation().expose())
                    .isEqualTo(written.credentialDerivation().expose());

            // The point of storing it at all: what came back still verifies the password.
            assertThat(DERIVER.matches(RawPassword.of(PASSWORD), read.credentialDerivation()))
                    .isTrue();
        }
    }

    @Test
    @DisplayName("findActive ignores superseded credentials")
    void findActiveIgnoresSuperseded() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            UUID identity = givenAnIdentity(app);
            JdbcCredentialStore store = new JdbcCredentialStore();
            Credential credential = credentialFor(identity);
            store.insert(app, credential);
            store.supersede(app, credential.id(), Instant.now(CLOCK));

            assertThat(store.findActive(app, IdentityId.of(identity), CredentialType.PASSWORD))
                    .as("a superseded credential must never authenticate anybody again")
                    .isEmpty();
        }
    }

    // -----------------------------------------------------------------

    private static final String PASSWORD = "correct horse battery staple";

    private static String derivation() {
        return DERIVER.derive(RawPassword.of(PASSWORD)).expose();
    }

    private static Credential credentialFor(UUID identity) {
        return Credential.forPassword(
                IDS,
                CLOCK,
                IdentityId.of(identity),
                CredentialType.PASSWORD,
                DERIVER,
                RawPassword.of(PASSWORD));
    }

    /**
     * A party and an identity to hang a credential on.
     *
     * <p>Identifiers come from {@link IdGenerator} rather than {@code UUID.randomUUID()}: ADR-0013
     * makes every {@code EntityId} a UUIDv7 and refuses a v4, so a fixture using the JDK's random
     * one fails in the domain type rather than in the assertion - which is the type doing its job.
     */
    private static UUID givenAnIdentity(Connection connection) throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        execute(
                connection,
                "INSERT INTO party.party (id, kind, display_name, registered_at)"
                        + " VALUES (?, 'PERSON', 'Ada Lovelace', now())",
                party);
        execute(
                connection,
                "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at,"
                        + " status_changed_at) VALUES (?, ?, ?, 'ACTIVE', now(), now())",
                identity,
                party,
                "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        return identity;
    }

    private static void insertRaw(
            Connection connection, UUID identity, String derivation, String algorithm)
            throws SQLException {
        insertRaw(connection, identity, derivation, algorithm, 1024, 1, 1);
    }

    private static void insertRaw(
            Connection connection,
            UUID identity,
            String derivation,
            String algorithm,
            int memoryKib,
            int iterations,
            int parallelism)
            throws SQLException {
        execute(
                connection,
                "INSERT INTO identity.credential (id, identity_id, type, algorithm, memory_kib,"
                        + " iterations, parallelism, derivation, status, created_at)"
                        + " VALUES (?, ?, 'PASSWORD', ?, ?, ?, ?, ?, 'ACTIVE', now())",
                UUID.randomUUID(),
                identity,
                algorithm,
                memoryKib,
                iterations,
                parallelism,
                derivation);
    }

    private static void insertWithNull(Connection connection, UUID identity, String nulledColumn)
            throws SQLException {
        String sql =
                "INSERT INTO identity.credential (id, identity_id, type, algorithm, memory_kib,"
                        + " iterations, parallelism, derivation, status, created_at) VALUES (?, ?,"
                        + " 'PASSWORD', "
                        + value("algorithm", nulledColumn, "'ARGON2ID'")
                        + ", "
                        + value("memory_kib", nulledColumn, "1024")
                        + ", "
                        + value("iterations", nulledColumn, "1")
                        + ", "
                        + value("parallelism", nulledColumn, "1")
                        + ", ?, 'ACTIVE', now())";
        execute(connection, sql, UUID.randomUUID(), identity, derivation());
    }

    private static String value(String column, String nulledColumn, String otherwise) {
        return column.equals(nulledColumn) ? "NULL" : otherwise;
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                Object argument = arguments[index];
                if (argument instanceof Instant instant) {
                    statement.setTimestamp(index + 1, Timestamp.from(instant));
                } else {
                    statement.setObject(index + 1, argument);
                }
            }
            statement.executeUpdate();
        }
    }

}
