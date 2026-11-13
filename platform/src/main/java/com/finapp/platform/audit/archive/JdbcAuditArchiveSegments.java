package com.finapp.platform.audit.archive;

import com.finapp.platform.retention.RetentionConnectionSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link AuditArchiveSegments} over platform {@code V015}'s functions (`P16-TSK-010`). Explicit SQL (ADR-0033); each call is one
 * transaction on one connection from the instance's pool, committed and returned before the call returns, so the archive holds no
 * connection while it talks to the store and the fleet's connection budget does not move.
 */
public final class JdbcAuditArchiveSegments implements AuditArchiveSegments {

    static final String CLAIM_SQL = "SELECT claimed_seq, claimed_holder FROM platform.claim_audit_archive_segment(?, ?)";

    static final String CONTENT_SQL = "SELECT platform.audit_archive_content(?)";

    static final String MANIFEST_SQL = "SELECT platform.audit_archive_manifest(?)";

    static final String COMPLETE_SQL = "SELECT platform.complete_audit_archive_segment(?, ?, ?, ?)";

    /** The queue's depth up to a cap, and its oldest entry's age in milliseconds on the database's clock (zero when empty). */
    static final String BACKLOG_SQL = "SELECT (SELECT count(*) FROM (SELECT 1 FROM platform.audit_archive_pending LIMIT ?) AS capped),"
            + " coalesce((extract(epoch FROM now() - (SELECT min(p.enqueued_at) FROM platform.audit_archive_pending p)) * 1000)::bigint, 0)";

    private final RetentionConnectionSource connections;

    public JdbcAuditArchiveSegments(RetentionConnectionSource connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    @Override
    public Optional<Claim> claim(Duration lease, int batchSize) {
        return inTransaction("claim", CLAIM_SQL, statement -> {
            statement.setLong(1, lease.toMillis());
            statement.setInt(2, batchSize);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Claim(rows.getLong(1), rows.getObject(2, UUID.class)));
            }
        });
    }

    @Override
    public String content(long segment) {
        return text("content", CONTENT_SQL, segment);
    }

    @Override
    public String manifest(long segment) {
        return text("manifest", MANIFEST_SQL, segment);
    }

    @Override
    public void complete(Claim claim, String contentSha256, String manifestSha256) {
        inTransaction("complete", COMPLETE_SQL, statement -> {
            statement.setLong(1, claim.segment());
            statement.setObject(2, claim.holder());
            statement.setString(3, contentSha256);
            statement.setString(4, manifestSha256);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
            }
            return null;
        });
    }

    @Override
    public Backlog backlog(int cap) {
        return inTransaction("backlog", BACKLOG_SQL, statement -> {
            statement.setInt(1, cap);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return new Backlog(rows.getLong(1), Duration.ofMillis(rows.getLong(2)));
            }
        });
    }

    private String text(String operation, String sql, long segment) {
        return inTransaction(operation, sql, statement -> {
            statement.setLong(1, segment);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getString(1);
            }
        });
    }

    @FunctionalInterface
    private interface Work<T> {
        T apply(PreparedStatement statement) throws SQLException;
    }

    private <T> T inTransaction(String operation, String sql, Work<T> work) {
        try (Connection connection = connections.open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                T result = work.apply(statement);
                connection.commit();
                return result;
            } catch (SQLException failure) {
                rollback(connection);
                throw failure;
            }
        } catch (SQLException failure) {
            // The SQLState alone: the driver's message can carry a refused row (StorageFailuresCarryNoRowTest's rule).
            throw new AuditArchiveDatabaseException(operation, failure.getSQLState());
        }
    }

    private static void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // The transaction is already failing, and the pool resets the connection when it is closed.
            System.getLogger(JdbcAuditArchiveSegments.class.getName())
                    .log(System.Logger.Level.DEBUG, "Could not roll back a failed audit archive call: SQLState {0}", ignored.getSQLState());
        }
    }
}
