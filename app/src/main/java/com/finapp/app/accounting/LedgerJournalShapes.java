package com.finapp.app.accounting;

import com.finapp.accounting.AccountingStorageException;
import com.finapp.accounting.EntryType;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.JournalShapes;
import com.finapp.accounting.PostingRuleRegister;
import com.finapp.accounting.Side;
import com.finapp.ledger.AdjustmentService;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.ReversalService;
import com.finapp.platform.persistence.DatabaseFailure;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * accounting's {@link JournalShapes} over the ledger's own tables (`P14-TSK-010`; ADR-0117 decision 2;
 * {@code INV-ACC-12}, {@code INV-ACC-14}) - in {@code app}, beside {@link LedgerJournalReads}, the
 * {@code LedgerMonitoredLedger} precedent of reading the command key from {@code idempotency_scope}: the ledger's own
 * scope prefix ({@code ledger.post:}, {@code ledger.reverse:}) stripped to the command key, whose namespace classifies a
 * posting or a reversal; an adjustment's key is its proposal ({@code ledger.adjust.approve:<proposal>}), so its rule is
 * read from the proposal row's {@code origin} by the proposal's {@code journal_entry_id}.
 *
 * <p><strong>{@code SELECT} only, on the caller's snapshot</strong> - two statements a page: the page's entries by
 * {@code (posting_date, id)} keyset (ledger {@code V029}'s index), then their lines by entry id. Never the entry's
 * {@code reference}, {@code reason} or {@code actor_id}; a line carries its account's purpose only.
 */
public final class LedgerJournalShapes implements JournalShapes {

    private static final String POSTING_PREFIX = PostingService.IDEMPOTENCY_SCOPE + ":";
    private static final String REVERSAL_PREFIX = ReversalService.IDEMPOTENCY_SCOPE + ":";

    /** A page of entries: the scope for a posting or reversal, the proposal's origin for an adjustment. */
    static final String ENTRIES = "SELECT entry.id, entry.entry_type, entry.posting_date, entry.idempotency_scope,"
            + " proposal.origin FROM ledger.journal_entry entry"
            + " LEFT JOIN ledger.adjustment_proposal proposal ON proposal.journal_entry_id = entry.id"
            + " WHERE entry.posting_date >= ? AND entry.posting_date <= ?";

    /** After the cursor, the index's own row order. */
    static final String AFTER = " AND (entry.posting_date, entry.id) > (?, ?)";

    static final String PAGE = " ORDER BY entry.posting_date, entry.id LIMIT ?";

    /** The page's lines - each line's account's purpose, side and amount. */
    static final String LINES = "SELECT line.entry_id, line.seq, account.purpose, line.direction, line.currency,"
            + " line.scale, line.amount_minor FROM ledger.journal_line line"
            + " JOIN ledger.ledger_account account ON account.id = line.ledger_account_id"
            + " WHERE line.entry_id = ANY (?) ORDER BY line.entry_id, line.seq";

    @Override
    public List<EntryShape> entries(Connection snapshot, JournalReads.Range range, Optional<EntryCursor> after,
            int limit) {
        Objects.requireNonNull(range, "range");
        Objects.requireNonNull(after, "after");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("a conformance page holds 1 to " + MAX_LIMIT + " entries");
        }
        Map<UUID, Header> headers = new LinkedHashMap<>();
        try (PreparedStatement read = snapshot.prepareStatement(ENTRIES + (after.isPresent() ? AFTER : "") + PAGE)) {
            int at = 1;
            read.setDate(at++, Date.valueOf(range.from()));
            read.setDate(at++, Date.valueOf(range.to()));
            if (after.isPresent()) {
                read.setDate(at++, Date.valueOf(after.get().postingDate()));
                read.setObject(at++, after.get().entryId());
            }
            read.setInt(at, limit);
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    UUID id = row.getObject("id", UUID.class);
                    EntryType type = EntryType.valueOf(row.getString("entry_type"));
                    headers.put(id, new Header(id, type, row.getDate("posting_date").toLocalDate(),
                            namespace(type, row.getString("idempotency_scope"), row.getString("origin"))));
                }
            }
        } catch (SQLException failure) {
            throw failed("reading a conformance page of the journal", failure);
        }
        if (headers.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<ShapeLine>> lines = new LinkedHashMap<>();
        try (PreparedStatement read = snapshot.prepareStatement(LINES)) {
            read.setArray(1, snapshot.createArrayOf("uuid", headers.keySet().toArray()));
            try (ResultSet row = read.executeQuery()) {
                while (row.next()) {
                    lines.computeIfAbsent(row.getObject("entry_id", UUID.class), id -> new ArrayList<>()).add(
                            new ShapeLine(row.getInt("seq"), row.getString("purpose"), side(row.getString("direction")),
                                    row.getString("currency").stripTrailing(), row.getInt("scale"),
                                    java.math.BigInteger.valueOf(row.getLong("amount_minor"))));
                }
            }
        } catch (SQLException failure) {
            throw failed("reading a conformance page's lines", failure);
        }
        List<EntryShape> page = new ArrayList<>(headers.size());
        for (Header header : headers.values()) {
            page.add(new EntryShape(header.id(), header.type(), header.postingDate(), header.namespace(),
                    lines.getOrDefault(header.id(), List.of())));
        }
        return page;
    }

    /**
     * The namespace an entry is judged under: a posting's or reversal's command key's namespace, with the ledger's own
     * scope prefix stripped; an adjustment's proposal origin. Empty for a scope of another shape.
     */
    static Optional<String> namespace(EntryType type, String scope, String origin) {
        return switch (type) {
            case POSTING -> keyed(scope, POSTING_PREFIX);
            case REVERSAL -> keyed(scope, REVERSAL_PREFIX);
            case ADJUSTMENT -> scope != null && scope.startsWith(AdjustmentService.IDEMPOTENCY_SCOPE + ".approve:")
                    ? Optional.ofNullable(origin)
                    : Optional.empty();
        };
    }

    private static Optional<String> keyed(String scope, String prefix) {
        if (scope == null || !scope.startsWith(prefix) || scope.length() == prefix.length()) {
            return Optional.empty();
        }
        return Optional.of(PostingRuleRegister.namespaceOf(scope.substring(prefix.length())));
    }

    private static Side side(String direction) {
        return switch (direction) {
            case "DEBIT" -> Side.DEBIT;
            case "CREDIT" -> Side.CREDIT;
            default -> throw new IllegalStateException("a journal line is a debit or a credit");
        };
    }

    private record Header(UUID id, EntryType type, LocalDate postingDate, Optional<String> namespace) {}

    private static AccountingStorageException failed(String operation, SQLException failure) {
        return new AccountingStorageException(DatabaseFailure.describe(operation, failure));
    }
}
