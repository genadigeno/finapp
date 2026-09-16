package com.finapp.ledger;

import java.time.LocalDate;
import java.util.Objects;

/**
 * The moment a balance is derived "as of" (`P3-TSK-008`).
 *
 * <p>Two of the three cuts answer different questions and neither substitutes for the other:
 * {@link PostingDate} is the <em>accounting</em> question — what did this account hold at the
 * end of a period's day, whatever order the rows were written in — while {@link ThroughEntry}
 * is the <em>replay</em> question: everything up to and including one entry, for a
 * point-in-time replay over rows already committed.
 *
 * <p><strong>The entry cut orders by the entry's UUIDv7 identifier, and the comparison lives in
 * SQL only.</strong> Entry ids sort by <em>mint</em> time (ADR-0013), and PostgreSQL compares
 * {@code uuid} bytewise — whereas {@code java.util.UUID.compareTo} compares two signed longs
 * and disagrees with that ordering, so no Java-side comparison of entry ids may ever be
 * written. The caveat the cut carries is stated rather than glossed: ids order by mint and
 * commits can interleave, so under live concurrent posting an entry with a smaller id can
 * commit <em>after</em> a derivation ran. That makes an id cut a replay boundary over rows
 * already committed, not a linearisation point — which is why the projection's watermark
 * ({@code P3-TSK-009}) is deliberately <em>not</em> an entry id: {@code last_entry_seq}
 * counts the entries applied to one row, serialised by that row's own lock, so it carries no
 * ordering semantics for this caveat to break, and the projection check compares against
 * {@link #latest()} under the row's lock rather than through this cut.
 */
public sealed interface AsOf {

    /** Every committed line. The verification job's everyday cut. */
    static AsOf latest() {
        return new Latest();
    }

    /**
     * Lines of entries whose {@code posting_date} is on or before {@code date} — inclusive,
     * because "the balance as of the 31st" means with the 31st's postings in it.
     */
    static AsOf postingDate(LocalDate date) {
        return new PostingDate(date);
    }

    /** Lines of entries up to and including {@code entry}, in UUIDv7 order. See class doc. */
    static AsOf throughEntry(JournalEntryId entry) {
        return new ThroughEntry(entry);
    }

    record Latest() implements AsOf {}

    record PostingDate(LocalDate date) implements AsOf {
        public PostingDate {
            Objects.requireNonNull(date, "date must not be null");
        }
    }

    record ThroughEntry(JournalEntryId entry) implements AsOf {
        public ThroughEntry {
            Objects.requireNonNull(entry, "entry must not be null");
        }
    }
}
