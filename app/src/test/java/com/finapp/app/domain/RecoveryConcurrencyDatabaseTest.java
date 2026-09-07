package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Argon2PasswordDeriver;
import com.finapp.identity.ContactChannelService;
import com.finapp.identity.ContactChannelStore;
import com.finapp.identity.CredentialStore;
import com.finapp.identity.DerivationParameters;
import com.finapp.identity.EmailAddress;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcContactChannelStore;
import com.finapp.identity.JdbcCredentialStore;
import com.finapp.identity.JdbcRecoveryRequestStore;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.LoginIdentifier;
import com.finapp.identity.PasswordDeriver;
import com.finapp.identity.RawPassword;
import com.finapp.identity.RecoveryRequestId;
import com.finapp.identity.RecoveryService;
import com.finapp.identity.SessionStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.database.SimulatedInstance;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.security.Sensitive;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Recovery under concurrent instances (`P1-TSK-023`, ADR-0014, {@code INV-CON-01}).
 *
 * <h2>Why the completion gate added this</h2>
 *
 * <p>Both statements carry a concurrency claim in their own comments — <em>"ten concurrent
 * initiations produce one"</em> and <em>"ten instances presenting one token produce one
 * completion"</em> — and <strong>nothing exercised either</strong>. That is the {@code P1-TSK-020}
 * finding about {@code assign}, repeated: {@code CLAUDE.md}'s multi-instance rule is not conditional
 * on the operation being financial.
 *
 * <h2>What would go wrong is not untidiness</h2>
 *
 * <p><strong>Two live requests</strong> would mean two valid tokens on one account, which is exactly
 * the state {@code cancelLiveFor} exists to prevent: an attacker's token surviving alongside the
 * customer's. <strong>Two completions</strong> would mean two credentials inserted for one identity,
 * and the partial unique index would then decide which one the customer actually has — a password
 * reset whose outcome is a race.
 *
 * <p>Ten instances, ten connections — the {@code P0-TST-009} convention. Two instances sharing one
 * connection serialise themselves and prove nothing.
 */
@Tag("database")
@DisplayName("recovery under concurrent instances (P1-TSK-023)")
class RecoveryConcurrencyDatabaseTest {

    private static final int INSTANCES = 10;

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    private final ContactChannelStore<Connection> channels = new JdbcContactChannelStore();
    private final CredentialStore<Connection> credentials = new JdbcCredentialStore();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final AuditWriter<Connection> audit = new JdbcAuditWriter();
    private final OutboxWriter<Connection> outbox = new JdbcOutboxWriter();

    /** Weak parameters, the five sibling suites' convention: nothing here is about the derivation. */
    private final PasswordDeriver deriver =
            new Argon2PasswordDeriver(new DerivationParameters(1024, 1, 1));

    private final ContactChannelService channelService =
            new ContactChannelService(channels, IDS, CLOCK, RANDOMNESS, audit);
    private final RecoveryService recoveries =
            new RecoveryService(
                    new JdbcRecoveryRequestStore(IDS),
                    credentials,
                    sessions,
                    deriver,
                    IDS,
                    CLOCK,
                    RANDOMNESS,
                    audit,
                    outbox);

    // -----------------------------------------------------------------

