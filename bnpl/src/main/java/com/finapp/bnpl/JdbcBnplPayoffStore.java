package com.finapp.bnpl;

import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link BnplPayoffStore} over plain JDBC (`P12-TSK-022`, ADR-0033; lending's {@code JdbcPayoffStore}): every instant the
 * database's - {@code bnpl V014}'s triggers stamp them with {@code clock_timestamp()} after the act's locks - every read of
 * an obligation the caller holds under B4, every failure its operation and SQLSTATE only ({@link BnplStorageException}) -
 * never an amount, a key or a party.
 */
public final class JdbcBnplPayoffStore implements BnplPayoffStore {

    private static final String QUOTES = "SELECT id, obligation_id, agreement_id, agreement_version, request_key,"
            + " business_date, good_through, currency, scale, principal_minor, principal_due_minor, interest_accrued_minor,"
            + " interest_due_minor, fees_due_minor, credit_balance_minor, projection_base_minor, projected_days,"
            + " projected_interest_minor, amount_minor, schedule_engine, accrual_engine, allocation_engine, created_at,"
            + " correlation_id FROM bnpl.bnpl_payoff_quote";

    @Override
    public Optional<BnplPayoffQuote> findQuoteByKey(Connection unitOfWork, BnplObligationId obligation,
            String requestKey) {
        Objects.requireNonNull(obligation, "obligation");
        Objects.requireNonNull(requestKey, "requestKey");
        try (PreparedStatement select = unitOfWork.prepareStatement(QUOTES
                + " WHERE obligation_id = ? AND request_key = ?")) {
            select.setObject(1, obligation.value());
            select.setString(2, requestKey);
            return quote(select);
        } catch (SQLException failure) {
            throw failed("reading a BNPL payoff quote by its key", failure);
        }
    }

    @Override
    public Optional<BnplPayoffQuote> findQuote(Connection unitOfWork, BnplObligationId obligation, UUID quote) {
        Objects.requireNonNull(obligation, "obligation");
        Objects.requireNonNull(quote, "quote");
        try (PreparedStatement select = unitOfWork.prepareStatement(QUOTES + " WHERE id = ? AND obligation_id = ?")) {
            select.setObject(1, quote);
            select.setObject(2, obligation.value());
            return quote(select);
        } catch (SQLException failure) {
            throw failed("reading a BNPL payoff quote", failure);
        }
    }

    @Override
    public Instant insertQuote(Connection unitOfWork, BnplPayoffQuote quote) {
        Objects.requireNonNull(quote, "quote");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                // created_at is the trigger's: clock_timestamp() after B4.
                "INSERT INTO bnpl.bnpl_payoff_quote (id, obligation_id, agreement_id, agreement_version, request_key,"
                        + " business_date, good_through, currency, scale, principal_minor, principal_due_minor,"
                        + " interest_accrued_minor, interest_due_minor, fees_due_minor, credit_balance_minor,"
                        + " projection_base_minor, projected_days, projected_interest_minor, amount_minor, schedule_engine,"
                        + " accrual_engine, allocation_engine, created_at, correlation_id)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?)"
                        + " RETURNING created_at")) {
            int i = 1;
            insert.setObject(i++, quote.id());
            insert.setObject(i++, quote.obligation().value());
            insert.setObject(i++, quote.agreement().value());
            insert.setInt(i++, quote.agreementVersion());
            insert.setString(i++, quote.requestKey());
            insert.setDate(i++, Date.valueOf(quote.businessDate()));
            insert.setDate(i++, Date.valueOf(quote.goodThrough()));
            insert.setString(i++, quote.amount().currency().code());
            insert.setInt(i++, quote.amount().scale());
            for (Money part : new Money[] {quote.principal(), quote.principalDue(), quote.interestAccrued(),
                    quote.interestDue(), quote.feesDue(), quote.creditBalance(), quote.projectionBase()}) {
                insert.setLong(i++, part.minorUnits());
            }
            insert.setInt(i++, quote.projectedDays());
            insert.setLong(i++, quote.projectedInterest().minorUnits());
            insert.setLong(i++, quote.amount().minorUnits());
            insert.setString(i++, quote.scheduleEngine());
            insert.setString(i++, quote.accrualEngine().orElse(null));
            insert.setString(i++, quote.allocationEngine());
            insert.setString(i, quote.correlation().value());
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return row.getTimestamp("created_at").toInstant();
            }
        } catch (SQLException failure) {
            throw failed("inserting a BNPL payoff quote", failure);
        }
    }

    @Override
    public Optional<BnplPayoffExecution> findExecution(Connection unitOfWork, BnplObligationId obligation) {
        Objects.requireNonNull(obligation, "obligation");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id, quote_id, request_key, business_date, value_date, executed_at, correlation_id"
                        + " FROM bnpl.bnpl_payoff_execution WHERE obligation_id = ?")) {
            select.setObject(1, obligation.value());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new BnplPayoffExecution(row.getObject("id", UUID.class),
                        row.getObject("quote_id", UUID.class), obligation, row.getString("request_key"),
                        row.getDate("business_date").toLocalDate(), row.getDate("value_date").toLocalDate(),
                        row.getTimestamp("executed_at").toInstant(), CorrelationId.of(row.getString("correlation_id"))));
            }
        } catch (SQLException failure) {
            throw failed("reading a BNPL payoff execution", failure);
        }
    }

    @Override
    public Instant insertExecution(Connection unitOfWork, BnplPayoffExecution execution) {
        Objects.requireNonNull(execution, "execution");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                // executed_at is the trigger's: clock_timestamp() after B4.
                "INSERT INTO bnpl.bnpl_payoff_execution (id, quote_id, obligation_id, request_key, business_date,"
                        + " value_date, executed_at, correlation_id) VALUES (?, ?, ?, ?, ?, ?, clock_timestamp(), ?)"
                        + " RETURNING executed_at")) {
            insert.setObject(1, execution.id());
            insert.setObject(2, execution.quote());
            insert.setObject(3, execution.obligation().value());
            insert.setString(4, execution.requestKey());
            insert.setDate(5, Date.valueOf(execution.businessDate()));
            insert.setDate(6, Date.valueOf(execution.valueDate()));
            insert.setString(7, execution.correlation().value());
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return row.getTimestamp("executed_at").toInstant();
            }
        } catch (SQLException failure) {
            throw failed("inserting a BNPL payoff execution", failure);
        }
    }

    private static Optional<BnplPayoffQuote> quote(PreparedStatement select) throws SQLException {
        try (ResultSet row = select.executeQuery()) {
            if (!row.next()) {
                return Optional.empty();
            }
            CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
            int scale = row.getInt("scale");
            return Optional.of(new BnplPayoffQuote(row.getObject("id", UUID.class),
                    BnplObligationId.of(row.getObject("obligation_id", UUID.class)),
                    BnplAgreementId.of(row.getObject("agreement_id", UUID.class)), row.getInt("agreement_version"),
                    row.getString("request_key"), row.getDate("business_date").toLocalDate(),
                    row.getDate("good_through").toLocalDate(), money(row, "principal_minor", currency, scale),
                    money(row, "principal_due_minor", currency, scale),
                    money(row, "interest_accrued_minor", currency, scale),
                    money(row, "interest_due_minor", currency, scale), money(row, "fees_due_minor", currency, scale),
                    money(row, "credit_balance_minor", currency, scale),
                    money(row, "projection_base_minor", currency, scale), row.getInt("projected_days"),
                    money(row, "projected_interest_minor", currency, scale), money(row, "amount_minor", currency, scale),
                    row.getString("schedule_engine"), Optional.ofNullable(row.getString("accrual_engine")),
                    row.getString("allocation_engine"), row.getTimestamp("created_at").toInstant(),
                    CorrelationId.of(row.getString("correlation_id"))));
        }
    }

    private static Money money(ResultSet row, String column, CurrencyCode currency, int scale) throws SQLException {
        return Money.ofPersisted(row.getLong(column), currency, scale);
    }

    private static BnplStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new BnplStorageException("bnpl storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
