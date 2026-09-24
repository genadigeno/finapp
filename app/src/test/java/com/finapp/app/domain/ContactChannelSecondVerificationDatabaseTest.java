package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.identity.ContactChannel;
import com.finapp.identity.ContactChannelKind;
import com.finapp.identity.ContactChannelService;
import com.finapp.identity.ContactChannelStore;
import com.finapp.identity.EmailAddress;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcContactChannelStore;
import com.finapp.identity.VerifiedChannelAlreadyExistsException;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.platform.testing.database.SimulatedInstance;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
 * A second verified contact channel is refused, and the refusal writes nothing (`X-TSK-004`,
 * {@code INV-IDN-06}).
 *
 * <h2>What this replaced</h2>
 *
 * <p>Verifying a channel for an identity that already had one verified broke {@code V011}'s
 * one-verified-per-identity-and-kind index, and the store reported the database's answer as its own
 * failure: an {@code IdentityStorageException}, which the boundary renders as
 * {@code 500 api.InternalError}. Found by the Phase 6 → 7 transition's audit.
 *
 * <h2>Three tests, because "refused and nothing written" is three claims</h2>
 *
 * <p>That the refusal is a domain answer and leaves both channels as they were; that it leaves the
 * caller's transaction usable, which is the savepoint's whole job and which the first test cannot
 * see; and that ten instances racing produce one verified channel. Each was demonstrated against a
 * different mutation ({@code MUTATION_TESTING.md}), so each says which it holds.
 */
@Tag("database")
@DisplayName("a second verified contact channel is refused (X-TSK-004, INV-IDN-06)")
class ContactChannelSecondVerificationDatabaseTest {

    /** Ten instances, ten connections - the {@code P0-TST-009} convention. */
    private static final int INSTANCES = 10;

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();

    private final ContactChannelStore<Connection> channels = new JdbcContactChannelStore();
    private final AuditWriter<Connection> audit = new JdbcAuditWriter();
    private final ContactChannelService channelService =
            new ContactChannelService(channels, IDS, CLOCK, RANDOMNESS, audit);

    // -----------------------------------------------------------------

    @Test
    @DisplayName("verifying a second channel is refused, and writes nothing")
    void aSecondVerificationIsRefusedAndWritesNothing() throws Exception {
        IdentityId identity = givenAnIdentity();
        ContactChannel first = givenAVerifiedChannel(identity);
        ContactChannelService.Added second =
                inAFlow(unitOfWork -> channelService.add(unitOfWork, identity, anAddress()));

        // Everything the refusal could write, taken whole. The service holds no outbox writer - an
        // unverified channel is not a fact worth announcing, and neither is a refused one - so its
        // channel rows and its audit records are all it can write.
        List<List<Object>> channelsBefore = channelRowsOf(identity);
        List<List<Object>> auditBefore = auditRowsAbout(first.id().value(), second.channel().id().value());

        assertThatThrownBy(
                        () ->
                                inAFlow(
                                        unitOfWork ->
                                                channelService.verify(
                                                        unitOfWork,
                                                        second.challenge().presentedValue())))
                .as("a second verified channel must be refused as a domain answer; it was an"
                        + " IdentityStorageException, which the boundary renders as a 500")
                .isInstanceOf(VerifiedChannelAlreadyExistsException.class);

        // Row for row and column for column: the first channel still verified when it was, the
        // second still pending with the SAME challenge. A refusal that spent the challenge, or
        // re-stamped the first channel's verified_at, would pass a count.
        assertThat(channelRowsOf(identity))
                .as("the refusal must write nothing to either channel")
                .isEqualTo(channelsBefore);
        assertThat(auditRowsAbout(first.id().value(), second.channel().id().value()))
                .as("a refused verification is not a verification, and nothing is audited")
                .isEqualTo(auditBefore);

        // INV-IDN-06, which is what refusing is FOR: recovery still reaches the address proven
        // first, and not the one a second mailbox's holder tried to put in its place.
        Optional<ContactChannel> recoveryReaches =
                inAFlow(
                        unitOfWork ->
                                channels.findVerified(unitOfWork, identity, ContactChannelKind.EMAIL));
        assertThat(recoveryReaches)
                .map(ContactChannel::id)
                .as("recovery must still reach the channel verified first")
                .contains(first.id());
    }

