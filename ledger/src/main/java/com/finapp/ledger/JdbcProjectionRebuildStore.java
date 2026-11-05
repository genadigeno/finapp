package com.finapp.ledger;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link ProjectionRebuildStore} over plain JDBC (`P14-TSK-026`, ADR-0033; {@code JdbcAdjustmentProposalStore}'s shape):
 * the rebuild inserted in the caller's transaction, the one move a conditional {@code UPDATE} of the decision and approval
 * columns ({@code ledger V031}'s grant), every failure its operation and SQLSTATE only ({@link DatabaseFailure}). No
 * statement here names a journal table.
 */
public final class JdbcProjectionRebuildStore implements ProjectionRebuildStore<Connection> {

    static final String COLUMNS = "id, ledger_account_id, currency, scale, status, reason, proposed_by, proposed_at,"
            + " observed_row, observed_posted_minor, observed_holds_minor, observed_seq, observed_derived_posted_minor,"
            + " observed_derived_holds_minor, observed_derived_seq, decided_by, decided_at, before_row, before_posted_minor,"
            + " before_holds_minor, before_seq, after_posted_minor, after_holds_minor, after_seq";

    static final String INSERT_SQL = "INSERT INTO ledger.projection_rebuild (id, ledger_account_id, currency, scale, status,"
            + " reason, proposed_by, proposed_at, observed_row, observed_posted_minor, observed_holds_minor, observed_seq,"
            + " observed_derived_posted_minor, observed_derived_holds_minor, observed_derived_seq)"
            + " VALUES (?, ?, ?, ?, 'PROPOSED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    static final String FIND_SQL = "SELECT " + COLUMNS + " FROM ledger.projection_rebuild WHERE id = ?";

    static final String LOCK_SQL = FIND_SQL + " FOR UPDATE";

    static final String STANDING_SQL = "SELECT " + COLUMNS + " FROM ledger.projection_rebuild"
            + " WHERE ledger_account_id = ? AND status = 'PROPOSED'";

    static final String DECIDE_SQL = "UPDATE ledger.projection_rebuild SET status = ?, decided_by = ?, decided_at = ?,"
            + " before_row = ?, before_posted_minor = ?, before_holds_minor = ?, before_seq = ?, after_posted_minor = ?,"
            + " after_holds_minor = ?, after_seq = ? WHERE id = ? AND status = 'PROPOSED'";

    /** {@code projection_rebuild_one_standing_per_account}'s violation: a second proposer that raced the first. */
    private static final String UNIQUE_VIOLATION = "23505";

    @Override
    public void insert(Connection unitOfWork, ProjectionRebuild rebuild) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(rebuild, "rebuild must not be null");
        try (PreparedStatement insert = unitOfWork.prepareStatement(INSERT_SQL)) {
            insert.setObject(1, rebuild.id().value());
            insert.setObject(2, rebuild.account().value());
            insert.setString(3, rebuild.currency().code());
            insert.setInt(4, rebuild.scale());
            insert.setString(5, rebuild.reason());
            insert.setString(6, rebuild.proposedBy());
            insert.setTimestamp(7, Timestamp.from(rebuild.proposedAt()));
            insert.setBoolean(8, rebuild.observedRow().isPresent());
            setFigures(insert, 9, rebuild.observedRow());
            insert.setLong(12, rebuild.observedDerived().postedMinor());
            insert.setLong(13, rebuild.observedDerived().holdsMinor());
            insert.setLong(14, rebuild.observedDerived().appliedEntries());
            insert.executeUpdate();
        } catch (SQLException failure) {
            if (UNIQUE_VIOLATION.equals(failure.getSQLState())) {
                throw new ProjectionRebuildStandingException(rebuild.account());
            }
            throw new LedgerStorageException(DatabaseFailure.describe("proposing projection rebuild " + rebuild.id(), failure));
        }
    }

    @Override
    public Optional<ProjectionRebuild> findById(Connection unitOfWork, ProjectionRebuildId id) {
        return one(unitOfWork, FIND_SQL, id.value(), "reading projection rebuild " + id);
    }

    @Override
    public Optional<ProjectionRebuild> lockById(Connection unitOfWork, ProjectionRebuildId id) {
        return one(unitOfWork, LOCK_SQL, id.value(), "locking projection rebuild " + id);
    }

    @Override
    public Optional<ProjectionRebuild> standingFor(Connection unitOfWork, LedgerAccountId account) {
        return one(unitOfWork, STANDING_SQL, account.value(), "reading the standing rebuild of account " + account);
    }

    @Override
    public boolean decide(
            Connection unitOfWork,
            ProjectionRebuildId id,
            ProjectionRebuildStatus to,
            String decidedBy,
            Instant decidedAt,
            Optional<ProjectionRebuild.Approval> approval) {
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(approval, "approval must not be null");
        try (PreparedStatement update = unitOfWork.prepareStatement(DECIDE_SQL)) {
            update.setString(1, to.name());
            update.setString(2, decidedBy);
            update.setTimestamp(3, Timestamp.from(decidedAt));
            if (approval.isPresent()) {
                update.setBoolean(4, approval.get().before().isPresent());
                setFigures(update, 5, approval.get().before());
                update.setLong(8, approval.get().after().postedMinor());
                update.setLong(9, approval.get().after().holdsMinor());
                update.setLong(10, approval.get().after().appliedEntries());
            } else {
                update.setNull(4, Types.BOOLEAN);
                setFigures(update, 5, Optional.empty());
                update.setNull(8, Types.BIGINT);
                update.setNull(9, Types.BIGINT);
                update.setNull(10, Types.BIGINT);
            }
            update.setObject(11, id.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new LedgerStorageException(DatabaseFailure.describe("deciding projection rebuild " + id, failure));
        }
    }

    /** Three figure columns from {@code index}: the posted, the holds, the seq - all NULL for an absent row. */
    private static void setFigures(PreparedStatement statement, int index, Optional<ProjectionFigures> figures)
            throws SQLException {
        if (figures.isPresent()) {
            statement.setLong(index, figures.get().postedMinor());
            statement.setLong(index + 1, figures.get().holdsMinor());
            statement.setLong(index + 2, figures.get().appliedEntries());
        } else {
            statement.setNull(index, Types.BIGINT);
            statement.setNull(index + 1, Types.BIGINT);
            statement.setNull(index + 2, Types.BIGINT);
        }
    }

    private static Optional<ProjectionRebuild> one(Connection unitOfWork, String sql, UUID key, String operation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, key);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(rebuild(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(DatabaseFailure.describe(operation, failure));
        }
    }

    private static ProjectionRebuild rebuild(ResultSet row) throws SQLException {
        ProjectionRebuildStatus status = ProjectionRebuildStatus.valueOf(row.getString("status"));
        Optional<ProjectionFigures> observedRow = row.getBoolean("observed_row")
                ? Optional.of(new ProjectionFigures(row.getLong("observed_posted_minor"), row.getLong("observed_holds_minor"),
                        row.getLong("observed_seq")))
                : Optional.empty();
        Timestamp decidedAt = row.getTimestamp("decided_at");
        Optional<ProjectionRebuild.Approval> approval = Optional.empty();
        if (status.approved()) {
            Optional<ProjectionFigures> before = row.getBoolean("before_row")
                    ? Optional.of(new ProjectionFigures(row.getLong("before_posted_minor"), row.getLong("before_holds_minor"),
                            row.getLong("before_seq")))
                    : Optional.empty();
            approval = Optional.of(new ProjectionRebuild.Approval(before, new ProjectionFigures(
                    row.getLong("after_posted_minor"), row.getLong("after_holds_minor"), row.getLong("after_seq"))));
        }
        return ProjectionRebuild.rehydrate(
                ProjectionRebuildId.of(row.getObject("id", UUID.class)),
                LedgerAccountId.of(row.getObject("ledger_account_id", UUID.class)),
                CurrencyCode.of(row.getString("currency").stripTrailing()),
                row.getShort("scale"),
                status,
                row.getString("reason"),
                row.getString("proposed_by"),
                row.getTimestamp("proposed_at").toInstant(),
                observedRow,
                new ProjectionFigures(row.getLong("observed_derived_posted_minor"),
                        row.getLong("observed_derived_holds_minor"), row.getLong("observed_derived_seq")),
                Optional.ofNullable(row.getString("decided_by")),
                decidedAt == null ? Optional.empty() : Optional.of(decidedAt.toInstant()),
                approval);
    }
}
