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
 * speak for. The page walks {@code (txid, id)} and is bounded, in its own statement, by {@code pg_snapshot_xmin(pg_current_snapshot())} on
 * that statement's snapshot (ledger {@code V034}): no clock enters it. {@code SELECT} only. The entry's {@code reference} is read, as the
 * join's key; never logged.
 */
public final class LedgerJournalCensusWalk implements JournalCensusWalk {

    private static final String POSTING_PREFIX = PostingService.IDEMPOTENCY_SCOPE + ":";
    private static final String REVERSAL_PREFIX = ReversalService.IDEMPOTENCY_SCOPE + ":";
    private static final String ADJUSTMENT_PREFIX = AdjustmentService.IDEMPOTENCY_SCOPE + ".approve:";

    /** After the position, finished transactions only: the bound is this statement's own snapshot, so nothing it omits can precede what it returns. */
    static final String PAGE = "SELECT entry.txid::text AS txid, entry.id, entry.entry_type, entry.idempotency_scope, entry.reference, proposal.origin"
            + " FROM ledger.journal_entry entry LEFT JOIN ledger.adjustment_proposal proposal ON proposal.journal_entry_id = entry.id"
            + " WHERE (entry.txid, entry.id) > (?::xid8, ?) AND entry.txid < pg_snapshot_xmin(pg_current_snapshot())"
            + " ORDER BY entry.txid, entry.id LIMIT ?";

    static final String LINES = "SELECT line.entry_id, line.seq, account.purpose, line.direction, line.currency, line.scale, line.amount_minor"
            + " FROM ledger.journal_line line JOIN ledger.ledger_account account ON account.id = line.ledger_account_id"
            + " WHERE line.entry_id = ANY (?) ORDER BY line.entry_id, line.seq";

    static final String OLDEST_AFTER = "SELECT id FROM ledger.journal_entry WHERE (txid, id) > (?::xid8, ?) ORDER BY txid, id LIMIT 1";

    private record Head(JournalCensusWalk.Position position, EntryType door, Optional<String> namespace, String suffix, String reference) {}

    @Override
    public List<CensusEntry> page(Connection connection, JournalCensusWalk.Position after, int limit) {
        Objects.requireNonNull(after, "after");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("a census page holds 1 to " + MAX_LIMIT + " entries");
        }
        Map<UUID, Head> heads = new LinkedHashMap<>();
        try (PreparedStatement read = connection.prepareStatement(PAGE)) {
            read.setString(1, Long.toString(after.txid()));
            read.setObject(2, after.id());
            read.setInt(3, limit);
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    UUID id = row.getObject("id", UUID.class);
                    EntryType door = EntryType.valueOf(row.getString("entry_type"));
                    String scope = row.getString("idempotency_scope");
                    heads.put(id, new Head(new JournalCensusWalk.Position(Long.parseLong(row.getString("txid")), id), door,
                            namespace(door, scope, row.getString("origin")), suffix(door, scope), row.getString("reference")));
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
            page.add(new CensusEntry(head.position(), head.door(), head.namespace(), head.suffix(), head.reference(),
                    lines.getOrDefault(head.position().id(), List.of())));
        }
        return page;
    }

    @Override
    public Optional<Instant> oldestAfter(Connection connection, JournalCensusWalk.Position position) {
        Objects.requireNonNull(position, "position");
        try (PreparedStatement read = connection.prepareStatement(OLDEST_AFTER)) {
            read.setString(1, Long.toString(position.txid()));
            read.setObject(2, position.id());
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
