package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.authentication.AuthenticationRequest;
import com.finapp.app.authentication.AuthenticationService;
import com.finapp.identity.Argon2PasswordDeriver;
import com.finapp.identity.AuthenticationThrottle;
import com.finapp.identity.Credential;
import com.finapp.identity.CredentialAlgorithm;
import com.finapp.identity.CredentialType;
import com.finapp.identity.CredentialVerifier;
import com.finapp.identity.DerivationParameters;
import com.finapp.identity.IdentityAuthentication;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcCredentialStore;
import com.finapp.identity.JdbcIdentityStore;
import com.finapp.identity.LockoutPolicy;
import com.finapp.identity.LoginIdentifier;
import com.finapp.identity.PasswordDeriver;
import com.finapp.identity.RawPassword;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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

/**
 * Lockout: it stops guessing, and it never becomes an oracle (`P1-TSK-011`).
 *
 * <h2>The property that is easy to get wrong</h2>
 *
 * <p>The instinct is that a locked account should be refused <em>without</em> paying for a
 * derivation - that is the CPU relief lockout appears to be for. It is an account-existence oracle:
 * attempt often enough against any identifier, and afterwards the locked one answers in a
 * millisecond while the unknown one still takes the full cost. So this suite asserts, by
 * <strong>counting derivations</strong>, that a locked identity costs exactly what every other
 * failure costs.
 *
 * <h2>{@code INV-CON-03}, three phases early</h2>
 *
 * <p><em>"Limits enforced non-atomically are limits that do not exist."</em> Catalogued at Phase 13,
 * enforced here because ADR-0032 makes verification expensive and names lockout as part of the same
 * design. Ten simulated instances, each with its own connection (the {@code P0-TST-009} convention),
 * must produce exactly ten counted failures - never fewer.
 */
