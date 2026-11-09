package com.finapp.accounting;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link SourceCensusStore} over {@code accounting}'s own tables (`P15-TSK-035`, {@code V015}). Every stamp is the database's clock
 * ({@code clock_timestamp()}), never an instance's: the fence, the position and the staleness are all one clock's.
 */
public final class JdbcSourceCensusStore implements SourceCensusStore {

    /**
     * The fence: the earlier of the database's now and the oldest open transaction this role can see. Not this connection's own.
     * {@code pg_stat_activity} shows {@code xact_start} only for sessions of the role reading it (or one holding
     * {@code pg_read_all_stats}): enough here, because the census runs as the application role and only that role posts.
     */
    static final String FENCE = "SELECT least(clock_timestamp(), coalesce(min(xact_start), clock_timestamp())) AS fence"
            + " FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid() AND xact_start IS NOT NULL";

    static final String CLAIM = "SELECT position, entries_judged FROM accounting.source_census_head WHERE id FOR UPDATE SKIP LOCKED";

    static final String INSERT_FINDING = "INSERT INTO accounting.source_census_finding (entry_id, kind, door, namespace, evidence, found_at)"
            + " VALUES (?, ?, ?, ?, ?, clock_timestamp()) ON CONFLICT (entry_id) DO NOTHING";

    static final String ADVANCE = "UPDATE accounting.source_census_head SET position = ?, entries_judged = entries_judged + ?,"
            + " last_success_at = clock_timestamp(), updated_at = clock_timestamp() WHERE id";

    static final String TOUCH = "UPDATE accounting.source_census_head SET last_success_at = clock_timestamp(), updated_at = clock_timestamp() WHERE id";

    static final String CLAIM_TO_MARK = "SELECT 1 FROM accounting.source_census_head WHERE id FOR UPDATE SKIP LOCKED";

    static final String MARK_FAILURE = "UPDATE accounting.source_census_head SET last_failure_at = clock_timestamp(),"
            + " last_failure_class = ?, updated_at = clock_timestamp() WHERE id";

    static final String STANDING = "SELECT kind, count(*) AS standing FROM accounting.source_census_finding"
            + " WHERE found_at > clock_timestamp() - ? * interval '1 millisecond' GROUP BY kind";

    static final String HEALTH = "SELECT position, last_success_at, last_failure_at,"
            + " (last_success_at IS NOT NULL AND (last_failure_at IS NULL OR last_failure_at <= last_success_at)"
            + " AND last_success_at > clock_timestamp() - ? * interval '1 millisecond') AS healthy"
            + " FROM accounting.source_census_head WHERE id";

    @Override
    public Instant fence(Connection connection) {
        Objects.requireNonNull(connection, "connection");
        try (PreparedStatement select = connection.prepareStatement(FENCE); ResultSet row = select.executeQuery()) {
            row.next();
            return row.getTimestamp("fence").toInstant();
        } catch (SQLException failure) {
            throw failed("reading the census's fence", failure);
        }
    }

    @Override
    public Optional<Head> claimHead(Connection connection) {
        try (PreparedStatement select = connection.prepareStatement(CLAIM); ResultSet row = select.executeQuery()) {
            if (!row.next()) {
                return Optional.empty();
            }
            return Optional.of(new Head(row.getObject("position", UUID.class), row.getLong("entries_judged")));
        } catch (SQLException failure) {
            throw failed("claiming the census's head", failure);
        }
    }

    @Override
    public void insertFindings(Connection connection, List<Finding> findings) {
        if (findings.isEmpty()) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(INSERT_FINDING)) {
            for (Finding finding : findings) {
                insert.setObject(1, finding.entryId());
                insert.setString(2, finding.kind().name());
                insert.setString(3, finding.door().name());
                insert.setString(4, finding.namespace());
                insert.setString(5, finding.evidence());
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException failure) {
            throw failed("inserting census findings", failure);
        }
    }

    @Override
    public void advance(Connection connection, UUID position, long entriesJudged) {
        try (PreparedStatement update = connection.prepareStatement(ADVANCE)) {
            update.setObject(1, position);
            update.setLong(2, entriesJudged);
            requireOne(update.executeUpdate(), "advancing the census's head");
        } catch (SQLException failure) {
            throw failed("advancing the census's head", failure);
        }
    }

    @Override
    public void touch(Connection connection) {
        try (PreparedStatement update = connection.prepareStatement(TOUCH)) {
            requireOne(update.executeUpdate(), "touching the census's head");
        } catch (SQLException failure) {
            throw failed("touching the census's head", failure);
        }
    }

    @Override
    public void markFailure(Connection connection, String failureClass) {
        Objects.requireNonNull(failureClass, "failureClass");
        try (PreparedStatement select = connection.prepareStatement(CLAIM_TO_MARK); ResultSet row = select.executeQuery()) {
            if (!row.next()) {
                return;
            }
            try (PreparedStatement update = connection.prepareStatement(MARK_FAILURE)) {
                update.setString(1, failureClass);
                update.executeUpdate();
            }
        } catch (SQLException failure) {
            throw failed("stamping the census's failure", failure);
        }
    }

    @Override
    public Reading read(Connection connection, Duration standing, Duration staleAfter) {
        Objects.requireNonNull(standing, "standing");
        Objects.requireNonNull(staleAfter, "staleAfter");
        boolean healthy;
        UUID position;
        try (PreparedStatement select = connection.prepareStatement(HEALTH)) {
            select.setLong(1, staleAfter.toMillis());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                healthy = row.getBoolean("healthy");
                position = row.getObject("position", UUID.class);
            }
        } catch (SQLException failure) {
            throw failed("reading the census's health", failure);
        }
        Map<SourceFindingKind, Long> counts = new EnumMap<>(SourceFindingKind.class);
        try (PreparedStatement select = connection.prepareStatement(STANDING)) {
            select.setLong(1, standing.toMillis());
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    counts.put(SourceFindingKind.valueOf(row.getString("kind")), row.getLong("standing"));
                }
            }
        } catch (SQLException failure) {
            throw failed("counting standing census findings", failure);
        }
        return new Reading(counts, healthy, Optional.of(position));
    }

    private static void requireOne(int updated, String what) {
        if (updated != 1) {
            throw new AccountingStorageException(what + ": the head row is seeded once and never removed");
        }
    }

    private static AccountingStorageException failed(String what, SQLException failure) {
        return new AccountingStorageException(DatabaseFailure.describe(what, failure));
    }
}
