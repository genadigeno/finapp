package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Argon2PasswordDeriver;
import com.finapp.identity.Credential;
import com.finapp.identity.CredentialId;
import com.finapp.identity.CredentialStore;
import com.finapp.identity.CredentialType;
import com.finapp.identity.CredentialVerifier;
import com.finapp.identity.DerivationParameters;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcCredentialStore;
import com.finapp.identity.JdbcIdentityStore;
import com.finapp.identity.LoginIdentifier;
import com.finapp.identity.RawPassword;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * The platform's only production log call on the credential path emits nothing sensitive.
 *
 * <h2>Why this one line gets its own test</h2>
 *
 * <p>{@code CredentialVerifier} logs exactly once - when an upgrade is discarded - and that line is
 * written on the <strong>failure</strong> path, which is the half of any component nobody reads
 * until something is wrong and therefore the half where a disclosure survives longest. It is also
 * the only place in the platform where a {@code Throwable} raised while handling a credential is
 * handed to a logger, and the {@code P1-TSK-008} gate found that a driver exception on these tables
 * carries <strong>the whole refused row</strong> in its {@code DETAIL}.
 *
 * <p>{@code DatabaseFailure} closed that route by removing the cause. This asserts the consequence
 * where it actually lands: in the emitted bytes, against a real PostgreSQL, with a real failure.
 *
 * <h2>Four things must be absent, and they are absent for different reasons</h2>
 *
 * <ul>
 *   <li>the <strong>password</strong> - {@code INV-IDN-01};
 *   <li>the <strong>derivation</strong> - not a password, so a rule about passwords would let it
 *       through, and it is offline-crackable material;
 *   <li>the <strong>login identifier</strong> - {@code CONFIDENTIAL}, because it carries existence
 *       ({@code INV-IDN-07}): a log line naming an account during a failure is an enumeration
 *       oracle for anyone who can read logs;
 *   <li>the <strong>identity identifier</strong> - same argument one step removed, and
 *       {@code PHASE_1_PLAN.md} §10 permits an attempted identifier in the audit trail and nowhere
 *       else.
 * </ul>
 */
@Tag("database")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("the credential path's one log line carries nothing sensitive (P1-TSK-009)")
class CredentialVerifierLogsNothingSensitiveDatabaseTest {

    private static final String PASSWORD = "zqx-verifier-log-marker-3d71fa";

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-06T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    private static final DerivationParameters POLICY = new DerivationParameters(2048, 2, 1);
    private static final DerivationParameters WEAK = new DerivationParameters(1024, 1, 1);

    @Test
    @DisplayName("a discarded upgrade names neither the account, the password nor the derivation")
    void theDiscardedUpgradeWarningIsQuiet(CapturedOutput output) throws SQLException {
        try (Connection app = transactional()) {
            IdentityId identityId = IdentityId.of(IDS.next());
            LoginIdentifier login = givenAnIdentityWithAWeakCredential(app, identityId);
            String derivation = storedDerivation(app, identityId);

            // A real store with the upgrade's insert made to fail - a double, not a weakened
            // control (.claude/rules/security.md). This drives the verifier's one catch-all, which
            // is the only place it logs.
            CredentialVerifier verifier =
                    new CredentialVerifier(
                            new JdbcIdentityStore(),
                            new FailingOnInsert(new JdbcCredentialStore()),
                            new Argon2PasswordDeriver(POLICY),
                            IDS,
                            CLOCK);

            boolean authenticated = verifier.verify(app, login, RawPassword.of(PASSWORD)).isSuccess();

            assertThat(authenticated)
                    .as("precondition: the upgrade must have been attempted on a SUCCESSFUL login")
                    .isTrue();
            assertThat(output.getAll())
                    .as("precondition: the warning must have been emitted, or nothing is being read")
                    .contains("A credential upgrade was discarded");

            assertThat(output.getAll()).as("the password").doesNotContain(PASSWORD);
            assertThat(output.getAll()).as("the derivation").doesNotContain(derivation);
            assertThat(output.getAll())
                    .as("the login identifier: it carries existence (INV-IDN-07)")
                    .doesNotContain(login.value());
            assertThat(output.getAll())
                    .as("and the identity it belongs to")
                    .doesNotContain(identityId.value().toString());
        }
    }

    // -----------------------------------------------------------------

    /** Auto-commit off: the verifier refuses anything else, deliberately (`P1-TSK-008`). */
    private static Connection transactional() throws SQLException {
        Connection connection = DatabaseRoles.application();
        connection.setAutoCommit(false);
        return connection;
    }

    /** The real store, with the upgrade's insert made to fail. */
    private record FailingOnInsert(CredentialStore<Connection> delegate)
            implements CredentialStore<Connection> {

        @Override
        public void insert(Connection unitOfWork, Credential credential) {
            throw new IllegalStateException("Injected failure during a credential upgrade");
        }

        @Override
        public boolean supersede(Connection unitOfWork, CredentialId id, Instant at) {
            return delegate.supersede(unitOfWork, id, at);
        }

        @Override
        public Optional<Credential> findActive(
                Connection unitOfWork, IdentityId identityId, CredentialType type) {
            return delegate.findActive(unitOfWork, identityId, type);
        }
    }

    private static LoginIdentifier givenAnIdentityWithAWeakCredential(
            Connection connection, IdentityId identityId) throws SQLException {
        UUID party = IDS.next();
        LoginIdentifier login =
                new LoginIdentifier(
                        "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        execute(
                connection,
                "INSERT INTO party.party (id, kind, display_name, registered_at)"
                        + " VALUES (?, 'PERSON', 'Ada Lovelace', now())",
                party);
        execute(
                connection,
                "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at,"
                        + " status_changed_at) VALUES (?, ?, ?, 'ACTIVE', now(), now())",
                identityId.value(),
                party,
                login.value());
        new JdbcCredentialStore()
                .insert(
                        connection,
                        Credential.forPassword(
                                IDS,
                                CLOCK,
                                identityId,
                                CredentialType.PASSWORD,
                                new Argon2PasswordDeriver(WEAK),
                                RawPassword.of(PASSWORD)));
        return login;
    }

    private static String storedDerivation(Connection connection, IdentityId identityId)
            throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT derivation FROM identity.credential WHERE identity_id = ?")) {
            select.setObject(1, identityId.value());
            try (var rows = select.executeQuery()) {
                rows.next();
                return rows.getString(1);
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
