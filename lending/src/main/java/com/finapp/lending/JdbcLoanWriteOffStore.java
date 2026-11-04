package com.finapp.lending;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.Date;
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
 * {@link LoanWriteOffStore} over plain JDBC (`P14-TSK-021`, ADR-0033): every instant the database's, every failure its operation
 * and SQLSTATE only ({@link LendingStorageException}) - never a reason or an amount.
 */
public final class JdbcLoanWriteOffStore implements LoanWriteOffStore {

    private static final String SELECT = "SELECT w.id, w.loan_id, w.currency, w.scale, w.principal_minor,"
            + " w.principal_due_minor, w.interest_accrued_minor, w.interest_due_minor, w.fees_due_minor, w.revision,"
            + " w.default_event_id, w.agreement_version, w.proposed_by, w.proposed_at, w.proposal_reason, d.outcome,"
            + " d.decided_by, d.decided_at, d.decision_reason, d.journal_entry_id, d.value_date"
            + " FROM lending.loan_write_off w LEFT JOIN lending.loan_write_off_decision d ON d.write_off_id = w.id";

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
    public Instant insert(Connection unitOfWork, NewProposal proposal) {
        Objects.requireNonNull(proposal, "proposal");
        LoanWriteOffPosition position = proposal.position();
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.loan_write_off (id, loan_id, currency, scale, principal_minor, principal_due_minor,"
                        + " interest_accrued_minor, interest_due_minor, fees_due_minor, revision, default_event_id,"
                        + " agreement_version, proposed_by, proposed_by_type, proposed_at, proposal_reason, correlation_id)"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, statement_timestamp(), ?, ?) RETURNING proposed_at")) {
            int i = 0;
            insert.setObject(++i, proposal.id());
            insert.setObject(++i, proposal.loan().value());
            insert.setString(++i, position.principal().currency().code());
            insert.setInt(++i, position.principal().scale());
            insert.setLong(++i, position.principal().minorUnits());
            insert.setLong(++i, position.principalDue().minorUnits());
            insert.setLong(++i, position.interestAccrued().minorUnits());
            insert.setLong(++i, position.interestDue().minorUnits());
            insert.setLong(++i, position.feesDue().minorUnits());
            insert.setLong(++i, position.revision());
            insert.setObject(++i, proposal.defaultEvent());
            insert.setInt(++i, proposal.agreementVersion());
            insert.setString(++i, proposal.proposer().id());
            insert.setString(++i, proposal.proposer().type().name());
            insert.setString(++i, proposal.reason());
            insert.setString(++i, proposal.correlation().value());
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return row.getTimestamp(1).toInstant();
            }
        } catch (SQLException failure) {
            throw failed("inserting a write-off", failure);
        }
    }

    @Override
    public boolean decide(Connection unitOfWork, Decision decision) {
        Objects.requireNonNull(decision, "decision");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.loan_write_off_decision (id, write_off_id, loan_id, proposed_by, outcome, decided_by,"
                        + " decided_by_type, decided_at, decision_reason, journal_entry_id, value_date, correlation_id)"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, statement_timestamp(), ?, ?, ?, ?)"
                        + " ON CONFLICT (write_off_id) DO NOTHING")) {
            int i = 0;
            insert.setObject(++i, decision.id());
            insert.setObject(++i, decision.writeOff());
            insert.setObject(++i, decision.loan().value());
            insert.setString(++i, decision.proposedBy());
            insert.setString(++i, decision.outcome().name());
            insert.setString(++i, decision.decider().id());
            insert.setString(++i, decision.decider().type().name());
            insert.setString(++i, decision.reason());
            insert.setObject(++i, decision.journalEntry().map(JournalEntryId::value).orElse(null));
            insert.setObject(++i, decision.valueDate().orElse(null));
            insert.setString(++i, decision.correlation().value());
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("inserting a write-off decision", failure);
        }
    }

    @Override
    public boolean markWrittenOff(Connection unitOfWork, LoanId loan, LoanStatus from) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(from, "from");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE lending.loan SET status = 'WRITTEN_OFF' WHERE id = ? AND status = ?")) {
            update.setObject(1, loan.value());
            update.setString(2, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("moving a loan WRITTEN_OFF", failure);
        }
    }

    @Override
    public Optional<LoanWriteOff> find(Connection unitOfWork, LoanId loan, UUID id) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement(SELECT + " WHERE w.id = ? AND w.loan_id = ?")) {
            select.setObject(1, id);
            select.setObject(2, loan.value());
            return writeOffs(select).stream().findFirst();
        } catch (SQLException failure) {
            throw failed("reading a write-off", failure);
        }
    }

    @Override
    public boolean pending(Connection unitOfWork, LoanId loan) {
        Objects.requireNonNull(loan, "loan");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM lending.loan_write_off w WHERE w.loan_id = ?"
                        + " AND NOT EXISTS (SELECT 1 FROM lending.loan_write_off_decision d WHERE d.write_off_id = w.id))")) {
            select.setObject(1, loan.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw failed("reading a pending write-off", failure);
        }
    }

    @Override
    public Optional<LoanWriteOff> approvedOf(Connection unitOfWork, LoanId loan) {
        Objects.requireNonNull(loan, "loan");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                SELECT + " WHERE w.loan_id = ? AND d.outcome = 'APPROVED'")) {
            select.setObject(1, loan.value());
            return writeOffs(select).stream().findFirst();
        } catch (SQLException failure) {
            throw failed("reading a loan's approved write-off", failure);
        }
    }

    @Override
    public List<LoanWriteOff> oldestPending(Connection unitOfWork, UUID excludedParty, int limit) {
        Objects.requireNonNull(excludedParty, "excludedParty");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                SELECT + " JOIN lending.loan l ON l.id = w.loan_id WHERE d.id IS NULL AND l.party_id <> ?"
                        + " ORDER BY w.proposed_at, w.id LIMIT ?")) {
            select.setObject(1, excludedParty);
            select.setInt(2, limit);
            return writeOffs(select);
        } catch (SQLException failure) {
            throw failed("reading the pending write-offs", failure);
        }
    }

    private static List<LoanWriteOff> writeOffs(PreparedStatement select) throws SQLException {
        List<LoanWriteOff> found = new ArrayList<>();
        try (ResultSet row = select.executeQuery()) {
            while (row.next()) {
                found.add(writeOff(row));
            }
        }
        return found;
    }

    private static LoanWriteOff writeOff(ResultSet row) throws SQLException {
        CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
        int scale = row.getInt("scale");
        String outcome = row.getString("outcome");
        Timestamp decidedAt = row.getTimestamp("decided_at");
        UUID entry = row.getObject("journal_entry_id", UUID.class);
        Date valueDate = row.getDate("value_date");
        LoanWriteOffPosition position = new LoanWriteOffPosition(
                Money.ofPersisted(row.getLong("principal_minor"), currency, scale),
                Money.ofPersisted(row.getLong("principal_due_minor"), currency, scale),
                Money.ofPersisted(row.getLong("interest_accrued_minor"), currency, scale),
                Money.ofPersisted(row.getLong("interest_due_minor"), currency, scale),
                Money.ofPersisted(row.getLong("fees_due_minor"), currency, scale),
                row.getLong("revision"));
        return new LoanWriteOff(
                row.getObject("id", UUID.class),
                LoanId.of(row.getObject("loan_id", UUID.class)),
                position,
                row.getObject("default_event_id", UUID.class),
                row.getInt("agreement_version"),
                outcome == null ? LoanWriteOffStatus.PROPOSED : LoanWriteOffStatus.valueOf(outcome),
                row.getString("proposed_by"),
                row.getTimestamp("proposed_at").toInstant(),
                row.getString("proposal_reason"),
                Optional.ofNullable(row.getString("decided_by")),
                Optional.ofNullable(decidedAt).map(Timestamp::toInstant),
                Optional.ofNullable(row.getString("decision_reason")),
                Optional.ofNullable(entry).map(JournalEntryId::of),
                Optional.ofNullable(valueDate).map(Date::toLocalDate));
    }

    private static LendingStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new LendingStorageException("lending storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
