package com.finapp.lending;

import com.finapp.creditmath.EngineVersion;
import com.finapp.creditmath.LendingEngine;
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
 * {@link PayoffStore} over plain JDBC (`P11-TSK-026`, ADR-0033): every instant the database's, every failure its operation
 * and SQLSTATE only ({@link LendingStorageException}) - never a key or a figure.
 */
public final class JdbcPayoffStore implements PayoffStore {

    private static final String QUOTE_COLUMNS = "id, loan_id, request_key, business_date, good_through, currency, scale,"
            + " principal_minor, principal_due_minor, interest_accrued_minor, interest_due_minor, fees_due_minor,"
            + " credit_balance_minor, projection_base_minor, projected_days, projected_interest_minor, outstanding_minor,"
            + " agreement_id, agreement_version, accrual_engine_version, allocation_engine_version, created_at";

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
    public Instant insertQuote(Connection unitOfWork, PayoffQuote quote, CorrelationId correlation) {
        Objects.requireNonNull(quote, "quote");
        Objects.requireNonNull(correlation, "correlation");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.payoff_quote (" + QUOTE_COLUMNS + ", correlation_id)"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, statement_timestamp(), ?)"
                        + " RETURNING created_at")) {
            int i = 0;
            insert.setObject(++i, quote.id());
            insert.setObject(++i, quote.loan().value());
            insert.setString(++i, quote.requestKey());
            insert.setObject(++i, quote.businessDate());
            insert.setObject(++i, quote.goodThrough());
            insert.setString(++i, quote.outstanding().currency().code());
            insert.setInt(++i, quote.outstanding().scale());
            insert.setLong(++i, quote.principal().minorUnits());
            insert.setLong(++i, quote.principalDue().minorUnits());
            insert.setLong(++i, quote.interestAccrued().minorUnits());
            insert.setLong(++i, quote.interestDue().minorUnits());
            insert.setLong(++i, quote.feesDue().minorUnits());
            insert.setLong(++i, quote.creditBalance().minorUnits());
            insert.setLong(++i, quote.projectionBase().minorUnits());
            insert.setInt(++i, quote.projectedDays());
            insert.setLong(++i, quote.projectedInterest().minorUnits());
            insert.setLong(++i, quote.outstanding().minorUnits());
            insert.setObject(++i, quote.agreementId().value());
            insert.setInt(++i, quote.agreementVersion());
            insert.setInt(++i, quote.accrualEngine().number());
            insert.setInt(++i, quote.allocationEngine().number());
            insert.setString(++i, correlation.value());
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return row.getTimestamp(1).toInstant();
            }
        } catch (SQLException failure) {
            throw failed("inserting a payoff quote", failure);
        }
    }

    @Override
    public Optional<PayoffQuote> findQuoteByKey(Connection unitOfWork, LoanId loan, String requestKey) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(requestKey, "requestKey");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + QUOTE_COLUMNS + " FROM lending.payoff_quote WHERE loan_id = ? AND request_key = ?")) {
            select.setObject(1, loan.value());
            select.setString(2, requestKey);
            return quote(select);
        } catch (SQLException failure) {
            throw failed("reading a payoff quote by key", failure);
        }
    }

    @Override
    public Optional<PayoffQuote> findQuote(Connection unitOfWork, LoanId loan, UUID id) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT " + QUOTE_COLUMNS + " FROM lending.payoff_quote WHERE loan_id = ? AND id = ?")) {
            select.setObject(1, loan.value());
            select.setObject(2, id);
            return quote(select);
        } catch (SQLException failure) {
            throw failed("reading a payoff quote", failure);
        }
    }

    @Override
    public Instant insertExecution(Connection unitOfWork, PayoffExecution execution, CorrelationId correlation) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(correlation, "correlation");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.payoff_execution (id, quote_id, loan_id, request_key, business_date, executed_at,"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " correlation_id) VALUES (?, ?, ?, ?, ?, statement_timestamp(), ?) RETURNING executed_at")) {
            int i = 0;
            insert.setObject(++i, execution.id());
            insert.setObject(++i, execution.quote());
            insert.setObject(++i, execution.loan().value());
            insert.setString(++i, execution.requestKey());
            insert.setObject(++i, execution.businessDate());
            insert.setString(++i, correlation.value());
            try (ResultSet row = insert.executeQuery()) {
                row.next();
                return row.getTimestamp(1).toInstant();
            }
        } catch (SQLException failure) {
            throw failed("inserting a payoff execution", failure);
        }
    }

    @Override
    public Optional<PayoffExecution> findExecution(Connection unitOfWork, LoanId loan) {
        Objects.requireNonNull(loan, "loan");
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT id, quote_id, request_key, business_date,"
                + " executed_at FROM lending.payoff_execution WHERE loan_id = ?")) {
            select.setObject(1, loan.value());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PayoffExecution(
                        row.getObject("id", UUID.class),
                        row.getObject("quote_id", UUID.class),
                        loan,
                        row.getString("request_key"),
                        row.getObject("business_date", LocalDate.class),
                        row.getTimestamp("executed_at").toInstant()));
            }
        } catch (SQLException failure) {
            throw failed("reading a payoff execution", failure);
        }
    }

    @Override
    public Optional<LoanClosure> closureOf(Connection unitOfWork, LoanId loan) {
        Objects.requireNonNull(loan, "loan");
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT id, reason, currency, scale, refund_minor,"
                + " refund_journal_entry_id, repayment_id FROM lending.loan_closure WHERE loan_id = ?")) {
            select.setObject(1, loan.value());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                UUID entry = row.getObject("refund_journal_entry_id", UUID.class);
                return Optional.of(new LoanClosure(
                        row.getObject("id", UUID.class),
                        loan,
                        ClosureReason.valueOf(row.getString("reason")),
                        Money.ofPersisted(row.getLong("refund_minor"), CurrencyCode.of(row.getString("currency").strip()),
                                row.getInt("scale")),
                        Optional.ofNullable(entry).map(JournalEntryId::of),
                        Optional.ofNullable(row.getObject("repayment_id", UUID.class))));
            }
        } catch (SQLException failure) {
            throw failed("reading a loan's closure", failure);
        }
    }

    private static Optional<PayoffQuote> quote(PreparedStatement select) throws SQLException {
        try (ResultSet row = select.executeQuery()) {
            if (!row.next()) {
                return Optional.empty();
            }
            CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
            int scale = row.getInt("scale");
            return Optional.of(new PayoffQuote(
                    row.getObject("id", UUID.class),
                    LoanId.of(row.getObject("loan_id", UUID.class)),
                    row.getString("request_key"),
                    row.getObject("business_date", LocalDate.class),
                    row.getObject("good_through", LocalDate.class),
                    money(row, "principal_minor", currency, scale),
                    money(row, "principal_due_minor", currency, scale),
                    money(row, "interest_accrued_minor", currency, scale),
                    money(row, "interest_due_minor", currency, scale),
                    money(row, "fees_due_minor", currency, scale),
                    money(row, "credit_balance_minor", currency, scale),
                    money(row, "projection_base_minor", currency, scale),
                    row.getInt("projected_days"),
                    money(row, "projected_interest_minor", currency, scale),
                    money(row, "outstanding_minor", currency, scale),
                    LoanAgreementId.of(row.getObject("agreement_id", UUID.class)),
                    row.getInt("agreement_version"),
                    EngineVersion.of(LendingEngine.ACCRUAL, row.getInt("accrual_engine_version"))
                            .orElseThrow(() -> new IllegalStateException("a quote names an engine version this build holds")),
                    EngineVersion.of(LendingEngine.ALLOCATION, row.getInt("allocation_engine_version"))
                            .orElseThrow(() -> new IllegalStateException("a quote names an engine version this build holds")),
                    row.getTimestamp("created_at").toInstant()));
        }
    }

    private static Money money(ResultSet row, String column, CurrencyCode currency, int scale) throws SQLException {
        return Money.ofPersisted(row.getLong(column), currency, scale);
    }

    private static LendingStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new LendingStorageException("lending storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
