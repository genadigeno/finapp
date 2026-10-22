package com.finapp.lending;

import com.finapp.ledger.JournalEntryId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@link RepaymentReversalStore} over plain JDBC (`P11-TSK-020`, ADR-0033): every instant the database's, every failure its
 * operation and SQLSTATE only ({@link LendingStorageException}) - never a reason or an amount.
 */
public final class JdbcRepaymentReversalStore implements RepaymentReversalStore {

    private static final String COLUMNS = "x.id, x.loan_id, x.repayment_id, x.agreement_version, x.status,"
            + " x.proposed_by, x.proposed_at, x.proposal_reason, x.decided_by, x.decided_at, x.decision_reason,"
            + " x.journal_entry_id";

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
    public Optional<Instant> insert(Connection unitOfWork, NewProposal proposal) {
        Objects.requireNonNull(proposal, "proposal");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.repayment_reversal (id, loan_id, repayment_id, agreement_version, status, proposed_by,"
                        + " proposed_by_type, proposed_at, proposal_reason, correlation_id)"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " VALUES (?, ?, ?, ?, 'PROPOSED', ?, ?, statement_timestamp(), ?, ?)"
                        + " ON CONFLICT (repayment_id) WHERE status IN ('PROPOSED', 'APPROVED') DO NOTHING"
                        + " RETURNING proposed_at")) {
            int i = 0;
            insert.setObject(++i, proposal.id());
            insert.setObject(++i, proposal.loan().value());
            insert.setObject(++i, proposal.repayment());
            insert.setInt(++i, proposal.agreementVersion());
            insert.setString(++i, proposal.proposer().id());
            insert.setString(++i, proposal.proposer().type().name());
            insert.setString(++i, proposal.reason());
            insert.setString(++i, proposal.correlation().value());
            try (ResultSet row = insert.executeQuery()) {
                return row.next() ? Optional.of(row.getTimestamp(1).toInstant()) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("inserting a repayment reversal", failure);
        }
    }

    @Override
    public Optional<RepaymentReversal> find(Connection unitOfWork, UUID id) {
        return one(unitOfWork, id, "", "reading a repayment reversal");
    }

    @Override
    public Optional<RepaymentReversal> lock(Connection unitOfWork, UUID id) {
        return one(unitOfWork, id, " FOR UPDATE", "locking a repayment reversal");
    }

    @Override
    public Optional<RepaymentReversal> live(Connection unitOfWork, UUID repayment) {
        Objects.requireNonNull(repayment, "repayment");
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT " + COLUMNS
                + " FROM lending.repayment_reversal x WHERE x.repayment_id = ? AND x.status IN ('PROPOSED', 'APPROVED')")) {
            select.setObject(1, repayment);
            return read(select).stream().findFirst();
        } catch (SQLException failure) {
            throw failed("reading a repayment's live reversal", failure);
        }
    }

    @Override
    public boolean decide(Connection unitOfWork, Decision decision) {
        Objects.requireNonNull(decision, "decision");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE lending.repayment_reversal SET status = ?, decided_by = ?, decided_by_type = ?,"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " decided_at = statement_timestamp(), decision_reason = ?, journal_entry_id = ?, value_date = ?"
                        + " WHERE id = ? AND status = 'PROPOSED'")) {
            int i = 0;
            update.setString(++i, decision.status().name());
            update.setString(++i, decision.decider().id());
            update.setString(++i, decision.decider().type().name());
            update.setString(++i, decision.reason());
            update.setObject(++i, decision.journalEntry().map(JournalEntryId::value).orElse(null));
            update.setObject(++i, decision.valueDate().orElse(null));
            update.setObject(++i, decision.reversal());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw failed("deciding a repayment reversal", failure);
        }
    }

    @Override
    public void insertNegations(Connection unitOfWork, UUID reversal, List<RepaymentAllocation> allocations,
            Supplier<UUID> ids) {
        Objects.requireNonNull(reversal, "reversal");
        Objects.requireNonNull(allocations, "allocations");
        Objects.requireNonNull(ids, "ids");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.repayment_allocation_reversal (id, reversal_id, allocation_id, currency, scale,"
                        + " amount_minor) VALUES (?, ?, ?, ?, ?, ?)")) {
            for (RepaymentAllocation allocation : allocations) {
                int i = 0;
                insert.setObject(++i, ids.get());
                insert.setObject(++i, reversal);
                insert.setObject(++i, allocation.id());
                insert.setString(++i, allocation.line().amount().currency().code());
                insert.setInt(++i, allocation.line().amount().scale());
                insert.setLong(++i, allocation.line().amount().minorUnits());
                insert.addBatch();
            }
            insert.executeBatch();
        } catch (SQLException failure) {
            throw failed("inserting a reversal's negating rows", failure);
        }
    }

    @Override
    public List<RepaymentReversal> ofLoan(Connection unitOfWork, LoanId loan, int limit) {
        Objects.requireNonNull(loan, "loan");
        if (limit < 1) {
            throw new IllegalArgumentException("a page holds at least one reversal");
        }
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT " + COLUMNS
                + " FROM lending.repayment_reversal x WHERE x.loan_id = ? ORDER BY x.proposed_at DESC, x.id DESC LIMIT ?")) {
            select.setObject(1, loan.value());
            select.setInt(2, limit);
            return read(select);
        } catch (SQLException failure) {
            throw failed("reading a loan's repayment reversals", failure);
        }
    }

    @Override
    public Set<UUID> reversedAmong(Connection unitOfWork, Collection<UUID> repayments) {
        Objects.requireNonNull(repayments, "repayments");
        Set<UUID> reversed = new HashSet<>();
        if (repayments.isEmpty()) {
            return reversed;
        }
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT repayment_id FROM lending.repayment_reversal"
                + " WHERE repayment_id = ANY (?) AND status = 'APPROVED'")) {
            select.setArray(1, unitOfWork.createArrayOf("uuid", repayments.toArray()));
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    reversed.add(row.getObject(1, UUID.class));
                }
            }
            return reversed;
        } catch (SQLException failure) {
            throw failed("reading which repayments are reversed", failure);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private Optional<RepaymentReversal> one(Connection unitOfWork, UUID id, String lock, String operation) {
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT " + COLUMNS
                + " FROM lending.repayment_reversal x WHERE x.id = ?" + lock)) {
            select.setObject(1, id);
            return read(select).stream().findFirst();
        } catch (SQLException failure) {
            throw failed(operation, failure);
        }
    }

    private static List<RepaymentReversal> read(PreparedStatement select) throws SQLException {
        List<RepaymentReversal> reversals = new ArrayList<>();
        try (ResultSet row = select.executeQuery()) {
            while (row.next()) {
                Timestamp decidedAt = row.getTimestamp("decided_at");
                UUID entry = row.getObject("journal_entry_id", UUID.class);
                reversals.add(new RepaymentReversal(
                        row.getObject("id", UUID.class),
                        LoanId.of(row.getObject("loan_id", UUID.class)),
                        row.getObject("repayment_id", UUID.class),
                        row.getInt("agreement_version"),
                        RepaymentReversalStatus.valueOf(row.getString("status")),
                        row.getString("proposed_by"),
                        row.getTimestamp("proposed_at").toInstant(),
                        row.getString("proposal_reason"),
                        Optional.ofNullable(row.getString("decided_by")),
                        Optional.ofNullable(decidedAt).map(Timestamp::toInstant),
                        Optional.ofNullable(row.getString("decision_reason")),
                        Optional.ofNullable(entry).map(JournalEntryId::of)));
            }
        }
        return reversals;
    }

    private static LendingStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new LendingStorageException("lending storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
