package com.finapp.accounting;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link AccountingCollections} over accounting's own tables (`P17-TSK-023`): each page one statement - the store's own row select,
 * a filter, a row comparison from the cursor and a {@code LIMIT}. The tables are bounded by people's acts - a version a mapping
 * change, a set a month's capture, a run an accountant's generation of one question at one revision - so a page is a top-N over
 * a period's index ({@code report_run_by_period}, {@code valuation_rate_set_by_period}), the version's unique key, or a table of
 * that size; no index is added.
 */
public final class JdbcAccountingCollections implements AccountingCollections {

    @Override
    public List<ReportRunStore.RunRow> reportRuns(Connection unitOfWork, RunFilter filter, Optional<RunCursor> after,
            int limit) {
        requirePage(limit);
        StringBuilder sql = new StringBuilder(JdbcReportRunStore.ROW).append(" WHERE TRUE");
        List<Object> arguments = new ArrayList<>();
        filter.period().ifPresent(period -> {
            sql.append(" AND r.period_id = ?");
            arguments.add(period.value());
        });
        filter.status().ifPresent(status -> {
            sql.append(" AND r.status = ?");
            arguments.add(status.name());
        });
        if (!filter.definitions().isEmpty()) {
            sql.append(" AND r.definition IN (").append(String.join(", ", filter.definitions().stream().map(any -> "?").toList()))
                    .append(")");
            arguments.addAll(filter.definitions().stream().sorted().toList());
        }
        after.ifPresent(cursor -> {
            sql.append(" AND (r.claimed_at, r.id) < (?, ?)");
            arguments.add(Timestamp.from(cursor.claimedAt()));
            arguments.add(cursor.runId());
        });
        sql.append(" ORDER BY r.claimed_at DESC, r.id DESC LIMIT ?");
        arguments.add(limit);
        try (PreparedStatement read = prepared(unitOfWork, sql.toString(), arguments); ResultSet rows = read.executeQuery()) {
            List<ReportRunStore.RunRow> page = new ArrayList<>();
            while (rows.next()) {
                page.add(JdbcReportRunStore.row(rows));
            }
            return page;
        } catch (SQLException failure) {
            throw new AccountingStorageException(DatabaseFailure.describe("listing report runs", failure));
        }
    }

    @Override
    public List<GlMappingStore.VersionRow> mappingVersions(Connection unitOfWork, Optional<GlMappingVersionStatus> status,
            Optional<Integer> beforeVersion, int limit) {
        requirePage(limit);
        StringBuilder sql = new StringBuilder(JdbcGlMappingStore.ROW).append(" WHERE TRUE");
        List<Object> arguments = new ArrayList<>();
        status.ifPresent(wanted -> {
            sql.append(" AND status = ?");
            arguments.add(wanted.name());
        });
        beforeVersion.ifPresent(version -> {
            sql.append(" AND version < ?");
            arguments.add(version);
        });
        sql.append(" ORDER BY version DESC LIMIT ?");
        arguments.add(limit);
        try (PreparedStatement read = prepared(unitOfWork, sql.toString(), arguments); ResultSet rows = read.executeQuery()) {
            List<GlMappingStore.VersionRow> page = new ArrayList<>();
            while (rows.next()) {
                page.add(JdbcGlMappingStore.row(rows));
            }
            return page;
        } catch (SQLException failure) {
            throw new AccountingStorageException(DatabaseFailure.describe("listing GL mapping versions", failure));
        }
    }

    @Override
    public List<ValuationRateSetStore.SetRow> rateSets(Connection unitOfWork, SetFilter filter, Optional<SetCursor> after,
            int limit) {
        requirePage(limit);
        StringBuilder sql = new StringBuilder(JdbcValuationRateSetStore.ROW).append(" WHERE TRUE");
        List<Object> arguments = new ArrayList<>();
        filter.period().ifPresent(period -> {
            sql.append(" AND s.period_id = ?");
            arguments.add(period.value());
        });
        filter.status().ifPresent(status -> {
            sql.append(" AND s.status = ?");
            arguments.add(status.name());
        });
        filter.capturedBy().ifPresent(person -> {
            sql.append(" AND s.captured_by = ?");
            arguments.add(person);
        });
        after.ifPresent(cursor -> {
            sql.append(" AND (s.captured_at, s.id) < (?, ?)");
            arguments.add(Timestamp.from(cursor.capturedAt()));
            arguments.add(cursor.setId());
        });
        sql.append(" ORDER BY s.captured_at DESC, s.id DESC LIMIT ?");
        arguments.add(limit);
        try (PreparedStatement read = prepared(unitOfWork, sql.toString(), arguments); ResultSet rows = read.executeQuery()) {
            List<ValuationRateSetStore.SetRow> page = new ArrayList<>();
            while (rows.next()) {
                page.add(JdbcValuationRateSetStore.row(rows));
            }
            return page;
        } catch (SQLException failure) {
            throw new AccountingStorageException(DatabaseFailure.describe("listing valuation rate sets", failure));
        }
    }

