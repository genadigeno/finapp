package com.finapp.party;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Plain-JDBC party reader and renamer (ADR-0033).
 *
 * <p>Explicit SQL on the connection it is handed, like every other writer here, so a lookup and the
 * command that acts on it share one transaction and one view of the data.
 */
public final class JdbcPartyStore implements PartyStore<Connection> {

    private static final String TABLE = "party.party";

    @Override
    public Optional<Party> findById(Connection unitOfWork, PartyId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");

        String sql =
                "SELECT id, kind, display_name, registered_at FROM " + TABLE + " WHERE id = ?";
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, id.value());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next()
                        ? Optional.of(
                                Party.rehydrate(
                                        PartyId.of((UUID) rows.getObject("id")),
                                        PartyKind.valueOf(rows.getString("kind")),
                                        new PartyName(rows.getString("display_name")),
                                        rows.getTimestamp("registered_at").toInstant()))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            // The message never names the party or its display name: the name is the clearest
            // RESTRICTED-PII column on the platform, and an exception message reaches a log line
            // (INV-AUD-02). DatabaseFailure.describe keeps the SQLState and drops the cause, so the
            // driver cannot put the refused row into the message either (P1-TSK-008's finding).
            throw new PartyStorageException(
                    DatabaseFailure.describe("Could not read party " + id, e));
        }
    }

    @Override
    public Optional<Customer> findLiveCustomerFor(Connection unitOfWork, PartyId partyId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");

        // The predicate is the customer_one_live_relationship_per_party index's own - the
        // non-terminal states, V005's derived form - so this read and the rule that makes it
        // unique agree by construction. Built from the same sqlTerminalValueList() the
        // migration cites, so a third terminal state changes both or neither.
        String sql =
                "SELECT id, party_id, status, opened_at, status_changed_at FROM party.customer"
                        + " WHERE party_id = ? AND status NOT IN ("
                        + CustomerStatus.sqlTerminalValueList() + ")";
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, partyId.value());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next()
                        ? Optional.of(
                                Customer.rehydrate(
                                        CustomerId.of((UUID) rows.getObject("id")),
                                        PartyId.of((UUID) rows.getObject("party_id")),
                                        CustomerStatus.valueOf(rows.getString("status")),
                                        rows.getTimestamp("opened_at").toInstant(),
                                        rows.getTimestamp("status_changed_at").toInstant()))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PartyStorageException(
                    DatabaseFailure.describe(
                            "Could not read the live customer of party " + partyId, e));
        }
    }

    @Override
    public Optional<PartyKind> kindOf(Connection unitOfWork, PartyId partyId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(partyId, "partyId must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement("SELECT kind FROM party.party WHERE id = ?")) {
            select.setObject(1, partyId.value());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next()
                        ? Optional.of(PartyKind.valueOf(rows.getString("kind")))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PartyStorageException(
                    DatabaseFailure.describe("Could not read the kind of party " + partyId, e));
        }
    }

    @Override
    public Optional<PartyKind> kindOfCustomer(Connection unitOfWork, CustomerId customerId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT p.kind FROM party.customer c"
                                + " JOIN party.party p ON p.id = c.party_id"
                                + " WHERE c.id = ?")) {
            select.setObject(1, customerId.value());
            try (ResultSet rows = select.executeQuery()) {
                return rows.next()
                        ? Optional.of(PartyKind.valueOf(rows.getString("kind")))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PartyStorageException(
                    DatabaseFailure.describe(
                            "Could not read the party kind behind customer " + customerId, e));
        }
    }

    @Override
    public boolean moveCustomerStatus(
            Connection unitOfWork,
            CustomerId customerId,
            CustomerStatus from,
            CustomerStatus to,
            java.time.Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(from, "from must not be null");
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(at, "at must not be null");
        if (!from.canTransitionTo(to)) {
            // The machine's answer, asked before any SQL (INV-LIFE-02): the store must not be
            // the second place a forbidden edge could be written.
            throw new IllegalCustomerTransitionException(customerId, from, to);
        }
        String sql =
                "UPDATE party.customer SET status = ?, status_changed_at = ?"
                        + " WHERE id = ? AND status = ?";
        try (PreparedStatement update = unitOfWork.prepareStatement(sql)) {
            update.setString(1, to.name());
            update.setTimestamp(2, java.sql.Timestamp.from(at));
            update.setObject(3, customerId.value());
            update.setString(4, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException e) {
            throw new PartyStorageException(
                    DatabaseFailure.describe(
                            "Could not move customer " + customerId + " from " + from + " to "
                                    + to,
                            e));
        }
    }

    @Override
    public Optional<PartyName> rename(Connection unitOfWork, PartyId id, PartyName newName) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(newName, "newName must not be null");

        // The CTE is evaluated against the statement's own snapshot, so it holds the value this
        // UPDATE actually replaced - not one read a moment earlier by a transaction that has since
        // lost a race. One statement is what makes the audit record's "before" true.
        //
        // `display_name <> ?` makes a no-op rename return no rows, so the caller writes no audit
        // record for a change that did not happen.
        String sql =
                "WITH before AS (SELECT display_name FROM " + TABLE + " WHERE id = ?)"
                        + " UPDATE " + TABLE + " SET display_name = ?"
                        + " WHERE id = ? AND display_name <> ?"
                        + " RETURNING (SELECT display_name FROM before)";
        try (PreparedStatement update = unitOfWork.prepareStatement(sql)) {
            update.setObject(1, id.value());
            update.setString(2, newName.value());
            update.setObject(3, id.value());
            update.setString(4, newName.value());
            try (ResultSet rows = update.executeQuery()) {
                return rows.next()
                        ? Optional.of(new PartyName(rows.getString(1)))
                        : Optional.empty();
            }
        } catch (SQLException e) {
            throw new PartyStorageException(
                    DatabaseFailure.describe("Could not rename party " + id, e));
        }
    }
}
