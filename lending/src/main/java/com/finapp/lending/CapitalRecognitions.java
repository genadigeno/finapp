package com.finapp.lending;

import com.finapp.ledger.JournalEntryId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The read port over reconciliation's facts about the contributions (`P11-TSK-004`, ADR-0096 sections 4 and 6):
 * declared here, implemented in {@code app}. Recognition is a derived condition, never a lending state - this port is
 * how lending learns it, and how the capital proof learns which entry each recognised contribution explains.
 */
public interface CapitalRecognitions {

    /** Each approved contribution's expectation, keyed by the expectation reference its approval recorded. */
    Map<String, ExpectationReading> expectations(Connection unitOfWork, Collection<String> expectationRefs);

    /** Every recognition in {@code currency}: the recognition entry it explains and the contribution's amount. */
    List<Recognition> recognitions(Connection unitOfWork, CurrencyCode currency);

    /**
     * An approved contribution's expectation as reconciliation holds it: its window, the database's date of the read,
     * whether it is settled, and - once recognised - the recognition entry and date.
     */
    record ExpectationReading(
            LocalDate expectedBy,
            LocalDate today,
            boolean settled,
            Optional<JournalEntryId> entry,
            Optional<LocalDate> recognisedOn) {

        public ExpectationReading {
            Objects.requireNonNull(expectedBy, "expectedBy must not be null");
            Objects.requireNonNull(today, "today must not be null");
            Objects.requireNonNull(entry, "entry must not be null");
            Objects.requireNonNull(recognisedOn, "recognisedOn must not be null");
        }

        /**
         * The derived condition (`CapitalRecognitionStatus.derive`): recognised while its recognition stands - the
         * expectation settled by it; a repudiated statement reopens the expectation and the capital with it.
         */
        public CapitalRecognitionStatus status() {
            return CapitalRecognitionStatus.derive(settled && entry.isPresent(), expectedBy, today);
        }
    }

    /** One recognition: the entry whose capital credit it explains, and the amount recognised. */
    record Recognition(JournalEntryId entry, Money amount) {

        public Recognition {
            Objects.requireNonNull(entry, "entry must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
        }

        @Override
        public String toString() {
            return "Recognition[" + entry.value() + "]";
        }
    }
}
