package com.finapp.risk;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One journal entry as {@link MonitoredLedger} serves it (`P13-TSK-001`; `-019` refines it) - the bounded facts the
 * intake classifies, never the ledger's row: the sequence it is read by, the entry's identifier (the monitored
 * transaction's born-once key), its posting key (whose namespace classifies it), when it was posted and its lines.
 */
public record MonitoredEntry(long sequence, UUID journalEntryId, String postingKey, Instant postedAt,
        List<Line> lines) {

    public MonitoredEntry {
        Objects.requireNonNull(journalEntryId, "journalEntryId");
        Objects.requireNonNull(postingKey, "postingKey");
        Objects.requireNonNull(postedAt, "postedAt");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        if (sequence < 1) {
            throw new IllegalArgumentException("a journal sequence is positive");
        }
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("a journal entry has lines");
        }
    }

    /** One line: the account, the side, the amount in minor units and its currency - never a balance. */
    public record Line(UUID accountId, Side side, long amountMinor, CurrencyCode currency) {

        public Line {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(currency, "currency");
            if (amountMinor < 0) {
                throw new IllegalArgumentException("a line's amount is not negative");
            }
        }
    }

    /** A line's side. */
    public enum Side {
        DEBIT,
        CREDIT
    }
}
