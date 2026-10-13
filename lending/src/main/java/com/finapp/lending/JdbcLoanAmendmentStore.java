package com.finapp.lending;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link LoanAmendmentStore} over plain JDBC (`P11-TSK-027`, ADR-0033): every instant the database's, the status derived in
 * the statement on the database clock, every failure its operation and SQLSTATE only ({@link LendingStorageException}) -
 * never a reason or a rate.
 */
public final class JdbcLoanAmendmentStore implements LoanAmendmentStore {

    /**
     * The amendment, its decision and the version its acceptance made, and its status derived now: accepted, rejected,
     * lapsed (the amended version no longer the latest, the loan no longer ACTIVE, or an approval expired), approved,
     * proposed - in that order.
     */
    private static final String SELECT = "SELECT m.id, m.loan_id, m.amends_agreement_id, m.amends_version,"
            + " m.nominal_annual_rate, m.remaining_instalments, m.reschedule_arrears, m.document_sha256, m.proposed_by,"
            + " m.proposed_at, m.proposal_reason, d.outcome, d.decided_by, d.decided_at, d.decision_reason, d.expires_at,"
            + " a0.nominal_annual_rate AS amends_rate, x.version AS accepted_version,"
            + " CASE WHEN x.id IS NOT NULL THEN 'ACCEPTED'"
            + " WHEN d.outcome = 'REJECTED' THEN 'REJECTED'"
            + " WHEN l.status <> 'ACTIVE'"
            + " OR m.amends_version <> (SELECT max(v.version) FROM lending.loan_agreement v WHERE v.loan_id = m.loan_id)"
            + " OR (d.outcome = 'APPROVED' AND d.expires_at <= statement_timestamp()) THEN 'LAPSED'"
            + " WHEN d.outcome = 'APPROVED' THEN 'APPROVED'"
            + " ELSE 'PROPOSED' END AS status"
            + " FROM lending.loan_amendment m"
            + " JOIN lending.loan l ON l.id = m.loan_id"
            + " JOIN lending.loan_agreement a0 ON a0.id = m.amends_agreement_id"
            + " LEFT JOIN lending.loan_amendment_decision d ON d.amendment_id = m.id"
            + " LEFT JOIN lending.loan_agreement x ON x.amendment_id = m.id";

