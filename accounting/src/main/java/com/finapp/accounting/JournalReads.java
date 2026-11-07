package com.finapp.accounting;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The ledger's journal as accounting reads it (`P14-TSK-001` declared it; `P14-TSK-007` gives it its methods;
 * {@code PHASE_14_PLAN.md} sections 4.2, 13.1; ADR-0113 decisions 1-2; {@code INV-ACC-02}, {@code INV-ACC-06},
 * {@code INV-ACC-14}) - the one financial truth, read through a port {@code app} implements over the ledger's own tables,
 * because {@code accounting} has no edge to {@code ledger} and must never gain one. GL aggregates (journal lines summed by
 * purpose, owner kind, counterparty, currency, scale and side over a posting-date range), the ledger's own totals over
 * the same range (no account join - the other side of GL = ledger), paged drill-down lines and one entry with its lines
 * - never a balance stored here, never a person's name: a line carries its account's owner <em>reference</em>.
 *
 * <p><strong>Read-only, on the caller's snapshot.</strong> Every method takes the connection of one
 * {@code REPEATABLE READ READ ONLY} transaction ({@code INV-ACC-14}, owner decision C10) and issues {@code SELECT}s only -
 * no lock, no advisory key - so all the reads of one request see one instant and a read never waits on a posting.
 *
 * <p><strong>Bounded.</strong> A range is at most {@link Range#MAX_DAYS} days of {@code posting_date} (ledger {@code V029}'s
 * index {@code journal_entry (posting_date, id)} serves it); drill-down pages are keyset pages of at most
 * {@link LineQuery#MAX_LIMIT} lines.
 */
public interface JournalReads {

    /** The journal lines of {@code range} summed per purpose, owner kind, counterparty, currency, scale and side. */
    List<Aggregate> aggregates(Connection snapshot, Range range);

    /**
     * The ledger's own totals over {@code range} per currency, scale and side - read without the account join, so it is
     * independent of any mapping: the ledger side of GL = ledger.
     */
    List<Total> totals(Connection snapshot, Range range);

    /** One keyset page of the lines {@code query} selects, ordered by posting date, entry id and line order. */
    List<Line> lines(Connection snapshot, LineQuery query);

    /** One journal entry with all its lines, in line order; empty when no entry has this id. */
    Optional<Entry> entry(Connection snapshot, UUID entryId);

    /**
     * How many journal lines dated on or after {@code fromInclusive} sit on an account whose {@link MappingRule#key()}
     * ({@code PURPOSE/OWNER_KIND}) is one of {@code keys} - the unmapped gauge's count (`P14-TSK-007`).
     */
    long countLines(Connection snapshot, Set<String> keys, LocalDate fromInclusive);

    // ------------------------------------------------------------------ the values

    /** A posting-date range, both ends inclusive, at most {@link #MAX_DAYS} days - a read is never unbounded. */
    record Range(LocalDate from, LocalDate to) {

        /** The longest range: a year, the longest statement period. The GL doors read one calendar month. */
        public static final int MAX_DAYS = 366;

        public Range {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (to.isBefore(from)) {
                throw new IllegalArgumentException("a posting-date range ends on or after its start");
            }
            if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
                throw new IllegalArgumentException("a posting-date range is at most " + MAX_DAYS + " days");
            }
        }

        /** The calendar month - an accounting period (owner decision C9). */
        public static Range of(YearMonth month) {
            return new Range(month.atDay(1), month.atEndOfMonth());
        }

        /** True when {@code day} is inside the range. */
        public boolean holds(LocalDate day) {
            return !day.isBefore(from) && !day.isAfter(to);
        }
    }

    /**
     * The lines of one (purpose, owner kind, counterparty, currency, scale, side) in a range: their minor units summed
     * exactly ({@code SUM(bigint)} is {@code numeric}) and counted. The scale is a grouping key, so no sum ever crosses a
     * scale ({@code TrialBalance}'s argument); {@link #amount()} is exact.
     */
    record Aggregate(
            String purpose,
            String ownerKind,
            Optional<String> counterparty,
            String currency,
            int scale,
            Side side,
            BigInteger minorUnits,
            long lines) {

        public Aggregate {
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(ownerKind, "ownerKind");
            Objects.requireNonNull(counterparty, "counterparty");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(minorUnits, "minorUnits");
            requireDirection(side);
        }

        /** {@link MappingRule#key()}'s shape - what a version's rules are looked up by. */
        public String key() {
            return purpose + "/" + ownerKind;
        }

        /** The sum as an exact decimal in the currency. */
        public BigDecimal amount() {
            return new BigDecimal(minorUnits, scale);
        }
    }

    /** The ledger's own total of one (currency, scale, side) in a range - no account, no purpose, no mapping. */
    record Total(String currency, int scale, Side side, BigInteger minorUnits, long lines) {

        public Total {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(minorUnits, "minorUnits");
            requireDirection(side);
        }

        public BigDecimal amount() {
            return new BigDecimal(minorUnits, scale);
        }
    }

    /**
     * The position after which a drill-down page starts: the last line served - its posting date, entry id and line
     * order, the order {@code V029}'s index serves. A position, not a capability: the next page is still filtered by its
     * query. {@link #encoded()} is its opaque, URL-safe form.
     */
    record LineCursor(LocalDate postingDate, UUID entryId, int seq) {

        public LineCursor {
            Objects.requireNonNull(postingDate, "postingDate");
            Objects.requireNonNull(entryId, "entryId");
            if (seq < 0) {
                throw new IllegalArgumentException("a line's order is natural");
            }
        }

        /** The URL-safe form a client hands back as {@code after}. */
        public String encoded() {
            String plain = postingDate + "|" + entryId + "|" + seq;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        }

        /** The cursor an encoded form names; {@link IllegalArgumentException} for anything this class did not write. */
        public static LineCursor parse(String encoded) {
            if (encoded == null || encoded.isEmpty() || encoded.length() > 128) {
                throw new IllegalArgumentException("not a drill-down cursor");
            }
            try {
                String[] parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)
                        .split("\\|", -1);
                if (parts.length != 3) {
                    throw new IllegalArgumentException("not a drill-down cursor");
                }
                return new LineCursor(LocalDate.parse(parts[0]), UUID.fromString(parts[1]), Integer.parseInt(parts[2]));
            } catch (IllegalArgumentException | DateTimeParseException malformed) {
                throw new IllegalArgumentException("not a drill-down cursor");
            }
        }

        /** True when {@code line} comes after this position in the drill-down's order. */
        public boolean precedes(Line line) {
            int byDate = line.postingDate().compareTo(postingDate);
            if (byDate != 0) {
                return byDate > 0;
            }
            int byEntry = compareIds(line.entryId(), entryId);
            if (byEntry != 0) {
                return byEntry > 0;
            }
            return line.seq() > seq;
        }

        /**
         * PostgreSQL's {@code uuid} order - the sixteen bytes compared unsigned, big-endian - which {@link UUID#compareTo}
         * is not (it compares signed halves); the drill-down's order is the database's.
         */
        public static int compareIds(UUID left, UUID right) {
            int high = Long.compareUnsigned(left.getMostSignificantBits(), right.getMostSignificantBits());
            return high != 0 ? high : Long.compareUnsigned(left.getLeastSignificantBits(), right.getLeastSignificantBits());
        }
    }

    /**
     * A drill-down page: the lines in {@code range} and {@code currency} whose account's key is one of {@code keys} - the
     * keys a GL account's rules map - after {@code after}, at most {@code limit}.
     */
    record LineQuery(Range range, Set<String> keys, String currency, Optional<LineCursor> after, int limit) {

        /** The largest page. */
        public static final int MAX_LIMIT = 500;

        public LineQuery {
            Objects.requireNonNull(range, "range");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(after, "after");
            keys = Set.copyOf(keys);
            if (keys.isEmpty()) {
                throw new IllegalArgumentException("a drill-down names the keys its figure folds");
            }
            if (limit < 1 || limit > MAX_LIMIT) {
                throw new IllegalArgumentException("a drill-down page holds 1 to " + MAX_LIMIT + " lines");
            }
        }
    }

    /**
     * One journal line as the GL drills to it: its entry and order, the entry's dates and type, its ledger account and
     * that account's purpose, owner kind and <strong>owner reference</strong> (a UUID - never a name) and counterparty
     * code, its side and amount. No reason, no actor (`RESTRICTED-PII`).
     */
    record Line(
            UUID entryId,
            int seq,
            LocalDate postingDate,
            LocalDate valueDate,
            String entryType,
            UUID ledgerAccountId,
            String purpose,
            String ownerKind,
            Optional<UUID> ownerRef,
            Optional<String> counterparty,
            Side side,
            long minorUnits,
            String currency,
            int scale) {

        public Line {
            Objects.requireNonNull(entryId, "entryId");
            Objects.requireNonNull(postingDate, "postingDate");
            Objects.requireNonNull(valueDate, "valueDate");
            Objects.requireNonNull(entryType, "entryType");
            Objects.requireNonNull(ledgerAccountId, "ledgerAccountId");
            Objects.requireNonNull(purpose, "purpose");
            Objects.requireNonNull(ownerKind, "ownerKind");
            Objects.requireNonNull(ownerRef, "ownerRef");
            Objects.requireNonNull(counterparty, "counterparty");
            Objects.requireNonNull(currency, "currency");
            requireDirection(side);
        }

        public String key() {
            return purpose + "/" + ownerKind;
        }

        public BigDecimal amount() {
            return BigDecimal.valueOf(minorUnits, scale);
        }

        /** This line as a position - the cursor after it. */
        public LineCursor cursor() {
            return new LineCursor(postingDate, entryId, seq);
        }
    }

    /**
     * One journal entry: its type, its two business dates, the entry it reverses, and every line in order. Not its
     * {@code reference} ({@code RESTRICTED-FINANCIAL}; for a {@code MANUAL} adjustment free text an operator typed, which
     * may name a person), nor its reason or actor ({@code RESTRICTED-PII}) - whether a later task serves the reference,
     * and under what control, is an owner item (`P14-TSK-007`'s review, I-1).
     *
     * @param namespace the posting key namespace the entry is judged under - a posting's or reversal's command key's namespace,
     *     an adjustment's proposal origin, as the rule conformance judge reads it ({@code P14-DOC-001}: the gate's "each line's
     *     entry, namespace" - a figure's line names its entry, and the entry its namespace and so its rule); empty for a scope
     *     of another shape, or where the reader does not say
     */
    record Entry(
            UUID id,
            String entryType,
            LocalDate postingDate,
            LocalDate valueDate,
            Optional<UUID> reverses,
            List<Line> lines,
            Optional<String> namespace) {

        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(entryType, "entryType");
            Objects.requireNonNull(postingDate, "postingDate");
            Objects.requireNonNull(valueDate, "valueDate");
            Objects.requireNonNull(reverses, "reverses");
            Objects.requireNonNull(namespace, "namespace");
            lines = List.copyOf(lines);
        }

        /** An entry whose namespace the reader does not say - the in-memory journals' and the oracle's. */
        public Entry(UUID id, String entryType, LocalDate postingDate, LocalDate valueDate, Optional<UUID> reverses, List<Line> lines) {
            this(id, entryType, postingDate, valueDate, reverses, lines, Optional.empty());
        }
    }

    private static void requireDirection(Side side) {
        if (side != Side.DEBIT && side != Side.CREDIT) {
            throw new IllegalArgumentException("a journal line is a debit or a credit");
        }
    }
}
