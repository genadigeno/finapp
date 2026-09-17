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

/**
 * Plain-JDBC storage for the journal (ADR-0033).
 *
 * <p><strong>Inserts only</strong>, matching the grants: the application role holds no
 * {@code UPDATE} and no {@code DELETE} here, and this class contains no statement the role
 * could not run. Balance is the deferred constraint triggers' to enforce at commit — this
 * store never re-checks what the domain already validated, because a third copy of the rule
 * is a third thing to drift.
 */
public final class JdbcJournalEntryStore implements JournalEntryStore<Connection> {

    private static final String ENTRY_TABLE = "ledger.journal_entry";
    private static final String LINE_TABLE = "ledger.journal_line";

    private final IdGenerator ids;

    /** Line identifiers are minted per line at append; the entry's is the aggregate's own. */
    public JdbcJournalEntryStore(IdGenerator ids) {
        this.ids = Objects.requireNonNull(ids, "ids must not be null");
    }

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
                    attribution =
                            new PostingAttribution(
                                    JournalEntryType.valueOf(row.getString("entry_type")),
                                    row.getString("reference"),
                                    Optional.ofNullable(row.getString("reason")),
                                    Optional.ofNullable(
                                                    row.getObject(
                                                            "reverses_entry_id", UUID.class))
                                            .map(JournalEntryId::of),
                                    row.getString("actor_id"),
                                    Correlation.startingWith(
                                                    com.finapp.sharedkernel.correlation
                                                            .CorrelationId.of(
                                                                    row.getString(
                                                                            "correlation_id")))
                                            .causing(
                                                    CausationId.of(
                                                            row.getString("causation_id"))),
                                    row.getString("idempotency_scope"));
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
                        lines.add(
                                new JournalLine(
                                        LedgerAccountId.of(
                                                row.getObject(
                                                        "ledger_account_id", UUID.class)),
                                        Direction.valueOf(row.getString("direction")),
                                        // ofPersisted, exactly: a stored amount reads back
                                        // as the amount it was, whatever the currency's
                                        // minor units say today (INV-MON-05).
                                        Money.ofPersisted(
                                                row.getLong("amount_minor"),
                                                com.finapp.sharedkernel.money.CurrencyCode.of(
                                                        row.getString("currency")
                                                                .stripTrailing()),
                                                row.getShort("scale"))));
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

}
