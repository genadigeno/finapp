package com.finapp.app.domain;


import com.finapp.app.authentication.AuthenticatedSession;
import com.finapp.app.authentication.AuthenticationRequest;
import com.finapp.app.authentication.AuthenticationService;
import com.finapp.identity.Argon2PasswordDeriver;
import com.finapp.identity.AuthenticationThrottle;
import com.finapp.identity.Credential;
import com.finapp.identity.CredentialStore;
import com.finapp.identity.CredentialType;
import com.finapp.identity.CredentialVerifier;
import com.finapp.identity.DerivationParameters;
import com.finapp.identity.IdentityAuthentication;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcCredentialStore;
import com.finapp.identity.JdbcIdentityStore;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.LockoutPolicy;
import com.finapp.identity.LoginIdentifier;
import com.finapp.identity.RawPassword;
import com.finapp.identity.SessionIssue;
import com.finapp.identity.SessionPolicy;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Authentication fails closed when the database is unavailable
 * (`P1-TSK-012`, {@code PHASE_1_PLAN.md} §8).
 *
 * <h2>Fails closed means two things, and both are asserted</h2>
 *
 * <p><strong>No success is reported</strong>, and <strong>no durable trace claims otherwise</strong>
 * — no audit record, no outbox row, no incremented failure counter. A platform that returned a
 * failure while having committed the audit record of a success would be worse than one that simply
 * crashed: the trail would say a person logged in, permanently, under {@code INV-HIST-03}.
 *
 * <h2>Two shapes of "unavailable", and only the second is interesting</h2>
 *
 * <p>Unreachable <em>before</em> anything starts is the easy case — nothing can happen because
 * nothing began. The case worth a test is the connection dying <strong>mid-transaction, after
 * verification has already succeeded</strong>: that is where an implementation holding its answer in
 * memory returns {@code AUTHENTICATED} while nothing was committed. Driven with
 * {@code pg_terminate_backend}, the {@code P0-TST-005} idiom.
 *
 * <h2>"No session issued" has no subject yet, and this says so rather than faking one</h2>
 *
 * <p>{@code PHASE_1_PLAN.md} §8 words this row as <em>"never a session issued without a durable
 * record"</em>, and there is no session until {@code P1-TSK-013}/{@code P1-TSK-027}. Asserting the
 * absence of sessions from an empty table would pass vacuously — the shape {@code P1-TSK-009}
 * refused. So this asserts <strong>what is committed</strong>, which is the mechanism that will
 * withhold a session the moment there is one: the session insert will live in this same transaction,
 * so a transaction that commits nothing issues nothing.
 */
