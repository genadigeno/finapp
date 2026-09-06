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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * A login and a credential change, at the same time, on one identity
 * (`P1-TSK-012`, {@code PHASE_1_PLAN.md} §8).
 *
 * <h2>The risk is not the obvious one</h2>
 *
 * <p>The obvious question — <em>does an in-flight login with the old password still succeed after
 * the change?</em> — has a boring answer. Under {@code READ COMMITTED} it may, the window is
 * milliseconds, and every real platform accepts it.
 *
 * <p><strong>The sharp risk is that the login silently reverts the change.</strong> A login against
 * a below-policy credential triggers upgrade-on-use (`P1-TSK-008`): supersede the old row, insert a
 * re-derivation <em>of the password that was just used</em>. Run that against a credential the
 * customer has already replaced and the platform would reinstate the <em>old</em> password — a
 * password change undone by a concurrent login, with nothing failing anywhere. The customer's new
 * password stops working and the one they were trying to get rid of starts working again.
 *
 * <h2>Two mechanisms protect it, and only one of them is this task's subject</h2>
 *
 * <p>The <strong>conditional supersede</strong> moves the credential only while it is still
 * {@code ACTIVE}, and its row count is the outcome — so a login that lost the race stops before
 * inserting anything. Behind it, the <strong>partial unique index</strong> would refuse a second
 * {@code ACTIVE} credential whatever the verifier did.
 *
 * <p>That second mechanism is why asserting the end state is not enough: <em>"the change
 * survived"</em> also holds against an implementation that reached for the revert and was stopped
 * by the index — same result, worse mechanism, and the conditional supersede's whole claim
 * unproven. So this asserts the <strong>coordination</strong>: the login attempted no insert at
 * all. That is the {@code P1-TSK-008} lesson, applied to the case it did not cover.
 *
 * <h2>The change is placed inside the verifier's own window</h2>
 *
 * <p>The first version of this test read the credential, waited for the change, and only then called
 * {@code verify} — but {@code verify} does its <em>own</em> {@code findActive}, so it read the new
 * credential, the old password simply did not match, and the upgrade path was never reached. Two
 * mutations survived against that version, which is how it was found.
 *
 * <p>So the store is decorated to block <em>inside</em> {@code findActive}, after it has returned
 * the old credential and before the verifier can act on it. That is the real race rather than a
 * model of it: a derivation takes ~46 ms (ADR-0032), an enormous window for a change to land in.
 */
