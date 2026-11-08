package com.finapp.platform.retention;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;

/**
 * Deletes one batch of rows past their retention bound (`P15-TSK-029`, C12).
 *
 * <p><strong>The predicate is the database's, not this class's.</strong> Each sweep is one call of a {@code SECURITY DEFINER}
 * function in platform {@code V012}, the only path by which the application can delete from these tables, and that function
 * chooses the rows: published outbox rows, and inbox records past both their written expiry and the retention in force. This
 * class supplies the bound and the batch size, and owns the transaction.
 *
 * <p><strong>Two bounds, and which is a floor.</strong> The inbox's bound <em>is</em> the dedupe window the
 * {@code InboxConsumer} was built with, so it cannot be configured below it: deleting a record a redelivery could still
 * match is a second effect ({@code INV-IDEM-04}). The outbox's bound is its own setting, for diagnosis, and is refused below
 * that same window, because an audited replay reads the outbox across it.
 *
 * <p><strong>Running as N instances (ADR-0014).</strong> The batch is {@code FOR UPDATE SKIP LOCKED}, so N sweepers delete
 * disjoint batches and a held row is passed over. One call borrows one connection, for one statement and its commit, and
 * returns it before the next call. A crash rolls the batch back and the next tick takes the same rows.
 */
public final class RetentionSweep {

    /** The largest batch the functions accept: a transaction stays short and its row locks few. */
    public static final int MAX_BATCH_SIZE = 1000;

    private static final String SWEEP_OUTBOX_SQL = "SELECT platform.sweep_published_outbox_events(?, ?)";

    private static final String SWEEP_INBOX_SQL = "SELECT platform.sweep_expired_inbox_messages(?, ?)";

    private final RetentionConnectionSource connections;
    private final String table;
    private final String statement;
    private final Duration bound;
    private final int batchSize;

    private RetentionSweep(
            RetentionConnectionSource connections, String table, String statement, Duration bound, int batchSize) {
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.table = table;
        this.statement = statement;
        this.bound = positive(bound, "bound");
        if (batchSize < 1 || batchSize > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batchSize must be 1.." + MAX_BATCH_SIZE + " but was " + batchSize);
        }
        this.batchSize = batchSize;
    }

    /**
     * The published rows of {@code platform.outbox_event}.
     *
     * @param bound how long a published row is kept
     * @param dedupeWindow the inbox's dedupe window: an audited replay reads the outbox across it, so {@code bound} is refused
     *     below it
     */
    public static RetentionSweep ofPublishedOutboxEvents(
            RetentionConnectionSource connections, Duration bound, Duration dedupeWindow, int batchSize) {
        positive(bound, "bound");
        positive(dedupeWindow, "dedupeWindow");
        if (bound.compareTo(dedupeWindow) < 0) {
            throw new IllegalArgumentException(
                    "the outbox's retention " + bound + " is below the inbox's dedupe window " + dedupeWindow
                            + ", across which an audited replay reads the outbox");
        }
        return new RetentionSweep(connections, "outbox_event", SWEEP_OUTBOX_SQL, bound, batchSize);
    }

    /**
     * The expired records of {@code platform.inbox_message}.
     *
     * @param dedupeWindow the retention the {@code InboxConsumer} writes with: this sweep's bound, with no setting of its own
     */
    public static RetentionSweep ofExpiredInboxMessages(
            RetentionConnectionSource connections, Duration dedupeWindow, int batchSize) {
        return new RetentionSweep(connections, "inbox_message", SWEEP_INBOX_SQL, dedupeWindow, batchSize);
    }

    private static Duration positive(Duration duration, String what) {
        Objects.requireNonNull(duration, what + " must not be null");
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(what + " must be positive but was " + duration);
        }
        return duration;
    }

    /**
     * Deletes one batch, in one transaction.
     *
     * @return how many rows this call deleted; fewer than {@link #batchSize()} when nothing further was free to delete
     * @throws RetentionSweepException if the batch failed, in which case nothing was deleted
     */
    public int sweepBatch() {
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement sweep = connection.prepareStatement(statement)) {
                sweep.setLong(1, bound.toMillis());
                sweep.setInt(2, batchSize);
                int deleted;
                try (ResultSet result = sweep.executeQuery()) {
                    result.next();
                    deleted = result.getInt(1);
                }
                connection.commit();
                return deleted;
            } catch (SQLException failure) {
                rollback(connection);
                throw failure;
            }
        } catch (SQLException failure) {
            throw new RetentionSweepException(table, failure.getSQLState(), failure);
        }
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The transaction is already failing, and the pool resets the connection when it is closed.
            System.getLogger(RetentionSweep.class.getName())
                    .log(System.Logger.Level.DEBUG, "Could not roll back a failed sweep batch", ignored);
        }
    }

    /** The table this sweep deletes from: a closed set of two, safe as a metric tag. */
    public String table() {
        return table;
    }

    /** How old a row must be before this sweep may delete it. */
    public Duration bound() {
        return bound;
    }

    public int batchSize() {
        return batchSize;
    }
}
