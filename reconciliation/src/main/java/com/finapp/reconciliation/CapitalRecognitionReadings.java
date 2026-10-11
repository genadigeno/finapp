package com.finapp.reconciliation;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What reconciliation knows of the capital contributions (`P11-TSK-004`, ADR-0096 section 4):
 * each contribution's expectation as it stands, and every recognition with the entry it explains.
 * Read by {@code app} for lending - reconciliation never calls lending and lending never reads this
 * schema; recognition is a derived condition, never a lending state.
 */
public final class CapitalRecognitionReadings {

    /**
     * One contribution's expectation: its status, its window, the database's date of the read, and
     * - once recognised - the entry and the date.
     */
    public record ContributionReading(
            String operationRef,
            ExpectationStatus status,
            LocalDate expectedBy,
            LocalDate today,
            Optional<UUID> journalEntryId,
            Optional<LocalDate> recognisedOn) {

        public ContributionReading {
            Objects.requireNonNull(operationRef, "operationRef must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(expectedBy, "expectedBy must not be null");
            Objects.requireNonNull(today, "today must not be null");
            Objects.requireNonNull(journalEntryId, "journalEntryId must not be null");
            Objects.requireNonNull(recognisedOn, "recognisedOn must not be null");
        }
    }

    /** One recognition: the entry whose capital line it explains, and the contribution's amount. */
    public record RecognitionReading(UUID journalEntryId, Money amount) {

        public RecognitionReading {
            Objects.requireNonNull(journalEntryId, "journalEntryId must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
        }

        @Override
        public String toString() {
            return "RecognitionReading[" + journalEntryId + "]";
        }
    }

    /** The expectations of the contributions whose operation references are {@code operationRefs}. */
    public List<ContributionReading> contributions(Connection unitOfWork, Collection<String> operationRefs) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(operationRefs, "operationRefs must not be null");
        if (operationRefs.isEmpty()) {
            return List.of();
        }
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT e.operation_ref, e.status, e.expected_by,"
                                + " (statement_timestamp() AT TIME ZONE 'UTC')::date AS today,"
                                + " r.journal_entry_id, r.recognised_on"
                                + " FROM reconciliation.expectation e"
                                + " LEFT JOIN reconciliation.capital_recognition r ON r.expectation_id = e.id"
                                + " WHERE e.kind = 'LENDING_CAPITAL_CONTRIBUTION' AND e.operation_ref = ANY (?)")) {
            Array refs = unitOfWork.createArrayOf("text", operationRefs.toArray());
            select.setArray(1, refs);
            List<ContributionReading> readings = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    readings.add(
                            new ContributionReading(
                                    row.getString("operation_ref"),
                                    ExpectationStatus.valueOf(row.getString("status")),
                                    row.getObject("expected_by", LocalDate.class),
                                    row.getObject("today", LocalDate.class),
                                    Optional.ofNullable(row.getObject("journal_entry_id", UUID.class)),
                                    Optional.ofNullable(row.getObject("recognised_on", LocalDate.class))));
                }
            }
            return List.copyOf(readings);
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not read the capital contributions' expectations", failure);
        }
    }

    /** Every recognition in {@code currency}, each with the entry it explains. */
    public List<RecognitionReading> recognitions(Connection unitOfWork, CurrencyCode currency) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(currency, "currency must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT journal_entry_id, amount_minor, scale FROM reconciliation.capital_recognition"
                                + " WHERE currency = ? ORDER BY journal_entry_id")) {
            select.setString(1, currency.code());
            List<RecognitionReading> readings = new ArrayList<>();
            try (ResultSet row = select.executeQuery()) {
                while (row.next()) {
                    readings.add(
                            new RecognitionReading(
                                    row.getObject("journal_entry_id", UUID.class),
                                    Money.ofPersisted(row.getLong("amount_minor"), currency, row.getShort("scale"))));
                }
            }
            return List.copyOf(readings);
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("could not read the capital recognitions", failure);
        }
    }
}