    @Test
    @DisplayName("the refusal leaves the caller's transaction usable, so work beside it commits")
    void theRefusalLeavesTheTransactionUsable() throws Exception {
        IdentityId identity = givenAnIdentity();
        givenAVerifiedChannel(identity);
        ContactChannelService.Added second =
                inAFlow(unitOfWork -> channelService.add(unitOfWork, identity, anAddress()));

        // THIS IS THE SAVEPOINT'S TEST, and the one above cannot stand in for it. A unique
        // violation aborts a PostgreSQL transaction, so a refusal thrown WITHOUT rolling back to the
        // savepoint leaves every later statement failing with 25P02 - and the test above still
        // passes, because it abandons its transaction anyway. What the savepoint buys is that the
        // refusal is an answer: work done beside it in the same transaction survives. Here that work
        // is a third channel added first; in production it is whatever shares the caller's
        // transaction, a notifier or an audit record.
        ContactChannelService.Added beside =
                inAFlow(
                        unitOfWork -> {
                            ContactChannelService.Added third =
                                    channelService.add(unitOfWork, identity, anAddress());
                            assertThatThrownBy(
                                            () ->
                                                    channelService.verify(
                                                            unitOfWork,
                                                            second.challenge().presentedValue()))
                                    .isInstanceOf(VerifiedChannelAlreadyExistsException.class);

                            assertThat(
                                            channels.findOwned(
                                                    unitOfWork, third.channel().id(), identity))
                                    .as("the transaction must still answer after the refusal")
                                    .isPresent();
                            return third;
                        });

        assertThat(channelIdsOf(identity))
                .as("the work beside the refusal must have committed")
                .contains(beside.channel().id().value());
    }

    @Test
    @DisplayName("ten instances verifying ten channels of one identity verify exactly one")
    void tenConcurrentVerificationsVerifyExactlyOne() throws Exception {
        IdentityId identity = givenAnIdentity();
        List<ContactChannelService.Added> pending = new ArrayList<>();
        for (int i = 0; i < INSTANCES; i++) {
            pending.add(
                    inAFlow(unitOfWork -> channelService.add(unitOfWork, identity, anAddress())));
        }
        AtomicInteger verified = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();

        raceOn(
                (instance, index) -> {
                    try {
                        if (channelService
                                .verify(
                                        instance.connection(),
                                        pending.get(index).challenge().presentedValue())
                                .isPresent()) {
                            verified.incrementAndGet();
                        }
                        instance.commit();
                    } catch (VerifiedChannelAlreadyExistsException expected) {
                        refused.incrementAndGet();
                        instance.rollback();
                    }
                });

        // The index is the arbiter, and nothing else could be: no instance sees another's
        // uncommitted verification, so every one of them would pass a check made first. The losers
        // wait on the winner's index entry and are refused when it commits. The COORDINATION is
        // asserted as well as the state, the P1-TSK-008 finding: nine instances believing they had
        // verified, over a table holding one, would be nine people told an address works.
        assertThat(verified.get()).as("exactly one instance may verify").isEqualTo(1);
        assertThat(refused.get())
                .as("every other instance must be refused as a domain answer, never a 500")
                .isEqualTo(INSTANCES - 1);
        assertThat(verifiedChannelsOf(identity))
                .as("an identity must end with exactly one verified channel of a kind")
                .isEqualTo(1);
        assertThat(liveChallengesOf(identity))
                .as("each refused verification must leave its challenge where it was")
                .isEqualTo(INSTANCES - 1);
        assertThat(verificationsAuditedFor(identity))
                .as("one verification happened, so one is audited")
                .isEqualTo(1);
    }

    // -----------------------------------------------------------------
    // Fixtures

