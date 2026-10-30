package com.finapp.ledger;

import com.finapp.platform.persistence.DatabaseFailure;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Plain-JDBC storage for the journal (ADR-0033).
 *
 * <p><strong>Inserts only</strong>, matching the grants: the application role holds no
 * {@code UPDATE} and no {@code DELETE} here, and this class contains no statement the role
 * could not run. Balance is the deferred constraint triggers' to enforce at commit — this
 * store never re-checks what the domain already validated, because a third copy of the rule
 * is a third thing to drift.
 */
@RequiredArgsConstructor
public final class JdbcJournalEntryStore implements JournalEntryStore<Connection> {

    private static final String ENTRY_TABLE = "ledger.journal_entry";
    private static final String LINE_TABLE = "ledger.journal_line";

    /** Line identifiers are minted per line at append; the entry's is the aggregate's own. */
    @NonNull private final IdGenerator ids;

    @Override
    public void append(
            Connection unitOfWork, JournalEntry entry, PostingAttribution attribution) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(entry, "entry must not be null");
        Objects.requireNonNull(attribution, "attribution must not be null");
        try {
            try (PreparedStatement insert =
                    unitOfWork.prepareStatement(
                            "INSERT INTO " + ENTRY_TABLE
                                    + " (id, posting_date, value_date, entry_type, reference,"
                                    + " reason, reverses_entry_id, actor_id, correlation_id,"
                                    + " causation_id, idempotency_scope, created_at)"
                                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setObject(1, entry.id().value());
                insert.setObject(2, entry.postingDate());
                insert.setObject(3, entry.valueDate());
                insert.setString(4, attribution.entryType().name());
                insert.setString(5, attribution.reference());
                insert.setString(6, attribution.reason().orElse(null));
                insert.setObject(
                        7, attribution.reverses().map(JournalEntryId::value).orElse(null));
                insert.setString(8, attribution.actorId());
                insert.setString(9, attribution.correlation().correlationId().value());
                insert.setString(10, attribution.correlation().cause().orElseThrow().value());
                insert.setString(11, attribution.idempotencyScope());
                insert.setTimestamp(12, Timestamp.from(entry.createdAt()));
                insert.executeUpdate();
            }
            try (PreparedStatement insert =
                    unitOfWork.prepareStatement(
                            "INSERT INTO " + LINE_TABLE
                                    + " (id, entry_id, ledger_account_id, direction,"
                                    + " amount_minor, currency, scale, seq)"
                                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                int seq = 0;
                for (JournalLine line : entry.lines()) {
                    insert.setObject(1, ids.next());
                    insert.setObject(2, entry.id().value());
                    insert.setObject(3, line.account().value());
                    insert.setString(4, line.direction().name());
                    insert.setLong(5, line.amount().minorUnits());
                    insert.setString(6, line.amount().currency().code());
                    insert.setInt(7, line.amount().scale());
                    insert.setInt(8, seq++);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        } catch (SQLException failure) {
            // V028's posting cutoff (P14-TSK-004, INV-ACC-03, INV-ACC-08): the entry is dated on or before the cutoff,
            // refused at insert under namespace 14 shared. Its own SQLSTATE, matched as the state and never the prose,
            // so the posting, reversal and adjustment surfaces all answer ledger.PeriodClosed; the composed message
            // carries the entry and its date, never a row or an amount (INV-AUD-02).
            if (PostingCutoffs.PERIOD_CLOSED_SQLSTATE.equals(failure.getSQLState())) {
                throw new PeriodClosedException(
                        "entry " + entry.id() + " is dated " + entry.postingDate() + ", on or before the posting"
                                + " cutoff - its accounting period is locked (INV-ACC-03, P14-TSK-004)");
            }
            // V007's trigger refuses a line on a non-ACTIVE account with ERRCODE 23514 and a
            // stable marker; translated to a named domain refusal so a commanding flow can
            // treat "this account stopped accepting postings" as an outcome (P3-TSK-014). The
            // marker is matched rather than the prose, and the server text is not propagated -
            // the composed message carries the entry, never a row (the P1-TSK-008 discipline).
            if (failure.getMessage() != null
                    && failure.getMessage().contains("ledger_account_accepts_postings")) {
                throw new LedgerAccountNotPostableException(
                        "a line of entry " + entry.id() + " names an account that is no longer"
                                + " ACTIVE; the posting is refused (P3-TSK-014)");
            }
            // The line FK and V005's composite currency binding (23503): a line named an
            // account the chart does not have, or a currency foreign to it - translated so
            // a boundary can answer the caller's 422 rather than our 500 (P3-TSK-017). The
            // SQLState is matched, never prose; entry-level FKs (reverses_entry_id) carry
            // their own earlier validation, so a 23503 on append is the lines'.
            if ("23503".equals(failure.getSQLState())) {
                throw new UnknownPostingAccountException(
                        "a line of entry " + entry.id() + " names an unknown ledger account,"
                                + " or a currency foreign to it (INV-MON-02, P3-TSK-017)");
            }
            // V009's bound trigger (INV-REV-02) and reversal-of-reversal trigger, same
            // pattern: the marker is matched, never the prose, and the composed message
            // carries identifiers and never a row or an amount (INV-AUD-02).
            if (failure.getMessage() != null
                    && (failure.getMessage().contains("ledger_reversal_is_bounded")
                            || failure.getMessage().contains("ledger_reversal_of_reversal"))) {
                throw new OverReversalException(
                        "entry " + entry.id() + " is refused: it would over-reverse its"
                                + " original, mirror a pair the original does not have, or"
                                + " reverse a reversal (INV-REV-01/02, P3-TSK-016)");
            }
            throw new LedgerStorageException(
                    DatabaseFailure.describe("appending journal entry " + entry.id(), failure));
        }
    }

    @Override
    public List<JournalLine> reversalLinesOf(Connection unitOfWork, JournalEntryId original) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(original, "original must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        // The V009 partial index's own read: every reversal of one original.
                        "SELECT line.ledger_account_id, line.direction, line.amount_minor,"
                                + " line.currency, line.scale"
                                + " FROM " + LINE_TABLE + " line"
                                + " JOIN " + ENTRY_TABLE + " entry ON entry.id = line.entry_id"
                                + " WHERE entry.reverses_entry_id = ?")) {
            select.setObject(1, original.value());
            try (ResultSet row = select.executeQuery()) {
                List<JournalLine> lines = new ArrayList<>();
                while (row.next()) {
                    lines.add(
                            new JournalLine(
                                    LedgerAccountId.of(
                                            row.getObject("ledger_account_id", UUID.class)),
                                    Direction.valueOf(row.getString("direction")),
                                    Money.ofPersisted(
                                            row.getLong("amount_minor"),
                                            com.finapp.sharedkernel.money.CurrencyCode.of(
                                                    row.getString("currency").stripTrailing()),
                                            row.getShort("scale"))));
                }
                return List.copyOf(lines);
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe(
                            "reading the reversals of entry " + original, failure));
        }
    }

    @Override
    public Optional<JournalEntryId> findByIdempotencyScope(Connection unitOfWork, String scope) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(scope, "scope must not be null");
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT id FROM " + ENTRY_TABLE + " WHERE idempotency_scope = ?")) {
            select.setString(1, scope);
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                JournalEntryId found = JournalEntryId.of(row.getObject("id", UUID.class));
                if (row.next()) {
                    // The kernel guarantees one committed execution per scope-and-key; a
                    // second row is a defect, and picking one would hide it (INV-IDEM-01).
                    throw new LedgerStorageException(
                            "two journal entries share one idempotency scope: the kernel's"
                                    + " one-execution guarantee was bypassed");
                }
                return Optional.of(found);
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe("reading the entry of one posting key", failure));
        }
    }

    @Override
    public List<LineKey> lineKeysOn(
            Connection unitOfWork, java.util.Collection<LedgerAccountId> accounts) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(accounts, "accounts must not be null");
        if (accounts.isEmpty()) {
            return List.of();
        }
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT entry_id, ledger_account_id FROM " + LINE_TABLE
                                + " WHERE ledger_account_id = ANY (?)")) {
            select.setArray(
                    1,
                    unitOfWork.createArrayOf(
                            "uuid",
                            accounts.stream().map(LedgerAccountId::value).toArray()));
            try (ResultSet rows = select.executeQuery()) {
                List<LineKey> keys = new ArrayList<>();
                while (rows.next()) {
                    keys.add(
                            new LineKey(
                                    JournalEntryId.of(rows.getObject("entry_id", UUID.class)),
                                    LedgerAccountId.of(
                                            rows.getObject("ledger_account_id", UUID.class))));
                }
                return List.copyOf(keys);
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe("reading the line keys of the positions", failure));
        }
    }

    @Override
    public Optional<PostedEntry> findById(Connection unitOfWork, JournalEntryId entryId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(entryId, "entryId must not be null");
        try {
            LocalDate postingDate;
            LocalDate valueDate;
            java.time.Instant createdAt;
            PostingAttribution attribution;
            try (PreparedStatement select =
                    unitOfWork.prepareStatement(
                            "SELECT posting_date, value_date, entry_type, reference, reason,"
                                    + " reverses_entry_id, actor_id, correlation_id,"
                                    + " causation_id, idempotency_scope, created_at"
                                    + " FROM " + ENTRY_TABLE + " WHERE id = ?")) {
                select.setObject(1, entryId.value());
                try (ResultSet row = select.executeQuery()) {
                    if (!row.next()) {
                        return Optional.empty();
                    }
                    postingDate = row.getObject("posting_date", LocalDate.class);
                    valueDate = row.getObject("value_date", LocalDate.class);
                    createdAt = row.getTimestamp("created_at").toInstant();
                    attribution = attributionOf(row);
                }
            }
            List<JournalLine> lines = new ArrayList<>();
            try (PreparedStatement select =
                    unitOfWork.prepareStatement(
                            "SELECT ledger_account_id, direction, amount_minor, currency,"
                                    + " scale FROM " + LINE_TABLE
                                    + " WHERE entry_id = ? ORDER BY seq")) {
                select.setObject(1, entryId.value());
                try (ResultSet row = select.executeQuery()) {
                    while (row.next()) {
                        lines.add(lineOf(row));
                    }
                }
            }
            return Optional.of(
                    new PostedEntry(
                            JournalEntry.rehydrate(
                                    entryId, postingDate, valueDate, lines, createdAt),
                            attribution));
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe("reading journal entry " + entryId, failure));
        }
    }

    /**
     * The settle horizon's bound, built in SQL from {@code clock_timestamp()} — never an
     * instance's clock: the lowest UUIDv7 of the millisecond {@code settleHorizon} ago (the
     * generator's layout, ADR-0013: 48 bits of milliseconds, the version nibble, a zero counter,
     * the RFC variant, zero randomness), so every id minted at or after that millisecond sorts
     * at or after it.
     */
    private static final String HORIZON_UUID =
            // An uncorrelated scalar subquery: evaluated once as an initplan, so the bound is a constant and the
            // planner keeps the primary key's range scan.
            "(SELECT (substr(h.hex, 1, 8) || '-' || substr(h.hex, 9, 4) || '-7000-8000-000000000000')::uuid"
                    + " FROM (SELECT lpad(to_hex(floor(extract(epoch FROM (clock_timestamp()"
                    + " - ? * interval '1 millisecond')) * 1000)::bigint), 12, '0') AS hex) h)";

    @Override
    public List<PostedEntry> entriesAfter(
            Connection unitOfWork, UUID after, java.time.Duration settleHorizon, int limit) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(after, "after must not be null");
        Objects.requireNonNull(settleHorizon, "settleHorizon must not be null");
        if (settleHorizon.isNegative()) {
            throw new IllegalArgumentException("the settle horizon is not negative");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("a page holds at least one entry: " + limit);
        }
        try {
            // The entries first: the page's ids in bytewise order, which is time order.
            record Head(JournalEntryId id, LocalDate postingDate, LocalDate valueDate,
                    java.time.Instant createdAt, PostingAttribution attribution) {}
            List<Head> heads = new ArrayList<>();
            try (PreparedStatement select =
                    unitOfWork.prepareStatement(
                            "SELECT e.id, e.posting_date, e.value_date, e.entry_type, e.reference,"
                                    + " e.reason, e.reverses_entry_id, e.actor_id, e.correlation_id,"
                                    + " e.causation_id, e.idempotency_scope, e.created_at"
                                    + " FROM " + ENTRY_TABLE + " e"
                                    + " WHERE e.id > ? AND e.id < " + HORIZON_UUID
                                    + " ORDER BY e.id LIMIT ?")) {
                select.setObject(1, after);
                select.setLong(2, settleHorizon.toMillis());
                select.setInt(3, limit);
                try (ResultSet row = select.executeQuery()) {
                    while (row.next()) {
                        heads.add(
                                new Head(
                                        JournalEntryId.of(row.getObject("id", UUID.class)),
                                        row.getObject("posting_date", LocalDate.class),
                                        row.getObject("value_date", LocalDate.class),
                                        row.getTimestamp("created_at").toInstant(),
                                        attributionOf(row)));
                    }
                }
            }
            if (heads.isEmpty()) {
                return List.of();
            }
            // Then the page's lines in one statement, in each entry's own order.
            java.util.Map<UUID, List<JournalLine>> lines = new java.util.HashMap<>();
            try (PreparedStatement select =
                    unitOfWork.prepareStatement(
                            "SELECT entry_id, ledger_account_id, direction, amount_minor, currency,"
                                    + " scale FROM " + LINE_TABLE
                                    + " WHERE entry_id = ANY (?) ORDER BY entry_id, seq")) {
                select.setArray(
                        1,
                        unitOfWork.createArrayOf(
                                "uuid", heads.stream().map(head -> head.id().value()).toArray()));
                try (ResultSet row = select.executeQuery()) {
                    while (row.next()) {
                        lines.computeIfAbsent(
                                        row.getObject("entry_id", UUID.class),
                                        entry -> new ArrayList<>())
                                .add(lineOf(row));
                    }
                }
            }
            List<PostedEntry> page = new ArrayList<>(heads.size());
            for (Head head : heads) {
                page.add(
                        new PostedEntry(
                                JournalEntry.rehydrate(
                                        head.id(),
                                        head.postingDate(),
                                        head.valueDate(),
                                        lines.getOrDefault(head.id().value(), List.of()),
                                        head.createdAt()),
                                head.attribution()));
            }
            return page;
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe("walking the journal", failure));
        }
    }

    @Override
    public long countBeyond(Connection unitOfWork, UUID after, long cap) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(after, "after must not be null");
        if (cap < 1) {
            throw new IllegalArgumentException("a cap is positive: " + cap);
        }
        try (PreparedStatement select =
                unitOfWork.prepareStatement(
                        "SELECT count(*) FROM (SELECT 1 FROM " + ENTRY_TABLE
                                + " WHERE id > ? LIMIT ?) beyond")) {
            select.setObject(1, after);
            select.setLong(2, cap);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        } catch (SQLException failure) {
            throw new LedgerStorageException(
                    DatabaseFailure.describe("counting the journal beyond a position", failure));
        }
    }

    private static PostingAttribution attributionOf(ResultSet row) throws SQLException {
        return new PostingAttribution(
                JournalEntryType.valueOf(row.getString("entry_type")),
                row.getString("reference"),
                Optional.ofNullable(row.getString("reason")),
                Optional.ofNullable(row.getObject("reverses_entry_id", UUID.class))
                        .map(JournalEntryId::of),
                row.getString("actor_id"),
                Correlation.startingWith(
                                com.finapp.sharedkernel.correlation.CorrelationId.of(
                                        row.getString("correlation_id")))
                        .causing(CausationId.of(row.getString("causation_id"))),
                row.getString("idempotency_scope"));
    }

    private static JournalLine lineOf(ResultSet row) throws SQLException {
        return new JournalLine(
                LedgerAccountId.of(row.getObject("ledger_account_id", UUID.class)),
                Direction.valueOf(row.getString("direction")),
                // ofPersisted, exactly: a stored amount reads back as the amount it was,
                // whatever the currency's minor units say today (INV-MON-05).
                Money.ofPersisted(
                        row.getLong("amount_minor"),
                        com.finapp.sharedkernel.money.CurrencyCode.of(
                                row.getString("currency").stripTrailing()),
                        row.getShort("scale")));
    }
}
