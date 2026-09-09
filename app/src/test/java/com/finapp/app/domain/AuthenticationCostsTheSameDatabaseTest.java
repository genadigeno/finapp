package com.finapp.app.domain;


import com.finapp.app.authentication.AuthenticatedSession;
import com.finapp.app.authentication.AuthenticationRequest;
import com.finapp.app.authentication.AuthenticationService;
import com.finapp.identity.Argon2PasswordDeriver;
import com.finapp.identity.Credential;
import com.finapp.identity.CredentialAlgorithm;
import com.finapp.identity.CredentialType;
import com.finapp.identity.CredentialVerifier;
import com.finapp.identity.DerivationParameters;
import com.finapp.identity.IdentityAuthentication;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcCredentialStore;
import com.finapp.identity.JdbcIdentityStore;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.LoginIdentifier;
import com.finapp.identity.PasswordDeriver;
import com.finapp.identity.RawPassword;
import com.finapp.identity.SessionIssue;
import com.finapp.identity.SessionPolicy;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every failing authentication costs the same work (`P1-TSK-010`, {@code INV-IDN-07}).
 *
 * <h2>Why this exists separately from the endpoint test</h2>
 *
 * <p>{@code AuthenticationEndpointDatabaseTest} proves the four failures are byte-identical. That
 * is only half of {@code INV-IDN-07} - and the completion of this task proved it, because a mutation
 * that made a malformed password fail <em>without doing the derivation</em>
 * <strong>survived</strong> it. The responses were still identical; what changed was how long one
 * of them took.
 *
 * <p>That is the same finding {@code P1-TSK-008} recorded one task earlier - <em>assert by counting
 * work, not by reading a clock</em> - reproduced by the person who wrote it down. A wall-clock
 * assertion would be flaky and would measure the machine; a counting deriver is deterministic and
 * measures the property actually at stake: <em>did the expensive path run at all?</em>
 *
 * <h2>The service, not the endpoint</h2>
 *
 * <p>Driven through {@link AuthenticationService} with a counting deriver injected, because the
 * running application is wired with a real one and swapping a bean would change the context every
 * other slice test shares.
 */
