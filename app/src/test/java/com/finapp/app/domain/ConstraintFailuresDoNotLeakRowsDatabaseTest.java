package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A refused row never reaches a log line (`P1-TSK-008` gate, {@code INV-AUD-02}).
 *
 * <h2>The defect this exists for</h2>
 *
 * <p>PostgreSQL reports a {@code CHECK} violation with a {@code DETAIL} line reading <em>"Failing
 * row contains (…)"</em> - <strong>every column of the refused row</strong>. The driver puts it in
 * {@code SQLException.getMessage()}, so an exception carrying that as its cause carries the row, and
 * anything that logs the exception logs the row.
 *
 * <p>Found by running the failing insert during the completion gate:
 *
 * <pre>
 * DETAIL:  Failing row contains (…, ARGON2ID, 1024, 1, 1, hunter2-my-actual-secret-password, …)
 * </pre>
 *
 * <p><strong>The constraint that exists to stop a plaintext being stored was causing the plaintext
 * to be logged when it fired.</strong> {@code INV-AUD-02} defeated by the mechanism protecting
 * {@code INV-IDN-01} - and not only for credentials: the same {@code DETAIL} carries a person's name
 * out of {@code party.party} and a login identifier out of {@code identity.identity}.
 *
 * <h2>Asserted over the whole stack trace, not the message</h2>
 *
 * <p>A message assertion would pass against an exception whose <em>cause</em> holds the row, which is
 * exactly the shape the defect had. So each probe renders the throwable the way a logger does -
 * message, causes and frames - and asserts the offending value appears nowhere in it.
 */
@Tag("database")
@DisplayName("a refused row never reaches a log line (P1-TSK-008)")
class ConstraintFailuresDoNotLeakRowsDatabaseTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    /** Distinctive, so finding it anywhere is proof rather than coincidence. */
    private static final String PLAINTEXT = "zqx-hunter2-marker-4a91c6";

    @Test
    @DisplayName("a plaintext refused by the derivation constraint is not in the exception at all")
    void aRefusedDerivationDoesNotLeak() throws SQLException {
        // The worst case, and the reason the constraint exists: something tries to store a
        // plaintext, and the refusal must not be the thing that discloses it.
        try (Connection app = DatabaseRoles.application()) {
            UUID identity = givenAnIdentity(app);

            Throwable thrown =
                    catchThrowable(
                            () ->
                                    execute(
                                            app,
                                            "INSERT INTO identity.credential (id, identity_id, type,"
                                                + " algorithm, memory_kib, iterations, parallelism,"
                                                + " derivation, status, created_at) VALUES (?, ?,"
                                                + " 'PASSWORD', 'ARGON2ID', 1024, 1, 1, ?, 'ACTIVE',"
                                                + " now())",
                                            IDS.next(),
                                            identity,
                                            PLAINTEXT));

            assertThat(thrown).as("the constraint must still refuse it").isNotNull();
            assertThat(renderedAsALoggerWould(thrown))
                    .as("the driver itself DOES carry the row - this is the precondition")
                    .contains(PLAINTEXT);
        }
    }

    @Test
    @DisplayName("the exception this platform raises carries the SQLState and nothing from the row")
    void ourExceptionCarriesNoRowData() throws SQLException {
        // The fix, asserted where it matters: whatever the driver said, what OUR code propagates -
        // and therefore what reaches a log line - contains no column value.
        try (Connection app = DatabaseRoles.application()) {
            UUID identity = givenAnIdentity(app);

            Throwable thrown =
                    catchThrowable(
                            () ->
                                    new com.finapp.identity.JdbcCredentialStore()
                                            .insert(app, aCredentialWithAPlaintextDerivation(identity)));

            // The domain refuses it before the database does, which is itself the first line of
            // defence - so this asserts the domain's refusal is equally quiet.
            assertThat(thrown).isNotNull();
            assertThat(renderedAsALoggerWould(thrown))
                    .as("nothing our code raises may carry the value that was refused")
                    .doesNotContain(PLAINTEXT);
        }
    }

    @Test
    @DisplayName("no storage exception on these tables can be given a cause at all")
    void thereIsNoConstructorThatCouldCarryARow() {
        // The structural half, and the one that survives a future author. An earlier version of
        // this test drove the party path and could pass vacuously - it guarded on whether anything
        // was thrown at all, which is the "green while checking nothing" shape this repository has
        // met five times.
        //
        // This cannot: if any of these exceptions regains a constructor taking a Throwable, the
        // safe path stops being the only path, and somebody will eventually attach the driver's
        // exception - which carries the whole refused row.
        for (Class<?> type :
                List.of(
                        com.finapp.identity.CredentialStorageException.class,
                        com.finapp.identity.IdentityStorageException.class,
                        com.finapp.party.PartyStorageException.class)) {

            List<String> constructorsTakingACause =
                    Arrays.stream(type.getDeclaredConstructors())
                            .filter(
                                    constructor ->
                                            Arrays.stream(constructor.getParameterTypes())
                                                    .anyMatch(Throwable.class::isAssignableFrom))
                            .map(constructor -> type.getSimpleName() + constructor)
                            .toList();

            assertThat(constructorsTakingACause)
                    .as(
                            "%s must not be constructible with a cause: PostgreSQL puts the whole"
                                + " refused row in a constraint violation, and these tables hold a"
                                + " password, a person's name and a login identifier",
                            type.getSimpleName())
                    .isEmpty();
        }
    }

    // -----------------------------------------------------------------

    private static com.finapp.identity.Credential aCredentialWithAPlaintextDerivation(UUID identity) {
        return com.finapp.identity.Credential.rehydrate(
                com.finapp.identity.CredentialId.of(IDS.next()),
                com.finapp.identity.IdentityId.of(identity),
                com.finapp.identity.CredentialType.PASSWORD,
                com.finapp.identity.CredentialAlgorithm.ARGON2ID,
                com.finapp.identity.DerivationParameters.current(),
                com.finapp.sharedkernel.security.Sensitive.of(PLAINTEXT),
                com.finapp.identity.CredentialStatus.ACTIVE,
                Instant.now(CLOCK),
                null);
    }

    /**
     * The throwable as a logging framework would write it: message, cause chain and frames.
     *
     * <p>Asserting on {@code getMessage()} alone would pass against an exception whose <em>cause</em>
     * holds the row - which is precisely the shape the defect had.
     */
    private static String renderedAsALoggerWould(Throwable thrown) {
        StringWriter rendered = new StringWriter();
        thrown.printStackTrace(new PrintWriter(rendered));
        return rendered.toString();
    }

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

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setObject(index + 1, arguments[index]);
            }
            statement.executeUpdate();
        }
    }
}
