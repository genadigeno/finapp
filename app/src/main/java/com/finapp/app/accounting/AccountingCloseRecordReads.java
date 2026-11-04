package com.finapp.app.accounting;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.CloseRecordReads;
import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * accounting's {@link CloseRecordReads} over its own close tables (`P14-TSK-016`; {@code accounting V008}) - in {@code app} with the other report ports' adapters, so {@code ReportingNeverWritesTest#noReportPortAdapterWrites} holds it. {@code SELECT} only, on the caller's snapshot, each
 * statement naming the columns it wants, and so no person, no reason and no evidence: the store's own reads select those, for the doors that serve them, and report code has no business holding them.
 */
public final class AccountingCloseRecordReads implements CloseRecordReads {

    /** The approved closes of a period through a revision, oldest first: {@code period_close_one_approved} makes it one per revision. Frozen once decided, for every writer. */
    static final String CLOSES_SQL = "SELECT id, period_revision, period_locked_at, close_run_id, pinned_mapping_version_id, valuation_rate_set_id, breaks_acknowledged, breaks_resolved, proposed_at, decided_at"
            + " FROM accounting.period_close WHERE period_id = ? AND status = 'APPROVED' AND period_revision <= ? ORDER BY period_revision";

    /** A close's nine checks, in code order. Insert-only. */
    static final String CHECKS_SQL = "SELECT check_code, status, attempt, close_check_id, judged_by FROM accounting.period_close_check WHERE close_id = ? ORDER BY check_code";

    @Override
    public Optional<CloseRecord> ofRevision(Connection snapshot, AccountingPeriodId period, int revision) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(period, "period");
        try (PreparedStatement read = snapshot.prepareStatement(CLOSES_SQL)) {
            read.setObject(1, period.value());
            read.setInt(2, revision);
            List<EarlierClose> earlier = new ArrayList<>();
            CloseRecord record = null;
            UUID close = null;
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    UUID id = row.getObject("id", UUID.class);
                    int at = row.getInt("period_revision");
                    if (at < revision) {
                        earlier.add(new EarlierClose(at, id, row.getTimestamp("decided_at").toInstant()));
                    } else {
                        close = id;
                        record = new CloseRecord(id, at, row.getTimestamp("period_locked_at").toInstant(), row.getObject("close_run_id", UUID.class),
                                row.getObject("pinned_mapping_version_id", UUID.class), row.getObject("valuation_rate_set_id", UUID.class), row.getLong("breaks_acknowledged"),
                                row.getLong("breaks_resolved"), row.getTimestamp("proposed_at").toInstant(), row.getTimestamp("decided_at").toInstant(), List.of(), List.of());
                    }
                }
            }
            if (record == null) {
                return Optional.empty();
            }
            return Optional.of(new CloseRecord(record.close(), record.revision(), record.lockedAt(), record.closeRun(), record.pinnedMappingVersion(), record.valuationRateSet(),
                    record.acknowledged(), record.resolved(), record.proposedAt(), record.decidedAt(), checks(snapshot, close), earlier));
        } catch (SQLException failure) {
            throw new AccountingStorageException(DatabaseFailure.describe("reading a period's close record", failure));
        }
    }

    private static List<Check> checks(Connection snapshot, UUID close) throws SQLException {
        try (PreparedStatement read = snapshot.prepareStatement(CHECKS_SQL)) {
            read.setObject(1, close);
            List<Check> checks = new ArrayList<>();
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    checks.add(new Check(row.getString("check_code"), row.getString("status"), row.getInt("attempt"), row.getObject("close_check_id", UUID.class), row.getString("judged_by")));
                }
            }
            return checks;
        }
    }
}
