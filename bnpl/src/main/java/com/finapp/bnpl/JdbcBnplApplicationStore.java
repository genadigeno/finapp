package com.finapp.bnpl;

import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link BnplApplicationStore} over plain JDBC (`P12-TSK-010`, ADR-0033; lending's {@code JdbcLoanApplicationStore}):
 * every time the database's, every customer read owner-scoped in the statement itself (ADR-0031), every failure its
 * operation and SQLSTATE only ({@link BnplStorageException}).
 */
public final class JdbcBnplApplicationStore implements BnplApplicationStore {

    private static final String COLUMNS =
            "a.id, a.party_id, a.product, a.plan, a.status, a.closure_reason, a.checkout_session_id, a.merchant_id,"
                    + " a.decision_request_id, a.terms_version_id, v.version, a.submitted_at, a.closed_at,"
                    + " a.correlation_id";

    private static final String FROM =
            " FROM bnpl.bnpl_application a JOIN bnpl.bnpl_terms_version v ON v.id = a.terms_version_id";

    @Override
    public boolean insert(Connection unitOfWork, NewApplication application) {
        Objects.requireNonNull(application, "application");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_application (id, party_id, product, plan, status, checkout_session_id,"
                        + " merchant_id, decision_request_id, terms_version_id, submitted_by, submitted_by_type,"
                        + " submitted_at, next_step_at, correlation_id)"
                        + " VALUES (?, ?, ?, ?, 'SUBMITTED', ?, ?, ?, ?, ?, ?, clock_timestamp(), clock_timestamp(), ?)"
                        // The one-open arbiters - per session (INV-BNPL-03) and per party and product: a second open
                        // application is nothing written, never an aborted transaction. No conflict target, so both
                        // partial uniques answer the same way.
                        + " ON CONFLICT DO NOTHING")) {
            insert.setObject(1, application.id().value());
            insert.setObject(2, application.party());
            insert.setString(3, application.product().name());
            insert.setString(4, application.plan().name());
            insert.setObject(5, application.checkoutSession());
            insert.setObject(6, application.merchant());
            insert.setObject(7, application.decisionRequest());
            insert.setObject(8, application.termsVersion().value());
            insert.setString(9, application.submittedBy().id());
            insert.setString(10, application.submittedBy().type().name());
            insert.setString(11, application.correlation().value());
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("inserting a BNPL application", failure);
        }
    }

    @Override
    public boolean transition(
            Connection unitOfWork, BnplApplicationId id, BnplApplicationStatus from, BnplApplicationStatus to) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE bnpl.bnpl_application SET status = ? WHERE id = ? AND status = ?")) {
            update.setString(1, to.name());
            update.setObject(2, id.value());
            update.setString(3, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("moving a BNPL application", failure);
        }
    }

    @Override
    public void appendEvent(Connection unitOfWork, HistoryRow row) {
        Objects.requireNonNull(row, "row");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_application_event (id, application_id, from_status, to_status, reason, actor,"
                        + " actor_type, occurred_at, correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?)")) {
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
            throw failed("appending a BNPL application event", failure);
        }
    }

    @Override
    public Optional<BnplApplication> ownedBy(Connection unitOfWork, BnplApplicationId id, UUID party) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + FROM + " WHERE a.id = ? AND a.party_id = ?")) {
            return owned(select, id, party);
        } catch (SQLException failure) {
            throw failed("reading a BNPL application", failure);
        }
    }

    @Override
    public Optional<BnplApplication> lockOwnedBy(Connection unitOfWork, BnplApplicationId id, UUID party) {
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + COLUMNS + FROM + " WHERE a.id = ? AND a.party_id = ? FOR UPDATE OF a")) {
            return owned(select, id, party);
        } catch (SQLException failure) {
            throw failed("locking a BNPL application", failure);
        }
    }

    /** Binds the id and the owner and reads the one row - the statement is the caller's own. */
    private static Optional<BnplApplication> owned(PreparedStatement select, BnplApplicationId id, UUID party)
            throws SQLException {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(party, "party");
        select.setObject(1, id.value());
        select.setObject(2, party);
        try (ResultSet row = select.executeQuery()) {
            return row.next() ? Optional.of(application(row)) : Optional.empty();
        }
    }

    private static BnplApplication application(ResultSet row) throws SQLException {
        Timestamp closed = row.getTimestamp("closed_at");
        String reason = row.getString("closure_reason");
        return new BnplApplication(
                BnplApplicationId.of(row.getObject("id", UUID.class)),
                row.getObject("party_id", UUID.class),
                BnplProduct.valueOf(row.getString("product")),
                BnplPlan.valueOf(row.getString("plan")),
                BnplApplicationStatus.valueOf(row.getString("status")),
                row.getObject("checkout_session_id", UUID.class),
                row.getObject("merchant_id", UUID.class),
                row.getObject("decision_request_id", UUID.class),
                BnplTermsVersionId.of(row.getObject("terms_version_id", UUID.class)),
                row.getInt("version"),
                Optional.ofNullable(reason).map(BnplApplicationClosureReason::valueOf),
                row.getTimestamp("submitted_at").toInstant(),
                Optional.ofNullable(closed).map(Timestamp::toInstant),
                CorrelationId.of(row.getString("correlation_id")));
    }

    private static BnplStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new BnplStorageException("bnpl storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