    private ContactChannel givenAVerifiedChannel(IdentityId identity) throws SQLException {
        ContactChannelService.Added added =
                inAFlow(unitOfWork -> channelService.add(unitOfWork, identity, anAddress()));
        return inAFlow(
                        unitOfWork ->
                                channelService.verify(
                                        unitOfWork, added.challenge().presentedValue()))
                .orElseThrow();
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

    private static EmailAddress anAddress() {
        return new EmailAddress(
                "ada" + UUID.randomUUID().toString().replace("-", "").substring(0, 10)
                        + "@example.com");
    }

    // -----------------------------------------------------------------
    // What is in the database

    private static List<List<Object>> channelRowsOf(IdentityId identity) throws SQLException {
        return rows(
                "SELECT id, kind, address, verification_token_hash, verification_expires_at,"
                        + " verified_at, added_at FROM identity.contact_channel"
                        + " WHERE identity_id = ? ORDER BY id",
                identity.value());
    }

    private static List<List<Object>> auditRowsAbout(UUID first, UUID second) throws SQLException {
        return rows(
                "SELECT audit_id, operation, target_id, outcome, change_summary"
                        + " FROM platform.audit_record"
                        + " WHERE target_type = 'ContactChannel' AND target_id IN (?, ?)"
                        + " ORDER BY audit_id",
                first.toString(),
                second.toString());
    }

    private static List<Object> channelIdsOf(IdentityId identity) throws SQLException {
        return rows("SELECT id FROM identity.contact_channel WHERE identity_id = ?", identity.value())
                .stream()
                .map(row -> row.get(0))
                .toList();
    }

    private static long verifiedChannelsOf(IdentityId identity) throws SQLException {
        return count(
                "SELECT count(*) FROM identity.contact_channel"
                        + " WHERE identity_id = ? AND verified_at IS NOT NULL",
                identity.value());
    }

    private static long liveChallengesOf(IdentityId identity) throws SQLException {
        return count(
                "SELECT count(*) FROM identity.contact_channel"
                        + " WHERE identity_id = ? AND verification_token_hash IS NOT NULL",
                identity.value());
    }

    /** The verification audit names the identity, never the address (ContactChannelService). */
    private static long verificationsAuditedFor(IdentityId identity) throws SQLException {
        return count(
                "SELECT count(*) FROM platform.audit_record"
                        + " WHERE operation = 'identity.ContactChannelVerified'"
                        + " AND change_summary = ?",
                "identity=" + identity);
    }

    private static List<List<Object>> rows(String sql, Object... arguments) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                select.setObject(i + 1, arguments[i]);
            }
            List<List<Object>> rows = new ArrayList<>();
            try (ResultSet result = select.executeQuery()) {
                int columns = result.getMetaData().getColumnCount();
                while (result.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int column = 1; column <= columns; column++) {
                        row.add(result.getObject(column));
                    }
                    rows.add(row);
                }
            }
            return rows;
        }
    }

    private static long count(String sql, Object argument) throws SQLException {
        return ((Number) rows(sql, argument).get(0).get(0)).longValue();
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

    // -----------------------------------------------------------------
    // Flows and instances

    private interface Work<T> {
        T run(Connection unitOfWork) throws SQLException;
    }

    private interface Racer {
        void run(SimulatedInstance instance, int index) throws Exception;
    }

    /** One transaction, as the platform, inside a correlation - what a channel operation needs. */
    private static <T> T inAFlow(Work<T> work) throws SQLException {
        CorrelationContext.Scope correlation =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of(UUID.randomUUID().toString())));
        SecurityContext.Scope actor = SecurityContext.enterSystem();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            try {
                T outcome = work.run(app);
                app.commit();
                return outcome;
            } catch (SQLException | RuntimeException failed) {
                app.rollback();
                throw failed;
            }
        } finally {
            actor.close();
            correlation.close();
        }
    }

    /**
     * Releases every instance together and lets the database do the blocking.
     *
     * <p>No barrier inside the statement: {@code P1-TSK-005} found that arranging the overlap that way
     * deadlocks, because the losers are blocked in their write and can never reach the barrier.
     */
    private static void raceOn(Racer racer) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(INSTANCES);
        CyclicBarrier start = new CyclicBarrier(INSTANCES);
        try {
            List<Callable<Void>> racers =
                    IntStream.range(0, INSTANCES)
                            .<Callable<Void>>mapToObj(
                                    index ->
                                            () -> {
                                                try (SimulatedInstance instance =
                                                        SimulatedInstance
                                                                .inAgreementWithTheServer()) {
                                                    start.await();
                                                    asThePlatform(() -> racer.run(instance, index));
                                                    return null;
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

    private interface Action {
        void run() throws Exception;
    }

    private static void asThePlatform(Action action) throws Exception {
        CorrelationContext.Scope correlation =
                CorrelationContext.enter(
                        Correlation.startingWith(CorrelationId.of(UUID.randomUUID().toString())));
        SecurityContext.Scope actor = SecurityContext.enterSystem();
        try {
            action.run();
        } finally {
            actor.close();
            correlation.close();
        }
    }
}
