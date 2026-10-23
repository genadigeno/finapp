package com.finapp.risk;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The channel-neutral record of one money movement, born once per ledger journal entry (`P13-TSK-019`;
 * {@code PHASE_13_PLAN.md} section 3.1, {@code RISK_LIFECYCLES.md} section 4.11; {@code INV-RSK-09}) - as
 * {@link MonitoringClassification} derives it from a {@link MonitoredEntry} and {@code risk V004} holds it,
 * {@code INSERT}-only: the kind its posting-key namespace yields (or {@link MonitoredKind#UNCLASSIFIED}), the subject
 * whose ledger account moved, the direction and the judged amount as seen from that account, the other side, the value
 * date. Never a balance; never updated.
 *
 * @param amountMinor the movement's size in minor units at {@code scale} - the subject account's lines summed on the
 *     dominant side, or the entry's debit total when it has no subject
 */
public record MonitoredTransaction(MonitoredTransactionId id, UUID journalEntryId, long journalSequence,
        MonitoredEntry.EntryType entryType, String postingNamespace, MonitoredKind kind,
        Optional<MonitoredSubjectKind> subjectKind, Optional<UUID> subjectRef, Optional<MonitoredDirection> direction,
        MonitoredCounterpartyKind counterpartyKind, Optional<UUID> counterpartyRef, Optional<String> counterpartyPurpose,
        long amountMinor, int scale, CurrencyCode currency, LocalDate valueDate, Instant postedAt,
        Optional<UUID> reversesJournalEntryId) {

    public MonitoredTransaction {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(journalEntryId, "journalEntryId");
        Objects.requireNonNull(entryType, "entryType");
        Objects.requireNonNull(postingNamespace, "postingNamespace");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(subjectKind, "subjectKind");
        Objects.requireNonNull(subjectRef, "subjectRef");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(counterpartyKind, "counterpartyKind");
        Objects.requireNonNull(counterpartyRef, "counterpartyRef");
        Objects.requireNonNull(counterpartyPurpose, "counterpartyPurpose");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(valueDate, "valueDate");
        Objects.requireNonNull(postedAt, "postedAt");
        Objects.requireNonNull(reversesJournalEntryId, "reversesJournalEntryId");
        if (journalSequence < 0) {
            throw new IllegalArgumentException("a journal sequence is not negative");
        }
        if (postingNamespace.isBlank() || postingNamespace.length() > 64) {
            throw new IllegalArgumentException("a posting namespace is 1 to 64 characters");
        }
        if (subjectKind.isPresent() != subjectRef.isPresent() || subjectKind.isPresent() != direction.isPresent()) {
            throw new IllegalArgumentException("a subject is whole: its kind, its reference and the direction together");
        }
        if (amountMinor < 0) {
            throw new IllegalArgumentException("a judged amount is not negative");
        }
        if ((entryType == MonitoredEntry.EntryType.REVERSAL) != reversesJournalEntryId.isPresent()) {
            throw new IllegalArgumentException("a REVERSAL names its original, and no other kind does");
        }
    }
}