    @Test
    @DisplayName("ten instances initiating recovery produce exactly one live request")
    void concurrentInitiationsProduceOneRequest() throws Exception {
        IdentityId identity = givenAnIdentityWithAVerifiedChannel();
        LoginIdentifier login = loginOf(identity);
        AtomicInteger issued = new AtomicInteger();

        raceOn(
                instance -> {
                    inTheFlowOf(
                            instance,
                            unitOfWork ->
                                    recoveries
                                            .initiate(unitOfWork, login)
                                            .ifPresent(started -> issued.incrementAndGet()));
                    return null;
                });

        // Asserting only the row count would pass against an implementation where all ten believe
        // they issued a token - and the customer would then have ten messages in their inbox, nine
        // of them naming a token that does not work. Assert the COORDINATION as well as the state,
        // the P1-TSK-008 finding.
        assertThat(issued.get())
                .as("exactly one instance may believe it issued a recovery token")
                .isEqualTo(1);
        assertThat(liveRequestsOf(identity))
                .as("two live requests means two valid tokens on one account, which is the state"
                        + " cancelLiveFor exists to prevent")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("ten instances presenting one token produce exactly one completion")
    void concurrentCompletionsProduceOneCompletion() throws Exception {
        IdentityId identity = givenAnIdentityWithAVerifiedChannel();
        Started started = givenARecoveryFor(identity);
        AtomicInteger completed = new AtomicInteger();

        raceOn(
                instance -> {
                    inTheFlowOf(
                            instance,
                            unitOfWork -> {
                                if (recoveries.complete(
                                        unitOfWork,
                                        started.id(),
                                        Sensitive.of(started.token()),
                                        new RawPassword(Sensitive.of("a-replacement-password")))) {
                                    completed.incrementAndGet();
                                }
                            });
                    return null;
                });

        // Two completions would insert two credentials for one identity, and the partial unique
        // index would then decide which one the customer actually has - a password reset whose
        // outcome is a race, and which nine of the ten callers were told had succeeded.
        assertThat(completed.get())
                .as("exactly one instance may believe it replaced the credential")
                .isEqualTo(1);
        assertThat(activeCredentialsOf(identity))
                .as("an identity must end with exactly one active credential")
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------

    private interface InstanceWork {
        Void run(SimulatedInstance instance) throws Exception;
    }

    private interface Work {
        void run(Connection unitOfWork) throws SQLException;
    }

    /**
     * Releases every instance together and lets the database do the blocking.
     *
     * <p>No barrier inside the statement: {@code P1-TSK-005} found that arranging the overlap that
     * way deadlocks, because the losers are blocked in their write and can never reach the barrier.
     * The contention needs no arranging.
     */
    private static void raceOn(InstanceWork work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(INSTANCES);
        CyclicBarrier start = new CyclicBarrier(INSTANCES);
        try {
            List<Callable<Void>> racers =
                    IntStream.range(0, INSTANCES)
                            .<Callable<Void>>mapToObj(
                                    i ->
                                            () -> {
                                                try (SimulatedInstance instance =
                                                        SimulatedInstance
                                                                .inAgreementWithTheServer()) {
                                                    start.await();
                                                    return work.run(instance);
                                                }
                                            })
                            .toList();
            for (Future<Void> outcome : pool.invokeAll(racers)) {
                outcome.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** One instance's own connection, inside its own correlation and security scope. */
    private static void inTheFlowOf(SimulatedInstance instance, Work work) throws SQLException {
        CorrelationContext.Scope correlation =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of(UUID.randomUUID().toString())));
        SecurityContext.Scope actor = SecurityContext.enterSystem();
        try {
            work.run(instance.connection());
            instance.commit();
        } catch (SQLException | RuntimeException failed) {
            instance.rollback();
            throw failed;
        } finally {
            actor.close();
            correlation.close();
        }
    }

    // -----------------------------------------------------------------

    private record Started(RecoveryRequestId id, String token) {}

    private Started givenARecoveryFor(IdentityId identity) throws SQLException {
        LoginIdentifier login = loginOf(identity);
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            CorrelationContext.Scope correlation =
                    CorrelationContext.enter(
                            Correlation.startingWith(
                                    CorrelationId.of(UUID.randomUUID().toString())));
            SecurityContext.Scope actor = SecurityContext.enterSystem();
            try {
                RecoveryService.Initiated started =
                        recoveries.initiate(app, login).orElseThrow();
                app.commit();
                return new Started(
                        started.request().id(), started.token().presentedValue().expose());
            } finally {
                actor.close();
                correlation.close();
            }
        }
    }

    private IdentityId givenAnIdentityWithAVerifiedChannel() throws SQLException {
        IdentityId identity = givenAnIdentity();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            CorrelationContext.Scope correlation =
                    CorrelationContext.enter(
                            Correlation.startingWith(
                                    CorrelationId.of(UUID.randomUUID().toString())));
            SecurityContext.Scope actor = SecurityContext.enterSystem();
            try {
                ContactChannelService.Added added =
                        channelService.add(
                                app,
                                identity,
                                new EmailAddress(
                                        "ada"
                                                + UUID.randomUUID().toString().replace("-", "")
                                                        .substring(0, 10)
                                                + "@example.com"));
                channelService.verify(app, added.challenge().presentedValue());
                app.commit();
            } finally {
                actor.close();
                correlation.close();
            }
        }
        return identity;
    }

    private static long liveRequestsOf(IdentityId identity) throws SQLException {
        return scalar(
                "SELECT count(*) FROM identity.recovery_request"
                        + " WHERE identity_id = ? AND status = 'INITIATED'",
                identity.value());
    }

    private static long activeCredentialsOf(IdentityId identity) throws SQLException {
        return scalar(
                "SELECT count(*) FROM identity.credential"
                        + " WHERE identity_id = ? AND status = 'ACTIVE'",
                identity.value());
    }

    private static long scalar(String sql, Object argument) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(sql)) {
            select.setObject(1, argument);
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static LoginIdentifier loginOf(IdentityId identity) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select =
                        app.prepareStatement(
                                "SELECT login_identifier FROM identity.identity WHERE id = ?")) {
            select.setObject(1, identity.value());
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return new LoginIdentifier(rows.getString(1));
            }
        }
    }

    private static IdentityId givenAnIdentity() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
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
                    "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        return IdentityId.of(identity);
    }

    private static void execute(Connection connection, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }
}
