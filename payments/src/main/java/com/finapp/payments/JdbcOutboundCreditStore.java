package com.finapp.payments;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Plain-JDBC storage for outbound credits (ADR-0033, `P9-TSK-019`); payments {@code V025} beneath. */
public final class JdbcOutboundCreditStore implements OutboundCreditStore {

    private static final String COLUMNS =
            "id, customer_party_id, subject_id, dispatch_key, rail, destination_reference, amount_minor, amount_currency,"
                    + " amount_scale, held_minor, held_currency, held_scale, hold_id, end_to_end_reference, status,"
                    + " provider_reference, created_at, last_dispatched_at";

    @Override
    public void insert(Connection unitOfWork, Draft draft) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(draft, "draft must not be null");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO payments.outbound_credit (id, customer_party_id, subject_id, dispatch_key, rail,"
                        + " destination_reference, amount_minor, amount_currency, amount_scale, held_minor, held_currency,"
                        + " held_scale, hold_id, end_to_end_reference, status, created_at, last_dispatched_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DISPATCHED', now(), now())")) {
            insert.setObject(1, draft.id().value());
            insert.setObject(2, draft.customerParty());
            insert.setObject(3, draft.subject());
            insert.setString(4, draft.dispatchKey());
            insert.setString(5, draft.rail().value());
            insert.setString(6, draft.destination().value());
            insert.setLong(7, draft.amount().minorUnits());
            insert.setString(8, draft.amount().currency().code());
            insert.setShort(9, (short) draft.amount().scale());
            insert.setLong(10, draft.held().minorUnits());
            insert.setString(11, draft.held().currency().code());
            insert.setShort(12, (short) draft.held().scale());
            insert.setObject(13, draft.holdId());
            insert.setString(14, draft.reference().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe("recording an outbound credit", failure));
        }
    }

    @Override
    public Optional<Row> bySubject(Connection unitOfWork, UUID subject) {
        Objects.requireNonNull(subject, "subject must not be null");
        return one(unitOfWork, "SELECT " + COLUMNS + " FROM payments.outbound_credit WHERE subject_id = ?", subject,
                "reading an outbound credit by its subject");
    }

    @Override
    public Optional<Row> lock(Connection unitOfWork, OutboundCreditId id) {
        Objects.requireNonNull(id, "id must not be null");
        return one(unitOfWork, "SELECT " + COLUMNS + " FROM payments.outbound_credit WHERE id = ? FOR UPDATE", id.value(),
                "locking outbound credit " + id);
    }

    @Override
    public boolean move(Connection unitOfWork, OutboundCreditId id, Status from, Status to) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE payments.outbound_credit SET status = ? WHERE id = ? AND status = ?")) {
            update.setString(1, to.name());
            update.setObject(2, id.value());
            update.setString(3, from.name());
            return update.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe("moving outbound credit " + id, failure));
        }
    }

    @Override
    public void renewPermit(Connection unitOfWork, OutboundCreditId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement update = unitOfWork.prepareStatement(
                "UPDATE payments.outbound_credit SET last_dispatched_at = statement_timestamp() WHERE id = ?")) {
            update.setObject(1, id.value());
            update.executeUpdate();
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe("renewing outbound credit " + id + "'s permit", failure));
        }
    }

    private static Optional<Row> one(Connection unitOfWork, String sql, UUID key, String doing) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(sql)) {
            select.setObject(1, key);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Row(
                        OutboundCreditId.of(row.getObject("id", UUID.class)),
                        row.getObject("customer_party_id", UUID.class),
                        row.getObject("subject_id", UUID.class),
                        row.getString("dispatch_key"),
                        RailId.of(row.getString("rail")),
                        new ProviderReference(row.getString("destination_reference")),
                        Money.ofPersisted(row.getLong("amount_minor"), CurrencyCode.of(row.getString("amount_currency")),
                                row.getShort("amount_scale")),
                        Money.ofPersisted(row.getLong("held_minor"), CurrencyCode.of(row.getString("held_currency")),
                                row.getShort("held_scale")),
                        row.getObject("hold_id", UUID.class),
                        new EndToEndReference(row.getString("end_to_end_reference")),
                        Status.valueOf(row.getString("status")),
                        Optional.ofNullable(row.getString("provider_reference")),
                        row.getTimestamp("created_at").toInstant(),
                        row.getTimestamp("last_dispatched_at").toInstant()));
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe(doing, failure));
        }
    }
}
