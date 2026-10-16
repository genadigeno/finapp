package com.finapp.lending;

import com.finapp.creditmath.DueItemId;
import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
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
 * {@link WaiverStore} over plain JDBC (`P11-TSK-025`, ADR-0033): every instant the database's, every failure its operation
 * and SQLSTATE only ({@link LendingStorageException}) - never a reason or an amount.
 */
public final class JdbcWaiverStore implements WaiverStore {

    private static final String SELECT = "SELECT w.id, w.loan_id, w.kind, w.fee_assessment_id, w.due_item_id, w.currency,"
            + " w.scale, w.amount_minor, w.agreement_version, w.proposed_by, w.proposed_at, w.proposal_reason, d.outcome,"
            + " d.decided_by, d.decided_at, d.decision_reason, d.journal_entry_id"
            + " FROM lending.waiver w LEFT JOIN lending.waiver_decision d ON d.waiver_id = w.id";

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
    public Instant insert(Connection unitOfWork, NewWaiver proposal) {
        Objects.requireNonNull(proposal, "proposal");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.waiver (id, loan_id, kind, fee_assessment_id, due_item_id, currency, scale,"
                        + " amount_minor, agreement_version, proposed_by, proposed_by_type, proposed_at, proposal_reason,"
                        + " correlation_id)"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, statement_timestamp(), ?, ?) RETURNING proposed_at")) {
            int i = 0;
            insert.setObject(++i, proposal.id());
            insert.setObject(++i, proposal.loan().value());
            insert.setString(++i, proposal.kind().name());
            insert.setObject(++i, proposal.assessment().orElse(null));
            insert.setObject(++i, proposal.dueItem().value());
            insert.setString(++i, proposal.amount().currency().code());
            insert.setInt(++i, proposal.amount().scale());
            insert.setLong(++i, proposal.amount().minorUnits());
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
            throw failed("inserting a waiver", failure);
        }
    }

    @Override
    public boolean decide(Connection unitOfWork, Decision decision) {
        Objects.requireNonNull(decision, "decision");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.waiver_decision (id, waiver_id, proposed_by, outcome, decided_by, decided_by_type,"
                        + " decided_at, decision_reason, journal_entry_id, correlation_id)"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " VALUES (?, ?, ?, ?, ?, ?, statement_timestamp(), ?, ?, ?)"
                        + " ON CONFLICT (waiver_id) DO NOTHING")) {
            int i = 0;
            insert.setObject(++i, decision.id());
            insert.setObject(++i, decision.waiver());
            insert.setString(++i, decision.proposedBy());
            insert.setString(++i, decision.outcome().name());
            insert.setString(++i, decision.decider().id());
            insert.setString(++i, decision.decider().type().name());
            insert.setString(++i, decision.reason());
            insert.setObject(++i, decision.journalEntry().map(JournalEntryId::value).orElse(null));
            insert.setString(++i, decision.correlation().value());
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("inserting a waiver decision", failure);
        }
    }

    @Override
    public Optional<Waiver> find(Connection unitOfWork, LoanId loan, UUID id) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement(SELECT + " WHERE w.id = ? AND w.loan_id = ?")) {
            select.setObject(1, id);
            select.setObject(2, loan.value());
            return waivers(select).stream().findFirst();
        } catch (SQLException failure) {
            throw failed("reading a waiver", failure);
        }
    }

    @Override
    public List<Waiver> ofLoan(Connection unitOfWork, LoanId loan, int limit) {
        Objects.requireNonNull(loan, "loan");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                SELECT + " WHERE w.loan_id = ? ORDER BY w.proposed_at DESC, w.id DESC LIMIT ?")) {
            select.setObject(1, loan.value());
            select.setInt(2, limit);
            return waivers(select);
        } catch (SQLException failure) {
            throw failed("reading a loan's waivers", failure);
        }
    }

    @Override
    public List<Waiver> ofAssessment(Connection unitOfWork, UUID assessment) {
        Objects.requireNonNull(assessment, "assessment");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                SELECT + " WHERE w.fee_assessment_id = ? ORDER BY w.proposed_at, w.id")) {
            select.setObject(1, assessment);
            return waivers(select);
        } catch (SQLException failure) {
            throw failed("reading an assessment's waivers", failure);
        }
    }

    @Override
    public boolean pending(Connection unitOfWork, LoanId loan, WaiverKind kind, DueItemId item) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(item, "item");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM lending.waiver w WHERE w.loan_id = ? AND w.kind = ? AND w.due_item_id = ?"
                        + " AND NOT EXISTS (SELECT 1 FROM lending.waiver_decision d WHERE d.waiver_id = w.id))")) {
            select.setObject(1, loan.value());
            select.setString(2, kind.name());
            select.setObject(3, item.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw failed("reading a pending waiver", failure);
        }
    }

    private static List<Waiver> waivers(PreparedStatement select) throws SQLException {
        List<Waiver> found = new ArrayList<>();
        try (ResultSet row = select.executeQuery()) {
            while (row.next()) {
                found.add(waiver(row));
            }
        }
        return found;
    }

    private static Waiver waiver(ResultSet row) throws SQLException {
        CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
        String outcome = row.getString("outcome");
        WaiverStatus status = outcome == null ? WaiverStatus.PROPOSED : WaiverStatus.valueOf(outcome);
        Timestamp decidedAt = row.getTimestamp("decided_at");
        UUID entry = row.getObject("journal_entry_id", UUID.class);
        return new Waiver(
                row.getObject("id", UUID.class),
                LoanId.of(row.getObject("loan_id", UUID.class)),
                WaiverKind.valueOf(row.getString("kind")),
                Optional.ofNullable(row.getObject("fee_assessment_id", UUID.class)),
                DueItemId.of(row.getObject("due_item_id", UUID.class)),
                Money.ofPersisted(row.getLong("amount_minor"), currency, row.getInt("scale")),
                row.getInt("agreement_version"),
                status,
                row.getString("proposed_by"),
                row.getTimestamp("proposed_at").toInstant(),
                row.getString("proposal_reason"),
                Optional.ofNullable(row.getString("decided_by")),
                Optional.ofNullable(decidedAt).map(Timestamp::toInstant),
                Optional.ofNullable(row.getString("decision_reason")),
                Optional.ofNullable(entry).map(JournalEntryId::of));
    }

    private static LendingStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new LendingStorageException("lending storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
