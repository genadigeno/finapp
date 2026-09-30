package com.finapp.merchant;

import com.finapp.ledger.JournalEntryId;
import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link PayoutReturnStore} over JDBC (`P8-TSK-019`, ADR-0033: explicit SQL, no ORM). The row
 * carries no merchant column — the payout names it — so the read joins the payout it returns.
 */
public final class JdbcPayoutReturnStore implements PayoutReturnStore<Connection> {

    @Override
    public void insert(Connection unitOfWork, PayoutReturn returned) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(returned, "returned must not be null");
        try (PreparedStatement insert =
                unitOfWork.prepareStatement(
                        "INSERT INTO merchant.payout_return (id, payout_id, amount_minor,"
                                + " currency, scale, external_item_ref, journal_entry_id,"
                                + " returned_on, value_date, recorded_at)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setObject(1, returned.id());
            insert.setObject(2, returned.payoutId().value());
            insert.setLong(3, returned.amount().minorUnits());
            insert.setString(4, returned.amount().currency().code());
            insert.setShort(5, (short) returned.amount().scale());
            insert.setObject(6, returned.externalItemRef());
            insert.setObject(7, returned.journalEntryId().value());
            insert.setDate(8, Date.valueOf(returned.returnedOn()));
            insert.setDate(9, Date.valueOf(returned.valueDate()));
            insert.setTimestamp(10, Timestamp.from(returned.recordedAt()));
            insert.executeUpdate();
        } catch (SQLException failure) {
            // Called under the payout's row lock after checking no return stands, so a unique
            // or key violation here is a defect - refused loudly, never guessed at.
            throw new MerchantStorageException(
                    DatabaseFailure.describe("recording a payout return", failure));
        }
    }

    @Override
    public Optional<PayoutReturn> findByPayout(Connection unitOfWork, MerchantPayoutId payout) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(payout, "payout must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT r.id, r.payout_id, p.merchant_id, r.amount_minor, r.currency,"
                                + " r.scale, r.external_item_ref, r.journal_entry_id,"
                                + " r.returned_on, r.value_date, r.recorded_at"
                                + " FROM merchant.payout_return r"
                                + " JOIN merchant.merchant_payout p ON p.id = r.payout_id"
                                + " WHERE r.payout_id = ?")) {
            select.setObject(1, payout.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(map(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("reading a payout's return", failure));
        }
    }

    @Override
    public List<PayoutReturn> page(Connection unitOfWork, UUID after, int limit) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(after, "after must not be null");
        if (limit < 1) {
            throw new IllegalArgumentException("a page holds at least one return: " + limit);
        }
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT r.id, r.payout_id, p.merchant_id, r.amount_minor, r.currency,"
                                + " r.scale, r.external_item_ref, r.journal_entry_id,"
                                + " r.returned_on, r.value_date, r.recorded_at"
                                + " FROM merchant.payout_return r"
                                + " JOIN merchant.merchant_payout p ON p.id = r.payout_id"
                                + " WHERE r.id > ? ORDER BY r.id LIMIT ?")) {
            select.setObject(1, after);
            select.setInt(2, limit);
            List<PayoutReturn> page = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    page.add(map(row));
                }
            }
            return page;
        } catch (SQLException failure) {
            throw new MerchantStorageException(
                    DatabaseFailure.describe("paging the payout returns", failure));
        }
    }

    private static PayoutReturn map(ResultSet row) throws SQLException {
        return new PayoutReturn(
                row.getObject("id", UUID.class),
                MerchantPayoutId.of(row.getObject("payout_id", UUID.class)),
                MerchantId.of(row.getObject("merchant_id", UUID.class)),
                Money.ofPersisted(
                        row.getLong("amount_minor"),
                        CurrencyCode.of(row.getString("currency").stripTrailing()),
                        row.getShort("scale")),
                row.getObject("external_item_ref", UUID.class),
                JournalEntryId.of(row.getObject("journal_entry_id", UUID.class)),
                row.getDate("returned_on").toLocalDate(),
                row.getDate("value_date").toLocalDate(),
                row.getTimestamp("recorded_at").toInstant());
    }
}
