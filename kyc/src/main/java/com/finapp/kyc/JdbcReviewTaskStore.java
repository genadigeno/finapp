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
