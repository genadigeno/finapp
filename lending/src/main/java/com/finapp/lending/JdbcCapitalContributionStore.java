package com.finapp.lending;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link CapitalContributionStore} over plain JDBC (`P11-TSK-004`, ADR-0033): every write an {@code INSERT}, every
 * time the database's, every failure its operation and SQLSTATE only ({@link LendingStorageException}).
 */
public final class JdbcCapitalContributionStore implements CapitalContributionStore {

    /**
     * Lending's administration namespace (`DISTRIBUTED_EXECUTION.md` section 3, reserved by the Phase 10 to 11
     * transition): registered from the code by this, its first writer - the capital administration, on
     * {@code hashtext(currency)}; the terms administration (`P11-TSK-005`) extends it on {@code hashtext(product)}.
     */
    public static final int NAMESPACE = 11;

    private static final String CURRENT =
            "SELECT c.id, c.currency, c.amount_minor, c.scale, c.reference, c.proposed_by, c.proposed_at,"
                    + " d.to_status, d.actor AS decided_by, d.occurred_at AS decided_at, d.expectation_ref"
                    + " FROM lending.capital_contribution c"
                    + " LEFT JOIN lending.capital_contribution_event d"
                    + " ON d.contribution_id = c.id AND d.to_status <> 'PROPOSED'";

    @Override
    public void lockCurrency(Connection unitOfWork, CurrencyCode currency) {
        try (PreparedStatement lock = unitOfWork.prepareStatement("SELECT pg_advisory_xact_lock(?, hashtext(?))")) {
            lock.setInt(1, NAMESPACE);
            lock.setString(2, currency.code());
            lock.execute();
        } catch (SQLException failure) {
            throw failed("taking the capital administration lock", failure);
        }
    }

    @Override
    public Instant transactionTime(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT transaction_timestamp()");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getTimestamp(1).toInstant();
        } catch (SQLException failure) {
            throw failed("reading the transaction's time", failure);
        }
    }

    @Override
    public boolean proposalPending(Connection unitOfWork, CurrencyCode currency) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT EXISTS (SELECT 1 FROM lending.capital_contribution c WHERE c.currency = ?"
                                + " AND NOT EXISTS (SELECT 1 FROM lending.capital_contribution_event d"
                                + " WHERE d.contribution_id = c.id AND d.to_status <> 'PROPOSED'))")) {
            select.setString(1, currency.code());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw failed("reading the pending capital contribution", failure);
        }
    }

    @Override
    public boolean referenceTaken(Connection unitOfWork, String reference) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT EXISTS (SELECT 1 FROM lending.capital_contribution WHERE reference = ?)")) {
            select.setString(1, reference);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        } catch (SQLException failure) {
            throw failed("reading a capital contribution's reference", failure);
        }
    }

    @Override
    public void insertProposal(Connection unitOfWork, NewProposal proposal) {
        try {
            try (PreparedStatement insert =
                    unitOfWork.prepareStatement(
                            "INSERT INTO lending.capital_contribution (id, currency, amount_minor, scale, reference,"
                                    + " proposed_by, proposed_by_type, reason, proposed_at, correlation_id)"
                                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, transaction_timestamp(), ?)")) {
                insert.setObject(1, proposal.id().value());
                insert.setString(2, proposal.amount().currency().code());
                insert.setLong(3, proposal.amount().minorUnits());
                insert.setInt(4, proposal.amount().scale());
                insert.setString(5, proposal.reference());
                insert.setString(6, proposal.proposer().id());
                insert.setString(7, proposal.proposer().type().name());
                insert.setString(8, proposal.reason());
                insert.setString(9, proposal.correlation().value());
                insert.executeUpdate();
            }
            try (PreparedStatement insert =
                    unitOfWork.prepareStatement(
                            "INSERT INTO lending.capital_contribution_event (id, contribution_id, from_status,"
                                    + " to_status, actor, actor_type, proposed_by, reason, expectation_ref,"
                                    + " occurred_at, correlation_id)"
                                    + " VALUES (?, ?, NULL, 'PROPOSED', ?, ?, ?, ?, NULL, transaction_timestamp(), ?)")) {
                insert.setObject(1, proposal.eventId());
                insert.setObject(2, proposal.id().value());
                insert.setString(3, proposal.proposer().id());
                insert.setString(4, proposal.proposer().type().name());
                insert.setString(5, proposal.proposer().id());
                insert.setString(6, proposal.reason());
                insert.setString(7, proposal.correlation().value());
                insert.executeUpdate();
            }
        } catch (SQLException failure) {
            throw failed("recording a capital contribution's proposal", failure);
        }
    }

    @Override
    public Optional<ContributionRow> find(Connection unitOfWork, CapitalContributionId id) {
        try (PreparedStatement select = unitOfWork.prepareStatement(CURRENT + " WHERE c.id = ?")) {
            select.setObject(1, id.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(row(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("reading a capital contribution", failure);
        }
    }

    @Override
    public List<ContributionRow> recent(Connection unitOfWork, int limit) {
        try (PreparedStatement select =
                unitOfWork.prepareStatement(CURRENT + " ORDER BY c.proposed_at DESC, c.id DESC LIMIT ?")) {
            select.setInt(1, limit);
            List<ContributionRow> rows = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    rows.add(row(row));
                }
            }
            return List.copyOf(rows);
        } catch (SQLException failure) {
            throw failed("reading the capital contributions", failure);
        }
    }

    @Override
    public Optional<Instant> decide(Connection unitOfWork, Decision decision) {
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO lending.capital_contribution_event (id, contribution_id, from_status, to_status,"
                                + " actor, actor_type, proposed_by, reason, expectation_ref, occurred_at, correlation_id)"
                                + " VALUES (?, ?, 'PROPOSED', ?, ?, ?, ?, ?, ?, transaction_timestamp(), ?)"
                                // The decided-once arbiter (and the expectation's unique): a second decision
                                // writes nothing, for every writer.
                                + " ON CONFLICT DO NOTHING RETURNING occurred_at")) {
            insert.setObject(1, decision.eventId());
            insert.setObject(2, decision.contribution().value());
            insert.setString(3, decision.status().name());
            insert.setString(4, decision.actor().id());
            insert.setString(5, decision.actor().type().name());
            insert.setString(6, decision.proposedBy());
            insert.setString(7, decision.reason());
            insert.setString(8, decision.expectationRef().orElse(null));
            insert.setString(9, decision.correlation().value());
            try (ResultSet row = insert.executeQuery()) {
                return row.next() ? Optional.of(row.getTimestamp(1).toInstant()) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("recording a capital contribution's decision", failure);
        }
    }

    private static ContributionRow row(ResultSet row) throws SQLException {
        CurrencyCode currency = CurrencyCode.of(row.getString("currency").trim());
        String decided = row.getString("to_status");
        return new ContributionRow(
                CapitalContributionId.of(row.getObject("id", UUID.class)),
                Money.ofPersisted(row.getLong("amount_minor"), currency, row.getShort("scale")),
                row.getString("reference"),
                row.getString("proposed_by"),
                row.getTimestamp("proposed_at").toInstant(),
                decided == null ? CapitalContributionStatus.PROPOSED : CapitalContributionStatus.valueOf(decided),
                Optional.ofNullable(row.getString("decided_by")),
                Optional.ofNullable(row.getTimestamp("decided_at")).map(java.sql.Timestamp::toInstant),
                Optional.ofNullable(row.getString("expectation_ref")));
    }

    private static LendingStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new LendingStorageException("lending storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
