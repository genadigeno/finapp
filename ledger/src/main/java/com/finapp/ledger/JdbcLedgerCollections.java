package com.finapp.ledger;

import com.finapp.platform.persistence.DatabaseFailure;
import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link LedgerCollections} over the ledger's own tables (`P17-TSK-022`): {@code SELECT} only, on the caller's snapshot, each
 * page one statement walking an index range from the cursor - a row comparison, so a continued page starts where the last one
 * ended rather than re-reading it. An entry's movement per currency is a second statement over the page's entries alone, served by
 * {@code journal_line}'s {@code (entry_id, seq)} key.
 *
 * <p><strong>Sums.</strong> {@code SUM(amount_minor)} over {@code bigint} is {@code numeric} - exact, read back whole and placed
 * at the lines' own scale - and the scale is a grouping key, so no addition crosses a scale ({@code INV-MON-03}, the
 * {@code TrialBalance} argument); an entry carries one scale per currency (V004's balance trigger).
 */
public final class JdbcLedgerCollections implements LedgerCollections {

    static final String ACCOUNT_COLUMNS = "SELECT id, purpose, owner_kind, owner_ref, currency, account_type, normal_balance,"
            + " status, created_at FROM ledger.ledger_account";

    static final String ENTRY_COLUMNS = "SELECT entry.id, entry.entry_type, entry.posting_date, entry.value_date,"
            + " entry.created_at, entry.reverses_entry_id FROM ledger.journal_entry entry";

    /** The page's entries' lines, per entry, currency and scale: the debits' sum and the line count. */
    static final String MOVEMENTS = "SELECT line.entry_id, line.currency, line.scale,"
            + " coalesce(sum(line.amount_minor) FILTER (WHERE line.direction = 'DEBIT'), 0) AS debits, count(*) AS lines"
            + " FROM ledger.journal_line line WHERE line.entry_id = ANY (?)"
            + " GROUP BY line.entry_id, line.currency, line.scale ORDER BY line.entry_id, line.currency";

    static final String PENDING_ADJUSTMENTS = "SELECT id, proposed_by, proposed_at, reason_code, posting_date"
            + " FROM ledger.adjustment_proposal WHERE status = 'PROPOSED' AND origin = 'MANUAL'";

    /** Which figures drift is read as booleans: the observed amounts never leave the database. */
    static final String PENDING_REBUILDS = "SELECT id, ledger_account_id, currency, proposed_by, proposed_at, observed_row,"
            + " observed_posted_minor IS DISTINCT FROM observed_derived_posted_minor AS posted_drifted,"
            + " observed_holds_minor IS DISTINCT FROM observed_derived_holds_minor AS holds_drifted,"
            + " observed_seq IS DISTINCT FROM observed_derived_seq AS applied_entries_drifted"
            + " FROM ledger.projection_rebuild WHERE status = 'PROPOSED'";

    @Override
    public List<AccountRow> accounts(Connection unitOfWork, AccountFilter filter, Optional<AccountCursor> after, int limit) {
        requirePage(limit);
        StringBuilder sql = new StringBuilder(ACCOUNT_COLUMNS).append(" WHERE TRUE");
        List<Object> arguments = new ArrayList<>();
        filter.purpose().ifPresent(purpose -> {
            sql.append(" AND purpose = ?");
            arguments.add(purpose.name());
        });
        filter.currency().ifPresent(currency -> {
            // char(3) against char(3): a text parameter would compare as text, which the index does not serve.
            sql.append(" AND currency = ?::char(3)");
            arguments.add(currency);
        });
        after.ifPresent(cursor -> {
            sql.append(" AND (purpose, currency, id) > (?, ?::char(3), ?)");
            arguments.add(cursor.purpose().name());
            arguments.add(cursor.currency());
            arguments.add(cursor.accountId());
        });
        sql.append(" ORDER BY purpose, currency, id LIMIT ?");
        arguments.add(limit);
        try (PreparedStatement read = prepared(unitOfWork, sql.toString(), arguments); ResultSet rows = read.executeQuery()) {
            List<AccountRow> page = new ArrayList<>();
            while (rows.next()) {
                page.add(new AccountRow(
                        rows.getObject("id", UUID.class),
                        AccountPurpose.valueOf(rows.getString("purpose")),
                        OwnerKind.valueOf(rows.getString("owner_kind")),
                        Optional.ofNullable(rows.getObject("owner_ref", UUID.class)),
                        rows.getString("currency").stripTrailing(),
                        AccountType.valueOf(rows.getString("account_type")),
                        NormalBalance.valueOf(rows.getString("normal_balance")),
                        LedgerAccountStatus.valueOf(rows.getString("status")),
                        rows.getTimestamp("created_at").toInstant()));
            }
            return page;
        } catch (SQLException failure) {
            throw new LedgerStorageException(DatabaseFailure.describe("reading the account register", failure));
        }
    }

    @Override
    public List<AccountLine> accountLines(
            Connection unitOfWork, LedgerAccountId account, Optional<LineCursor> after, int limit) {
        requirePage(limit);
        StringBuilder sql = new StringBuilder("SELECT line.entry_id, line.seq, line.direction, line.amount_minor, line.currency,"
                + " line.scale, entry.entry_type, entry.posting_date, entry.value_date, entry.created_at"
                + " FROM ledger.journal_line line JOIN ledger.journal_entry entry ON entry.id = line.entry_id"
                + " WHERE line.ledger_account_id = ?");
        List<Object> arguments = new ArrayList<>(List.of(account.value()));
        after.ifPresent(cursor -> {
            sql.append(" AND (line.entry_id, line.seq) < (?, ?)");
            arguments.add(cursor.entryId());
            arguments.add(cursor.seq());
        });
        sql.append(" ORDER BY line.entry_id DESC, line.seq DESC LIMIT ?");
        arguments.add(limit);
        List<AccountLine> page = new ArrayList<>();
        try (PreparedStatement read = prepared(unitOfWork, sql.toString(), arguments); ResultSet rows = read.executeQuery()) {
            while (rows.next()) {
                page.add(new AccountLine(
                        rows.getObject("entry_id", UUID.class),
                        rows.getInt("seq"),
                        JournalEntryType.valueOf(rows.getString("entry_type")),
                        rows.getDate("posting_date").toLocalDate(),
                        rows.getDate("value_date").toLocalDate(),
                        rows.getTimestamp("created_at").toInstant(),
                        rows.getString("direction"),
                        rows.getString("currency").stripTrailing(),
                        BigDecimal.valueOf(rows.getLong("amount_minor"), rows.getInt("scale"))));
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(DatabaseFailure.describe("reading an account's lines", failure));
        }
        return page;
    }

    @Override
    public List<EntryHead> entries(Connection unitOfWork, EntryFilter filter, Optional<EntryCursor> after, int limit) {
        requirePage(limit);
        StringBuilder sql = new StringBuilder(ENTRY_COLUMNS).append(" WHERE entry.posting_date >= ? AND entry.posting_date <= ?");
        List<Object> arguments = new ArrayList<>(List.of(Date.valueOf(filter.from()), Date.valueOf(filter.to())));
        filter.reference().ifPresent(reference -> {
            sql.append(" AND entry.reference = ?");
            arguments.add(reference);
        });
        filter.account().ifPresent(account -> {
            sql.append(" AND EXISTS (SELECT 1 FROM ledger.journal_line line WHERE line.entry_id = entry.id"
                    + " AND line.ledger_account_id = ?)");
            arguments.add(account.value());
        });
        after.ifPresent(cursor -> {
            sql.append(" AND (entry.posting_date, entry.id) < (?, ?)");
            arguments.add(Date.valueOf(cursor.postingDate()));
            arguments.add(cursor.entryId());
        });
        sql.append(" ORDER BY entry.posting_date DESC, entry.id DESC LIMIT ?");
        arguments.add(limit);
        record Head(UUID id, JournalEntryType type, java.time.LocalDate posting, java.time.LocalDate value,
                java.time.Instant postedAt, Optional<UUID> reverses) {}
        List<Head> heads = new ArrayList<>();
        try (PreparedStatement read = prepared(unitOfWork, sql.toString(), arguments); ResultSet rows = read.executeQuery()) {
            while (rows.next()) {
                heads.add(new Head(
                        rows.getObject("id", UUID.class),
                        JournalEntryType.valueOf(rows.getString("entry_type")),
                        rows.getDate("posting_date").toLocalDate(),
                        rows.getDate("value_date").toLocalDate(),
                        rows.getTimestamp("created_at").toInstant(),
                        Optional.ofNullable(rows.getObject("reverses_entry_id", UUID.class))));
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(DatabaseFailure.describe("searching the journal", failure));
        }
        if (heads.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<EntryAmount>> amounts = new LinkedHashMap<>();
        Map<UUID, Integer> lines = new LinkedHashMap<>();
        try (PreparedStatement read = unitOfWork.prepareStatement(MOVEMENTS)) {
            Array ids = unitOfWork.createArrayOf("uuid", heads.stream().map(Head::id).toArray());
            read.setArray(1, ids);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    UUID entry = rows.getObject("entry_id", UUID.class);
                    BigDecimal minor = rows.getBigDecimal("debits");
                    amounts.computeIfAbsent(entry, any -> new ArrayList<>()).add(new EntryAmount(
                            rows.getString("currency").stripTrailing(),
                            new BigDecimal(minor.toBigIntegerExact(), rows.getInt("scale"))));
                    lines.merge(entry, rows.getInt("lines"), Integer::sum);
                }
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(DatabaseFailure.describe("reading the searched entries' lines", failure));
        }
        return heads.stream().map(head -> new EntryHead(head.id(), head.type(), head.posting(), head.value(), head.postedAt(),
                head.reverses(), lines.getOrDefault(head.id(), 0), amounts.getOrDefault(head.id(), List.of()))).toList();
    }

    @Override
    public List<PendingAdjustment> pendingAdjustments(Connection unitOfWork, Optional<PendingCursor> after, int limit) {
        requirePage(limit);
        try (PreparedStatement read = pending(unitOfWork, PENDING_ADJUSTMENTS, after, limit);
                ResultSet rows = read.executeQuery()) {
            List<PendingAdjustment> page = new ArrayList<>();
            while (rows.next()) {
                page.add(new PendingAdjustment(
                        AdjustmentProposalId.of(rows.getObject("id", UUID.class)),
                        rows.getString("proposed_by"),
                        rows.getTimestamp("proposed_at").toInstant(),
                        AdjustmentReasonCode.valueOf(rows.getString("reason_code")),
                        rows.getDate("posting_date").toLocalDate()));
            }
            return page;
        } catch (SQLException failure) {
            throw new LedgerStorageException(DatabaseFailure.describe("reading the pending adjustments", failure));
        }
    }

    @Override
    public List<PendingRebuild> pendingRebuilds(Connection unitOfWork, Optional<PendingCursor> after, int limit) {
        requirePage(limit);
        try (PreparedStatement read = pending(unitOfWork, PENDING_REBUILDS, after, limit); ResultSet rows = read.executeQuery()) {
            List<PendingRebuild> page = new ArrayList<>();
            while (rows.next()) {
                page.add(new PendingRebuild(
                        ProjectionRebuildId.of(rows.getObject("id", UUID.class)),
                        LedgerAccountId.of(rows.getObject("ledger_account_id", UUID.class)),
                        rows.getString("currency").stripTrailing(),
                        rows.getString("proposed_by"),
                        rows.getTimestamp("proposed_at").toInstant(),
                        rows.getBoolean("observed_row"),
                        rows.getBoolean("posted_drifted"),
                        rows.getBoolean("holds_drifted"),
                        rows.getBoolean("applied_entries_drifted")));
            }
            return page;
        } catch (SQLException failure) {
            throw new LedgerStorageException(DatabaseFailure.describe("reading the pending projection rebuilds", failure));
        }
    }

    /** A queue's page: its {@code PROPOSED} rows forwards from the cursor, oldest first, the id breaking the tie. */
    private static PreparedStatement pending(Connection unitOfWork, String select, Optional<PendingCursor> after, int limit)
            throws SQLException {
        List<Object> arguments = new ArrayList<>();
        String sql = select + (after.isPresent() ? " AND (proposed_at, id) > (?, ?)" : "") + " ORDER BY proposed_at, id LIMIT ?";
        after.ifPresent(cursor -> {
            arguments.add(Timestamp.from(cursor.proposedAt()));
            arguments.add(cursor.id());
        });
        arguments.add(limit);
        return prepared(unitOfWork, sql, arguments);
    }

    private static PreparedStatement prepared(Connection unitOfWork, String sql, List<Object> arguments) throws SQLException {
        PreparedStatement statement = unitOfWork.prepareStatement(sql);
        try {
            for (int i = 0; i < arguments.size(); i++) {
                statement.setObject(i + 1, arguments.get(i));
            }
            return statement;
        } catch (SQLException failure) {
            statement.close();
            throw failure;
        }
    }

    private static void requirePage(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("a page holds at least one row, not " + limit);
        }
    }
}
