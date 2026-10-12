package com.finapp.lending;

import com.finapp.ledger.HoldId;
import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link LoanPayoutStore} over plain JDBC (`P11-TSK-015`, ADR-0033): every instant the database's, every edge conditional
 * on the state its caller judged and - for a claim's step - on the permit the claim stamped, so a step whose permit lapsed
 * and was re-claimed writes nothing; every failure its operation and SQLSTATE only ({@link LendingStorageException}).
 */
public final class JdbcLoanPayoutStore implements LoanPayoutStore {

    private static final String COLUMNS = "p.id, p.loan_id, p.payment_method_id, p.wallet_account_id, p.hold_id, p.status,"
            + " p.withdrawal_id, p.not_dispatched_reason, p.next_attempt_at, p.created_at, p.concluded_at";

    @Override
    public void insert(Connection unitOfWork, UUID id, LoanId loan, UUID paymentMethod, LedgerAccountId wallet,
            HoldId hold, CorrelationId correlation) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(paymentMethod, "paymentMethod");
        Objects.requireNonNull(wallet, "wallet");
        Objects.requireNonNull(hold, "hold");
        Objects.requireNonNull(correlation, "correlation");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.loan_payout (id, loan_id, payment_method_id, wallet_account_id, hold_id, status,"
                        // The instants are the trigger's; the placeholders are overwritten.
                        + " next_attempt_at, created_at, correlation_id)"
                        + " VALUES (?, ?, ?, ?, ?, 'PENDING', statement_timestamp(), statement_timestamp(), ?)")) {
            insert.setObject(1, id);
            insert.setObject(2, loan.value());
            insert.setObject(3, paymentMethod);
            insert.setObject(4, wallet.value());
            insert.setObject(5, hold.value());
            insert.setString(6, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a loan payout", failure);
        }
    }

    @Override
    public List<Claimed> claimDue(Connection unitOfWork, int limit, Duration permit) {
        Objects.requireNonNull(permit, "permit");
        if (limit < 1) {
            throw new IllegalArgumentException("a claim takes at least one payout");
        }
        try (PreparedStatement claim = unitOfWork.prepareStatement(
                "UPDATE lending.loan_payout SET status = CASE status WHEN 'PENDING' THEN 'DISPATCHING' ELSE status END,"
                        + " next_attempt_at = statement_timestamp() + ? * interval '1 millisecond'"
                        + " WHERE id IN (SELECT id FROM lending.loan_payout"
                        + " WHERE status IN ('PENDING', 'DISPATCHING', 'DISPATCHED')"
                        + " AND next_attempt_at <= statement_timestamp()"
                        + " ORDER BY next_attempt_at, id LIMIT ? FOR UPDATE SKIP LOCKED)"
                        + " AND status IN ('PENDING', 'DISPATCHING', 'DISPATCHED')"
                        + " RETURNING loan_id, status, next_attempt_at")) {
            claim.setLong(1, permit.toMillis());
            claim.setInt(2, limit);
            List<Claimed> claimed = new ArrayList<>();
            try (ResultSet rows = claim.executeQuery()) {
                while (rows.next()) {
                    claimed.add(new Claimed(LoanId.of(rows.getObject(1, UUID.class)),
                            PayoutStatus.valueOf(rows.getString(2)), rows.getTimestamp(3).toInstant()));
                }
            }
            return claimed;
        } catch (SQLException failure) {
            throw failed("claiming due loan payouts", failure);
        }
    }

    @Override
    public Optional<LoanPayout> find(Connection unitOfWork, LoanId loan) {
        return one(unitOfWork, loan, "", "reading a loan payout");
    }

    @Override
    public Optional<LoanPayout> lock(Connection unitOfWork, LoanId loan) {
        return one(unitOfWork, loan, " FOR UPDATE", "locking a loan payout");
    }

    @Override
    public boolean dispatched(Connection unitOfWork, LoanId loan, Instant permit, UUID withdrawal) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(permit, "permit");
        Objects.requireNonNull(withdrawal, "withdrawal");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE lending.loan_payout SET status = 'DISPATCHED', withdrawal_id = ?"
                        + " WHERE loan_id = ? AND status = 'DISPATCHING' AND next_attempt_at = ?")) {
            update.setObject(1, withdrawal);
            update.setObject(2, loan.value());
            update.setTimestamp(3, Timestamp.from(permit));
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("recording a loan payout dispatched", failure);
        }
    }

    @Override
    public Optional<Instant> notDispatched(Connection unitOfWork, LoanId loan, Instant permit, NotDispatchedReason reason) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(permit, "permit");
        Objects.requireNonNull(reason, "reason");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE lending.loan_payout SET status = 'NOT_DISPATCHED', not_dispatched_reason = ?"
                        + " WHERE loan_id = ? AND status = 'DISPATCHING' AND next_attempt_at = ? RETURNING concluded_at")) {
            update.setString(1, reason.name());
            update.setObject(2, loan.value());
            update.setTimestamp(3, Timestamp.from(permit));
            try (ResultSet row = update.executeQuery()) {
                return row.next() ? Optional.of(row.getTimestamp(1).toInstant()) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("recording a loan payout not dispatched", failure);
        }
    }

    @Override
    public boolean observed(Connection unitOfWork, LoanId loan) {
        Objects.requireNonNull(loan, "loan");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                // The instant is the trigger's; the placeholder only marks the column moved.
                "UPDATE lending.loan_payout SET last_observed_at = statement_timestamp()"
                        + " WHERE loan_id = ? AND status = 'DISPATCHED'")) {
            update.setObject(1, loan.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("observing a loan payout", failure);
        }
    }

    @Override
    public Optional<Instant> concluded(
            Connection unitOfWork, LoanId loan, PayoutStatus outcome, Instant paymentsConcludedAt) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(paymentsConcludedAt, "paymentsConcludedAt");
        if (outcome != PayoutStatus.PAID_OUT && outcome != PayoutStatus.FAILED) {
            throw new IllegalArgumentException("payments concludes a payout PAID_OUT or FAILED, never " + outcome);
        }
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE lending.loan_payout SET status = ?, concluded_at = ?"
                        + " WHERE loan_id = ? AND status = 'DISPATCHED' RETURNING concluded_at")) {
            update.setString(1, outcome.name());
            update.setTimestamp(2, Timestamp.from(paymentsConcludedAt));
            update.setObject(3, loan.value());
            try (ResultSet row = update.executeQuery()) {
                return row.next() ? Optional.of(row.getTimestamp(1).toInstant()) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("concluding a loan payout", failure);
        }
    }

    @Override
    public Optional<PayoutStatus> statusOwnedBy(Connection unitOfWork, LoanId loan, UUID party) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(party, "party");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT p.status FROM lending.loan_payout p JOIN lending.loan l ON l.id = p.loan_id"
                        + " WHERE p.loan_id = ? AND l.party_id = ?")) {
            select.setObject(1, loan.value());
            select.setObject(2, party);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(PayoutStatus.valueOf(row.getString(1))) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("reading an owned loan payout's status", failure);
        }
    }

    @Override
    public Optional<Duration> oldestUnconcludedAge(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT floor(extract(epoch FROM statement_timestamp() - min(created_at)) * 1000)::bigint"
                        + " FROM lending.loan_payout WHERE status IN ('PENDING', 'DISPATCHING', 'DISPATCHED')");
                ResultSet row = select.executeQuery()) {
            row.next();
            long millis = row.getLong(1);
            return row.wasNull() ? Optional.empty() : Optional.of(Duration.ofMillis(Math.max(0, millis)));
        } catch (SQLException failure) {
            throw failed("reading the oldest unconcluded payout", failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static Optional<LoanPayout> one(Connection unitOfWork, LoanId loan, String lock, String operation) {
        Objects.requireNonNull(loan, "loan");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + " FROM lending.loan_payout p WHERE p.loan_id = ?" + lock)) {
            select.setObject(1, loan.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(payout(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
    }

    private static LoanPayout payout(ResultSet row) throws SQLException {
        String reason = row.getString("not_dispatched_reason");
        Timestamp concluded = row.getTimestamp("concluded_at");
        return new LoanPayout(
                row.getObject("id", UUID.class),
                LoanId.of(row.getObject("loan_id", UUID.class)),
                row.getObject("payment_method_id", UUID.class),
                LedgerAccountId.of(row.getObject("wallet_account_id", UUID.class)),
                HoldId.of(row.getObject("hold_id", UUID.class)),
                PayoutStatus.valueOf(row.getString("status")),
                Optional.ofNullable(row.getObject("withdrawal_id", UUID.class)),
                Optional.ofNullable(reason).map(NotDispatchedReason::valueOf),
                row.getTimestamp("next_attempt_at").toInstant(),
                row.getTimestamp("created_at").toInstant(),
                Optional.ofNullable(concluded).map(Timestamp::toInstant));
    }

    private static LendingStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new LendingStorageException("lending storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
