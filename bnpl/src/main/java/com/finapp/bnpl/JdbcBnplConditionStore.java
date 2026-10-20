package com.finapp.bnpl;

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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link BnplConditionStore} over plain JDBC (`P12-TSK-020`, ADR-0033; lending's {@code JdbcLoanConditionStore} and
 * {@code JdbcFeeAssessmentStore}): every instant the database's - {@code bnpl V013}'s triggers stamp it after the act's
 * locks - every failure its operation and SQLSTATE only ({@link BnplStorageException}) - never a figure.
 */
public final class JdbcBnplConditionStore implements BnplConditionStore {

    @Override
    public Instant databaseNow(Connection unitOfWork) {
        try (PreparedStatement select = unitOfWork.prepareStatement("SELECT clock_timestamp()");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getTimestamp(1).toInstant();
        } catch (SQLException failure) {
            throw failed("reading the database clock", failure);
        }
    }

    @Override
    public Optional<BnplConditionEvent> latest(Connection unitOfWork, BnplObligationId obligation,
            BnplConditionKind kind) {
        Objects.requireNonNull(obligation, "obligation");
        Objects.requireNonNull(kind, "kind");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                // An obligation the caller holds FOR UPDATE (B4) - the servicing step's - or the owner's read's own.
                "SELECT id, business_date, days_past_due, bucket, previous_bucket, defaulted, agreement_id,"
                        + " agreement_version, correlation_id FROM bnpl.bnpl_condition_event"
                        + " WHERE obligation_id = ? AND kind = ? ORDER BY business_date DESC LIMIT 1")) {
            select.setObject(1, obligation.value());
            select.setString(2, kind.name());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new BnplConditionEvent(
                        row.getObject("id", UUID.class),
                        obligation,
                        row.getObject("business_date", LocalDate.class),
                        kind,
                        new BnplDelinquencyState(row.getInt("days_past_due"),
                                BnplDelinquencyBucket.fromRecorded(row.getString("bucket")), row.getBoolean("defaulted")),
                        Optional.ofNullable(row.getString("previous_bucket"))
                                .flatMap(BnplDelinquencyBucket::fromRecorded),
                        BnplAgreementId.of(row.getObject("agreement_id", UUID.class)),
                        row.getInt("agreement_version"),
                        CorrelationId.of(row.getString("correlation_id"))));
            }
        } catch (SQLException failure) {
            throw failed("reading an obligation's latest condition", failure);
        }
    }

    @Override
    public void insertCondition(Connection unitOfWork, BnplConditionEvent event) {
        Objects.requireNonNull(event, "event");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_condition_event (id, obligation_id, business_date, kind, days_past_due, bucket,"
                        + " previous_bucket, defaulted, agreement_id, agreement_version, created_at, correlation_id)"
                        // created_at is the trigger's, clock_timestamp() after B4.
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?)")) {
            int i = 0;
            insert.setObject(++i, event.id());
            insert.setObject(++i, event.obligation().value());
            insert.setObject(++i, event.businessDate());
            insert.setString(++i, event.kind().name());
            insert.setInt(++i, event.state().daysPastDue());
            insert.setString(++i, event.state().bucketName());
            insert.setString(++i, event.kind() == BnplConditionKind.DELINQUENCY ? event.previousBucketName() : null);
            insert.setBoolean(++i, event.state().defaulted());
            insert.setObject(++i, event.agreement().value());
            insert.setInt(++i, event.agreementVersion());
            insert.setString(++i, event.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a condition change", failure);
        }
    }

    @Override
    public List<BnplFeeAssessment> assessments(Connection unitOfWork, BnplObligationId obligation) {
        Objects.requireNonNull(obligation, "obligation");
        try (PreparedStatement select = unitOfWork.prepareStatement(
                // An obligation the caller holds FOR UPDATE (B4): the servicing step's.
                "SELECT id, instalment_id, kind, business_date, currency, scale, amount_minor, agreement_id,"
                        + " agreement_version, journal_entry_id, correlation_id FROM bnpl.bnpl_fee_assessment"
                        + " WHERE obligation_id = ? ORDER BY business_date, created_at")) {
            select.setObject(1, obligation.value());
            List<BnplFeeAssessment> assessments = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    assessments.add(new BnplFeeAssessment(row.getObject("id", UUID.class), obligation,
                            row.getObject("instalment_id", UUID.class), BnplFeeKind.valueOf(row.getString("kind")),
                            row.getObject("business_date", LocalDate.class),
                            Money.ofPersisted(row.getLong("amount_minor"),
                                    CurrencyCode.of(row.getString("currency").strip()), row.getInt("scale")),
                            BnplAgreementId.of(row.getObject("agreement_id", UUID.class)),
                            row.getInt("agreement_version"),
                            JournalEntryId.of(row.getObject("journal_entry_id", UUID.class)),
                            CorrelationId.of(row.getString("correlation_id"))));
                }
            }
            return assessments;
        } catch (SQLException failure) {
            throw failed("reading an obligation's fee assessments", failure);
        }
    }

    @Override
    public void insertAssessment(Connection unitOfWork, BnplFeeAssessment assessment) {
        Objects.requireNonNull(assessment, "assessment");
        try (PreparedStatement insert = unitOfWork.prepareStatement(
                "INSERT INTO bnpl.bnpl_fee_assessment (id, obligation_id, instalment_id, kind, business_date, currency,"
                        + " scale, amount_minor, agreement_id, agreement_version, journal_entry_id, created_at,"
                        + " correlation_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, clock_timestamp(), ?)")) {
            int i = 0;
            insert.setObject(++i, assessment.id());
            insert.setObject(++i, assessment.obligation().value());
            insert.setObject(++i, assessment.instalment());
            insert.setString(++i, assessment.kind().name());
            insert.setObject(++i, assessment.businessDate());
            insert.setString(++i, assessment.amount().currency().code());
            insert.setInt(++i, assessment.amount().scale());
            insert.setLong(++i, assessment.amount().minorUnits());
            insert.setObject(++i, assessment.agreement().value());
            insert.setInt(++i, assessment.agreementVersion());
            insert.setObject(++i, assessment.entry().value());
            insert.setString(++i, assessment.correlation().value());
            insert.executeUpdate();
        } catch (SQLException failure) {
            throw failed("inserting a BNPL fee assessment", failure);
        }
    }

    @Override
    public Map<BnplProduct, Map<BnplDelinquencyBucket, Long>> pastDueByProductAndBucket(Connection unitOfWork) {
        Map<BnplProduct, Map<BnplDelinquencyBucket, Long>> counts = new EnumMap<>(BnplProduct.class);
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT o.product, e.bucket, count(*) FROM bnpl.bnpl_obligation o"
                        + " JOIN LATERAL (SELECT c.bucket FROM bnpl.bnpl_condition_event c WHERE c.obligation_id = o.id"
                        + " AND c.kind = 'DELINQUENCY' ORDER BY c.business_date DESC LIMIT 1) e ON true"
                        + " WHERE o.status = 'ACTIVE' AND e.bucket <> 'CURRENT' GROUP BY o.product, e.bucket");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                counts.computeIfAbsent(BnplProduct.valueOf(rows.getString(1)),
                        product -> new EnumMap<>(BnplDelinquencyBucket.class))
                        .put(BnplDelinquencyBucket.valueOf(rows.getString(2)), rows.getLong(3));
            }
            return counts;
        } catch (SQLException failure) {
            throw failed("counting past-due obligations", failure);
        }
    }

    @Override
    public Map<BnplProduct, Long> defaultedByProduct(Connection unitOfWork) {
        Map<BnplProduct, Long> counts = new EnumMap<>(BnplProduct.class);
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT o.product, count(*) FROM bnpl.bnpl_obligation o"
                        + " JOIN LATERAL (SELECT c.defaulted FROM bnpl.bnpl_condition_event c WHERE c.obligation_id = o.id"
                        + " AND c.kind = 'DEFAULT' ORDER BY c.business_date DESC LIMIT 1) e ON true"
                        + " WHERE o.status = 'ACTIVE' AND e.defaulted GROUP BY o.product");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                counts.put(BnplProduct.valueOf(rows.getString(1)), rows.getLong(2));
            }
            return counts;
        } catch (SQLException failure) {
            throw failed("counting defaulted obligations", failure);
        }
    }

    @Override
    public Map<BnplProduct, Map<BnplObligationStatus, Long>> obligationsByProductAndStatus(Connection unitOfWork) {
        Map<BnplProduct, Map<BnplObligationStatus, Long>> counts = new EnumMap<>(BnplProduct.class);
        try (PreparedStatement select = unitOfWork.prepareStatement(
                "SELECT product, status, count(*) FROM bnpl.bnpl_obligation GROUP BY product, status");
                ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                counts.computeIfAbsent(BnplProduct.valueOf(rows.getString(1)),
                        product -> new EnumMap<>(BnplObligationStatus.class))
                        .put(BnplObligationStatus.valueOf(rows.getString(2)), rows.getLong(3));
            }
            return counts;
        } catch (SQLException failure) {
            throw failed("counting BNPL obligations", failure);
        }
    }

    private static BnplStorageException failed(String operation, SQLException failure) {
        Objects.requireNonNull(failure, "failure");
        return new BnplStorageException("bnpl storage failed " + operation + ": SQLSTATE " + failure.getSQLState());
    }
}
