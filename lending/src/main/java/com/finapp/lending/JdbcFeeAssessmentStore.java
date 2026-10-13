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
 * {@link FeeAssessmentStore} over plain JDBC (`P11-TSK-025`, ADR-0033): every instant the database's, every failure its
 * operation and SQLSTATE only ({@link LendingStorageException}) - never an amount.
 */
public final class JdbcFeeAssessmentStore implements FeeAssessmentStore {

    private static final String COLUMNS = "SELECT id, loan_id, due_item_id, kind, business_date, currency, scale,"
            + " amount_minor, agreement_id, agreement_version, journal_entry_id FROM lending.fee_assessment";

    @Override
    public List<FeeAssessment> ofLoan(Connection unitOfWork, LoanId loan) {
        Objects.requireNonNull(loan, "loan");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                COLUMNS + " WHERE loan_id = ? ORDER BY business_date, created_at, id")) {
            select.setObject(1, loan.value());
            List<FeeAssessment> found = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    found.add(assessment(row));
                }
            }
            return found;
        } catch (SQLException failure) {
            throw failed("reading a loan's fee assessments", failure);
        }
    }

    @Override
    public Optional<FeeAssessment> find(Connection unitOfWork, LoanId loan, UUID id) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(id, "id");
        try (PreparedStatement select = unitOfWork.prepareStatement(COLUMNS + " WHERE id = ? AND loan_id = ?")) {
            select.setObject(1, id);
            select.setObject(2, loan.value());
            try (ResultSet row = select.executeQuery()) {
                return row.next() ? Optional.of(assessment(row)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("reading a fee assessment", failure);
        }
    }

    @Override
    public void insert(Connection unitOfWork, FeeAssessment assessment, CorrelationId correlation) {
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(correlation, "correlation");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.fee_assessment (id, loan_id, due_item_id, kind, business_date, currency, scale,"
                        + " amount_minor, agreement_id, agreement_version, journal_entry_id, created_at, correlation_id)"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, statement_timestamp(), ?)")) {
            int i = 0;
            insert.setObject(++i, assessment.id());
            insert.setObject(++i, assessment.loan().value());
            insert.setObject(++i, assessment.dueItem().value());
            insert.setString(++i, assessment.kind().name());
            insert.setObject(++i, assessment.businessDate());
            insert.setString(++i, assessment.amount().currency().code());
            insert.setInt(++i, assessment.amount().scale());
            insert.setLong(++i, assessment.amount().minorUnits());
            insert.setObject(++i, assessment.agreement().value());
            insert.setInt(++i, assessment.agreementVersion());
            insert.setObject(++i, assessment.entry().value());
            insert.setString(++i, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a fee assessment", failure);
        }
    }

    private static FeeAssessment assessment(ResultSet row) throws SQLException {
        CurrencyCode currency = CurrencyCode.of(row.getString("currency").strip());
        return new FeeAssessment(
                row.getObject("id", UUID.class),
                LoanId.of(row.getObject("loan_id", UUID.class)),
                DueItemId.of(row.getObject("due_item_id", UUID.class)),
                FeeKind.valueOf(row.getString("kind")),
                row.getObject("business_date", LocalDate.class),
                Money.ofPersisted(row.getLong("amount_minor"), currency, row.getInt("scale")),
                LoanAgreementId.of(row.getObject("agreement_id", UUID.class)),
                row.getInt("agreement_version"),
                JournalEntryId.of(row.getObject("journal_entry_id", UUID.class)));
    }

    private static LendingStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new LendingStorageException("lending storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
