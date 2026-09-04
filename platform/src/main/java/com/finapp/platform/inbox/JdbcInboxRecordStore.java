package com.finapp.platform.inbox;

import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Plain-JDBC storage for inbox records.
 *
 * <p>Explicit SQL, which ADR-0033 makes the platform-wide decision. This adapter is deliberately
 * small; {@link InboxConsumer} depends on
 * {@link InboxRecordStore}, not on this class.
 *
 * <p><strong>Isolation this relies on.</strong> PostgreSQL's default {@code READ COMMITTED}. A
 * second insert on a key another transaction holds <em>blocks</em> until that transaction ends,
 * and only then reports a unique violation. That is the behaviour the whole design rests on: it
 * is the database, not this code, that arbitrates between two instances handed the same
 * redelivery at the same instant.
 */
public final class JdbcInboxRecordStore implements InboxRecordStore<Connection> {

    private static final String TABLE = "platform.inbox_message";

    /** PostgreSQL SQLStates. Locale-independent, unlike the messages. */
    private static final String UNIQUE_VIOLATION = "23505";

    private static final String LOCK_NOT_AVAILABLE = "55P03";

    /**
     * How long to wait for a competing delivery before giving up on this one.
     *
     * <p>Deliberately shorter than the idempotency store's equivalent, and for a different
     * reason. There, a caller is waiting for a response and being told "unknown" is a poor
     * outcome, so waiting a few seconds is worth it. Here nobody is waiting: giving up costs one
     * redelivery, which the broker was going to perform anyway. Holding a connection open to win
     * a race we do not need to win is how a consumer pool is exhausted during exactly the
     * traffic spike that produced the duplicates.
     */
    private final Duration claimWait;

    /** Uses the default bounded wait. */
    public JdbcInboxRecordStore() {
        this(Duration.ofMillis(500));
    }

    public JdbcInboxRecordStore(Duration claimWait) {
        Objects.requireNonNull(claimWait, "claimWait must not be null");
        if (claimWait.isNegative() || claimWait.isZero()) {
            throw new IllegalArgumentException("claimWait must be positive but was " + claimWait);
        }
        this.claimWait = claimWait;
    }

