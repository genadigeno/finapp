package com.finapp.reconciliation;

import com.finapp.ledger.AccountPurpose;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One settlement expectation at birth (`P8-TSK-004`, ADR-0067 §4): copies of immutable
 * facts taken at completion, never references to another module's tables — the amount,
 * entry and account equal the clearing journal line the completing transaction just posted,
 * and matching will never read another schema to allocate (ADR-0064).
 *
 * @param journalEntryId absent exactly for a {@code REMITTANCE} (`V002`'s {@code CHECK}):
 *     a statement line's expectation is opened at report acceptance (`P8-TSK-009`) and
 *     records no posting of ours
 * @param settlementCycle the cycle the completion announced, when it announced one — a
 *     matching attribute and a report dimension, never a key (the transition's A5)
 * @param expectedBy {@code posting_date + lag_days[kind]} from the source's active rule
 *     set, whose id {@code ruleSetId} pins on the row ({@code INV-HIST-04})
 */
public record NewExpectation(
        ExpectationKind kind,
        String operationRef,
        String postingKey,
        UUID sourceId,
        AccountPurpose positionPurpose,
        UUID ledgerAccountId,
        ExpectationDirection direction,
        Money amount,
        Optional<UUID> journalEntryId,
        LocalDate postingDate,
        Optional<String> settlementCycle,
        LocalDate expectedBy,
        UUID ruleSetId,
        List<ExpectationKey> keys,
        Actor openedBy,
        Instant openedAt,
        CorrelationId correlation) {

    /** One typed reference the counterparty will quote, or an anchor a rule resolves. */
    public record ExpectationKey(KeyKind kind, String value) {
        public ExpectationKey {
            Objects.requireNonNull(kind, "key kind must not be null");
            Objects.requireNonNull(value, "key value must not be null");
            if (value.isBlank()) {
                throw new IllegalArgumentException("a key value must not be blank");
            }
        }
    }

    public NewExpectation {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(operationRef, "operationRef must not be null");
        Objects.requireNonNull(postingKey, "postingKey must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(positionPurpose, "positionPurpose must not be null");
        Objects.requireNonNull(ledgerAccountId, "ledgerAccountId must not be null");
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(journalEntryId, "journalEntryId must not be null");
        Objects.requireNonNull(postingDate, "postingDate must not be null");
        Objects.requireNonNull(settlementCycle, "settlementCycle must not be null");
        Objects.requireNonNull(expectedBy, "expectedBy must not be null");
        Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
        Objects.requireNonNull(openedBy, "openedBy must not be null");
        Objects.requireNonNull(openedAt, "openedAt must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        keys = List.copyOf(keys);
        if (CoverLegKey.qualifies(kind)) {
            // A cover's two legs share their references: each leg's key is its currency's (P9-TSK-012).
            keys = keys.stream()
                    .map(key -> CoverLegKey.qualifies(key.kind())
                            ? new ExpectationKey(key.kind(), CoverLegKey.qualify(key.value(), amount.currency()))
                            : key)
                    .toList();
        }
        // Invalid input is a programming defect and throws loudly, rolling the completion
        // back with its expectation (ADR-0067 §6): an amount that asserts nothing cannot
        // decompose a position, and a non-remittance with no entry records no posting.
        if (!amount.isPositive()) {
            throw new IllegalArgumentException(
                    "an expectation's amount must be strictly positive (ADR-0067 §6)");
        }
        if ((kind == ExpectationKind.REMITTANCE) == journalEntryId.isPresent()) {
            throw new IllegalArgumentException(
                    "a REMITTANCE expectation records no posting of ours, and every other"
                            + " kind records exactly the entry its completion posted"
                            + " (ADR-0067 §4)");
        }
    }
}
