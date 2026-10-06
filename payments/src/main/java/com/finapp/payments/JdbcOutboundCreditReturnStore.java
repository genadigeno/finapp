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

/** Plain-JDBC storage for outbound credit returns (ADR-0033, `P9-TSK-023`); payments {@code V027} beneath. */
public final class JdbcOutboundCreditReturnStore implements OutboundCreditReturnStore {

    @Override
    public boolean insert(Connection unitOfWork, Return returned) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(returned, "returned must not be null");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO payments.outbound_credit_return (id, outbound_credit_id, amount_minor, amount_currency,"
                        + " amount_scale, return_reference, applied_by, resolution_id, journal_entry_id, returned_at,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())"
                        + " ON CONFLICT (outbound_credit_id) DO NOTHING")) {
            insert.setObject(1, returned.id());
            insert.setObject(2, returned.credit().value());
            insert.setLong(3, returned.amount().minorUnits());
            insert.setString(4, returned.amount().currency().code());
            insert.setShort(5, (short) returned.amount().scale());
            insert.setString(6, returned.returnReference().orElse(null));
            insert.setString(7, returned.appliedBy().name());
            insert.setObject(8, returned.resolutionId().orElse(null));
            insert.setObject(9, returned.journalEntryId().orElse(null));
            insert.setTimestamp(10, Timestamp.from(returned.returnedAt()));
            return insert.executeUpdate() == 1;
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe("recording an outbound credit's return", failure));
        }
    }

    @Override
    public Optional<Return> findByCredit(Connection unitOfWork, OutboundCreditId credit) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(credit, "credit must not be null");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id, amount_minor, amount_currency, amount_scale, return_reference, applied_by, resolution_id,"
                        + " journal_entry_id, returned_at FROM payments.outbound_credit_return WHERE outbound_credit_id = ?")) {
            select.setObject(1, credit.value());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Return(
                        row.getObject("id", UUID.class),
                        credit,
                        Money.ofPersisted(row.getLong("amount_minor"), CurrencyCode.of(row.getString("amount_currency")),
                                row.getShort("amount_scale")),
                        Optional.ofNullable(row.getString("return_reference")),
                        AppliedBy.valueOf(row.getString("applied_by")),
                        Optional.ofNullable(row.getObject("resolution_id", UUID.class)),
                        Optional.ofNullable(row.getObject("journal_entry_id", UUID.class)),
                        row.getTimestamp("returned_at").toInstant()));
            }
        } catch (SQLException failure) {
            throw new PaymentsStorageException(DatabaseFailure.describe("reading an outbound credit's return", failure));
        }
    }
}
