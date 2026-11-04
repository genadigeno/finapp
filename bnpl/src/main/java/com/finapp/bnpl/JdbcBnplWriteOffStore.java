package com.finapp.bnpl;

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
 * {@link BnplWriteOffStore} over plain JDBC (`P14-TSK-021`, ADR-0033): every instant the database's, every failure its operation and
 * SQLSTATE only ({@link BnplStorageException}) - never a reason or an amount.
 */
public final class JdbcBnplWriteOffStore implements BnplWriteOffStore {

    private static final String SELECT = "SELECT w.id, w.obligation_id, w.agreement_id, w.currency, w.scale, w.principal_minor,"
            + " w.principal_due_minor, w.interest_accrued_minor, w.interest_due_minor, w.fees_due_minor, w.revision,"
            + " w.default_event_id, w.agreement_version, w.proposed_by, w.proposed_at, w.proposal_reason, d.outcome,"
            + " d.decided_by, d.decided_at, d.decision_reason, d.journal_entry_id, d.value_date"
            + " FROM bnpl.bnpl_write_off w LEFT JOIN bnpl.bnpl_write_off_decision d ON d.write_off_id = w.id";

    @Override
    public Instant insert(Connection unitOfWork, NewProposal proposal) {
        Objects.requireNonNull(proposal, "proposal");
        BnplWriteOffPosition position = proposal.position();
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_write_off (id, obligation_id, agreement_id, currency, scale, principal_minor,"
                        + " principal_due_minor, interest_accrued_minor, interest_due_minor, fees_due_minor, revision,"
                        + " default_event_id, agreement_version, proposed_by, proposed_by_type, proposed_at, proposal_reason,"
                        + " correlation_id)"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, statement_timestamp(), ?, ?) RETURNING proposed_at")) {
            int i = 0;
            insert.setObject(++i, proposal.id());
            insert.setObject(++i, proposal.obligation().value());
            insert.setObject(++i, proposal.agreement().value());
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
                "INSERT INTO bnpl.bnpl_write_off_decision (id, write_off_id, obligation_id, proposed_by, outcome, decided_by,"
                        + " decided_by_type, decided_at, decision_reason, journal_entry_id, value_date, correlation_id)"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, statement_timestamp(), ?, ?, ?, ?)"
                        + " ON CONFLICT (write_off_id) DO NOTHING")) {
            int i = 0;
            insert.setObject(++i, decision.id());
            insert.setObject(++i, decision.writeOff());
            insert.setObject(++i, decision.obligation().value());
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
    public boolean markWrittenOff(Connection unitOfWork, BnplObligationId obligation) {
        Objects.requireNonNull(obligation, "obligation");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE bnpl.bnpl_obligation SET status = 'WRITTEN_OFF' WHERE id = ? AND status = 'ACTIVE'")) {
            update.setObject(1, obligation.value());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("moving an obligation WRITTEN_OFF", failure);
        }
    }

    @Override
    public Optional<BnplWriteOff> find(Connection unitOfWork, BnplObligationId obligation, UUID id) {
        Objects.requireNonNull(obligation, "obligation");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement(SELECT + " WHERE w.id = ? AND w.obligation_id = ?")) {
            select.setObject(1, id);
            select.setObject(2, obligation.value());
            return writeOffs(select).stream().findFirst();
        } catch (SQLException failure) {
            throw failed("reading a write-off", failure);
        }
    }

    @Override
    public boolean pending(Connection unitOfWork, BnplObligationId obligation) {
        Objects.requireNonNull(obligation, "obligation");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM bnpl.bnpl_write_off w WHERE w.obligation_id = ?"
                        + " AND NOT EXISTS (SELECT 1 FROM bnpl.bnpl_write_off_decision d WHERE d.write_off_id = w.id))")) {
            select.setObject(1, obligation.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw failed("reading a pending write-off", failure);
        }
    }

    @Override
    public Optional<BnplWriteOff> approvedOf(Connection unitOfWork, BnplObligationId obligation) {
        Objects.requireNonNull(obligation, "obligation");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                SELECT + " WHERE w.obligation_id = ? AND d.outcome = 'APPROVED'")) {
            select.setObject(1, obligation.value());
            return writeOffs(select).stream().findFirst();
        } catch (SQLException failure) {
            throw failed("reading an obligation's approved write-off", failure);
        }
    }

    @Override
    public List<BnplWriteOff> oldestPending(Connection unitOfWork, UUID excludedParty, int limit) {
        Objects.requireNonNull(excludedParty, "excludedParty");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                SELECT + " JOIN bnpl.bnpl_obligation o ON o.id = w.obligation_id WHERE d.id IS NULL AND o.party_id <> ?"
                        + " ORDER BY w.proposed_at, w.id LIMIT ?")) {
            select.setObject(1, excludedParty);
            select.setInt(2, limit);
            return writeOffs(select);
        } catch (SQLException failure) {
            throw failed("reading the pending write-offs", failure);
        }
    }

    private static List<BnplWriteOff> writeOffs(PreparedStatement select) throws SQLException {
        List<BnplWriteOff> found = new ArrayList<>();
        try (ResultSet row = select.executeQuery()) {
            while (row.next()) {
                found.add(writeOff(row));
            }
        }
        return found;
    }

    private static BnplWriteOff writeOff(ResultSet row) throws SQLException {
        CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
        int scale = row.getInt("scale");
        String outcome = row.getString("outcome");
        Timestamp decidedAt = row.getTimestamp("decided_at");
        UUID entry = row.getObject("journal_entry_id", UUID.class);
        Date valueDate = row.getDate("value_date");
        BnplWriteOffPosition position = new BnplWriteOffPosition(
                Money.ofPersisted(row.getLong("principal_minor"), currency, scale),
                Money.ofPersisted(row.getLong("principal_due_minor"), currency, scale),
                Money.ofPersisted(row.getLong("interest_accrued_minor"), currency, scale),
                Money.ofPersisted(row.getLong("interest_due_minor"), currency, scale),
                Money.ofPersisted(row.getLong("fees_due_minor"), currency, scale),
                row.getLong("revision"));
        return new BnplWriteOff(
                row.getObject("id", UUID.class),
                BnplObligationId.of(row.getObject("obligation_id", UUID.class)),
                BnplAgreementId.of(row.getObject("agreement_id", UUID.class)),
                position,
                row.getObject("default_event_id", UUID.class),
                row.getInt("agreement_version"),
                outcome == null ? BnplCorrectionStatus.PROPOSED : BnplCorrectionStatus.valueOf(outcome),
                row.getString("proposed_by"),
                row.getTimestamp("proposed_at").toInstant(),
                row.getString("proposal_reason"),
                Optional.ofNullable(row.getString("decided_by")),
                Optional.ofNullable(decidedAt).map(Timestamp::toInstant),
                Optional.ofNullable(row.getString("decision_reason")),
                Optional.ofNullable(entry).map(JournalEntryId::of),
                Optional.ofNullable(valueDate).map(Date::toLocalDate));
    }

    private static BnplStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new BnplStorageException("bnpl storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