    @Override
    public Instant databaseNow(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT statement_timestamp()");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getTimestamp(1).toInstant();
        } catch (SQLException failure) {
            throw failed("reading the database clock", failure);
        }
    }

    @Override
    public Instant insert(Connection unitOfWork, NewAmendment proposal) {
        Objects.requireNonNull(proposal, "proposal");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.loan_amendment (id, loan_id, amends_agreement_id, amends_version, nominal_annual_rate,"
                        + " remaining_instalments, reschedule_arrears, document_sha256, proposed_by, proposed_by_type,"
                        + " proposed_at, proposal_reason, correlation_id)"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, statement_timestamp(), ?, ?) RETURNING proposed_at")) {
            int i = 0;
            insert.setObject(++i, proposal.id());
            insert.setObject(++i, proposal.loan().value());
            insert.setObject(++i, proposal.amendsAgreement().value());
            insert.setInt(++i, proposal.amendsVersion());
            insert.setString(++i, proposal.nominalAnnualRate().toPlainString());
            insert.setInt(++i, proposal.remainingInstalments());
            insert.setBoolean(++i, proposal.rescheduleArrears());
            insert.setBytes(++i, proposal.documentSha256());
            insert.setString(++i, proposal.proposer().id());
            insert.setString(++i, proposal.proposer().type().name());
            insert.setString(++i, proposal.reason());
            insert.setString(++i, proposal.correlation().value());
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return row.getTimestamp(1).toInstant();
            }
        } catch (SQLException failure) {
            throw failed("inserting an amendment", failure);
        }
    }

    @Override
    public boolean decide(Connection unitOfWork, Decision decision) {
        Objects.requireNonNull(decision, "decision");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.loan_amendment_decision (id, amendment_id, proposed_by, outcome, decided_by,"
                        + " decided_by_type, decided_at, expires_at, decision_reason, correlation_id)"
                        // The instants are the trigger's; the placeholders are overwritten.
                        + " VALUES (?, ?, ?, ?, ?, ?, statement_timestamp(), NULL, ?, ?)"
                        + " ON CONFLICT (amendment_id) DO NOTHING")) {
            int i = 0;
            insert.setObject(++i, decision.id());
            insert.setObject(++i, decision.amendment());
            insert.setString(++i, decision.proposedBy());
            insert.setString(++i, decision.outcome().name());
            insert.setString(++i, decision.decider().id());
            insert.setString(++i, decision.decider().type().name());
            insert.setString(++i, decision.reason());
            insert.setString(++i, decision.correlation().value());
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("inserting an amendment decision", failure);
        }
    }

    @Override
    public Optional<LoanAmendment> find(Connection unitOfWork, LoanId loan, UUID id) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement(SELECT + " WHERE m.id = ? AND m.loan_id = ?")) {
            select.setObject(1, id);
            select.setObject(2, loan.value());
            return amendments(select).stream().findFirst();
        } catch (SQLException failure) {
            throw failed("reading an amendment", failure);
        }
    }

    @Override
    public List<LoanAmendment> ofLoan(Connection unitOfWork, LoanId loan, int limit) {
        Objects.requireNonNull(loan, "loan");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                SELECT + " WHERE m.loan_id = ? ORDER BY m.proposed_at DESC, m.id DESC LIMIT ?")) {
            select.setObject(1, loan.value());
            select.setInt(2, limit);
            return amendments(select);
        } catch (SQLException failure) {
            throw failed("reading a loan's amendments", failure);
        }
    }

    @Override
    public boolean open(Connection unitOfWork, LoanId loan) {
        Objects.requireNonNull(loan, "loan");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM (" + SELECT + " WHERE m.loan_id = ?) a"
                        + " WHERE a.status IN ('PROPOSED', 'APPROVED'))")) {
            select.setObject(1, loan.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw failed("reading a loan's open amendment", failure);
        }
    }

    @Override
    public Optional<Integer> scheduleVersionOf(Connection unitOfWork, LoanId loan, int agreementVersion) {
        Objects.requireNonNull(loan, "loan");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT schedule_version FROM lending.repayment_schedule WHERE loan_id = ? AND agreement_version = ?")) {
            select.setObject(1, loan.value());
            select.setInt(2, agreementVersion);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(row.getInt(1)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("reading an agreement version's schedule", failure);
        }
    }

    @Override
    public void insertRescheduling(Connection unitOfWork, Rescheduling rescheduling) {
        Objects.requireNonNull(rescheduling, "rescheduling");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.loan_amendment_rescheduling (id, amendment_id, loan_id, billing_id, currency, scale,"
                        + " principal_minor, journal_entry_id, created_at, correlation_id)"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, statement_timestamp(), ?)")) {
            int i = 0;
            insert.setObject(++i, rescheduling.id());
            insert.setObject(++i, rescheduling.amendment());
            insert.setObject(++i, rescheduling.loan().value());
            insert.setObject(++i, rescheduling.billing().value());
            insert.setString(++i, rescheduling.principal().currency().code());
            insert.setInt(++i, rescheduling.principal().scale());
            insert.setLong(++i, rescheduling.principal().minorUnits());
            insert.setObject(++i, rescheduling.entry().value());
            insert.setString(++i, rescheduling.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a re-scheduling", failure);
        }
    }

    private static List<LoanAmendment> amendments(PreparedStatement select) throws SQLException {
        List<LoanAmendment> found = new ArrayList<>();
        try (ResultSet row = select.executeQuery()) {
            while (row.next()) {
                found.add(amendment(row));
            }
        }
        return found;
    }

    private static LoanAmendment amendment(ResultSet row) throws SQLException {
        String outcome = row.getString("outcome");
        Timestamp decidedAt = row.getTimestamp("decided_at");
        Timestamp expiresAt = row.getTimestamp("expires_at");
        int accepted = row.getInt("accepted_version");
        Optional<Integer> acceptedVersion = row.wasNull() ? Optional.empty() : Optional.of(accepted);
        return new LoanAmendment(
                row.getObject("id", UUID.class),
                LoanId.of(row.getObject("loan_id", UUID.class)),
                LoanAgreementId.of(row.getObject("amends_agreement_id", UUID.class)),
                row.getInt("amends_version"),
                new BigDecimal(row.getString("amends_rate")),
                new BigDecimal(row.getString("nominal_annual_rate")),
                row.getInt("remaining_instalments"),
                row.getBoolean("reschedule_arrears"),
                row.getBytes("document_sha256"),
                LoanAmendmentStatus.valueOf(row.getString("status")),
                row.getString("proposed_by"),
                row.getTimestamp("proposed_at").toInstant(),
                row.getString("proposal_reason"),
                "APPROVED".equals(outcome),
                Optional.ofNullable(row.getString("decided_by")),
                Optional.ofNullable(decidedAt).map(Timestamp::toInstant),
                Optional.ofNullable(row.getString("decision_reason")),
                Optional.ofNullable(expiresAt).map(Timestamp::toInstant),
                acceptedVersion);
    }

    private static LendingStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new LendingStorageException("lending storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
