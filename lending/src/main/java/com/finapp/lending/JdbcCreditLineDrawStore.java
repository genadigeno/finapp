package com.finapp.lending;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link CreditLineDrawStore} over plain JDBC (`P11-TSK-021`, ADR-0033): every instant the database's, every failure its
 * operation and SQLSTATE only ({@link LendingStorageException}) - never the key or an amount.
 */
public final class JdbcCreditLineDrawStore implements CreditLineDrawStore {

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
    public Instant insert(Connection unitOfWork, CreditLineDraw draw, CorrelationId correlation) {
        Objects.requireNonNull(draw, "draw");
        Objects.requireNonNull(correlation, "correlation");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.credit_line_draw (id, loan_id, draw_key, currency, scale, amount_minor, agreement_id,"
                        + " agreement_version, journal_entry_id, value_date, created_at, correlation_id)"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, statement_timestamp(), ?) RETURNING created_at")) {
            int i = 0;
            insert.setObject(++i, draw.id());
            insert.setObject(++i, draw.loan().value());
            insert.setString(++i, draw.drawKey());
            insert.setString(++i, draw.amount().currency().code());
            insert.setInt(++i, draw.amount().scale());
            insert.setLong(++i, draw.amount().minorUnits());
            insert.setObject(++i, draw.agreement().value());
            insert.setInt(++i, draw.agreementVersion());
            insert.setObject(++i, draw.journalEntry().value());
            insert.setObject(++i, draw.valueDate());
            insert.setString(++i, correlation.value());
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return row.getTimestamp(1).toInstant();
            }
        } catch (SQLException failure) {
            throw failed("inserting a credit line draw", failure);
        }
    }

    @Override
    public Optional<CreditLineDraw> findByKey(Connection unitOfWork, LoanId loan, String drawKey) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(drawKey, "drawKey");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id, draw_key, currency, scale, amount_minor, agreement_id, agreement_version, journal_entry_id,"
                        + " value_date, created_at FROM lending.credit_line_draw WHERE loan_id = ? AND draw_key = ?")) {
            select.setObject(1, loan.value());
            select.setString(2, drawKey);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
                return Optional.of(new CreditLineDraw(
                        row.getObject("id", UUID.class),
                        loan,
                        row.getString("draw_key"),
                        Money.ofPersisted(row.getLong("amount_minor"), currency, row.getInt("scale")),
                        LoanAgreementId.of(row.getObject("agreement_id", UUID.class)),
                        row.getInt("agreement_version"),
                        JournalEntryId.of(row.getObject("journal_entry_id", UUID.class)),
                        row.getObject("value_date", LocalDate.class),
                        row.getTimestamp("created_at").toInstant()));
            }
        } catch (SQLException failure) {
            throw failed("reading a credit line draw", failure);
        }
    }

    private static LendingStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new LendingStorageException("lending storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