    @Override
    public RecordOutcome record(
            Connection connection,
            InboxKey key,
            String messageType,
            CorrelationId correlationId,
            Instant processedAt,
            Duration retention) {

        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(messageType, "messageType must not be null");
        Objects.requireNonNull(correlationId, "correlationId must not be null");
        Objects.requireNonNull(processedAt, "processedAt must not be null");
        Objects.requireNonNull(retention, "retention must not be null");
        if (messageType.isBlank()) {
            throw new IllegalArgumentException("messageType must not be blank");
        }
        if (retention.isNegative() || retention.isZero()) {
            // A record that expires the moment it is written deduplicates nothing, and would do
            // so quietly. INV-IDEM-04 would then hold only for redeliveries that happened to
            // arrive before the sweep.
            throw new IllegalArgumentException("retention must be positive but was " + retention);
        }

        requireTransaction(connection);

        // A savepoint, because in PostgreSQL a failed statement poisons the whole transaction.
        // Without it, losing the race would abort the caller's transaction, and a duplicate
        // delivery - an entirely normal event - would take the consumer down with it.
        Savepoint beforeRecord = savepoint(connection);
        // Bounded, and confined to this statement: SET LOCAL would otherwise stay in force for
        // the rest of the caller's transaction, so the handler's own writes would inherit a
        // timeout chosen for the dedupe insert and could fail spuriously.
        setLockTimeout(connection, claimWait.toMillis() + "ms");
        String sql =
                "INSERT INTO " + TABLE + " (consumer, dedupe_key, message_type, correlation_id, "
                        + "processed_at, expires_at) "
                        // Integer milliseconds rather than fractional seconds: INV-MON-01's rule
                        // forbids the double, and a floating-point duration deciding when a
                        // dedupe record disappears is the same category of mistake as
                        // floating-point money.
                        //
                        // now() is the SERVER's clock, deliberately. processed_at beside it is
                        // the application's, from one injected Clock; the expiry is a
                        // coordination boundary with a sweeper on another instance, and ADR-0014
                        // requires those to use the one clock every instance shares.
                        + "VALUES (?, ?, ?, ?, ?, now() + (? * INTERVAL '1 millisecond'))";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, key.consumer());
            insert.setString(2, key.dedupeKey());
            insert.setString(3, messageType);
            insert.setString(4, correlationId.value());
            insert.setTimestamp(5, Timestamp.from(processedAt));
            insert.setLong(6, retention.toMillis());
            insert.executeUpdate();
            release(connection, beforeRecord);
            return RecordOutcome.RECORDED;
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                rollbackTo(connection, beforeRecord);
                return RecordOutcome.ALREADY_PROCESSED;
            }
            if (LOCK_NOT_AVAILABLE.equals(e.getSQLState())) {
                // Another instance holds the key in an open transaction. It may still commit or
                // roll back, so the outcome is unknown rather than merely elsewhere.
                rollbackTo(connection, beforeRecord);
                return RecordOutcome.CONTENDED;
            }
            throw new InboxStorageException("Could not record " + key, e);
        } finally {
            restoreLockTimeout(connection);
        }
    }

    /**
     * Refuses a connection in auto-commit mode.
     *
     * <p>The one guarantee this class provides is that the dedupe record and the side effect
     * commit together. On an auto-commit connection the record commits on its own, immediately —
     * so a handler that then fails leaves the message recorded as processed and its effect
     * absent, which is a silently lost message and precisely what {@code INV-IDEM-04} is
     * protecting against.
     *
     * <p>Checked explicitly rather than left to the savepoint call, which also fails in
     * auto-commit mode but reports "cannot establish a savepoint" — an error about a mechanism,
     * not about the mistake. This was found by calling the store from a test that had not opened
     * a transaction, which is exactly how a caller will meet it.
     */
    private static void requireTransaction(Connection connection) {
        try {
            if (connection.getAutoCommit()) {
                throw new InboxStorageException(
                        "The inbox record must commit with the handler's effect, so it needs the "
                                + "caller's transaction: this connection is in auto-commit mode, "
                                + "which would commit the dedupe record on its own and lose the "
                                + "message if the handler then failed (INV-IDEM-04)",
                        null);
            }
        } catch (SQLException e) {
            throw new InboxStorageException("Could not determine the connection's commit mode", e);
        }
    }

    private static void setLockTimeout(Connection connection, String value) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = '" + value + "'");
        } catch (SQLException e) {
            throw new InboxStorageException("Could not bound the dedupe wait", e);
        }
    }

    private static void restoreLockTimeout(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = DEFAULT");
        } catch (SQLException e) {
            // If this cannot run the transaction is already failing; masking that with a second
            // exception would hide the real one.
            System.getLogger(JdbcInboxRecordStore.class.getName())
                    .log(System.Logger.Level.DEBUG, "Could not restore lock_timeout", e);
        }
    }

    private static Savepoint savepoint(Connection connection) {
        try {
            return connection.setSavepoint("before_inbox_record");
        } catch (SQLException e) {
            throw new InboxStorageException("Could not create a savepoint for the dedupe insert", e);
        }
    }

    private static void release(Connection connection, Savepoint savepoint) {
        try {
            connection.releaseSavepoint(savepoint);
        } catch (SQLException e) {
            throw new InboxStorageException("Could not release the dedupe savepoint", e);
        }
    }

    private static void rollbackTo(Connection connection, Savepoint savepoint) {
        try {
            connection.rollback(savepoint);
        } catch (SQLException e) {
            throw new InboxStorageException("Could not roll back to the dedupe savepoint", e);
        }
    }
}
