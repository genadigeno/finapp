package com.finapp.lending;

import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link LoanConditionStore} over plain JDBC (`P11-TSK-024`, ADR-0033): every instant the database's, every failure its
 * operation and SQLSTATE only ({@link LendingStorageException}) - never a figure.
 */
public final class JdbcLoanConditionStore implements LoanConditionStore {

    private static final String CURRENT = "CURRENT";

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
    public Optional<LoanConditionEvent> latest(Connection unitOfWork, LoanId loan, ConditionKind kind) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(kind, "kind");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT id, business_date, days_past_due, bucket, previous_bucket, defaulted, agreement_id,"
                        + " agreement_version FROM lending.loan_condition_event WHERE loan_id = ? AND kind = ?"
                        + " ORDER BY business_date DESC LIMIT 1")) {
            select.setObject(1, loan.value());
            select.setString(2, kind.name());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                int dpd = row.getInt("days_past_due");
                return Optional.of(new LoanConditionEvent(
                        row.getObject("id", UUID.class),
                        loan,
                        row.getObject("business_date", LocalDate.class),
                        kind,
                        new DelinquencyState(dpd, bucket(row.getString("bucket")), row.getBoolean("defaulted")),
                        Optional.ofNullable(row.getString("previous_bucket")).flatMap(JdbcLoanConditionStore::bucket),
                        LoanAgreementId.of(row.getObject("agreement_id", UUID.class)),
                        row.getInt("agreement_version")));
            }
        } catch (SQLException failure) {
            throw failed("reading a loan's latest condition", failure);
        }
    }

    @Override
    public void insert(Connection unitOfWork, LoanConditionEvent event, CorrelationId correlation) {
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(correlation, "correlation");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO lending.loan_condition_event (id, loan_id, business_date, kind, days_past_due, bucket,"
                        + " previous_bucket, defaulted, agreement_id, agreement_version, created_at, correlation_id)"
                        // The instant is the trigger's; the placeholder is overwritten.
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, statement_timestamp(), ?)")) {
            int i = 0;
            insert.setObject(++i, event.id());
            insert.setObject(++i, event.loan().value());
            insert.setObject(++i, event.businessDate());
            insert.setString(++i, event.kind().name());
            insert.setInt(++i, event.state().daysPastDue());
            insert.setString(++i, event.state().bucketName());
            insert.setString(++i, event.kind() == ConditionKind.DELINQUENCY ? event.previousBucketName() : null);
            insert.setBoolean(++i, event.state().defaulted());
            insert.setObject(++i, event.agreement().value());
            insert.setInt(++i, event.agreementVersion());
            insert.setString(++i, correlation.value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a condition change", failure);
        }
    }

    @Override
    public Map<LoanProduct, Map<DelinquencyBucket, Long>> pastDueByProductAndBucket(Connection unitOfWork) {
        Map<LoanProduct, Map<DelinquencyBucket, Long>> counts = new EnumMap<>(LoanProduct.class);
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT l.product, e.bucket, count(*) FROM lending.loan l"
                        + " JOIN LATERAL (SELECT c.bucket FROM lending.loan_condition_event c"
                        + " WHERE c.loan_id = l.id AND c.kind = 'DELINQUENCY' ORDER BY c.business_date DESC LIMIT 1) e"
                        + " ON true WHERE l.status IN ('ACTIVE', 'CLOSING') AND e.bucket <> 'CURRENT'"
                        + " GROUP BY l.product, e.bucket");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                counts.computeIfAbsent(LoanProduct.valueOf(rows.getString(1)),
                        product -> new EnumMap<>(DelinquencyBucket.class))
                        .put(DelinquencyBucket.valueOf(rows.getString(2)), rows.getLong(3));
            }
            return counts;
        } catch (SQLException failure) {
            throw failed("counting past-due accounts", failure);
        }
    }

    @Override
    public Map<LoanProduct, Long> defaultedByProduct(Connection unitOfWork) {
        Map<LoanProduct, Long> counts = new EnumMap<>(LoanProduct.class);
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT l.product, count(*) FROM lending.loan l"
                        + " JOIN LATERAL (SELECT c.defaulted FROM lending.loan_condition_event c"
                        + " WHERE c.loan_id = l.id AND c.kind = 'DEFAULT' ORDER BY c.business_date DESC LIMIT 1) e"
                        + " ON true WHERE l.status IN ('ACTIVE', 'CLOSING') AND e.defaulted GROUP BY l.product");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                counts.put(LoanProduct.valueOf(rows.getString(1)), rows.getLong(2));
            }
            return counts;
        } catch (SQLException failure) {
            throw failed("counting defaulted accounts", failure);
        }
    }

    private static Optional<DelinquencyBucket> bucket(String recorded) {
        return CURRENT.equals(recorded) ? Optional.empty() : Optional.of(DelinquencyBucket.valueOf(recorded));
    }

    private static LendingStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new LendingStorageException("lending storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
