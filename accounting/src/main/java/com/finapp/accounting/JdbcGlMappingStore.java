package com.finapp.accounting;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.platform.security.Actor;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@link GlMappingStore} over plain JDBC (`P14-TSK-003`, ADR-0033; risk's {@code JdbcRiskPolicyStore}): every time the
 * database's, every move a conditional {@code UPDATE} of the machine row's decision or retirement columns under
 * {@code accounting V002}'s edge trigger, the content and the history INSERT-only, every failure its operation and
 * SQLSTATE only ({@link AccountingStorageException}).
 */
public final class JdbcGlMappingStore implements GlMappingStore {

    /**
     * accounting's advisory namespace (`DISTRIBUTED_EXECUTION.md` section 3, reserved by the Phase 13 → 14 transition
     * for accounting and the ledger's posting cutoff; key {@code cutoff} is `P14-TSK-004`'s, key {@code mapping} this
     * store's): every proposal and decision of a GL mapping version serialises on {@code (14, hashtext('mapping'))}.
     */
    public static final int NAMESPACE = 14;

    /** The key within the namespace - distinct from the cutoff's. */
    public static final String KEY = "mapping";

    /** A2's statement, pinned by {@code AccountingMappingMigrationTest#namespaceFourteenKeyMappingIsPinned}. */
    static final String LOCK_SQL = "SELECT pg_advisory_xact_lock(" + NAMESPACE + ", hashtext('" + KEY + "'))";

    private static final String ROW = "SELECT id, version, status, effective_from_period, effective_to_period,"
            + " proposed_by, proposed_at, decided_by, decided_at, retired_at FROM accounting.gl_mapping_version";

    @Override
    public void lockMapping(Connection unitOfWork) {
        try (PreparedStatement lock = unitOfWork.prepareStatement(LOCK_SQL)) {
            lock.execute();
        } catch (SQLException failure) {
            throw failed("taking the mapping administration lock", failure);
        }
    }

    @Override
    public int maxVersion(Connection unitOfWork) {
        try (PreparedStatement read = unitOfWork.prepareStatement(
                        "SELECT COALESCE(max(version), 0) FROM accounting.gl_mapping_version");
                ResultSet row = read.executeQuery()) {
            row.next();
            return row.getInt(1);
        } catch (SQLException failure) {
            throw failed("reading the latest mapping version number", failure);
        }
    }

    @Override
    public void insertProposal(Connection unitOfWork, NewProposal proposal) {
        try {
            try (PreparedStatement insert = unitOfWork.prepareStatement(
                    "INSERT INTO accounting.gl_mapping_version (id, version, status, effective_from_period, proposed_by,"
                            + " proposed_by_type, proposed_at, proposal_reason, correlation_id)"
                            + " VALUES (?, ?, 'PROPOSED', ?, ?, ?, clock_timestamp(), ?, ?)")) {
                insert.setObject(1, proposal.id().value());
                insert.setInt(2, proposal.version());
                insert.setDate(3, Date.valueOf(proposal.content().effectiveFromPeriod()));
                insert.setString(4, proposal.actor().id());
                insert.setString(5, proposal.actor().type().name());
                insert.setString(6, proposal.reason());
                insert.setString(7, proposal.correlation().value());
                insert.executeUpdate();
            }
            try (PreparedStatement insert = unitOfWork.prepareStatement(
                    "INSERT INTO accounting.gl_account (version_id, code, name, classification, statement_section,"
                            + " normal_balance) VALUES (?, ?, ?, ?, ?, ?)")) {
                for (GlAccount account : proposal.content().accounts()) {
                    insert.setObject(1, proposal.id().value());
                    insert.setString(2, account.code());
                    insert.setString(3, account.name());
                    insert.setString(4, account.classification().name());
                    insert.setString(5, account.section().name());
                    insert.setString(6, account.normalBalance());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            try (PreparedStatement insert = unitOfWork.prepareStatement(
                    "INSERT INTO accounting.gl_mapping_rule (version_id, ledger_purpose, owner_kind, gl_account_code)"
                            + " VALUES (?, ?, ?, ?)")) {
                for (MappingRule rule : proposal.content().rules()) {
                    insert.setObject(1, proposal.id().value());
                    insert.setString(2, rule.purpose());
                    insert.setString(3, rule.ownerKind());
                    insert.setString(4, rule.glAccountCode());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        } catch (SQLException failure) {
            throw failed("inserting mapping version " + proposal.version(), failure);
        }
        appendEvent(unitOfWork, new HistoryRow(proposal.historyId(), proposal.id(), Optional.empty(),
                GlMappingVersionStatus.PROPOSED, proposal.actor(), proposal.reason(), proposal.correlation()));
    }

    @Override
    public Optional<VersionRow> find(Connection unitOfWork, GlMappingVersionId id) {
        return one(unitOfWork, ROW + " WHERE id = ?", id, "reading mapping version");
    }

    @Override
    public Optional<VersionRow> lock(Connection unitOfWork, GlMappingVersionId id) {
        return one(unitOfWork, ROW + " WHERE id = ? FOR UPDATE", id, "locking mapping version");
    }

    @Override
    public Optional<VersionRow> lockActive(Connection unitOfWork) {
        return one(unitOfWork, ROW + " WHERE status = 'ACTIVE' FOR UPDATE", null, "locking the active mapping version");
    }

    @Override
    public Optional<VersionRow> active(Connection unitOfWork) {
        return one(unitOfWork, ROW + " WHERE status = 'ACTIVE'", null, "reading the active mapping version");
    }

    @Override
    public Optional<YearMonth> latestEffectiveFrom(Connection unitOfWork) {
        try (PreparedStatement read = unitOfWork.prepareStatement("SELECT max(effective_from_period)"
                        + " FROM accounting.gl_mapping_version WHERE status IN ('ACTIVE', 'RETIRED')");
                ResultSet row = read.executeQuery()) {
            row.next();
            Date latest = row.getDate(1);
            return latest == null ? Optional.empty() : Optional.of(YearMonth.from(latest.toLocalDate()));
        } catch (SQLException failure) {
            throw failed("reading the latest effective month", failure);
        }
    }

    @Override
    public GlMappingVersion content(Connection unitOfWork, GlMappingVersionId id) {
        try {
            YearMonth effectiveFrom = find(unitOfWork, id)
                    .orElseThrow(() -> new IllegalStateException("content is read for a version that exists"))
                    .effectiveFrom();
            List<GlAccount> accounts = new ArrayList<>();
            try (PreparedStatement read = unitOfWork.prepareStatement("SELECT code, name, classification,"
                    + " statement_section FROM accounting.gl_account WHERE version_id = ? ORDER BY code")) {
                read.setObject(1, id.value());
                try (ResultSet rows = read.executeQuery()) {
                    while (rows.next()) {
                        accounts.add(new GlAccount(rows.getString(1), rows.getString(2),
                                GlClassification.valueOf(rows.getString(3)), StatementSection.valueOf(rows.getString(4))));
                    }
                }
            }
            List<MappingRule> rules = new ArrayList<>();
            try (PreparedStatement read = unitOfWork.prepareStatement("SELECT ledger_purpose, owner_kind, gl_account_code"
                    + " FROM accounting.gl_mapping_rule WHERE version_id = ? ORDER BY ledger_purpose, owner_kind")) {
                read.setObject(1, id.value());
                try (ResultSet rows = read.executeQuery()) {
                    while (rows.next()) {
                        rules.add(new MappingRule(rows.getString(1), rows.getString(2), rows.getString(3)));
                    }
                }
            }
            return new GlMappingVersion(effectiveFrom, accounts, rules);
        } catch (SQLException failure) {
            throw failed("reading mapping version content", failure);
        }
    }

    @Override
    public boolean retire(Connection unitOfWork, GlMappingVersionId id, YearMonth endsAt) {
        try (PreparedStatement update = unitOfWork.prepareStatement("UPDATE accounting.gl_mapping_version"
                + " SET status = 'RETIRED', effective_to_period = ?, retired_at = clock_timestamp()"
                + " WHERE id = ? AND status = 'ACTIVE'")) {
            update.setDate(1, Date.valueOf(endsAt.atDay(1)));
            update.setObject(2, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("retiring mapping version", failure);
        }
    }

    @Override
    public boolean decide(
            Connection unitOfWork, GlMappingVersionId id, GlMappingVersionStatus to, Actor actor, String reason) {
        try (PreparedStatement update = unitOfWork.prepareStatement("UPDATE accounting.gl_mapping_version"
                + " SET status = ?, decided_by = ?, decided_by_type = ?, decided_at = clock_timestamp(),"
                + " decision_reason = ? WHERE id = ? AND status = 'PROPOSED'")) {
            update.setString(1, to.name());
            update.setString(2, actor.id());
            update.setString(3, actor.type().name());
            update.setString(4, reason);
            update.setObject(5, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("deciding mapping version", failure);
        }
    }

    @Override
    public void appendEvent(Connection unitOfWork, HistoryRow row) {
        try (PreparedStatement insert = unitOfWork.prepareStatement("INSERT INTO accounting.gl_mapping_version_event"
                + " (id, version_id, from_status, to_status, actor, actor_type, reason, occurred_at, correlation_id)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?)")) {
            insert.setObject(1, row.id());
            insert.setObject(2, row.versionId().value());
            insert.setString(3, row.from().map(Enum::name).orElse(null));
            insert.setString(4, row.to().name());
            insert.setString(5, row.actor().id());
            insert.setString(6, row.actor().type().name());
            insert.setString(7, row.reason());
            insert.setString(8, row.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("appending mapping version history", failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static Optional<VersionRow> one(Connection unitOfWork, String sql, GlMappingVersionId id, String operation) {
        try (PreparedStatement read = unitOfWork.prepareStatement(sql)) {
            if (id != null) {
                read.setObject(1, id.value());
            }
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(row(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
    }

    private static VersionRow row(ResultSet row) throws SQLException {
        Date to = row.getDate("effective_to_period");
        return new VersionRow(
                GlMappingVersionId.of(row.getObject("id", java.util.UUID.class)),
                row.getInt("version"),
                GlMappingVersionStatus.valueOf(row.getString("status")),
                YearMonth.from(row.getDate("effective_from_period").toLocalDate()),
                to == null ? Optional.empty() : Optional.of(YearMonth.from(to.toLocalDate())),
                row.getString("proposed_by"),
                row.getTimestamp("proposed_at").toInstant(),
                Optional.ofNullable(row.getString("decided_by")),
                instant(row.getTimestamp("decided_at")),
                instant(row.getTimestamp("retired_at")));
    }

    private static Optional<Instant> instant(Timestamp timestamp) {
        return timestamp == null ? Optional.empty() : Optional.of(timestamp.toInstant());
    }

    private static AccountingStorageException failed(String operation, SQLException failure) {
        return new AccountingStorageException(DatabaseFailure.describe(operation, failure));
    }
}
