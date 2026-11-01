package com.finapp.accounting;

import java.math.BigInteger;
import java.sql.Connection;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The journal as rule conformance reads it (`P14-TSK-010`; ADR-0117 decision 2; {@code INV-ACC-12}) - a port {@code app}
 * implements over the ledger's own tables, beside {@link JournalReads}, because {@code accounting} has no ledger edge: a
 * period's entries, each with the door it was written through, the namespace of the command key that wrote it (or, for
 * an adjustment, its proposal's origin - every adjustment's key is {@code ledger.adjust.approve:<proposal>}) and every
 * line's purpose, side and amount. Never the entry's free-text {@code reference}, reason or actor.
 *
 * <p><strong>Read-only, on the caller's snapshot</strong> - {@code SELECT}s only on one {@code REPEATABLE READ READ ONLY}
 * transaction ({@code INV-ACC-14}). <strong>Bounded</strong>: one {@link JournalReads.Range} (a month), keyset pages of at
 * most {@link #MAX_LIMIT} entries in {@code (posting_date, id)} order - ledger {@code V029}'s index.
 */
public interface JournalShapes {

    /** The largest page. */
    int MAX_LIMIT = 500;

    /**
     * One keyset page of the entries posted in {@code range}, after {@code after}, at most {@code limit}, ordered by
     * posting date and id, each with all its lines in line order.
     */
    List<EntryShape> entries(Connection snapshot, JournalReads.Range range, Optional<EntryCursor> after, int limit);

    /** The position after which a page starts: the last entry served. */
    record EntryCursor(LocalDate postingDate, UUID entryId) {

        public EntryCursor {
            Objects.requireNonNull(postingDate, "postingDate");
            Objects.requireNonNull(entryId, "entryId");
        }
    }

    /**
     * One journal entry as conformance judges it.
     *
     * @param type the ledger door that wrote it
     * @param namespace for a {@link EntryType#POSTING} or {@link EntryType#REVERSAL}, its command key's namespace
     *     ({@link PostingRuleRegister#namespaceOf}); for an {@link EntryType#ADJUSTMENT}, its proposal's origin - empty
     *     when the ledger names none (a key of another shape, an adjustment with no proposal)
     */
    record EntryShape(UUID id, EntryType type, LocalDate postingDate, Optional<String> namespace, List<ShapeLine> lines) {

        public EntryShape {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(postingDate, "postingDate");
            Objects.requireNonNull(namespace, "namespace");
            lines = List.copyOf(lines);
        }

        /** This entry as a position - the cursor after it. */
        public EntryCursor cursor() {
            return new EntryCursor(postingDate, id);
        }
    }

    /** One line: its order, its account's purpose, its side, and its amount in minor units at its scale. */
    record ShapeLine(int seq, String purpose, Side side, String currency, int scale, BigInteger minorUnits) {

        public ShapeLine {
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(minorUnits, "minorUnits");
            if (side != Side.DEBIT && side != Side.CREDIT) {
                throw new IllegalArgumentException("a journal line is a debit or a credit");
            }
        }
    }
}
