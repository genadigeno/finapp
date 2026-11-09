package com.finapp.app.accounting;

import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.EntryType;
import com.finapp.accounting.JournalCensusWalk;
import com.finapp.accounting.JournalShapes;
import com.finapp.accounting.PostingRuleRegister;
import com.finapp.accounting.Side;
import com.finapp.ledger.AdjustmentService;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.ReversalService;
import com.finapp.platform.persistence.DatabaseFailure;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * accounting's {@link JournalCensusWalk} over the ledger's own tables (`P15-TSK-035`) - in {@code app}, the {@code LedgerJournalShapes}
 * precedent: {@code accounting} has no ledger edge, and the ledger's journal is the one thing the census reads that no module's port can
 * speak for. The walk's bound is built in SQL from the fence's instant - the lowest UUIDv7 of that millisecond, ADR-0013's layout - and
 * bound as a parameter, so an entry minted at the fence or after it never reaches the page. {@code SELECT} only. The entry's
 * {@code reference} is read, as the join's key; never logged.
 */
public final class LedgerJournalCensusWalk implements JournalCensusWalk {

    private static final String POSTING_PREFIX = PostingService.IDEMPOTENCY_SCOPE + ":";
    private static final String REVERSAL_PREFIX = ReversalService.IDEMPOTENCY_SCOPE + ":";
    private static final String ADJUSTMENT_PREFIX = AdjustmentService.IDEMPOTENCY_SCOPE + ".approve:";

    /** The lowest UUIDv7 of the bound's millisecond: every id minted at or after it sorts at or after this. */
    private static final String MINTED_BEFORE_BOUND = "(SELECT (substr(h.hex, 1, 8) || '-' || substr(h.hex, 9, 4) || '-7000-8000-000000000000')::uuid"
            + " FROM (SELECT lpad(to_hex(floor(extract(epoch FROM ?::timestamptz) * 1000)::bigint), 12, '0') AS hex) h)";

    static final String PAGE = "SELECT entry.id, entry.entry_type, entry.idempotency_scope, entry.reference, proposal.origin"
            + " FROM ledger.journal_entry entry LEFT JOIN ledger.adjustment_proposal proposal ON proposal.journal_entry_id = entry.id"
            + " WHERE entry.id > ? AND entry.id < " + MINTED_BEFORE_BOUND + " ORDER BY entry.id LIMIT ?";

    static final String LINES = "SELECT line.entry_id, line.seq, account.purpose, line.direction, line.currency, line.scale, line.amount_minor"
            + " FROM ledger.journal_line line JOIN ledger.ledger_account account ON account.id = line.ledger_account_id"
            + " WHERE line.entry_id = ANY (?) ORDER BY line.entry_id, line.seq";

    static final String OLDEST_AFTER = "SELECT id FROM ledger.journal_entry WHERE id > ? ORDER BY id LIMIT 1";

    private record Head(UUID id, EntryType door, Optional<String> namespace, String suffix, String reference) {}

    @Override
    public List<CensusEntry> page(Connection connection, UUID after, Instant mintedBefore, int limit) {
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(mintedBefore, "mintedBefore");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("a census page holds 1 to " + MAX_LIMIT + " entries");
        }
        Map<UUID, Head> heads = new LinkedHashMap<>();
        try (PreparedStatement read = connection.prepareStatement(PAGE)) {
            read.setObject(1, after);
            read.setObject(2, OffsetDateTime.ofInstant(mintedBefore, ZoneOffset.UTC));
            read.setInt(3, limit);
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    UUID id = row.getObject("id", UUID.class);
                    EntryType door = EntryType.valueOf(row.getString("entry_type"));
                    String scope = row.getString("idempotency_scope");
                    heads.put(id, new Head(id, door, namespace(door, scope, row.getString("origin")), suffix(door, scope),
                            row.getString("reference")));
                }
            }
        } catch (SQLException failure) {
            throw failed("reading a page of the journal for the census", failure);
        }
        if (heads.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<JournalShapes.ShapeLine>> lines = new LinkedHashMap<>();
        try (PreparedStatement read = connection.prepareStatement(LINES)) {
            read.setArray(1, connection.createArrayOf("uuid", heads.keySet().toArray()));
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    lines.computeIfAbsent(row.getObject("entry_id", UUID.class), key -> new ArrayList<>()).add(new JournalShapes.ShapeLine(
                            row.getInt("seq"), row.getString("purpose"), "DEBIT".equals(row.getString("direction")) ? Side.DEBIT : Side.CREDIT,
                            row.getString("currency").stripTrailing(), row.getInt("scale"), BigInteger.valueOf(row.getLong("amount_minor"))));
                }
            }
        } catch (SQLException failure) {
            throw failed("reading a census page's lines", failure);
        }
        List<CensusEntry> page = new ArrayList<>(heads.size());
        for (Head head : heads.values()) {
            page.add(new CensusEntry(head.id(), head.door(), head.namespace(), head.suffix(), head.reference(), lines.getOrDefault(head.id(), List.of())));
        }
        return page;
    }

    @Override
    public Optional<Instant> oldestAfter(Connection connection, UUID position) {
        try (PreparedStatement read = connection.prepareStatement(OLDEST_AFTER)) {
            read.setObject(1, Objects.requireNonNull(position, "position"));
            try (ResultSet row = read.executeQuery()) {
                return row.next() ? Optional.of(Instant.ofEpochMilli(row.getObject("id", UUID.class).getMostSignificantBits() >>> 16)) : Optional.empty();
            }
        } catch (SQLException failure) {
            throw failed("reading the census's oldest unjudged entry", failure);
        }
    }

    /** The namespace the census judges under: {@link PostingRuleRegister#namespaceOf}, or a proposal's origin; empty for a scope of another shape. */
    static Optional<String> namespace(EntryType door, String scope, String origin) {
        return switch (door) {
            case POSTING -> keyOf(scope, POSTING_PREFIX).map(PostingRuleRegister::namespaceOf);
            case REVERSAL -> keyOf(scope, REVERSAL_PREFIX).map(PostingRuleRegister::namespaceOf);
            case ADJUSTMENT -> scope != null && scope.startsWith(ADJUSTMENT_PREFIX) ? Optional.ofNullable(origin) : Optional.empty();
        };
    }

    /** The command key past its namespace; for an adjustment the proposal id; empty when none. */
    static String suffix(EntryType door, String scope) {
        if (door == EntryType.ADJUSTMENT) {
            return scope != null && scope.startsWith(ADJUSTMENT_PREFIX) ? scope.substring(ADJUSTMENT_PREFIX.length()) : "";
        }
        return keyOf(scope, door == EntryType.POSTING ? POSTING_PREFIX : REVERSAL_PREFIX)
                .map(key -> {
                    int colon = key.indexOf(':');
                    return colon < 0 ? "" : key.substring(colon + 1);
                })
                .orElse("");
    }

    private static Optional<String> keyOf(String scope, String prefix) {
        if (scope == null || !scope.startsWith(prefix) || scope.length() == prefix.length()) {
            return Optional.empty();
        }
        return Optional.of(scope.substring(prefix.length()));
    }

    private static AccountingStorageException failed(String what, SQLException failure) {
        return new AccountingStorageException(DatabaseFailure.describe(what, failure));
    }
}
