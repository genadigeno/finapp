package com.finapp.platform.idempotency;

import com.finapp.platform.correlation.CorrelationId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Plain-JDBC storage for idempotency claims.
 *
 * <p>Plain JDBC because no data-access mechanism has been chosen yet (unresolved question 12).
 * This adapter is deliberately small and replaceable; {@link IdempotentExecutor} depends on
 * {@link IdempotencyRecordStore}, not on this class.
 *
 * <p><strong>Isolation this relies on.</strong> PostgreSQL's default {@code READ COMMITTED}. Two
 * behaviours matter and both are properties of the engine rather than of this code: a second
 * insert on a claimed key <em>blocks</em> until the first transaction ends and only then reports
 * a unique violation, and a statement issued afterwards sees what that transaction committed. A
 * stricter isolation level would turn the loser's outcome into a serialisation failure instead,
 * which is a different error the caller would have to handle — worth knowing before anyone
 * raises it globally.
 */
public final class JdbcIdempotencyRecordStore implements IdempotencyRecordStore<Connection> {

    private static final String TABLE = "platform.idempotency_record";

    /** PostgreSQL SQLStates. Locale-independent, unlike the messages. */
    private static final String UNIQUE_VIOLATION = "23505";

    private static final String LOCK_NOT_AVAILABLE = "55P03";

    /**
     * How long to wait for a competing claim before giving up on the key.
     *
     * <p>ADR-0004 requires a bounded wait. Without one, a duplicate request blocks for as long
     * as the first command takes: on a hot key with a retrying client that is how a connection
     * pool is exhausted, which is the same failure this design rejects waiting on an
     * {@code IN_PROGRESS} record to avoid.
     *
     * <p>The value is a trade in both directions. Too short and a perfectly normal short
     * command's duplicate is told "unknown, retry" when it could have waited a moment and
     * replayed. Too long and the pool-exhaustion risk returns. A few seconds is longer than any
     * command holding a claim should take, and short enough that a caller is never parked.
     */
    private final Duration claimWait;

    /** Uses the default bounded wait. */
    public JdbcIdempotencyRecordStore() {
        this(Duration.ofSeconds(3));
    }

    public JdbcIdempotencyRecordStore(Duration claimWait) {
        Objects.requireNonNull(claimWait, "claimWait must not be null");
        if (claimWait.isNegative() || claimWait.isZero()) {
            throw new IllegalArgumentException("claimWait must be positive but was " + claimWait);
        }
        this.claimWait = claimWait;
    }