@Tag("database")
@SpringBootTest
@DisplayName("every failing authentication does the same work (P1-TSK-010)")
class AuthenticationCostsTheSameDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final DerivationParameters WEAK = new DerivationParameters(1024, 1, 1);
    private static final String PASSWORD = "correct horse battery staple";

    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("all five failing paths perform exactly one derivation each")
    void everyFailingPathDoesTheWork() throws SQLException {
        LoginIdentifier present = givenAnIdentityWithACredential();
        LoginIdentifier suspended = givenAnIdentityWithACredential();
        suspend(suspended);
        LoginIdentifier noCredential = givenAnIdentityWithoutACredential();
        LoginIdentifier absent = new LoginIdentifier(someLogin());

        Map<String, AuthenticationRequest> failures = new LinkedHashMap<>();
        failures.put("no identity at all", request(absent, PASSWORD));
        failures.put("identity that cannot authenticate", request(suspended, PASSWORD));
        failures.put("identity with no credential", request(noCredential, PASSWORD));
        failures.put("wrong password", request(present, "not the password"));
        // The fifth, and the one a mutation walked through: a value RawPassword refuses to build.
        // Skipping the derivation here would make "your guess was under eight characters" readable
        // from a clock - a third outcome on an endpoint that must have two.
        failures.put("a password the platform could never have stored", request(present, "abc"));

        failures.forEach(
                (cause, attempt) -> {
                    CountingDeriver counting = new CountingDeriver(new Argon2PasswordDeriver(WEAK));

                    assertThat(authenticateInAFlow(counting, attempt))
                            .as("%s must fail", cause)
                            .isEmpty();
                    assertThat(counting.verifications())
                            .as("%s must cost exactly one verification, like every other failure",
                                    cause)
                            .isEqualTo(1);
                });
    }

    @Test
    @DisplayName("a success costs one verification too, so the count is not itself an oracle")
    void aSuccessCostsTheSame() throws SQLException {
        // Without this the guard above would be satisfied by an implementation that verifies twice
        // on success - which would make success distinguishable in the other direction.
        LoginIdentifier present = givenAnIdentityWithACredential();
        CountingDeriver counting = new CountingDeriver(new Argon2PasswordDeriver(WEAK));

        assertThat(authenticateInAFlow(counting, request(present, PASSWORD)))
                .isPresent();
        assertThat(counting.verifications()).isEqualTo(1);
    }

    // -----------------------------------------------------------------

    /**
     * Runs one authentication inside a correlation scope, as a request would.
     *
     * <p>The scope is normally established by {@code CorrelationFilter}, and calling the service
     * directly has none - which this test discovered by failing. That is the requirement working:
     * {@code IdentityAuthentication} refuses to write an audit record or an event it cannot join to
     * a flow, rather than fabricating an identifier that would point at nothing (`P0-TSK-014`).
     */
    @SuppressWarnings("try") // The Scope is used for its close side effect.
    private Optional<AuthenticatedSession> authenticateInAFlow(
            PasswordDeriver deriver, AuthenticationRequest attempt) {
        try (CorrelationContext.Scope ignored =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return serviceWith(deriver).authenticate(attempt, null);
        }
    }

    private AuthenticationService serviceWith(PasswordDeriver deriver) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new AuthenticationService(
                new CredentialVerifier(
                        new JdbcIdentityStore(), new JdbcCredentialStore(), deriver, IDS, CLOCK),
                new com.finapp.identity.AuthenticationThrottle(
                        com.finapp.identity.LockoutPolicy.current(),
                        IDS,
                        CLOCK,
                        new com.finapp.platform.audit.JdbcAuditWriter()),
                new IdentityAuthentication(
                        IDS,
                        CLOCK,
                        new com.finapp.platform.audit.JdbcAuditWriter(),
                        new com.finapp.platform.outbox.JdbcOutboxWriter()),
                new SessionIssue(
                        new JdbcSessionStore(),
                        SessionPolicy.current(),
                        IDS,
                        CLOCK,
                        new java.security.SecureRandom()),
                template,
                dataSource,
                new SimpleMeterRegistry());
    }

    private static AuthenticationRequest request(LoginIdentifier login, String password) {
        return new AuthenticationRequest(login.value(), Sensitive.of(password));
    }

    /**
     * Counts verifications, so "the work happened" is deterministic rather than timed.
     *
     * <p>Deliberately counts {@code matches} and not {@code derive}: the constructor derives a
     * throwaway password once to build the dummy, and counting that would make every case one
     * higher and hide an off-by-one in the thing being measured.
     */
    private static final class CountingDeriver implements PasswordDeriver {

        private final PasswordDeriver delegate;
        private final AtomicInteger verifications = new AtomicInteger();

        CountingDeriver(PasswordDeriver delegate) {
            this.delegate = delegate;
        }

        int verifications() {
            return verifications.get();
        }

        @Override
        public Sensitive<String> derive(RawPassword password) {
            return delegate.derive(password);
        }

        @Override
        public boolean matches(RawPassword password, Sensitive<String> credentialDerivation) {
            verifications.incrementAndGet();
            return delegate.matches(password, credentialDerivation);
        }

        @Override
        public CredentialAlgorithm algorithm() {
            return delegate.algorithm();
        }

        @Override
        public DerivationParameters currentParameters() {
            return delegate.currentParameters();
        }

        @Override
        public DerivationParameters parametersOf(Sensitive<String> credentialDerivation) {
            return delegate.parametersOf(credentialDerivation);
        }
    }

    private LoginIdentifier givenAnIdentityWithACredential() throws SQLException {
        LoginIdentifier login = givenAnIdentityWithoutACredential();
        try (Connection app = DatabaseRoles.application()) {
            new JdbcCredentialStore()
                    .insert(
                            app,
                            Credential.forPassword(
                                    IDS,
                                    CLOCK,
                                    identityIdOf(app, login),
                                    CredentialType.PASSWORD,
                                    new Argon2PasswordDeriver(WEAK),
                                    RawPassword.of(PASSWORD)));
        }
        return login;
    }

    private LoginIdentifier givenAnIdentityWithoutACredential() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        LoginIdentifier login = new LoginIdentifier(someLogin());
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Ada Lovelace', now())",
                    party);
            execute(
                    app,
                    // Back-dated: this fixture is later moved to another status by an UPDATE that
                    // reads now() again, and the local container's clock is corrected backwards
                    // between statements (P1-TSK-031; observed 225 ms). The ordering constraint is
                    // right and a fixture must not depend on two now() reads being ordered.
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at) VALUES (?, ?, ?, 'ACTIVE',"
                            + " now() - interval '1 hour', now() - interval '1 hour')",
                    identity,
                    party,
                    login.value());
        }
        return login;
    }

    private static IdentityId identityIdOf(Connection connection, LoginIdentifier login)
            throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT id FROM identity.identity WHERE login_identifier = ?")) {
            select.setString(1, login.value());
            try (var rows = select.executeQuery()) {
                rows.next();
                return IdentityId.of((UUID) rows.getObject(1));
            }
        }
    }

    private void suspend(LoginIdentifier login) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "UPDATE identity.identity SET status = 'SUSPENDED', status_changed_at = now()"
                            + " WHERE login_identifier = ?",
                    login.value());
        }
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

    private static String someLogin() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}
