package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Argon2PasswordDeriver;
import com.finapp.identity.Credential;
import com.finapp.identity.CredentialStore;
import com.finapp.identity.CredentialType;
import com.finapp.identity.CredentialVerifier;
import com.finapp.identity.DerivationParameters;
import com.finapp.identity.Identity;
import com.finapp.identity.IdentityId;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.JdbcCredentialStore;
import com.finapp.identity.JdbcIdentityStore;
import com.finapp.identity.LoginIdentifier;
import com.finapp.identity.PasswordDeriver;
import com.finapp.identity.RawPassword;
import com.finapp.identity.VerificationOutcome;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Verification and upgrade-on-use, against a real PostgreSQL (`P1-TSK-008`, ADR-0032).
 *
 * <h2>Timing is asserted by counting work, not by reading a clock</h2>
 *
 * <p>{@code INV-IDN-07}'s timing half says an absent account must cost what an existing one costs.
 * A wall-clock assertion for that is flaky and measures the machine; a <strong>counting
 * deriver</strong> is deterministic and measures the property that actually matters - <em>did the
 * expensive path run at all?</em> Every failing path is asserted to perform exactly one derivation,
 * which is what makes "equivalent cost" a fact rather than a hope.
 */
@Tag("database")
@DisplayName("credential verification and upgrade-on-use (P1-TSK-008)")
class CredentialVerificationDatabaseTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private static final String PASSWORD = "correct horse battery staple";

    /** Current policy for these tests: cheap, so the suite is about behaviour and not about cost. */
    private static final DerivationParameters POLICY = new DerivationParameters(2048, 2, 1);

    /** What a credential written before the policy was raised looks like. */
    private static final DerivationParameters WEAK = new DerivationParameters(1024, 1, 1);

    private final IdentityStore<Connection> identities = new JdbcIdentityStore();
    private final CredentialStore<Connection> credentials = new JdbcCredentialStore();

    @Test
    @DisplayName("a connection with no transaction is refused, not silently degraded")
    void anAutoCommitConnectionIsRefused() throws SQLException {
        // The defect this test exists for: with auto-commit on, setSavepoint throws and the
        // upgrade's catch-all swallowed it - so every login would have verified correctly and
        // upgraded nothing, permanently, with nothing failing. A control that reports success for
        // work it did not do.
        try (Connection app = DatabaseRoles.application()) {
            Fixture fixture = givenAnIdentityWithACredential(app, WEAK);

            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () ->
                                    verifier(POLICY)
                                            .verify(app, fixture.login(), RawPassword.of(PASSWORD)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("auto-commit");
        }
    }

    @Test
    @DisplayName("the right password verifies and returns the identity")
    void theRightPasswordVerifies() throws SQLException {
        try (Connection app = transactional()) {
            Fixture fixture = givenAnIdentityWithACredential(app, POLICY);

            VerificationOutcome outcome =
                    verifier(POLICY).verify(app, fixture.login(), RawPassword.of(PASSWORD));

            assertThat(outcome.isSuccess()).isTrue();
            assertThat(outcome.identityId()).contains(fixture.identityId());
        }
    }

    @Test
    @DisplayName("the wrong password does not verify")
    void theWrongPasswordDoesNot() throws SQLException {
        try (Connection app = transactional()) {
            Fixture fixture = givenAnIdentityWithACredential(app, POLICY);

            VerificationOutcome outcome =
                    verifier(POLICY).verify(app, fixture.login(), RawPassword.of("not the password"));

            assertThat(outcome.isSuccess()).isFalse();
            assertThat(outcome.identityId()).isEmpty();
        }
    }

    @Test
    @DisplayName("EVERY failing path performs the derivation, so absence is not readable from timing")
    void everyFailingPathDoesTheWork() throws SQLException {
        // INV-IDN-07's timing half, and the four cases in the order an implementation forgets them.
        // The middle two are the ones that matter: a suspended account answering instantly tells an
        // attacker that it exists AND that it is suspended.
        try (Connection app = transactional()) {
            Fixture withCredential = givenAnIdentityWithACredential(app, POLICY);
            Fixture suspended = givenAnIdentityWithACredential(app, POLICY);
            suspendTheIdentity(app, suspended.identityId());
            Fixture noCredential = givenAnIdentityWithoutACredential(app);

            for (Case probe :
                    List.of(
                            new Case("no such identity", new LoginIdentifier(someLogin())),
                            new Case("suspended identity", suspended.login()),
                            new Case("identity with no credential", noCredential.login()),
                            new Case("wrong password", withCredential.login()))) {

                CountingDeriver counting = new CountingDeriver(new Argon2PasswordDeriver(POLICY));
                VerificationOutcome outcome =
                        new CredentialVerifier(identities, credentials, counting, IDS, CLOCK)
                                .verify(app, probe.login(), RawPassword.of("not the password"));

                assertThat(outcome.isSuccess()).as("%s must fail", probe.name()).isFalse();
                assertThat(counting.verifications())
                        .as("%s must still cost a full verification", probe.name())
                        .isEqualTo(1);
            }
        }
    }

    @Test
    @DisplayName("a suspended identity does not authenticate even with the right password")
    void aSuspendedIdentityCannotAuthenticate() throws SQLException {
        try (Connection app = transactional()) {
            Fixture fixture = givenAnIdentityWithACredential(app, POLICY);
            suspendTheIdentity(app, fixture.identityId());

            assertThat(
                            verifier(POLICY)
                                    .verify(app, fixture.login(), RawPassword.of(PASSWORD))
                                    .isSuccess())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("a credential below policy verifies AND is upgraded, with no forced reset")
    void aWeakCredentialIsUpgraded() throws SQLException {
        // The acceptance criterion: the store converges without a forced reset. The customer types
        // the same password, notices nothing, and is protected more strongly afterwards.
        try (Connection app = transactional()) {
            Fixture fixture = givenAnIdentityWithACredential(app, WEAK);

            assertThat(
                            verifier(POLICY)
                                    .verify(app, fixture.login(), RawPassword.of(PASSWORD))
                                    .isSuccess())
                    .isTrue();

            Credential upgraded =
                    credentials
                            .findActive(app, fixture.identityId(), CredentialType.PASSWORD)
                            .orElseThrow();

            assertThat(upgraded.parameters())
                    .as("the active credential is now at current policy")
                    .isEqualTo(POLICY);
            assertThat(upgraded.isWeakerThan(POLICY)).isFalse();
            assertThat(upgraded.id())
                    .as("a NEW credential: the derivation is never rewritten in place")
                    .isNotEqualTo(fixture.credentialId());

            assertThat(credentialRows(app, fixture.identityId()))
                    .as("the old one is retained as evidence of when protection changed")
                    .isEqualTo(2);
            assertThat(supersededRows(app, fixture.identityId())).isEqualTo(1);

            // And the point of the whole exercise: the same password still works afterwards.
            assertThat(
                            verifier(POLICY)
                                    .verify(app, fixture.login(), RawPassword.of(PASSWORD))
                                    .isSuccess())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("a credential already at policy is left alone")
    void aCurrentCredentialIsNotRewritten() throws SQLException {
        // Without this, an implementation that re-derived on every login would pass every assertion
        // above - and would write a row and burn 46 ms on every single authentication.
        try (Connection app = transactional()) {
            Fixture fixture = givenAnIdentityWithACredential(app, POLICY);

            verifier(POLICY).verify(app, fixture.login(), RawPassword.of(PASSWORD));

            assertThat(credentialRows(app, fixture.identityId()))
                    .as("no second row: there was nothing to upgrade")
                    .isEqualTo(1);
            assertThat(supersededRows(app, fixture.identityId())).isZero();
        }
    }

    @Test
    @DisplayName("a failed verification never upgrades anything")
    void aFailedVerificationChangesNothing() throws SQLException {
        try (Connection app = transactional()) {
            Fixture fixture = givenAnIdentityWithACredential(app, WEAK);

            verifier(POLICY).verify(app, fixture.login(), RawPassword.of("not the password"));

            assertThat(credentialRows(app, fixture.identityId()))
                    .as("the plaintext was never in hand, so no upgrade was possible or attempted")
                    .isEqualTo(1);
            assertThat(supersededRows(app, fixture.identityId())).isZero();
        }
    }

    @Test
    @DisplayName("an upgrade that fails does not fail the authentication")
    void aFailedUpgradeStillAuthenticates() throws SQLException {
        // The rule that governs upgrade-on-use. The customer typed the right thing; refusing them
        // because a background optimisation collided would be a self-inflicted outage.
        try (Connection app = transactional()) {
            Fixture fixture = givenAnIdentityWithACredential(app, WEAK);

            CredentialVerifier verifier =
                    new CredentialVerifier(
                            identities,
                            new FailingOnInsert(credentials),
                            new Argon2PasswordDeriver(POLICY),
                            IDS,
                            CLOCK);

            assertThat(verifier.verify(app, fixture.login(), RawPassword.of(PASSWORD)).isSuccess())
                    .as("a correct password is a successful authentication, whatever the upgrade did")
                    .isTrue();

            // And the savepoint rolled the half-done upgrade back, so the credential is intact and
            // still usable rather than superseded with no replacement.
            assertThat(credentialRows(app, fixture.identityId())).isEqualTo(1);
            assertThat(supersededRows(app, fixture.identityId()))
                    .as("the supersede was undone: an identity with no active credential could"
                            + " never log in again")
                    .isZero();
            assertThat(verifier(POLICY).verify(app, fixture.login(), RawPassword.of(PASSWORD)).isSuccess())
                    .isTrue();
        }
    }

    // -----------------------------------------------------------------

    /**
     * A connection with auto-commit off, because that is what this component requires.
     *
     * <p>The fixture originally used the default auto-commit connection and the upgrade silently
     * did not happen: {@code setSavepoint} threw, and the verifier's catch-all discarded it as an
     * ordinary upgrade collision. The verifier now refuses such a connection outright; this is the
     * shape a caller actually presents.
     */
    private static Connection transactional() throws SQLException {
        Connection connection = DatabaseRoles.application();
        connection.setAutoCommit(false);
        return connection;
    }

    private CredentialVerifier verifier(DerivationParameters policy) {
        return new CredentialVerifier(
                identities, credentials, new Argon2PasswordDeriver(policy), IDS, CLOCK);
    }

    private record Case(String name, LoginIdentifier login) {}

    private record Fixture(
            IdentityId identityId,
            LoginIdentifier login,
            com.finapp.identity.CredentialId credentialId) {}

    /** Counts verifications, so "the work happened" is deterministic rather than timed. */
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
        public com.finapp.identity.CredentialAlgorithm algorithm() {
            return delegate.algorithm();
        }

        @Override
        public DerivationParameters currentParameters() {
            return delegate.currentParameters();
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
        public DerivationParameters parametersOf(Sensitive<String> credentialDerivation) {
            return delegate.parametersOf(credentialDerivation);
        }
    }

    /** The real store, with the upgrade's insert made to fail. A double, not a weakened control. */
    private static final class FailingOnInsert implements CredentialStore<Connection> {

        private final CredentialStore<Connection> delegate;
        private final AtomicInteger inserts = new AtomicInteger();

        FailingOnInsert(CredentialStore<Connection> delegate) {
            this.delegate = delegate;
        }

        @Override
        public void insert(Connection unitOfWork, Credential credential) {
            if (inserts.incrementAndGet() > 0) {
                throw new IllegalStateException("Injected failure during a credential upgrade");
            }
            delegate.insert(unitOfWork, credential);
        }

        @Override
        public boolean supersede(Connection unitOfWork, com.finapp.identity.CredentialId id, Instant at) {
            return delegate.supersede(unitOfWork, id, at);
        }

        @Override
        public Optional<Credential> findActive(
                Connection unitOfWork, IdentityId identityId, CredentialType type) {
            return delegate.findActive(unitOfWork, identityId, type);
        }
    }

    private Fixture givenAnIdentityWithACredential(
            Connection connection, DerivationParameters parameters) throws SQLException {
        Fixture identity = givenAnIdentityWithoutACredential(connection);
        Credential credential =
                Credential.forPassword(
                        IDS,
                        CLOCK,
                        identity.identityId(),
                        CredentialType.PASSWORD,
                        new Argon2PasswordDeriver(parameters),
                        RawPassword.of(PASSWORD));
        credentials.insert(connection, credential);
        return new Fixture(identity.identityId(), identity.login(), credential.id());
    }

    private Fixture givenAnIdentityWithoutACredential(Connection connection) throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        LoginIdentifier login = new LoginIdentifier(someLogin());
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
                login.value());
        return new Fixture(IdentityId.of(identity), login, null);
    }

    private void suspendTheIdentity(Connection connection, IdentityId identityId)
            throws SQLException {
        execute(
                connection,
                "UPDATE identity.identity SET status = 'SUSPENDED', status_changed_at = now()"
                        + " WHERE id = ?",
                identityId.value());
    }

    private static String someLogin() {
        return "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private int credentialRows(Connection connection, IdentityId identityId) throws SQLException {
        return count(connection, "SELECT count(*) FROM identity.credential WHERE identity_id = ?",
                identityId.value());
    }

    private int supersededRows(Connection connection, IdentityId identityId) throws SQLException {
        return count(
                connection,
                "SELECT count(*) FROM identity.credential WHERE identity_id = ?"
                        + " AND status = 'SUPERSEDED'",
                identityId.value());
    }

    private static int count(Connection connection, String sql, Object argument)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, argument);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
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
}