@Tag("database")
@SpringBootTest
@DisplayName("authentication lockout (P1-TSK-011)")
class AuthenticationLockoutDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final DerivationParameters WEAK = new DerivationParameters(1024, 1, 1);
    private static final String PASSWORD = "correct horse battery staple";

    /** Small, so a test that must cross the threshold does not pay for ten real derivations. */
    private static final LockoutPolicy POLICY =
            new LockoutPolicy(3, Duration.ofMinutes(15), Duration.ofMinutes(15));

    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("the threshold locks, and the response never says so")
    void crossingTheThresholdLocks() throws SQLException {
        LoginIdentifier login = givenAnIdentityWithACredential();

        for (int attempt = 1; attempt < POLICY.threshold(); attempt++) {
            assertThat(authenticate(login, "wrong")).isEqualTo(AuthenticationService.Outcome.REFUSED);
            assertThat(lockedUntil(login)).as("not locked before the threshold").isNull();
        }

        assertThat(authenticate(login, "wrong")).isEqualTo(AuthenticationService.Outcome.REFUSED);
        assertThat(lockedUntil(login)).as("the threshold attempt locks").isNotNull();
        assertThat(failures(login)).isEqualTo(POLICY.threshold());
    }

    @Test
    @DisplayName("a CORRECT password is refused while locked, and does not clear the lock")
    void aCorrectPasswordIsStillRefusedWhileLocked() throws SQLException {
        // The point of a lock. One that a correct guess clears is not a lock - it is a signal that
        // the guess was right, which is the single thing the attacker is trying to learn.
        LoginIdentifier login = givenAnIdentityWithACredential();
        lockIt(login);

        assertThat(authenticate(login, PASSWORD))
                .as("the password is right and the account is locked; locked wins")
                .isEqualTo(AuthenticationService.Outcome.REFUSED);
        assertThat(lockedUntil(login))
                .as("and the lock survives it: a correct password must not be a way out")
                .isNotNull();
    }

    @Test
    @DisplayName("a locked identity costs exactly what every other failure costs (INV-IDN-07)")
    void aLockedIdentityIsNotCheaper() throws SQLException {
        LoginIdentifier locked = givenAnIdentityWithACredential();
        lockIt(locked);
        LoginIdentifier unknown = new LoginIdentifier(someLogin());

        CountingDeriver againstLocked = new CountingDeriver(new Argon2PasswordDeriver(WEAK));
        CountingDeriver againstUnknown = new CountingDeriver(new Argon2PasswordDeriver(WEAK));

        assertThat(authenticateWith(againstLocked, locked, PASSWORD))
                .isEqualTo(AuthenticationService.Outcome.REFUSED);
        assertThat(authenticateWith(againstUnknown, unknown, PASSWORD))
                .isEqualTo(AuthenticationService.Outcome.REFUSED);

        assertThat(againstLocked.verifications())
                .as("a locked account that skipped the derivation would answer in a millisecond"
                        + " while an unknown one took the full cost - an existence oracle")
                .isEqualTo(againstUnknown.verifications())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a success clears the counter, so a person who eventually remembers is not punished")
    void aSuccessClearsTheCounter() throws SQLException {
        LoginIdentifier login = givenAnIdentityWithACredential();

        assertThat(authenticate(login, "wrong")).isEqualTo(AuthenticationService.Outcome.REFUSED);
        assertThat(failures(login)).isEqualTo(1);

        assertThat(authenticate(login, PASSWORD))
                .isEqualTo(AuthenticationService.Outcome.AUTHENTICATED);
        assertThat(failures(login))
                .as("the row is gone: absence already means 'nothing counted', and two"
                        + " representations of one fact is how they come to disagree")
                .isZero();
    }

    @Test
    @DisplayName("an expired lock lets the identity authenticate again, with no operator involved")
    void anExpiredLockClearsItself() throws SQLException {
        LoginIdentifier login = givenAnIdentityWithACredential();
        lockIt(login);

        // Back-date by the SERVER's clock, because that is what the component compares against.
        // A client-computed timestamp here would test this JVM's clock, not the protocol.
        expireTheLock(login);

        assertThat(authenticate(login, PASSWORD))
                .as("self-healing: an operator unlock would turn a cheap attack into a"
                        + " support-desk denial of service")
                .isEqualTo(AuthenticationService.Outcome.AUTHENTICATED);
    }

    @Test
    @DisplayName("after a lock expires, ONE failure does not re-lock: the count starts again")
    void anExpiredLockResetsTheCount() throws SQLException {
        // The gap this suite had, and the critical defect it hid. `anExpiredLockClearsItself`
        // authenticates SUCCESSFULLY after the lock expires, and a success deletes the row - so it
        // never exercised the path where the next attempt is another FAILURE.
        //
        // With the reset guarded on `locked_until IS NULL` alone, that path incremented to
        // threshold + 1 and re-locked immediately. Since window_started_at always precedes
        // locked_until, an expired lock implies an expired window, so the guard blocked the reset
        // exactly when it was due: an account locked once was locked for ever, at one failure per
        // lock period. Permanent lockout - which is the attack the whole design says it avoids.
        LoginIdentifier login = givenAnIdentityWithACredential();
        lockIt(login);
        expireTheLock(login);

        assertThat(authenticate(login, "wrong"))
                .as("still the wrong password, so still refused")
                .isEqualTo(AuthenticationService.Outcome.REFUSED);

        assertThat(failures(login))
                .as("the count starts again rather than continuing from the locked run")
                .isEqualTo(1);
        assertThat(lockedUntil(login))
                .as("and one failure after an expired lock must not re-lock, or an attacker locks"
                        + " somebody out for ever at one attempt per lock period")
                .isNull();
    }

    @Test
    @DisplayName("a served lock ends the run even when the window is still live")
    void aServedLockEndsTheRunIndependentlyOfTheWindow() throws SQLException {
        // The shipped policy has window == lockFor, so "the lock expired" and "the window elapsed"
        // coincide and either condition alone appears to work. That coincidence is what let the
        // first version look correct. This policy breaks it: a one-minute lock inside a
        // sixty-minute window, which LockoutPolicy accepts and which a future tuning could ship.
        LockoutPolicy shortLock =
                new LockoutPolicy(3, Duration.ofMinutes(60), Duration.ofMinutes(1));
        LoginIdentifier login = givenAnIdentityWithACredential();

        for (int attempt = 0; attempt < shortLock.threshold(); attempt++) {
            recordOneFailureUnder(shortLock, login);
        }
        assertThat(lockedUntil(login)).as("precondition: locked").isNotNull();

        // The lock expires; the window has 59 minutes left.
        expireTheLock(login);

        recordOneFailureUnder(shortLock, login);

        assertThat(failures(login))
                .as("a served lock ends the run: making the reset depend on the window as well"
                        + " would re-lock this account on one failure, for ever")
                .isEqualTo(1);
        assertThat(lockedUntil(login)).isNull();
    }

    @Test
    @DisplayName("a live lock still accumulates, so waiting out the window is not a way around it")
    void aLiveLockDoesNotReset() throws SQLException {
        // The other half of the same condition, and it must not be lost to the fix above. While the
        // lock is LIVE the window elapsing must not reset anything, or an attacker waits out the
        // window instead of the lock.
        LoginIdentifier login = givenAnIdentityWithACredential();
        lockIt(login);
        expireTheWindowButNotTheLock(login);

        assertThat(authenticate(login, "wrong")).isEqualTo(AuthenticationService.Outcome.REFUSED);

        assertThat(failures(login))
                .as("still counting up, because the lock is live")
                .isEqualTo(POLICY.threshold() + 1);
        assertThat(lockedUntil(login)).as("and still locked").isNotNull();
    }

    @Test
    @DisplayName("an unknown login identifier is counted nowhere and creates no row")
    void anUnknownIdentifierIsNotCounted() throws SQLException {
        LoginIdentifier unknown = new LoginIdentifier(someLogin());

        assertThat(authenticate(unknown, PASSWORD)).isEqualTo(AuthenticationService.Outcome.REFUSED);

        assertThat(rowCountFor(unknown))
                .as("keying on the attempted string would build a caller-controlled,"
                        + " attacker-fillable table of things people typed")
                .isZero();
        // And the guard against this test passing because the query itself finds nothing: a
        // KNOWN identifier that has failed does produce a row, through the same query.
        LoginIdentifier known = givenAnIdentityWithACredential();
        assertThat(authenticate(known, "wrong")).isEqualTo(AuthenticationService.Outcome.REFUSED);
        assertThat(rowCountFor(known)).as("the query can see a row when there is one").isEqualTo(1);
    }

    @Test
    @DisplayName("ten instances failing concurrently produce exactly ten counted failures")
    void theCounterIsNotBypassableByConcurrency() throws Exception {
        // INV-CON-03, and the acceptance criterion. Each racer gets its own connection - the
        // P0-TST-009 convention - because a shared one would serialise them in the driver and the
        // test would pass without ever contending.
        LoginIdentifier login = givenAnIdentityWithACredential();

        int racers = 10;
        CountDownLatch ready = new CountDownLatch(racers);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int i = 0; i < racers; i++) {
                results.add(
                        pool.submit(
                                () -> {
                                    ready.countDown();
                                    go.await(30, TimeUnit.SECONDS);
                                    return recordOneFailureOnItsOwnConnection(login);
                                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> result : results) {
                result.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures(login))
                .as("a read-then-write would lose increments here: ten racers all read the same"
                        + " count and all write one more than it")
                .isEqualTo(racers);
    }

    @Test
    @DisplayName("the counter survives an instance restart")
    void theCounterIsDurable() throws SQLException {
        // Durable, not process-local (ADR-0024, transition risk R7). A component built fresh - a new
        // instance, in effect - sees what the previous one counted.
        LoginIdentifier login = givenAnIdentityWithACredential();
        assertThat(authenticate(login, "wrong")).isEqualTo(AuthenticationService.Outcome.REFUSED);

        AuthenticationThrottle restarted =
                new AuthenticationThrottle(POLICY, IDS, CLOCK, new JdbcAuditWriter());
        try (Connection fresh = transactional()) {
            AuthenticationThrottle.Lock lock = restarted.recordFailure(fresh, login);
            fresh.commit();
            assertThat(lock.consecutiveFailures())
                    .as("a process-local counter would start again at one")
                    .isEqualTo(2);
        }
    }

    @Test
    @DisplayName("crossing the threshold is audited exactly once, however many attempts follow")
    void theLockIsAuditedOnce() throws SQLException {
        LoginIdentifier login = givenAnIdentityWithACredential();
        for (int attempt = 0; attempt < POLICY.threshold() + 3; attempt++) {
            authenticate(login, "wrong");
        }

        assertThat(lockAuditRows(login))
                .as("repeating the record while the account stays locked buries the event that"
                        + " matters under copies of it")
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------

    private AuthenticationService.Outcome authenticate(LoginIdentifier login, String password) {
        return authenticateWith(new Argon2PasswordDeriver(WEAK), login, password);
    }

    @SuppressWarnings("try") // The Scope is used for its close side effect.
    private AuthenticationService.Outcome authenticateWith(
            PasswordDeriver deriver, LoginIdentifier login, String password) {
        try (CorrelationContext.Scope ignored =
                CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return serviceWith(deriver)
                    .authenticate(new AuthenticationRequest(login.value(), Sensitive.of(password)));
        }
    }

    private AuthenticationService serviceWith(PasswordDeriver deriver) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new AuthenticationService(
                new CredentialVerifier(
                        new JdbcIdentityStore(), new JdbcCredentialStore(), deriver, IDS, CLOCK),
                new AuthenticationThrottle(POLICY, IDS, CLOCK, new JdbcAuditWriter()),
                new IdentityAuthentication(IDS, CLOCK, new JdbcAuditWriter(), new JdbcOutboxWriter()),
                template,
                dataSource,
                new SimpleMeterRegistry());
    }

    /** One failure, on a connection of this racer's own — never the shared pool's. */
    @SuppressWarnings("try")
    private Object recordOneFailureOnItsOwnConnection(LoginIdentifier login) throws SQLException {
        try (CorrelationContext.Scope ignored =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                com.finapp.platform.security.SecurityContext.Scope actor =
                        com.finapp.platform.security.SecurityContext.enterSystem();
                Connection own = transactional()) {
            new AuthenticationThrottle(POLICY, IDS, CLOCK, new JdbcAuditWriter())
                    .recordFailure(own, login);
            own.commit();
            return null;
        }
    }

    @SuppressWarnings("try")
    private void recordOneFailureUnder(LockoutPolicy policy, LoginIdentifier login)
            throws SQLException {
        try (CorrelationContext.Scope ignored =
                        CorrelationContext.enter(
                                Correlation.startingWith(CorrelationId.generate(IDS)));
                com.finapp.platform.security.SecurityContext.Scope actor =
                        com.finapp.platform.security.SecurityContext.enterSystem();
                Connection own = transactional()) {
            new AuthenticationThrottle(policy, IDS, CLOCK, new JdbcAuditWriter())
                    .recordFailure(own, login);
            own.commit();
        }
    }

    private void lockIt(LoginIdentifier login) throws SQLException {
        for (int attempt = 0; attempt < POLICY.threshold(); attempt++) {
            authenticate(login, "wrong");
        }
        assertThat(lockedUntil(login)).as("precondition: it really is locked").isNotNull();
    }

    /**
     * Elapses the window while leaving the lock live.
     *
     * <p>Reachable only by moving {@code window_started_at} back, because the table's own
     * {@code CHECK} requires the lock to follow the window — which is also why an expired lock
     * always implies an expired window in production, and why the first reset condition was wrong.
     */
    private void expireTheWindowButNotTheLock(LoginIdentifier login) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "UPDATE identity.authentication_failure"
                        + " SET window_started_at = now() - interval '99 minutes'"
                        + " WHERE identity_id = (SELECT id FROM identity.identity"
                        + " WHERE login_identifier = ?)",
                    login.value());
        }
    }

    private void expireTheLock(LoginIdentifier login) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            execute(
                    app,
                    "UPDATE identity.authentication_failure SET locked_until = now() - interval"
                        + " '1 second', window_started_at = now() - interval '2 seconds'"
                        + " WHERE identity_id = (SELECT id FROM identity.identity"
                        + " WHERE login_identifier = ?)",
                    login.value());
        }
    }

    private Object lockedUntil(LoginIdentifier login) throws SQLException {
        return scalar(
                "SELECT locked_until FROM identity.authentication_failure"
                        + " WHERE identity_id = (SELECT id FROM identity.identity"
                        + " WHERE login_identifier = ?)",
                login.value());
    }

    private int failures(LoginIdentifier login) throws SQLException {
        Object value =
                scalar(
                        "SELECT failures FROM identity.authentication_failure"
                                + " WHERE identity_id = (SELECT id FROM identity.identity"
                                + " WHERE login_identifier = ?)",
                        login.value());
        return value == null ? 0 : (Integer) value;
    }

    private int lockAuditRows(LoginIdentifier login) throws SQLException {
        Object value =
                scalar(
                        "SELECT count(*) FROM platform.audit_record"
                                + " WHERE operation = 'identity.AuthenticationLocked'"
                                + " AND target_id = (SELECT id::text FROM identity.identity"
                                + " WHERE login_identifier = ?)",
                        login.value());
        return ((Number) value).intValue();
    }

    private int rowCountFor(LoginIdentifier login) throws SQLException {
        Object value =
                scalar(
                        "SELECT count(*) FROM identity.authentication_failure f"
                                + " JOIN identity.identity i ON i.id = f.identity_id"
                                + " WHERE i.login_identifier = ?",
                        login.value());
        return ((Number) value).intValue();
    }

    private Object scalar(String sql, Object argument) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(sql)) {
            select.setObject(1, argument);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? rows.getObject(1) : null;
            }
        }
    }

    private static Connection transactional() throws SQLException {
        Connection connection = DatabaseRoles.application();
        connection.setAutoCommit(false);
        return connection;
    }

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
        return login;
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