@Tag("database")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("a login racing a credential change (P1-TSK-012)")
class LoginRacingACredentialChangeDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());

    /** Below policy, so a login against it triggers upgrade-on-use — which is the hazard. */
    private static final DerivationParameters WEAK = new DerivationParameters(1024, 1, 1);

    private static final DerivationParameters POLICY = new DerivationParameters(2048, 2, 1);

    private static final String OLD_PASSWORD = "the password being replaced";
    private static final String NEW_PASSWORD = "the password that replaces it";

    private final CredentialStore<Connection> credentials = new JdbcCredentialStore();

    @Test
    @DisplayName("the change survives: the login never reinstates the replaced password")
    void aConcurrentLoginDoesNotRevertTheChange() throws Exception {
        Fixture fixture = givenAWeakCredential();

        Outcome outcome = raceLoginAgainstChange(fixture);

        assertThat(activeCredentials(fixture.identityId()))
                .as("exactly one active credential - never two, never none")
                .isEqualTo(1);

        // The decisive assertion. If the login had reinstated the old password, this is where it
        // shows: the password the customer deliberately replaced would authenticate again.
        assertThat(authenticatesNow(fixture.login(), NEW_PASSWORD))
                .as("the password the customer changed TO must work: a login that reverted the"
                        + " change would leave the old one working and this one dead")
                .isTrue();
        assertThat(authenticatesNow(fixture.login(), OLD_PASSWORD))
                .as("and the password they changed FROM must not - reinstating it is the silent"
                        + " defect this test exists for")
                .isFalse();

        assertThat(outcome.loginFailure()).as("the login must not have thrown").isNull();
        assertThat(outcome.changeFailure()).as("nor the change").isNull();
    }

    @Test
    @DisplayName("the coordination: the login stops CLEANLY, not by blowing up and being swallowed")
    void theLoginStopsAtTheConditionalSupersede(CapturedOutput output) throws Exception {
        // THREE mechanisms would each produce the right end state here, and the completion of this
        // task had to separate them because two mutations survived an outcome-only assertion:
        //
        //   1. the CONDITIONAL SUPERSEDE - the login is told it lost and stops, quietly;
        //   2. the APPEND-ONLY TRIGGER - an update to an already-superseded row is refused, the
        //      verifier's catch-all discards it, and the login succeeds anyway;
        //   3. the PARTIAL UNIQUE INDEX - a second ACTIVE credential is refused.
        //
        // Only the first is this task's subject, and only the first is silent. So the assertions are
        // "no insert was attempted" AND "nothing was discarded": together they say the login was
        // told it lost rather than finding out by failing.
        Fixture fixture = givenAWeakCredential();

        Outcome outcome = raceLoginAgainstChange(fixture);

        assertThat(outcome.upgradeInsertsAttempted())
                .as("the login found the credential already superseded and did not even try")
                .isZero();
        assertThat(output.getAll())
                .as("and it stopped CLEANLY. A discarded upgrade means the conditional supersede"
                        + " did not do the work - something further down threw and was swallowed,"
                        + " which is the same result reached by a worse mechanism")
                .doesNotContain("A credential upgrade was discarded");
    }

    @Test
    @DisplayName("the discard warning is real, so its absence above means something")
    void theDiscardWarningWouldBeVisible() throws Exception {
        // Without this, the assertion above passes over output that never contains the phrase for
        // any reason at all - a changed message, a filter, a capture that caught nothing. The
        // negative control proves the string is one this platform actually emits.
        assertThat(
                        readProductionSource(
                                "identity/src/main/java/com/finapp/identity/CredentialVerifier.java"))
                .as("the phrase asserted absent above is the one the verifier logs")
                .contains("A credential upgrade was discarded");
    }

    @Test
    @DisplayName("no lost update: every credential row is accounted for")
    void nothingIsLost() throws Exception {
        Fixture fixture = givenAWeakCredential();

        raceLoginAgainstChange(fixture);

        // The original and its replacement. A third row would mean the login inserted after all.
        assertThat(totalCredentials(fixture.identityId()))
                .as("every write that claimed to happen is present, and none that did not")
                .isEqualTo(2);

        // Superseded rows are never rewritten in place (`P1-TSK-007`'s BEFORE UPDATE trigger), so
        // the history of the change is intact whichever way the race went.
        assertThat(supersededCredentials(fixture.identityId())).isEqualTo(1);
    }

    @Test
    @DisplayName("the race is not vacuous: the two transactions really overlapped")
    void theRaceIsReal() throws Exception {
        // "The change survives" would also hold if the two ran one after another - the mode this
        // test exists to escape. The login is blocked INSIDE its read until the change has
        // committed, so the overlap is constructed rather than hoped for.
        Fixture fixture = givenAWeakCredential();

        Outcome outcome = raceLoginAgainstChange(fixture);

        assertThat(outcome.loginReadBeforeTheChangeCommitted())
                .as("the login held a credential the change then replaced underneath it")
                .isTrue();
    }

    // -----------------------------------------------------------------

    private record Fixture(IdentityId identityId, LoginIdentifier login, Credential original) {}

    private record Outcome(
            boolean loginReadBeforeTheChangeCommitted,
            int upgradeInsertsAttempted,
            Throwable loginFailure,
            Throwable changeFailure) {}

    private Outcome raceLoginAgainstChange(Fixture fixture) throws Exception {
        CountDownLatch loginHasRead = new CountDownLatch(1);
        CountDownLatch changeCommitted = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        BlockingAfterRead observed =
                new BlockingAfterRead(credentials, loginHasRead, changeCommitted);

        try {
            Future<Throwable> login =
                    pool.submit(
                            () -> {
                                try (Connection own = transactional()) {
                                    new CredentialVerifier(
                                                    new JdbcIdentityStore(),
                                                    observed,
                                                    new Argon2PasswordDeriver(POLICY),
                                                    IDS,
                                                    CLOCK)
                                            .verify(
                                                    own,
                                                    fixture.login(),
                                                    RawPassword.of(OLD_PASSWORD));
                                    own.commit();
                                    return null;
                                } catch (Throwable failure) {
                                    return failure;
                                }
                            });

            Future<Throwable> change =
                    pool.submit(
                            () -> {
                                try (Connection own = transactional()) {
                                    loginHasRead.await(30, TimeUnit.SECONDS);
                                    changeTheCredential(own, fixture);
                                    own.commit();
                                    return null;
                                } catch (Throwable failure) {
                                    return failure;
                                } finally {
                                    changeCommitted.countDown();
                                }
                            });

            Throwable changeFailure = change.get(60, TimeUnit.SECONDS);
            Throwable loginFailure = login.get(60, TimeUnit.SECONDS);
            return new Outcome(
                    loginHasRead.getCount() == 0, observed.inserts(), loginFailure, changeFailure);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * The real store, blocking after the read so the change lands in the verifier's window.
     *
     * <p>A double that <strong>delays and counts</strong>; it weakens no control and delegates every
     * write ({@code .claude/rules/security.md}).
     */
    private static final class BlockingAfterRead implements CredentialStore<Connection> {

        private final CredentialStore<Connection> delegate;
        private final CountDownLatch hasRead;
        private final CountDownLatch changeCommitted;
        private final AtomicInteger insertCount = new AtomicInteger();

        BlockingAfterRead(
                CredentialStore<Connection> delegate,
                CountDownLatch hasRead,
                CountDownLatch changeCommitted) {
            this.delegate = delegate;
            this.hasRead = hasRead;
            this.changeCommitted = changeCommitted;
        }

        int inserts() {
            return insertCount.get();
        }

        @Override
        public Optional<Credential> findActive(
                Connection unitOfWork, IdentityId identityId, CredentialType type) {
            Optional<Credential> found = delegate.findActive(unitOfWork, identityId, type);
            hasRead.countDown();
            try {
                changeCommitted.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return found;
        }

        @Override
        public void insert(Connection unitOfWork, Credential credential) {
            insertCount.incrementAndGet();
            delegate.insert(unitOfWork, credential);
        }

        @Override
        public boolean supersede(Connection unitOfWork, CredentialId id, Instant at) {
            return delegate.supersede(unitOfWork, id, at);
        }
    }

    /**
     * A credential change, as {@code P1-TSK-026} will perform it: supersede, then insert.
     *
     * <p>Written against the store because no credential-change endpoint exists yet. That is the
     * shape of the operation rather than an approximation — the endpoint will do exactly these two
     * writes in one transaction.
     */
    private void changeTheCredential(Connection unitOfWork, Fixture fixture) {
        boolean superseded =
                credentials.supersede(unitOfWork, fixture.original().id(), Instant.now(CLOCK));
        assertThat(superseded)
                .as("precondition: the change really replaced the credential it meant to")
                .isTrue();
        credentials.insert(
                unitOfWork,
                Credential.forPassword(
                        IDS,
                        CLOCK,
                        fixture.identityId(),
                        CredentialType.PASSWORD,
                        new Argon2PasswordDeriver(POLICY),
                        RawPassword.of(NEW_PASSWORD)));
    }

    private boolean authenticatesNow(LoginIdentifier login, String password) throws SQLException {
        try (Connection own = transactional()) {
            boolean success =
                    new CredentialVerifier(
                                    new JdbcIdentityStore(),
                                    credentials,
                                    new Argon2PasswordDeriver(POLICY),
                                    IDS,
                                    CLOCK)
                            .verify(own, login, RawPassword.of(password))
                            .isSuccess();
            own.rollback();
            return success;
        }
    }

    private Fixture givenAWeakCredential() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        LoginIdentifier login =
                new LoginIdentifier(
                        "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        Credential original;
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
            original =
                    Credential.forPassword(
                            IDS,
                            CLOCK,
                            IdentityId.of(identity),
                            CredentialType.PASSWORD,
                            new Argon2PasswordDeriver(WEAK),
                            RawPassword.of(OLD_PASSWORD));
            credentials.insert(app, original);
        }
        return new Fixture(IdentityId.of(identity), login, original);
    }

    private int activeCredentials(IdentityId identityId) throws SQLException {
        return count(
                "SELECT count(*) FROM identity.credential WHERE identity_id = ?"
                        + " AND status = 'ACTIVE'",
                identityId.value());
    }

    private int totalCredentials(IdentityId identityId) throws SQLException {
        return count(
                "SELECT count(*) FROM identity.credential WHERE identity_id = ?",
                identityId.value());
    }

    private int supersededCredentials(IdentityId identityId) throws SQLException {
        return count(
                "SELECT count(*) FROM identity.credential WHERE identity_id = ?"
                        + " AND status = 'SUPERSEDED'",
                identityId.value());
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

    private static Connection transactional() throws SQLException {
        Connection connection = DatabaseRoles.application();
        connection.setAutoCommit(false);
        return connection;
    }

    private static String readProductionSource(String relativePath) {
        java.nio.file.Path directory = java.nio.file.Path.of("").toAbsolutePath();
        while (directory != null
                && !java.nio.file.Files.isRegularFile(directory.resolve("settings.gradle.kts"))) {
            directory = directory.getParent();
        }
        if (directory == null) {
            throw new IllegalStateException("No settings.gradle.kts above the working directory");
        }
        try {
            return java.nio.file.Files.readString(
                    directory.resolve(relativePath), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Could not read " + relativePath, e);
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
