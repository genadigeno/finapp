package com.finapp.kyc;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;

/**
 * Plain-JDBC storage for review tasks (ADR-0033, `P2-TSK-010`).
 *
 * <p>{@code ON CONFLICT (check_id) DO NOTHING} rather than the savepoint idiom, deliberately:
 * the savepoint exists so a caller's <em>other</em> writes survive a lost race that aborts the
 * transaction, and here the lost race must not abort anything — a duplicate task insert is the
 * expected converge path of every re-run and every concurrent assessor, single-row, with nothing
 * to read back. The {@code RoleAssignment} precedent.
 */
public final class JdbcReviewTaskStore implements ReviewTaskStore<Connection> {

    private static final String TABLE = "kyc.review_task";

    @Override
    public boolean openForCheck(Connection unitOfWork, ReviewTask fresh) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(fresh, "fresh must not be null");
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO " + TABLE
                                + " (id, case_id, check_id, status, opened_at)"
                                + " VALUES (?, ?, ?, ?, ?)"
                                + " ON CONFLICT (check_id) DO NOTHING")) {
            insert.setObject(1, fresh.id().value());
            insert.setObject(2, fresh.caseId().value());
            insert.setObject(3, fresh.checkId().value());
            insert.setString(4, fresh.status().name());
            insert.setTimestamp(5, Timestamp.from(fresh.openedAt()));
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe(
                            "opening a review task for check " + fresh.checkId(), failure));
        }
    }

    @Override
    public boolean resolve(
            Connection unitOfWork,
            ReviewTaskId taskId,
            KycCaseId caseId,
            java.util.UUID resolvedBy,
            String reason,
            java.time.Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(resolvedBy, "resolvedBy must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Objects.requireNonNull(at, "at must not be null");
        try (PreparedStatement update =
                unitOfWork.prepareStatement(
                        "UPDATE " + TABLE
                                + " SET status = 'RESOLVED', resolved_by = ?, resolved_at = ?,"
                                + " resolution_reason = ?"
                                // id names the task; case_id refuses another case's task named
                                // through the wrong URL; status = 'OPEN' is the concurrency
                                // arbiter. Row count is the outcome.
                                + " WHERE id = ? AND case_id = ? AND status = 'OPEN'")) {
            update.setObject(1, resolvedBy);
            update.setTimestamp(2, Timestamp.from(at));
            update.setString(3, reason);
            update.setObject(4, taskId.value());
            update.setObject(5, caseId.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            // The reason is free prose and must not ride an exception into a log (INV-AUD-02);
            // DatabaseFailure.describe carries no cause and this message carries identifiers only.
            throw new KycStorageException(
                    DatabaseFailure.describe("resolving review task " + taskId, failure));
        }
    }

    @Override
    public java.util.Optional<ReviewTask> findByIdForCase(
            Connection unitOfWork, ReviewTaskId taskId, KycCaseId caseId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        SELECT_COLUMNS + " WHERE id = ? AND case_id = ?")) {
            select.setObject(1, taskId.value());
            select.setObject(2, caseId.value());
            try (ResultSet rows = select.executeQuery()) {
                if (!rows.next()) {
                    return java.util.Optional.empty();
                }
                return java.util.Optional.of(rehydrate(rows));
            }
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe("reading review task " + taskId, failure));
        }
    }

    @Override
    public java.util.List<ReviewTask> forCase(Connection unitOfWork, KycCaseId caseId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        SELECT_COLUMNS + " WHERE case_id = ? ORDER BY opened_at, id")) {
            select.setObject(1, caseId.value());
            try (ResultSet rows = select.executeQuery()) {
                java.util.List<ReviewTask> tasks = new java.util.ArrayList<>();
                while (rows.next()) {
                    tasks.add(rehydrate(rows));
                }
                return java.util.List.copyOf(tasks);
            }
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe(
                            "listing the review tasks of case " + caseId, failure));
        }
    }

    private static final String SELECT_COLUMNS =
            "SELECT id, case_id, check_id, status, opened_at, resolved_by, resolved_at,"
                    + " resolution_reason FROM " + TABLE;

    private static ReviewTask rehydrate(ResultSet row) throws SQLException {
        ReviewTaskStatus status = ReviewTaskStatus.valueOf(row.getString("status"));
        java.util.Optional<ReviewTask.Resolution> resolution =
                status == ReviewTaskStatus.RESOLVED
                        ? java.util.Optional.of(
                                new ReviewTask.Resolution(
                                        row.getObject("resolved_by", java.util.UUID.class),
                                        row.getTimestamp("resolved_at").toInstant(),
                                        row.getString("resolution_reason")))
                        : java.util.Optional.empty();
        return ReviewTask.rehydrate(
                ReviewTaskId.of(row.getObject("id", java.util.UUID.class)),
                KycCaseId.of(row.getObject("case_id", java.util.UUID.class)),
                CheckId.of(row.getObject("check_id", java.util.UUID.class)),
                status,
                row.getTimestamp("opened_at").toInstant(),
                resolution);
    }

    @Override
    public long countOpen(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT count(*) FROM " + TABLE + " WHERE status = 'OPEN'")) {
            try (ResultSet rows = select.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        } catch (SQLException failure) {
            throw new KycStorageException(
                    DatabaseFailure.describe("counting open review tasks", failure));
        }
    }
}
