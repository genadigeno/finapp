package com.finapp.lending;

import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link LoanApplicationStore} over plain JDBC (`P11-TSK-010`, ADR-0033): every time the database's, every customer
 * read owner-scoped in the statement itself (ADR-0031), every failure its operation and SQLSTATE only
 * ({@link LendingStorageException}).
 */
public final class JdbcLoanApplicationStore implements LoanApplicationStore {

    private static final String COLUMNS =
            "a.id, a.party_id, a.product, a.status, a.decision_request_id, a.terms_version_id, v.version,"
                    + " a.repayment_day, a.repayment_method, a.payout_preference, a.submitted_at, a.closed_at,"
                    + " a.correlation_id";

    @Override
    public boolean insert(Connection unitOfWork, NewApplication application) {
        Objects.requireNonNull(application, "application");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.loan_application (id, party_id, product, status, decision_request_id,"
                        + " terms_version_id, repayment_day, repayment_method, payout_preference, submitted_by,"
                        + " submitted_by_type, correlation_id)"
                        + " VALUES (?, ?, ?, 'SUBMITTED', ?, ?, ?, ?, ?, ?, ?, ?)"
                        // The one-open arbiter's backstop: a second open application for the party and product is
                        // nothing written, never an aborted transaction.
                        + " ON CONFLICT (party_id, product) WHERE status IN ('SUBMITTED', 'AWAITING_DECISION')"
                        + " DO NOTHING")) {
            insert.setObject(1, application.id().value());
            insert.setObject(2, application.party());
            insert.setString(3, application.product().name());
            insert.setObject(4, application.decisionRequest());
            insert.setObject(5, application.termsVersion().value());
            insert.setInt(6, application.repaymentDay());
            insert.setString(7, application.repaymentMethod().name());
            insert.setString(8, application.payoutPreference().name());
            insert.setString(9, application.submittedBy().id());
            insert.setString(10, application.submittedBy().type().name());
            insert.setString(11, application.correlation().value());
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("inserting a loan application", failure);
        }
    }

    @Override
    public boolean transition(
            Connection unitOfWork, LoanApplicationId id, LoanApplicationStatus from, LoanApplicationStatus to) {
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE lending.loan_application SET status = ? WHERE id = ? AND status = ?")) {
            update.setString(1, to.name());
            update.setObject(2, id.value());
            update.setString(3, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("moving a loan application", failure);
        }
    }

    @Override
    public void appendEvent(Connection unitOfWork, HistoryRow row) {
        Objects.requireNonNull(row, "row");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.loan_application_event (id, application_id, from_status, to_status, reason, actor,"
                        + " actor_type, correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, row.eventId());
            insert.setObject(2, row.application().value());
            insert.setString(3, row.from().map(Enum::name).orElse(null));
            insert.setString(4, row.to().name());
            insert.setString(5, row.reason());
            insert.setString(6, row.actor().id());
            insert.setString(7, row.actor().type().name());
            insert.setString(8, row.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("appending a loan application event", failure);
        }
    }

    @Override
    public Optional<LoanApplication> ownedBy(Connection unitOfWork, LoanApplicationId id, UUID party) {
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT " + COLUMNS
                + " FROM lending.loan_application a JOIN lending.loan_terms_version v ON v.id = a.terms_version_id"
                + " WHERE a.id = ? AND a.party_id = ?")) {
            return owned(select, id, party);
        } catch (SQLException failure) {
            throw failed("reading a loan application", failure);
        }
    }

    @Override
    public Optional<LoanApplication> lockOwnedBy(Connection unitOfWork, LoanApplicationId id, UUID party) {
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT " + COLUMNS
                + " FROM lending.loan_application a JOIN lending.loan_terms_version v ON v.id = a.terms_version_id"
                + " WHERE a.id = ? AND a.party_id = ? FOR UPDATE OF a")) {
            return owned(select, id, party);
        } catch (SQLException failure) {
            throw failed("locking a loan application", failure);
        }
    }

    @Override
    public List<LoanApplicationId> claimAwaiting(Connection unitOfWork, int limit, Duration permit) {
        if (limit < 1) {
            throw new IllegalArgumentException("a claim takes at least one application");
        }
        try (PreparedStatement claim = unitOfWork.prepareStatement(
                "UPDATE lending.loan_application SET next_step_at = statement_timestamp() + ? * interval '1 millisecond'"
                        + " WHERE id IN (SELECT id FROM lending.loan_application WHERE status = 'AWAITING_DECISION'"
                        + " AND next_step_at <= statement_timestamp()"
                        + " ORDER BY next_step_at, id LIMIT ? FOR UPDATE SKIP LOCKED)"
                        + " AND status = 'AWAITING_DECISION' RETURNING id")) {
            claim.setLong(1, permit.toMillis());
            claim.setInt(2, limit);
            List<LoanApplicationId> claimed = new ArrayList<>();
            try (ResultSet rows = claim.executeQuery()) {
                while (rows.next()) {
                    claimed.add(LoanApplicationId.of(rows.getObject(1, UUID.class)));
                }
            }
            return claimed;
        } catch (SQLException failure) {
            throw failed("claiming loan applications awaiting a decision", failure);
        }
    }

    @Override
    public Optional<LoanApplication> lockClaimed(Connection unitOfWork, LoanApplicationId id) {
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT " + COLUMNS
                + " FROM lending.loan_application a JOIN lending.loan_terms_version v ON v.id = a.terms_version_id"
                + " WHERE a.id = ? FOR UPDATE OF a")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(application(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("locking a claimed loan application", failure);
        }
    }

    @Override
    public boolean hint(Connection unitOfWork, UUID decisionRequest) {
        Objects.requireNonNull(decisionRequest, "decisionRequest");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE lending.loan_application SET next_step_at = statement_timestamp()"
                        + " WHERE decision_request_id = ? AND status = 'AWAITING_DECISION'"
                        + " AND next_step_at > statement_timestamp()")) {
            update.setObject(1, decisionRequest);
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("hinting a loan application", failure);
        }
    }

    /** Binds the id and the owner and reads the one row - the statement is the caller's own. */
    private static Optional<LoanApplication> owned(PreparedStatement select, LoanApplicationId id, UUID party)
            throws SQLException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(party, "party");
        select.setObject(1, id.value());
        select.setObject(2, party);
        try (ResultSet row = select.executeQuery()) {
            return row.next() ? Optional.of(application(row)) : Optional.empty();
        }
    }

    private static LoanApplication application(ResultSet row) throws SQLException {
        Timestamp closed = row.getTimestamp("closed_at");
        return new LoanApplication(
                LoanApplicationId.of(row.getObject("id", UUID.class)),
                row.getObject("party_id", UUID.class),
                LoanProduct.valueOf(row.getString("product")),
                LoanApplicationStatus.valueOf(row.getString("status")),
                row.getObject("decision_request_id", UUID.class),
                LoanTermsVersionId.of(row.getObject("terms_version_id", UUID.class)),
                row.getInt("version"),
                row.getInt("repayment_day"),
                RepaymentMethod.valueOf(row.getString("repayment_method")),
                PayoutPreference.valueOf(row.getString("payout_preference")),
                row.getTimestamp("submitted_at").toInstant(),
                Optional.ofNullable(closed).map(Timestamp::toInstant),
                CorrelationId.of(row.getString("correlation_id")));
    }

    private static LendingStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new LendingStorageException("lending storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