    @Override
    public ClaimOutcome claim(
            Connection connection,
            IdempotencyKey key,
            RequestFingerprint fingerprint,
            CorrelationId correlationId,
            Instant now,
            Instant expiresAt) {

        // A savepoint, because in PostgreSQL a failed statement poisons the whole transaction.
        // Without it, losing the race would abort the caller's transaction and take the reader
        // that follows down with it - so the loser could never read the winner's response, and
        // a duplicate request would fail instead of replaying.
        Savepoint beforeClaim = savepoint(connection);
        // Bounded, and confined to this statement. SET LOCAL would otherwise stay in force for
        // the rest of the caller's transaction, so the command's own writes would inherit a
        // timeout chosen for the claim and could fail spuriously.
        setLockTimeout(connection, claimWait.toMillis() + "ms");
        String sql =
                "INSERT INTO " + TABLE + " (scope, idempotency_key, request_fingerprint, "
                        + "fingerprint_algorithm, state, correlation_id, created_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, key.scope());
            insert.setString(2, key.key());
            insert.setBytes(3, fingerprint.digest());
            insert.setString(4, fingerprint.algorithm());
            insert.setString(5, IdempotencyState.IN_PROGRESS.name());
            insert.setString(6, correlationId.value());
            insert.setTimestamp(7, Timestamp.from(now));
            insert.setTimestamp(8, Timestamp.from(expiresAt));
            insert.executeUpdate();
            release(connection, beforeClaim);
            return ClaimOutcome.CLAIMED;
        } catch (SQLException e) {
            if (UNIQUE_VIOLATION.equals(e.getSQLState())) {
                rollbackTo(connection, beforeClaim);
                return ClaimOutcome.ALREADY_CLAIMED;
            }
            if (LOCK_NOT_AVAILABLE.equals(e.getSQLState())) {
                // Someone holds the key in an open transaction. There is nothing committed to
                // read - they may still commit or roll back - so the outcome is genuinely
                // unknown rather than merely elsewhere.
                rollbackTo(connection, beforeClaim);
                return ClaimOutcome.CONTENDED;
            }
            throw new IdempotencyStorageException("Could not claim " + key, e);
        } finally {
            restoreLockTimeout(connection);
        }
    }

    private static void setLockTimeout(Connection connection, String value) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = '" + value + "'");
        } catch (SQLException e) {
            throw new IdempotencyStorageException("Could not bound the claim wait", e);
        }
    }

    private static void restoreLockTimeout(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL lock_timeout = DEFAULT");
        } catch (SQLException e) {
            // If this cannot run the transaction is already failing; masking that with a second
            // exception would hide the real one.
            System.getLogger(JdbcIdempotencyRecordStore.class.getName())
                    .log(System.Logger.Level.DEBUG, "Could not restore lock_timeout", e);
        }
    }

    @Override
    public Optional<IdempotencyRecord> find(Connection connection, IdempotencyKey key) {
        String sql =
                "SELECT request_fingerprint, fingerprint_algorithm, state, response_body, "
                        + "response_media_type, correlation_id, created_at, expires_at "
                        + "FROM " + TABLE + " WHERE scope = ? AND idempotency_key = ?";
        try (PreparedStatement select = connection.prepareStatement(sql)) {
            select.setString(1, key.scope());
            select.setString(2, key.key());
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                byte[] body = rows.getBytes("response_body");
                String mediaType = rows.getString("response_media_type");
                return Optional.of(
                        new IdempotencyRecord(
                                key,
                                RequestFingerprint.ofStored(
                                        rows.getBytes("request_fingerprint"),
                                        rows.getString("fingerprint_algorithm")),
                                IdempotencyState.valueOf(rows.getString("state")),
                                body == null ? StoredResponse.empty() : StoredResponse.of(body, mediaType),
                                CorrelationId.of(rows.getString("correlation_id")),
                                rows.getTimestamp("created_at").toInstant(),
                                rows.getTimestamp("expires_at").toInstant()));
            }
        } catch (SQLException e) {
            throw new IdempotencyStorageException("Could not read " + key, e);
        }
    }

    @Override
    public boolean complete(
            Connection connection,
            IdempotencyKey key,
            IdempotencyState terminalState,
            StoredResponse response,
            Instant completedAt) {

        if (!terminalState.isTerminal()) {
            throw new IllegalArgumentException(
                    "complete() records an outcome; " + terminalState + " is not one");
        }
        // Conditional on the row still being IN_PROGRESS. V003's trigger would reject an update
        // to a terminal row anyway, but a WHERE clause turns "someone else already finished
        // this" into a false return rather than an exception, which is the difference between a
        // handled race and a stack trace.
        String sql =
                "UPDATE " + TABLE + " SET state = ?, response_body = ?, response_media_type = ?, "
                        + "completed_at = ? WHERE scope = ? AND idempotency_key = ? AND state = ?";
        try (PreparedStatement update = connection.prepareStatement(sql)) {
            update.setString(1, terminalState.name());
            update.setBytes(2, response.bodyBytes().orElse(null));
            update.setString(3, response.contentType().orElse(null));
            update.setTimestamp(4, Timestamp.from(completedAt));
            update.setString(5, key.scope());
            update.setString(6, key.key());
            update.setString(7, IdempotencyState.IN_PROGRESS.name());
            return update.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IdempotencyStorageException("Could not record the outcome for " + key, e);
        }
    }

    @Override
    public boolean reclaimIfStale(
            Connection connection,
            IdempotencyKey key,
            RequestFingerprint fingerprint,
            CorrelationId correlationId,
            Instant staleBefore,
            Instant now,
            Instant expiresAt) {

        // The staleness test is in the WHERE clause, not in the caller. Two processes reclaiming
        // the same abandoned key at once would both read the same row and both believe they had
        // won; letting the database decide means exactly one UPDATE reports a row.
        //
        // The fingerprint is NOT rewritten - V003 forbids it, and rightly: a reclaim must not
        // quietly convert a claim into one for a different request. A caller whose fingerprint
        // differs is a conflict, decided before reclaim is attempted.
        String sql =
                "UPDATE " + TABLE + " SET correlation_id = ?, created_at = ?, expires_at = ? "
                        + "WHERE scope = ? AND idempotency_key = ? AND state = ? AND created_at < ?";
        try (PreparedStatement update = connection.prepareStatement(sql)) {
            update.setString(1, correlationId.value());
            update.setTimestamp(2, Timestamp.from(now));
            update.setTimestamp(3, Timestamp.from(expiresAt));
            update.setString(4, key.scope());
            update.setString(5, key.key());
            update.setString(6, IdempotencyState.IN_PROGRESS.name());
            update.setTimestamp(7, Timestamp.from(staleBefore));
            return update.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new IdempotencyStorageException("Could not reclaim " + key, e);
        }
    }

    // -----------------------------------------------------------------

    private static Savepoint savepoint(Connection connection) {
        try {
            return connection.setSavepoint("before_idempotency_claim");
        } catch (SQLException e) {
            throw new IdempotencyStorageException("Could not create a savepoint for the claim", e);
        }
    }

    private static void rollbackTo(Connection connection, Savepoint savepoint) {
        try {
            connection.rollback(savepoint);
        } catch (SQLException e) {
            throw new IdempotencyStorageException("Could not roll back to the claim savepoint", e);
        }
    }

    private static void release(Connection connection, Savepoint savepoint) {
        try {
            connection.releaseSavepoint(savepoint);
        } catch (SQLException e) {
            // Not fatal: an unreleased savepoint costs a little memory until the transaction
            // ends. Failing the command over it would turn a successful claim into an error.
            throw new IdempotencyStorageException("Could not release the claim savepoint", e);
        }
    }
}
