package com.finapp.lending;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link CreditLineStatementStore} over plain JDBC (`P11-TSK-022`, ADR-0033): every failure its operation and SQLSTATE only
 * ({@link LendingStorageException}) - never an amount or a date of a borrower's line. Paid figures are the database's exact
 * {@code bigint} arithmetic ({@code lending.billing_component_paid}) - never a floating point.
 */
public final class JdbcCreditLineStatementStore implements CreditLineStatementStore {

    @Override
    public Optional<LocalDate> latestStatementDate(Connection unitOfWork, LoanId line) {
        Objects.requireNonNull(line, "line");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT max(cycle_end) FROM lending.credit_line_statement WHERE loan_id = ?")) {
            select.setObject(1, line.value());
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return Optional.ofNullable(row.getObject(1, LocalDate.class));
            }
        } catch (SQLException failure) {
            throw failed("reading a line's latest statement", failure);
        }
    }

    @Override
    public List<InterestAccrual> accrualsIn(Connection unitOfWork, LoanId line, AccrualPeriod cycle) {
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(cycle, "cycle");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT accrual_date, period_start, period_end, currency, scale, base_minor, cumulative_exact,"
                        + " posted_minor, accrual_engine_version FROM lending.interest_accrual"
                        + " WHERE loan_id = ? AND period_start = ? AND period_end = ? ORDER BY accrual_date")) {
            select.setObject(1, line.value());
            select.setObject(2, cycle.start());
            select.setObject(3, cycle.endExclusive());
            List<InterestAccrual> accruals = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    accruals.add(JdbcLoanServicingStore.accrual(rows));
                }
            }
            return accruals;
        } catch (SQLException failure) {
            throw failed("reading a cycle's accruals", failure);
        }
    }

    @Override
    public void insert(Connection unitOfWork, CreditLineStatement statement, CorrelationId correlation) {
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(correlation, "correlation");
        Statement figures = statement.statement();
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.credit_line_statement (id, loan_id, cycle_start, cycle_end, due_date, currency, scale,"
                        + " interest_billed_minor, fees_carried_minor, principal_billed_minor, minimum_payment_minor,"
                        + " agreement_id, agreement_version, statement_engine_version, accrual_engine_version,"
                        + " journal_entry_id, created_at, correlation_id)"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, statement_timestamp(), ?)")) {
            int i = 0;
            insert.setObject(++i, statement.id());
            insert.setObject(++i, statement.loan().value());
            insert.setObject(++i, figures.cycle().start());
            insert.setObject(++i, figures.cycle().endExclusive());
            insert.setObject(++i, figures.dueDate());
            insert.setString(++i, figures.interestBilled().currency().code());
            insert.setInt(++i, figures.interestBilled().scale());
            insert.setLong(++i, figures.interestBilled().minorUnits());
            insert.setLong(++i, figures.feesCarried().minorUnits());
            insert.setLong(++i, figures.principalBilled().minorUnits());
            insert.setLong(++i, figures.minimumPayment().minorUnits());
            insert.setObject(++i, statement.agreement().value());
            insert.setInt(++i, statement.agreementVersion());
            insert.setInt(++i, figures.engine().number());
            insert.setInt(++i, statement.accrualEngine().number());
            insert.setObject(++i, statement.entry().map(JournalEntryId::value).orElse(null));
            insert.setString(++i, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a credit line statement", failure);
        }
    }

    @Override
    public List<StatedItem> statementsOwnedBy(Connection unitOfWork, LoanId line, UUID party) {
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(party, "party");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT s.id, s.loan_id, s.cycle_start, s.cycle_end, s.due_date, s.currency, s.scale,"
                        + " s.interest_billed_minor, s.fees_carried_minor, s.principal_billed_minor,"
                        + " s.minimum_payment_minor, s.agreement_id, s.agreement_version, s.statement_engine_version,"
                        + " s.accrual_engine_version, s.journal_entry_id,"
                        + " lending.billing_component_paid(s.id, 'FEES') AS fees_paid,"
                        + " lending.billing_component_paid(s.id, 'INTEREST') AS interest_paid,"
                        + " lending.billing_component_paid(s.id, 'PRINCIPAL') AS principal_paid"
                        + " FROM lending.credit_line_statement s JOIN lending.loan l ON l.id = s.loan_id"
                        + " WHERE s.loan_id = ? AND l.party_id = ? ORDER BY s.cycle_end")) {
            select.setObject(1, line.value());
            select.setObject(2, party);
            List<StatedItem> items = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    items.add(item(row));
                }
            }
            return items;
        } catch (SQLException failure) {
            throw failed("reading a party's line's statements", failure);
        }
    }

    private static StatedItem item(ResultSet row) throws SQLException {
        CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
        int scale = row.getInt("scale");
        UUID entry = row.getObject("journal_entry_id", UUID.class);
        Statement figures = new Statement(
                new AccrualPeriod(row.getObject("cycle_start", LocalDate.class),
                        row.getObject("cycle_end", LocalDate.class)),
                Money.ofPersisted(row.getLong("interest_billed_minor"), currency, scale),
                Money.ofPersisted(row.getLong("fees_carried_minor"), currency, scale),
                Money.ofPersisted(row.getLong("principal_billed_minor"), currency, scale),
                Money.ofPersisted(row.getLong("minimum_payment_minor"), currency, scale),
                row.getObject("due_date", LocalDate.class),
                EngineVersion.of(LendingEngine.STATEMENT, row.getInt("statement_engine_version"))
                        .orElseThrow(() -> new IllegalStateException("a statement names an engine version this build holds")));
        CreditLineStatement statement = new CreditLineStatement(
                row.getObject("id", UUID.class),
                LoanId.of(row.getObject("loan_id", UUID.class)),
                figures,
                LoanAgreementId.of(row.getObject("agreement_id", UUID.class)),
                row.getInt("agreement_version"),
                EngineVersion.of(LendingEngine.ACCRUAL, row.getInt("accrual_engine_version"))
                        .orElseThrow(() -> new IllegalStateException("a statement names an engine version this build holds")),
                Optional.ofNullable(entry).map(JournalEntryId::of));
        return new StatedItem(statement,
                Money.ofPersisted(row.getLong("fees_paid"), currency, scale),
                Money.ofPersisted(row.getLong("interest_paid"), currency, scale),
                Money.ofPersisted(row.getLong("principal_paid"), currency, scale));
    }

    private static LendingStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new LendingStorageException("lending storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