    // ------------------------------------------------------------------ the pending-decision queues (`P17-TSK-040`)

    /** A pending queue's select: id, proposer, instant and subject under one set of names, so one mapper reads every queue. */
    static final String PENDING_MAPPING_VERSIONS = "SELECT id, proposed_by, proposed_at, CAST(NULL AS uuid) AS subject_id"
            + " FROM accounting.gl_mapping_version WHERE status = 'PROPOSED'";

    static final String PENDING_RATE_SETS = "SELECT id, captured_by AS proposed_by, captured_at AS proposed_at, period_id AS subject_id"
            + " FROM accounting.valuation_rate_set WHERE status = 'PROPOSED'";

    static final String PENDING_CLOSES = "SELECT id, proposed_by, proposed_at, period_id AS subject_id"
            + " FROM accounting.period_close WHERE status = 'PROPOSED'";

    static final String PENDING_REOPENS = "SELECT id, proposed_by, proposed_at, period_id AS subject_id"
            + " FROM accounting.period_reopen WHERE status = 'PROPOSED'";

    static final String PENDING_UNLOCKS = "SELECT id, unlock_proposed_by AS proposed_by, unlock_proposed_at AS proposed_at, id AS subject_id"
            + " FROM accounting.accounting_period WHERE status = 'LOCKED' AND unlock_proposed_by IS NOT NULL AND unlock_approved_by IS NULL";

    @Override
    public List<PendingRow> pendingMappingVersions(Connection unitOfWork, Optional<PendingCursor> after, int limit) {
        return pending(unitOfWork, PENDING_MAPPING_VERSIONS, "proposed_at", after, limit, "listing the GL mapping versions awaiting a decision");
    }

    @Override
    public List<PendingRow> pendingRateSets(Connection unitOfWork, Optional<PendingCursor> after, int limit) {
        return pending(unitOfWork, PENDING_RATE_SETS, "captured_at", after, limit, "listing the valuation rate sets awaiting a decision");
    }

    @Override
    public List<PendingRow> pendingCloses(Connection unitOfWork, Optional<PendingCursor> after, int limit) {
        return pending(unitOfWork, PENDING_CLOSES, "proposed_at", after, limit, "listing the close proposals awaiting a decision");
    }

    @Override
    public List<PendingRow> pendingReopens(Connection unitOfWork, Optional<PendingCursor> after, int limit) {
        return pending(unitOfWork, PENDING_REOPENS, "proposed_at", after, limit, "listing the reopen proposals awaiting a decision");
    }

    @Override
    public List<PendingRow> pendingUnlocks(Connection unitOfWork, Optional<PendingCursor> after, int limit) {
        return pending(unitOfWork, PENDING_UNLOCKS, "unlock_proposed_at", after, limit, "listing the unlock proposals awaiting a decision");
    }

    /** The queue's page: oldest first, the id the tiebreak, strictly after the cursor's row comparison; {@code at} a constant of this class. */
    static String pendingSql(String select, String at, boolean continued) {
        return select + (continued ? " AND (" + at + ", id) > (?, ?)" : "") + " ORDER BY " + at + " ASC, id ASC LIMIT ?";
    }

    private static List<PendingRow> pending(Connection unitOfWork, String select, String at, Optional<PendingCursor> after, int limit,
            String operation) {
        requirePage(limit);
        List<Object> arguments = new ArrayList<>();
        after.ifPresent(cursor -> {
            arguments.add(Timestamp.from(cursor.proposedAt()));
            arguments.add(cursor.id());
        });
        arguments.add(limit);
        try (PreparedStatement read = prepared(unitOfWork, pendingSql(select, at, after.isPresent()), arguments);
                ResultSet rows = read.executeQuery()) {
            List<PendingRow> page = new ArrayList<>();
            while (rows.next()) {
                page.add(new PendingRow(rows.getObject("id", UUID.class), rows.getString("proposed_by"),
                        rows.getTimestamp("proposed_at").toInstant(), Optional.ofNullable(rows.getObject("subject_id", UUID.class))));
            }
            return page;
        } catch (SQLException failure) {
            throw new AccountingStorageException(DatabaseFailure.describe(operation, failure));
        }
    }

    private static PreparedStatement prepared(Connection unitOfWork, String sql, List<Object> arguments) throws SQLException {
        PreparedStatement statement = unitOfWork.prepareStatement(sql);
        try {
            for (int i = 0; i < arguments.size(); i++) {
                statement.setObject(i + 1, arguments.get(i));
            }
            return statement;
        } catch (SQLException failure) {
            statement.close();
            throw failure;
        }
    }

    private static void requirePage(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("a page holds at least one row, not " + limit);
        }
    }
}
