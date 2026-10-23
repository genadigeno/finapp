package com.finapp.risk;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One journal entry as {@link MonitoredLedger} serves it (`P13-TSK-001`; reshaped by `P13-TSK-019`, which first
 * implements the port) - the bounded facts the intake classifies, never the ledger's row: the entry's identifier (the
 * monitored transaction's born-once key) and its sequence (the identifier's 48-bit millisecond prefix, ADR-0013 - the
 * journal has no sequence column, {@code DESIGN-019} section 0), its type, its posting key (whose namespace classifies
 * it; the ledger's own scope prefix already stripped by the port's implementation), the original a reversal compensates,
 * when it was posted, its value date and its lines - each with its account's owner kind, owner reference and purpose as
 * the ledger names them by value, so the subject and the counterparty are read from the journal alone.
 *
 * @param sequence the entry id's millisecond prefix - what the watermark's {@code last_sequence} and the door's
 *     {@code fromSequence} speak in
 * @param postingKey the command key the ledger recorded, without its {@code ledger.post:} / {@code ledger.reverse:} /
 *     {@code ledger.adjust:} prefix
 */
public record MonitoredEntry(UUID journalEntryId, long sequence, EntryType entryType, String postingKey,
        Optional<UUID> reverses, Instant postedAt, LocalDate valueDate, List<Line> lines) {

    public MonitoredEntry {
        Objects.requireNonNull(journalEntryId, "journalEntryId");
        Objects.requireNonNull(entryType, "entryType");
        Objects.requireNonNull(postingKey, "postingKey");
        Objects.requireNonNull(reverses, "reverses");
        Objects.requireNonNull(postedAt, "postedAt");
        Objects.requireNonNull(valueDate, "valueDate");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        if (sequence < 0) {
            throw new IllegalArgumentException("a journal sequence is not negative");
        }
        if (postingKey.isBlank()) {
            throw new IllegalArgumentException("a journal entry names its command key");
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("a journal entry has lines");
        }
        if ((entryType == EntryType.REVERSAL) != reverses.isPresent()) {
            throw new IllegalArgumentException("a REVERSAL names the entry it compensates, and no other kind does");
        }
    }

    /** The ledger's entry types by value. */
    public enum EntryType {
        POSTING,
        REVERSAL,
        ADJUSTMENT
    }

    /**
     * One line: the account, its owner as the ledger names it (the owner kind code, the opaque owner reference where it
     * has one, the purpose code), the side, the amount in minor units at its stored scale and its currency - never a
     * balance.
     */
    public record Line(UUID accountId, String ownerKind, Optional<UUID> ownerRef, String purpose, Side side,
            long amountMinor, int scale, CurrencyCode currency) {

        public Line {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(ownerKind, "ownerKind");
            Objects.requireNonNull(ownerRef, "ownerRef");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(currency, "currency");
            if (amountMinor < 0) {
                throw new IllegalArgumentException("a line's amount is not negative");
            }
            if (scale < 0 || scale > 9) {
                throw new IllegalArgumentException("a line's scale is the ledger's: 0 to 9");
            }
        }
    }

    /** A line's side. */
    public enum Side {
        DEBIT,
        CREDIT
    }
}
