package com.finapp.app.lending;

import com.finapp.ledger.JournalEntryId;
import com.finapp.lending.CapitalRecognitions;
import com.finapp.reconciliation.CapitalRecognitionReadings;
import com.finapp.reconciliation.ExpectationStatus;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * lending's {@link CapitalRecognitions}, over reconciliation's readings (`P11-TSK-004`, ADR-0096 sections 4 and 6):
 * recognition is reconciliation's fact, read here and handed to lending - reconciliation never calls lending, and
 * lending never reads reconciliation's schema.
 */
@RequiredArgsConstructor
public final class ReconciliationCapitalRecognitions implements CapitalRecognitions {

    @NonNull private final CapitalRecognitionReadings readings;

    @Override
    public Map<String, ExpectationReading> expectations(Connection unitOfWork, Collection<String> expectationRefs) {
        Map<String, ExpectationReading> found = new LinkedHashMap<>();
        for (CapitalRecognitionReadings.ContributionReading reading : readings.contributions(unitOfWork, expectationRefs)) {
            found.put(
                    reading.operationRef(),
                    new ExpectationReading(
                            reading.expectedBy(),
                            reading.today(),
                            reading.status() == ExpectationStatus.SETTLED,
                            reading.journalEntryId().map(JournalEntryId::of),
                            reading.recognisedOn()));
        }
        return Map.copyOf(found);
    }

    @Override
    public List<Recognition> recognitions(Connection unitOfWork, CurrencyCode currency) {
        return readings.recognitions(unitOfWork, currency).stream()
                .map(reading -> new Recognition(JournalEntryId.of(reading.journalEntryId()), reading.amount()))
                .toList();
    }
}
