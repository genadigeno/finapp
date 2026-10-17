package com.finapp.merchant;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link BnplEnablementStore} over JDBC (`P12-TSK-006`; ADR-0033: explicit SQL, no ORM).
 *
 * <p>The three-rank discipline of the payout destination's flow (`V006`), with the Phase 11 gate's stamp lesson: the
 * service judges the machine and the person first; each conditional statement converges or refuses in itself - the
 * approval also on {@code proposed_by <> ?}; `V010`'s edge trigger and four-eyes {@code CHECK} refuse raw SQL that skipped
 * both. No instant is supplied from here: `V010`'s trigger stamps each one with {@code clock_timestamp()}, after the lock
 * the act waited on, and every write returns the row as written.
 */
public final class JdbcBnplEnablementStore implements BnplEnablementStore<Connection> {

    private static final String TABLE = "merchant.merchant_bnpl_enablement";

    private static final String COLUMNS =
            "id, merchant_id, status, proposed_by, proposed_at, proposal_reason, decided_by, decided_at,"
                    + " decision_reason, withdrawn_by, withdrawn_at, withdrawal_reason";

    /** SQLState 23505 - the one-live index's answer, not a failure. */
    private static final String UNIQUE_VIOLATION = "23505";

    @Override
    public BnplEnablement insertProposal(
            Connection unitOfWork, BnplEnablementId id, MerchantId merchant, String proposedBy, String reason) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Savepoint attempt;
        try {
            // The savepoint keeps a lost race a domain answer: a unique violation poisons the transaction, and the
            // caller's idempotency claim shares it.
            attempt = unitOfWork.setSavepoint("bnpl_enablement_propose");
        } catch (SQLException failure) {
            throw new MerchantStorageException(DatabaseFailure.describe("preparing a BNPL enablement proposal", failure));
        }
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO " + TABLE + " (id, merchant_id, status, proposed_by, proposed_at, proposal_reason)"
                        + " VALUES (?, ?, 'PROPOSED', ?, clock_timestamp(), ?) RETURNING " + COLUMNS)) {
            insert.setObject(1, id.value());
            insert.setObject(2, merchant.value());
            insert.setString(3, proposedBy);
            insert.setString(4, reason);
            BnplEnablement proposed;
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                proposed = from(row);
            }
            unitOfWork.releaseSavepoint(attempt);
            return proposed;
        } catch (SQLException failure) {
            if (UNIQUE_VIOLATION.equals(failure.getSQLState())) {
                // The only unique a fresh UUIDv7 row can meet is the one-live index.
                rollbackTo(unitOfWork, attempt);
                throw new LivePresent();
            }
            throw new MerchantStorageException(DatabaseFailure.describe("proposing a BNPL enablement", failure));
        }
    }

    @Override
    public Optional<BnplEnablement> find(Connection unitOfWork, MerchantId merchant, BnplEnablementId id) {
        return one(unitOfWork, "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ? AND merchant_id = ?",
                "reading a BNPL enablement", id.value(), merchant.value());
    }

    @Override
    public Optional<BnplEnablement> lock(Connection unitOfWork, MerchantId merchant, BnplEnablementId id) {
        return one(unitOfWork, "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ? AND merchant_id = ? FOR UPDATE",
                "locking a BNPL enablement", id.value(), merchant.value());
    }

    @Override
    public Optional<BnplEnablement> decide(
            Connection unitOfWork, BnplEnablementId id, BnplEnablementStatus to, String decidedBy, String reason) {
        if (to != BnplEnablementStatus.ACTIVE && to != BnplEnablementStatus.REJECTED) {
            throw new IllegalArgumentException("a proposal is decided ACTIVE or REJECTED, never " + to);
        }
        // The approval's statement carries the four-eyes predicate itself: the middle rank of three.
        String fourEyes = to == BnplEnablementStatus.ACTIVE ? " AND proposed_by <> ?" : "";
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE " + TABLE + " SET status = ?, decided_by = ?, decision_reason = ?"
                        + " WHERE id = ? AND status = 'PROPOSED'" + fourEyes + " RETURNING " + COLUMNS)) {
            update.setString(1, to.name());
            update.setString(2, decidedBy);
            update.setString(3, reason);
            update.setObject(4, id.value());
            if (to == BnplEnablementStatus.ACTIVE) {
                update.setString(5, decidedBy);
            }
            return returned(update);
        } catch (SQLException failure) {
            throw new MerchantStorageException(DatabaseFailure.describe("deciding a BNPL enablement", failure));
        }
    }

    @Override
    public Optional<BnplEnablement> withdraw(
            Connection unitOfWork, BnplEnablementId id, String withdrawnBy, String reason) {
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE " + TABLE + " SET status = 'WITHDRAWN', withdrawn_by = ?, withdrawal_reason = ?"
                        + " WHERE id = ? AND status = 'ACTIVE' RETURNING " + COLUMNS)) {
            update.setString(1, withdrawnBy);
            update.setString(2, reason);
            update.setObject(3, id.value());
            return returned(update);
        } catch (SQLException failure) {
            throw new MerchantStorageException(DatabaseFailure.describe("withdrawing a BNPL enablement", failure));
        }
    }

    @Override
    public Optional<BnplEnablement> activeForShare(Connection unitOfWork, MerchantId merchant) {
        return one(unitOfWork,
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE merchant_id = ? AND status = 'ACTIVE' FOR SHARE",
                "reading a merchant's active BNPL enablement", merchant.value());
    }

    @Override
    public Optional<BnplEnablement> live(Connection unitOfWork, MerchantId merchant) {
        return one(unitOfWork,
                "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE merchant_id = ?"
                        + " AND status IN (" + BnplEnablementStatus.liveSqlValueList() + ")",
                "reading a merchant's live BNPL enablement", merchant.value());
    }

    // ------------------------------------------------------------------ plumbing

    private static Optional<BnplEnablement> one(Connection unitOfWork, String sql, String what, UUID... parameters) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                select.setObject(i + 1, parameters[i]);
            }
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? Optional.of(from(rows)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(DatabaseFailure.describe(what, failure));
        }
    }

    private static Optional<BnplEnablement> returned(PreparedStatement update) throws SQLException {
        try (ResultSet rows = update.executeQuery()) {
            return rows.next() ? Optional.of(from(rows)) : Optional.empty();
        }
    }

    private static void rollbackTo(Connection unitOfWork, Savepoint attempt) {
        try {
            unitOfWork.rollback(attempt);
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("abandoning a lost BNPL enablement proposal race", failure));
        }
    }

    private static BnplEnablement from(ResultSet row) throws SQLException {
        return new BnplEnablement(
                BnplEnablementId.of(row.getObject(1, UUID.class)),
                MerchantId.of(row.getObject(2, UUID.class)),
                BnplEnablementStatus.valueOf(row.getString(3)),
                row.getString(4),
                row.getTimestamp(5).toInstant(),
                row.getString(6),
                Optional.ofNullable(row.getString(7)),
                instant(row.getTimestamp(8)),
                Optional.ofNullable(row.getString(9)),
                Optional.ofNullable(row.getString(10)),
                instant(row.getTimestamp(11)),
                Optional.ofNullable(row.getString(12)));
    }

    private static Optional<Instant> instant(Timestamp stamp) {
        return Optional.ofNullable(stamp).map(Timestamp::toInstant);
    }
}