@Tag("database")
@SpringBootTest
@DisplayName("authentication fails closed (P1-TSK-012)")
class AuthenticationFailsClosedDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final DerivationParameters WEAK = new DerivationParameters(1024, 1, 1);
    private static final String PASSWORD = "correct horse battery staple";

    @Autowired private DataSource dataSource;

    @Test
    @DisplayName("an unreachable database reports no success and writes nothing")
    void anUnreachableDatabaseFailsClosed() throws SQLException {
        Fixture fixture = givenAnIdentityWithACredential();

        try (HikariDataSource closedPort = pointingAtAClosedPort()) {
            Throwable thrown = catchThrowable(() -> authenticateThrough(closedPort, fixture.login(), PASSWORD));

            assertThat(thrown)
                    .as("it fails, and it fails LOUDLY: a caller told nothing would be a caller"
                            + " that assumes the worst outcome silently succeeded")
                    .isNotNull();
        }

        assertNothingWasRecordedFor(fixture);
    }

    @Test
    @DisplayName("a connection killed mid-transaction after a SUCCESSFUL verification commits nothing")
    void aConnectionKilledMidFlightFailsClosed() throws Exception {
        // The case that matters. Verification has already succeeded in memory - the password was
        // right - and the process then loses its connection before the commit. An implementation
        // that returned its in-memory answer would report a login that never happened.
        Fixture fixture = givenAnIdentityWithACredential();

        Throwable thrown =
                catchThrowable(() -> authenticateAndKillTheConnectionMidFlight(fixture.login()));

        assertThat(thrown)
                .as("the transaction could not commit, so the caller is told so")
                .isNotNull();

        assertNothingWasRecordedFor(fixture);
    }

    @Test
    @DisplayName("the guard is not vacuous: a healthy authentication DOES record all three")
    void theAssertionsCanSeeAWrite() throws SQLException {
        // Without this, `assertNothingWasRecordedFor` passes over queries that find nothing for any
        // reason at all - a wrong table, a wrong predicate, a fixture that never existed. The
        // positive control proves the three things it looks for are things this platform writes.
        Fixture fixture = givenAnIdentityWithACredential();

        authenticateThrough(dataSource, fixture.login(), PASSWORD);

        assertThat(auditRows(fixture.login())).as("a healthy login IS audited").isEqualTo(1);
        assertThat(outboxRowsFor(fixture.identityId()))
                .as("and announced, scoped to THIS identity so the query is proven to select")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the counter query can see a row: only a FAILED authentication can prove that")
    void theCounterAssertionIsNotVacuous() throws SQLException {
        // The gap the completion gate found. `theAssertionsCanSeeAWrite` drives a SUCCESS, and a
        // success CLEARS the counter - so it can never demonstrate that this query selects
        // anything. Proven by making the predicate unsatisfiable: every negative assertion still
        // passed, because a query pointing at nothing finds nothing exactly as reliably as a
        // correct one.
        //
        // Only a failure leaves a row, so only a failure is a control for it.
        Fixture fixture = givenAnIdentityWithACredential();

        authenticateThrough(dataSource, fixture.login(), "not the password");

        assertThat(failureCounterRows(fixture.login()))
                .as("a failed authentication leaves exactly the row the negative assertions look"
                        + " for, so their finding none means something")
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------

    /**
     * Asserts the three durable traces of an authentication are all absent.
     *
     * <p>Three <strong>independent</strong> ones, written by three different components in the same
     * transaction, because a partial commit is precisely what "fails closed" forbids. The first
     * version had a third assertion derived from the other two - a restatement dressed as a check -
     * and the completion gate replaced it with the outbox, which is a genuinely separate writer and
     * was not covered at all.
     *
     * <p>Each has a positive control below. Without them these are three ways of finding nothing,
     * and a query pointing at the wrong thing finds nothing just as reliably as a correct one - the
     * gate proved that by making the counter predicate unsatisfiable and watching the suite stay
     * green.
     */
    private void assertNothingWasRecordedFor(Fixture fixture) throws SQLException {
        assertThat(auditRows(fixture.login()))
                .as("no audit record: a trail saying somebody logged in when they did not is"
                        + " permanent, and worse than no trail at all (INV-HIST-03)")
                .isZero();
        assertThat(failureCounterRows(fixture.login()))
                .as("and the failure counter did not move either - the attempt did not happen,"
                        + " so it must not count against the customer")
                .isZero();
        // The session insert will join this same transaction (P1-TSK-027), so a transaction that
        // commits nothing issues nothing. The outbox is the nearest thing that exists today: an
        // announcement of a login that did not happen would reach consumers and could not be
        // retracted.
        assertThat(outboxRowsFor(fixture.identityId()))
                .as("and nothing was announced: a published event cannot be taken back")
                .isZero();
    }

    private void authenticateThrough(DataSource source, LoginIdentifier login, String password) {
        TransactionTemplate template =
                new TransactionTemplate(new DataSourceTransactionManager(source));
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        authenticateThrough(source, template, login, password, new JdbcCredentialStore());
    }

    @SuppressWarnings("try") // The Scope is used for its close side effect.
    private void authenticateThrough(
            DataSource source,
            TransactionTemplate template,
            LoginIdentifier login,
            String password,
            CredentialStore<Connection> store) {
        try (CorrelationContext.Scope ignored =
                CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            new AuthenticationService(
                            new CredentialVerifier(
                                    new JdbcIdentityStore(),
                                    store,
                                    new Argon2PasswordDeriver(WEAK),
                                    IDS,
                                    CLOCK),
                            new AuthenticationThrottle(
                                    LockoutPolicy.current(), IDS, CLOCK, new JdbcAuditWriter()),
                            new IdentityAuthentication(
                                    IDS, CLOCK, new JdbcAuditWriter(), new JdbcOutboxWriter()),
                            new SessionIssue(
                                    new JdbcSessionStore(),
                                    SessionPolicy.current(),
                                    IDS,
                                    CLOCK,
                                    new java.security.SecureRandom()),
                            template,
                            source,
                            new SimpleMeterRegistry())
                    .authenticate(
                            new AuthenticationRequest(login.value(), Sensitive.of(password)),
                            // No device: these suites drive the SERVICE, and the User-Agent is a
                            // boundary concern the controller resolves. Passing a label here would
                            // test the fixture rather than anything the endpoint does.
                            null);
        }
    }

    /**
     * Authenticates, terminating this transaction's own backend part-way through it.
     *
     * <p><strong>Deterministic, not timed.</strong> The first version slept 60 ms and then killed
     * whatever was idle in a transaction — and the derivation at test parameters takes about a
     * millisecond, so the transaction had already committed and the test passed having disrupted
     * nothing. A sleep racing the thing it means to interrupt is the shape {@code P1-TSK-002} was
     * bitten by; the fix there was the same as here, which is to wait on the condition rather than
     * for a duration.
     *
     * <p>So the kill happens <em>inside</em> the flow: a store decorator reads the credential, asks
     * the connection for its own backend identifier, terminates it from a second connection, and
     * only then returns. Everything the verifier does afterwards — the upgrade, the audit record,
     * the commit — meets a connection that is already gone.
     */
    private void authenticateAndKillTheConnectionMidFlight(LoginIdentifier login) throws Exception {
        try (HikariDataSource single = pointingAtTheRealDatabase(1)) {
            TransactionTemplate template =
                    new TransactionTemplate(new DataSourceTransactionManager(single));
            template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            authenticateThrough(single, template, login, PASSWORD, new KillsItsOwnBackend());
        }
    }

    /** Reads the credential, then terminates the connection that read it. */
    private static final class KillsItsOwnBackend implements CredentialStore<Connection> {

        private final CredentialStore<Connection> delegate = new JdbcCredentialStore();

        @Override
        public java.util.Optional<Credential> findActive(
                Connection unitOfWork, IdentityId identityId, CredentialType type) {
            java.util.Optional<Credential> found = delegate.findActive(unitOfWork, identityId, type);
            try {
                terminate(backendPidOf(unitOfWork));
            } catch (SQLException e) {
                throw new IllegalStateException("could not terminate the backend", e);
            }
            return found;
        }

        @Override
        public void insert(Connection unitOfWork, Credential credential) {
            delegate.insert(unitOfWork, credential);
        }

        @Override
        public boolean supersede(
                Connection unitOfWork, com.finapp.identity.CredentialId id, java.time.Instant at) {
            return delegate.supersede(unitOfWork, id, at);
        }

        private static int backendPidOf(Connection connection) throws SQLException {
            try (PreparedStatement select = connection.prepareStatement("SELECT pg_backend_pid()");
                    ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }

        private static void terminate(int pid) throws SQLException {
            try (Connection other = DatabaseRoles.application();
                    PreparedStatement kill =
                            other.prepareStatement("SELECT pg_terminate_backend(?)")) {
                kill.setInt(1, pid);
                kill.executeQuery().close();
            }
        }
    }

    private HikariDataSource pointingAtAClosedPort() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://127.0.0.1:1/absent");
        config.setUsername("finapp_app");
        config.setPassword("nothing listens here");
        config.setConnectionTimeout(2_000);
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    private HikariDataSource pointingAtTheRealDatabase(int size) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(System.getProperty("finapp.db.url"));
        config.setUsername(System.getProperty("finapp.db.app.user"));
        config.setPassword(System.getProperty("finapp.db.app.password"));
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(size);
        config.setConnectionTimeout(5_000);
        return new HikariDataSource(config);
    }

    private int auditRows(LoginIdentifier login) throws SQLException {
        return count(
                "SELECT count(*) FROM platform.audit_record WHERE target_id = ?", login.value());
    }

    /**
     * Outbox rows announcing a successful login <strong>for this identity</strong>.
     *
     * <p>Scoped by the identity, not by event type. An unscoped count would be non-zero from other
     * tests in the same database, so a negative assertion over it could never fail - and this is
     * one of the three things "fails closed" forbids surviving.
     */
    private int outboxRowsFor(IdentityId identityId) throws SQLException {
        return count(
                "SELECT count(*) FROM platform.outbox_event"
                        + " WHERE event_type = 'identity.AuthenticationSucceeded'"
                        + " AND convert_from(payload, 'UTF8') LIKE ?",
                "%" + identityId.value() + "%");
    }

    private int failureCounterRows(LoginIdentifier login) throws SQLException {
        return count(
                "SELECT count(*) FROM identity.authentication_failure f"
                        + " JOIN identity.identity i ON i.id = f.identity_id"
                        + " WHERE i.login_identifier = ?",
                login.value());
    }

    private int count(String sql, Object argument) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(sql)) {
            select.setObject(1, argument);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    private record Fixture(IdentityId identityId, LoginIdentifier login) {}

    private Fixture givenAnIdentityWithACredential() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        LoginIdentifier login =
                new LoginIdentifier(
                        "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Ada Lovelace', now())",
                    party);
            execute(
                    app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at) VALUES (?, ?, ?, 'ACTIVE', now(), now())",
                    identity,
                    party,
                    login.value());
            new JdbcCredentialStore()
                    .insert(
                            app,
                            Credential.forPassword(
                                    IDS,
                                    CLOCK,
                                    IdentityId.of(identity),
                                    CredentialType.PASSWORD,
                                    new Argon2PasswordDeriver(WEAK),
                                    RawPassword.of(PASSWORD)));
        }
        return new Fixture(IdentityId.of(identity), login);
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
