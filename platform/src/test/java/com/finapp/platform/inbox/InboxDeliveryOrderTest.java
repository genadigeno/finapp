package com.finapp.platform.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The delivery assumptions in {@code EVENT_ARCHITECTURE.md}, made executable.
 *
 * <p>That document tells consumers to tolerate duplication, delay, reordering and replay, and
 * then says something sharper: <em>the inbox addresses duplication only, and a handler that
 * would be wrong seeing {@code TransferCompleted} before {@code TransferInitiated} is wrong
 * whether or not it deduplicates.</em> Until now that was prose. Prose is exactly what a future
 * reader will not believe about a component called a dedupe wrapper — the document itself says
 * so: "a dedupe wrapper is precisely the component people later assume solved ordering too."
 *
 * <p>So this class demonstrates three things rather than asserting them:
 *
 * <ol>
 *   <li>the inbox deduplicates regardless of the order messages arrive in;
 *   <li>an order-dependent handler is <strong>still wrong</strong> under the inbox, which is what
 *       makes the warning worth having;
 *   <li>an ordering key fixes it, which is what a consumer is supposed to do instead.
 * </ol>
 *
 * <p>The handlers here are test code, and that is the point: the platform does not and cannot
 * solve ordering, so the thing being pinned is the <em>consumer contract</em>. A test that only
 * exercised platform code could not express it.
 *
 * <p>Run as the application role, so the inbox's deliberately narrow grant — {@code SELECT},
 * {@code INSERT}, {@code DELETE}, and no {@code UPDATE} — is proven sufficient for real consumer
 * use rather than only inspected in the catalogue.
 */
@Tag("database")
@SuppressWarnings("try") // A correlation Scope is used for its close side effect.
class InboxDeliveryOrderTest {

    private static final String INBOX = "platform.inbox_message";
    private static final String PROJECTION = "platform.transfer_projection_probe";
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-01T12:00:00Z"), ZoneOffset.UTC);
    private static final Duration RETENTION = Duration.ofHours(24);
    private static final CorrelationId FLOW = CorrelationId.of("delivery-order-flow");

    /** The two events of one transfer, in the order they genuinely happened. */
    private static final int INITIATED = 1;

    private static final int COMPLETED = 2;

    private static Connection connection;

