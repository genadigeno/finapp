package com.finapp.crossborder;

import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Plain-JDBC storage for cross-border payments (ADR-0033, `P9-TSK-019`); {@code crossborder V005} beneath. */
public final class JdbcPaymentStore implements PaymentStore {

    private static final String COLUMNS =
            "id, owner_party, beneficiary_id, offer_id, quote_id, corridor, dispatch_key, outbound_credit_id, cover_id,"
                    + " hold_id, status, created_at";

    /** What every read selects: the inserted columns, and the failure class an edge may have set. */
    private static final String READ_COLUMNS = COLUMNS + ", failure_reason";

    @Override
    public void insert(Connection unitOfWork, Row payment) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(payment, "payment must not be null");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO crossborder.payment (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, payment.id());
            insert.setObject(2, payment.owner());
            insert.setObject(3, payment.beneficiary().value());
            insert.setObject(4, payment.offer());
            insert.setObject(5, payment.quote());
            insert.setString(6, payment.corridor().code());
            insert.setString(7, payment.dispatchKey());
            insert.setObject(8, payment.outboundCredit());
            insert.setObject(9, payment.cover());
            insert.setObject(10, payment.hold());
            insert.setString(11, payment.status().name());
            insert.setTimestamp(12, Timestamp.from(payment.createdAt()));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("recording a cross-border payment", failure));
        }
    }

    @Override
    public void appendEvent(Connection unitOfWork, UUID eventId, UUID payment, Optional<Status> from, Status to, String cause,
            Instant at) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO crossborder.payment_event (id, payment_id, from_status, to_status, cause, occurred_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, eventId);
            insert.setObject(2, payment);
            insert.setString(3, from.map(Enum::name).orElse(null));
            insert.setString(4, to.name());
            insert.setString(5, cause);
            insert.setTimestamp(6, Timestamp.from(at));
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("recording a move of payment " + payment, failure));
        }
    }

    @Override
    public Optional<Row> byDispatchKey(Connection unitOfWork, UUID owner, String dispatchKey) {
        return one(unitOfWork, "SELECT " + READ_COLUMNS + " FROM crossborder.payment WHERE owner_party = ? AND dispatch_key = ?",
                owner, dispatchKey, "reading a payment by its dispatch key");
    }

    @Override
    public Optional<Row> findOwned(Connection unitOfWork, UUID id, UUID owner) {
        Objects.requireNonNull(id, "id must not be null");
        return one(unitOfWork, "SELECT " + READ_COLUMNS + " FROM crossborder.payment WHERE owner_party = ? AND id = ?",
                owner, id, "reading payment " + id);
    }

    @Override
    public Optional<Row> lock(Connection unitOfWork, UUID id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(id, "id must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + READ_COLUMNS + " FROM crossborder.payment WHERE id = ? FOR UPDATE")) {
            select.setObject(1, id);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(read(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("locking payment " + id, failure));
        }
    }

    @Override
    public boolean move(Connection unitOfWork, UUID id, Status from, Status to, Optional<String> failureReason) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(failureReason, "failureReason must not be null");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE crossborder.payment SET status = ?, failure_reason = ? WHERE id = ? AND status = ?")) {
            update.setString(1, to.name());
            update.setString(2, failureReason.orElse(null));
            update.setObject(3, id);
            update.setString(4, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("moving payment " + id, failure));
        }
    }

    private static Optional<Row> one(Connection unitOfWork, String sql, UUID owner, Object key, String doing) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, owner);
            select.setObject(2, key);
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(read(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe(doing, failure));
        }
    }

    @Override
    public java.util.Map<String, Instant> oldestInTransit(Connection unitOfWork) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT p.corridor, min(e.occurred_at) FROM crossborder.payment p JOIN crossborder.payment_event e"
                        + " ON e.payment_id = p.id AND e.to_status = 'IN_TRANSIT' WHERE p.status = 'IN_TRANSIT'"
                        + " GROUP BY p.corridor");
                ResultSet rows = select.executeQuery()) {
            java.util.Map<String, Instant> oldest = new java.util.HashMap<>();
            while (rows.next()) {
                oldest.put(rows.getString(1), rows.getTimestamp(2).toInstant());
            }
            return oldest;
        } catch (SQLException failure) {
            throw new CrossborderStorageException(DatabaseFailure.describe("could not read the payments in transit", failure));
        }
    }

    private static Row read(ResultSet row) throws SQLException {
        return new Row(
                        row.getObject("id", UUID.class),
                        row.getObject("owner_party", UUID.class),
                        BeneficiaryId.of(row.getObject("beneficiary_id", UUID.class)),
                        row.getObject("offer_id", UUID.class),
                        row.getObject("quote_id", UUID.class),
                        CorridorKey.parse(row.getString("corridor")),
                        row.getString("dispatch_key"),
                        row.getObject("outbound_credit_id", UUID.class),
                        row.getObject("cover_id", UUID.class),
                        row.getObject("hold_id", UUID.class),
                        Status.valueOf(row.getString("status")),
                        row.getTimestamp("created_at").toInstant(),
                        Optional.ofNullable(row.getString("failure_reason")));
    }
}