    @BeforeAll
    static void connect() throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                Statement statement = migrator.createStatement()) {
            statement.execute(
                    "CREATE TABLE IF NOT EXISTS " + PROJECTION
                            + " (aggregate_id UUID PRIMARY KEY, status TEXT NOT NULL, "
                            + "applied_sequence INTEGER NOT NULL)");
            // The projection is the consumer's own state, so it legitimately holds UPDATE - which
            // the inbox table itself does not. Two different tables, two different grants.
            statement.execute(
                    "GRANT SELECT, INSERT, UPDATE, DELETE ON " + PROJECTION + " TO finapp_app");
        }
        connection = DatabaseRoles.application();
        connection.setAutoCommit(false);
    }

    @AfterAll
    static void dropProbeAndDisconnect() throws SQLException {
        if (connection != null) {
            connection.close();
        }
        try (Connection migrator = DatabaseRoles.migrator();
                Statement statement = migrator.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + PROJECTION);
        }
    }

    @BeforeEach
    void clean() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + INBOX);
            statement.executeUpdate("DELETE FROM " + PROJECTION);
        }
        connection.commit();
    }

    // -----------------------------------------------------------------
    // Duplication, independent of order
    // -----------------------------------------------------------------

    @Test
    @DisplayName("duplicates are skipped whatever order they arrive in")
    void deduplicationDoesNotDependOnOrder() throws SQLException {
        // Delivered completed, initiated, completed, initiated - a redelivery of each, arriving
        // interleaved and backwards, which is what an at-least-once transport rebalancing mid
        // stream actually looks like.
        UUID transfer = UUID.randomUUID();
        AtomicInteger handled = new AtomicInteger();

        assertThat(deliver(transfer, COMPLETED, counting(handled))).isEqualTo(InboxConsumer.Outcome.PROCESSED);
        assertThat(deliver(transfer, INITIATED, counting(handled))).isEqualTo(InboxConsumer.Outcome.PROCESSED);
        assertThat(deliver(transfer, COMPLETED, counting(handled))).isEqualTo(InboxConsumer.Outcome.SKIPPED_DUPLICATE);
        assertThat(deliver(transfer, INITIATED, counting(handled))).isEqualTo(InboxConsumer.Outcome.SKIPPED_DUPLICATE);

        assertThat(handled)
                .as("two distinct messages, four deliveries, two handler runs")
                .hasValue(2);
    }

    // -----------------------------------------------------------------
    // Ordering: what the inbox does NOT do
    // -----------------------------------------------------------------

    @Test
    @DisplayName("an order-dependent handler is still wrong under the inbox, which is the warning's point")
    void deduplicationDoesNotRescueAnOrderDependentHandler() throws SQLException {
        // The claim EVENT_ARCHITECTURE.md makes and nothing demonstrated: deduplication and
        // ordering are different problems, and a component that solves the first does not touch
        // the second.
        //
        // The handler here is the one everybody writes first - take the event's status and store
        // it. Delivered completed-then-initiated, it ends up believing a finished transfer is
        // still in flight, and every downstream decision made from that projection is wrong.
        // Nothing failed; nothing retried; no duplicate occurred. The inbox did its job perfectly.
        UUID transfer = UUID.randomUUID();

        deliver(transfer, COMPLETED, lastWriteWins(transfer, COMPLETED));
        deliver(transfer, INITIATED, lastWriteWins(transfer, INITIATED));

        assertThat(statusOf(transfer))
                .as("the naive handler ends on whichever event arrived last, not the latest one")
                .isEqualTo("INITIATED");
        assertThat(appliedSequenceOf(transfer)).isEqualTo(INITIATED);
    }

    @Test
    @DisplayName("an ordering key makes the same deliveries produce the right answer")
    void anOrderingKeyFixesIt() throws SQLException {
        // The remedy the document prescribes, shown working on the identical delivery order. The
        // handler carries the event's sequence and refuses to apply anything it has already
        // moved past - so a late arrival is ignored rather than believed.
        UUID transfer = UUID.randomUUID();

        deliver(transfer, COMPLETED, orderIndependent(transfer, COMPLETED));
        deliver(transfer, INITIATED, orderIndependent(transfer, INITIATED));

        assertThat(statusOf(transfer))
                .as("a late TransferInitiated must not undo a TransferCompleted already applied")
                .isEqualTo("COMPLETED");
        assertThat(appliedSequenceOf(transfer)).isEqualTo(COMPLETED);
    }

    @Test
    @DisplayName("the ordering key is not order-dependent in disguise: in-order delivery works too")
    void theOrderingKeyWorksInOrderAsWell() throws SQLException {
        // The positive control. A handler that simply ignored every second message would pass the
        // test above and be useless.
        UUID transfer = UUID.randomUUID();

        deliver(transfer, INITIATED, orderIndependent(transfer, INITIATED));
        assertThat(statusOf(transfer)).isEqualTo("INITIATED");

        deliver(transfer, COMPLETED, orderIndependent(transfer, COMPLETED));
        assertThat(statusOf(transfer)).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("reordering and duplication together still leave the right answer")
    void reorderingAndDuplicationTogether() throws SQLException {
        // Both hazards at once, which is the realistic case: a consumer restarts mid-partition,
        // gets a replay of what it already saw, in the wrong order.
        UUID transfer = UUID.randomUUID();
        AtomicInteger handled = new AtomicInteger();

        deliver(transfer, COMPLETED, both(orderIndependent(transfer, COMPLETED), handled));
        deliver(transfer, COMPLETED, both(orderIndependent(transfer, COMPLETED), handled));
        deliver(transfer, INITIATED, both(orderIndependent(transfer, INITIATED), handled));
        deliver(transfer, INITIATED, both(orderIndependent(transfer, INITIATED), handled));

        assertThat(handled).as("one run per distinct message").hasValue(2);
        assertThat(statusOf(transfer)).isEqualTo("COMPLETED");
    }

    // -----------------------------------------------------------------
    // Replay, and why retention is a correctness bound
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a swept dedupe record admits the effect again, which is why retention is a bound")
    void aSweptDedupeRecordAdmitsTheEffectAgain() throws SQLException {
        // DATA_MIGRATIONS.md §9 says the retention window is a correctness bound rather than
        // housekeeping: "too long merely costs storage; too short costs money". This is that
        // sentence made executable.
        //
        // The record is deleted to stand in for a sweep that ran too early - which is exactly
        // what a retention shorter than the producer's redelivery window produces. The handler
        // then runs a second time for a message already applied, and nothing anywhere reports it.
        UUID transfer = UUID.randomUUID();
        AtomicInteger handled = new AtomicInteger();

        deliver(transfer, COMPLETED, counting(handled));
        assertThat(deliver(transfer, COMPLETED, counting(handled)))
                .as("while the record exists, the redelivery is skipped")
                .isEqualTo(InboxConsumer.Outcome.SKIPPED_DUPLICATE);
        assertThat(handled).hasValue(1);

        sweep(transfer, COMPLETED);

        assertThat(deliver(transfer, COMPLETED, counting(handled)))
                .as("with the record gone, the same message is handled as though it were new")
                .isEqualTo(InboxConsumer.Outcome.PROCESSED);
        assertThat(handled)
                .as("a second effect from one message: what too-short retention costs")
                .hasValue(2);
    }

    // -----------------------------------------------------------------
    // Handlers
    // -----------------------------------------------------------------

    /**
     * What everybody writes first: store whatever the latest delivery said.
     *
     * <p>Built <em>for</em> the message, closing over what it needs, which is how a real consumer
     * works - the caller has already deserialised the message and knows what it says.
     * {@code InboxConsumer.Handler} receives only the unit of work precisely because everything
     * else is the caller's to supply, and a test that smuggled the message in through ambient
     * state would be modelling something no consumer does.
     */
    private static InboxConsumer.Handler<Connection> lastWriteWins(UUID transfer, int sequence) {
        return unitOfWork ->
                upsert(
                        unitOfWork,
                        "INSERT INTO " + PROJECTION + " (aggregate_id, status, applied_sequence) "
                                + "VALUES (?, ?, ?) ON CONFLICT (aggregate_id) DO UPDATE "
                                + "SET status = EXCLUDED.status, "
                                + "applied_sequence = EXCLUDED.applied_sequence",
                        transfer,
                        sequence);
    }

    /** Carries the event's sequence and refuses to move backwards. */
    private static InboxConsumer.Handler<Connection> orderIndependent(UUID transfer, int sequence) {
        return unitOfWork ->
                upsert(
                        unitOfWork,
                        "INSERT INTO " + PROJECTION + " (aggregate_id, status, applied_sequence) "
                                + "VALUES (?, ?, ?) ON CONFLICT (aggregate_id) DO UPDATE "
                                + "SET status = EXCLUDED.status, "
                                + "applied_sequence = EXCLUDED.applied_sequence "
                                + "WHERE " + PROJECTION
                                + ".applied_sequence < EXCLUDED.applied_sequence",
                        transfer,
                        sequence);
    }

    private static InboxConsumer.Handler<Connection> counting(AtomicInteger handled) {
        return unitOfWork -> handled.incrementAndGet();
    }

    private static InboxConsumer.Handler<Connection> both(
            InboxConsumer.Handler<Connection> handler, AtomicInteger handled) {
        return unitOfWork -> {
            handled.incrementAndGet();
            handler.handle(unitOfWork);
        };
    }

    // -----------------------------------------------------------------
    // Fixture
    // -----------------------------------------------------------------

    private static InboxConsumer.Outcome deliver(
            UUID transfer, int sequence, InboxConsumer.Handler<Connection> handler)
            throws SQLException {
        try (CorrelationContext.Scope ignored =
                CorrelationContext.enter(Correlation.startingWith(FLOW))) {
            InboxConsumer.Outcome outcome =
                    new InboxConsumer<Connection>(new JdbcInboxRecordStore(), CLOCK, RETENTION)
                            .consume(connection, keyFor(transfer, sequence), typeOf(sequence), handler);
            connection.commit();
            return outcome;
        }
    }

    /**
     * Deletes the dedupe record, standing in for a retention sweep that ran too early.
     *
     * <p>Deleted rather than waited out: the record's expiry is a day away, and the point is what
     * happens when it is gone, not how long that takes.
     */
    private static void sweep(UUID transfer, int sequence) throws SQLException {
        InboxKey key = keyFor(transfer, sequence);
        try (PreparedStatement delete =
                connection.prepareStatement(
                        "DELETE FROM " + INBOX + " WHERE consumer = ? AND dedupe_key = ?")) {
            delete.setString(1, key.consumer());
            delete.setString(2, key.dedupeKey());
            delete.executeUpdate();
        }
        connection.commit();
    }

    private static InboxKey keyFor(UUID transfer, int sequence) {
        // The event id in production; here a stable value per (transfer, event), which is what an
        // event id is.
        return new InboxKey("transfer-projector", transfer + ":" + sequence);
    }

    private static String typeOf(int sequence) {
        return sequence == INITIATED ? "transfers.TransferInitiated" : "transfers.TransferCompleted";
    }

    private static String statusFor(int sequence) {
        return sequence == INITIATED ? "INITIATED" : "COMPLETED";
    }

    private static void upsert(Connection unitOfWork, String sql, UUID transfer, int sequence) {
        try (PreparedStatement statement = unitOfWork.prepareStatement(sql)) {
            statement.setObject(1, transfer);
            statement.setString(2, statusFor(sequence));
            statement.setInt(3, sequence);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("the probe projection could not be written", e);
        }
    }

    private static String statusOf(UUID transfer) throws SQLException {
        return column(transfer, "status");
    }

    private static int appliedSequenceOf(UUID transfer) throws SQLException {
        return Integer.parseInt(column(transfer, "applied_sequence"));
    }

    private static String column(UUID transfer, String name) throws SQLException {
        try (PreparedStatement select =
                connection.prepareStatement(
                        "SELECT " + name + " FROM " + PROJECTION + " WHERE aggregate_id = ?")) {
            select.setObject(1, transfer);
            try (ResultSet rows = select.executeQuery()) {
                assertThat(rows.next()).as("the projection must have a row").isTrue();
                return rows.getString(1);
            }
        } finally {
            connection.commit();
        }
    }



}
