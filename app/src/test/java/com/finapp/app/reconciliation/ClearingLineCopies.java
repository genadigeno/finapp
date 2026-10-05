package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.PostingService;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.KeyKind;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The expectation-opener register's proof, shared by every suite that names a completion's
 * opening (`P8-TSK-005`, ADR-0067 §9): an expectation IS its clearing journal line's copy, and
 * this helper checks that against the LEDGER — never against the applier's inputs, which is
 * what the expectation would share a defect with.
 *
 * <p>{@link #assertOpensItsClearingLinesCopy} is the per-completion proof
 * {@code ExpectationOpenerRegisterTest} requires every "opens" row's test to call: exactly one
 * expectation for (kind, operation); its entry found by the posting key it names (the ledger's
 * own {@code idempotency_scope}), exactly one line of that entry on its account, amount, currency
 * and scale equal, the direction the ledger's sign ({@code DEBIT → INBOUND}), the account's
 * purpose the expectation's position, the posting date the entry's, the deciding rule set the
 * source's ACTIVE version and {@code expected_by} its seeded lag after the entry's date.
 *
 * <p>{@link #assertEveryClearingLineIsCopied} is the storms' form: over a scope of entries,
 * EVERY line on a reconciled clearing position has exactly one copy, and no expectation names a
 * scoped entry except as such a copy — a completion that opened nothing, opened twice, opened
 * for the wrong line or opened where nothing settles all fail it.
 */
public final class ClearingLineCopies {

    /**
     * The positions whose lines an expectation must copy (ADR-0067 §9's clearing three, and the FX
     * provider's counterparty clearing a cover's legs copy - `P9-TSK-012`).
     */
    public static final List<String> RECONCILED_CLEARINGS =
            List.of("SETTLEMENT_CLEARING", "INSTANT_CLEARING", "PAYOUT_CLEARING", "FX_PROVIDER_CLEARING");

    private static final String CLEARINGS_SQL = "('SETTLEMENT_CLEARING', 'INSTANT_CLEARING',"
            + " 'PAYOUT_CLEARING', 'FX_PROVIDER_CLEARING')";

    private ClearingLineCopies() {}

    /** What one proven expectation is, for the caller's own key and cycle assertions. */
    public record Opened(
            UUID id,
            UUID sourceId,
            UUID journalEntryId,
            ExpectationDirection direction,
            long amountMinor,
            Optional<String> settlementCycle) {}

    /**
     * Proves the ONE expectation of {@code kind} for {@code operationRef} is the copy of the
     * clearing line posted under {@code postingKey}, read back from the ledger.
     */
    public static Opened assertOpensItsClearingLinesCopy(
            ExpectationKind kind,
            String operationRef,
            String postingKey,
            ExpectationDirection direction)
            throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            ExpectationRow row = theOne(app, kind, operationRef);
            assertThat(row.postingKey())
                    .as("the %s expectation names the posting key its operation posted under",
                            kind)
                    .isEqualTo(postingKey);
            assertThat(row.status()).as("born OPEN").isEqualTo("OPEN");

            UUID entry;
            LocalDate entryDate;
            LocalDate entryValueDate;
            try (PreparedStatement read =
                    app.prepareStatement(
                            "SELECT id, posting_date, value_date FROM ledger.journal_entry"
                                    + " WHERE idempotency_scope = ?")) {
                read.setString(1, PostingService.IDEMPOTENCY_SCOPE + ":" + postingKey);
                try (ResultSet rows = read.executeQuery()) {
                    assertThat(rows.next()).as("the entry posted under %s exists", postingKey)
                            .isTrue();
                    entry = rows.getObject("id", UUID.class);
                    entryDate = rows.getObject("posting_date", LocalDate.class);
                    entryValueDate = rows.getObject("value_date", LocalDate.class);
                    assertThat(rows.next()).as("one entry per posting key").isFalse();
                }
            }
            assertThat(row.journalEntryId())
                    .as("the %s expectation names ITS entry", kind)
                    .isEqualTo(entry);
            assertThat(row.postingDate())
                    .as("the posting date copied from the entry, never re-read from a clock")
                    .isEqualTo(entryDate);

            try (PreparedStatement read =
                    app.prepareStatement(
                            "SELECT l.direction, l.amount_minor, l.currency, l.scale, a.purpose"
                                    + " FROM ledger.journal_line l"
                                    + " JOIN ledger.ledger_account a"
                                    + "   ON a.id = l.ledger_account_id"
                                    + " WHERE l.entry_id = ? AND l.ledger_account_id = ?")) {
                read.setObject(1, entry);
                read.setObject(2, row.ledgerAccountId());
                try (ResultSet line = read.executeQuery()) {
                    assertThat(line.next())
                            .as("the %s expectation's account carries a line of its entry", kind)
                            .isTrue();
                    String ledgerDirection = line.getString("direction");
                    assertThat(row.amountMinor())
                            .as("the %s expectation's amount IS its clearing line's", kind)
                            .isEqualTo(line.getLong("amount_minor"));
                    assertThat(row.currency()).isEqualTo(line.getString("currency"));
                    assertThat(row.scale()).isEqualTo(line.getInt("scale"));
                    assertThat(row.positionPurpose())
                            .as("the expectation's position is the posted account's purpose")
                            .isEqualTo(line.getString("purpose"))
                            .isIn(RECONCILED_CLEARINGS);
                    assertThat(row.direction())
                            .as("the ledger's own sign: a DEBIT on the position is INBOUND")
                            .isEqualTo("DEBIT".equals(ledgerDirection)
                                    ? ExpectationDirection.INBOUND.name()
                                    : ExpectationDirection.OUTBOUND.name())
                            .isEqualTo(direction.name());
                    assertThat(line.next())
                            .as("exactly one line of the entry on the clearing account")
                            .isFalse();
                }
            }

            try (PreparedStatement read =
                    app.prepareStatement(
                            "SELECT s.id, l.lag_days FROM reconciliation.rule_set s"
                                    + " JOIN reconciliation.rule_set_lag l"
                                    + "   ON l.rule_set_id = s.id AND l.expectation_kind = ?"
                                    + " WHERE s.source_id = ? AND s.status = 'ACTIVE'")) {
                read.setString(1, kind.name());
                read.setObject(2, row.sourceId());
                try (ResultSet active = read.executeQuery()) {
                    assertThat(active.next())
                            .as("the source's ACTIVE rule set dates %s", kind)
                            .isTrue();
                    assertThat(row.ruleSetId())
                            .as("the deciding version pinned on the row (INV-HIST-04)")
                            .isEqualTo(active.getObject(1, UUID.class));
                    assertThat(row.expectedBy())
                            .as("expected_by = the entry's date + the seeded %s lag", kind)
                            .isEqualTo(kind == ExpectationKind.FX_SELL_LEG || kind == ExpectationKind.FX_BUY_LEG
                                    // A cover leg is expected on the provider's confirmed value date (P9-TSK-013).
                                    ? entryValueDate
                                    : entryDate.plusDays(active.getInt(2)));
                }
            }
            assertThat(count(app,
                            "SELECT count(*) FROM reconciliation.expectation_event"
                                    + " WHERE expectation_id = ? AND event_type = 'OPENED'",
                            row.id()))
                    .as("one OPENED event")
                    .isEqualTo(1);
            return new Opened(
                    row.id(),
                    row.sourceId(),
                    row.journalEntryId(),
                    ExpectationDirection.valueOf(row.direction()),
                    row.amountMinor(),
                    Optional.ofNullable(row.settlementCycle()));
        }
    }

    /** The key {@code kind}/{@code value} is registered on {@code opened}'s source, to it. */
    public static void assertKeyed(Opened opened, KeyKind kind, String value) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement read =
                        app.prepareStatement(
                                "SELECT expectation_id FROM reconciliation.expectation_key"
                                        + " WHERE source_id = ? AND key_kind = ?"
                                        + " AND key_value = ?")) {
            read.setObject(1, opened.sourceId());
            read.setString(2, kind.name());
            read.setString(3, value);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).as("key %s registered on the source", kind).isTrue();
                assertThat(row.getObject(1, UUID.class))
                        .as("key %s resolves to its expectation", kind)
                        .isEqualTo(opened.id());
            }
        }
    }

    /** How many expectations of {@code kind} name {@code operationRef}. */
    public static long expectationsOf(ExpectationKind kind, String operationRef)
            throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return count(app,
                    "SELECT count(*) FROM reconciliation.expectation"
                            + " WHERE kind = ? AND operation_ref = ?",
                    kind.name(), operationRef);
        }
    }

    /** The entry posted under {@code postingKey} exists, and no expectation names it. */
    public static void assertOpensNothing(String postingKey) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            assertThat(count(app,
                            "SELECT count(*) FROM ledger.journal_entry"
                                    + " WHERE idempotency_scope = ?",
                            PostingService.IDEMPOTENCY_SCOPE + ":" + postingKey))
                    .as("the posting %s exists - absence of an expectation is not vacuous",
                            postingKey)
                    .isEqualTo(1);
            assertThat(count(app,
                            "SELECT count(*) FROM reconciliation.expectation x"
                                    + " JOIN ledger.journal_entry e ON e.id = x.journal_entry_id"
                                    + " WHERE e.idempotency_scope = ?",
                            PostingService.IDEMPOTENCY_SCOPE + ":" + postingKey))
                    .as("%s touches no reconciled position and opens nothing", postingKey)
                    .isZero();
        }
    }

    /**
     * Over the entries {@code scopedEntryIds} selects (a SQL query yielding entry ids, with
     * {@code arguments} bound in order): every line on a reconciled clearing position has
     * EXACTLY ONE expectation copying it — its entry, account, amount, currency, scale, purpose,
     * the ledger's sign and its entry's posting key — and no expectation names a scoped entry
     * except as such a copy.
     *
     * @return the copies proven, per posting-key prefix — so the caller can demand each
     *     completion it drove was really in scope (vacuity is not a pass)
     */
    public static Map<String, Long> assertEveryClearingLineIsCopied(
            Connection reader, String description, String scopedEntryIds, Object... arguments)
            throws SQLException {
        List<String> uncopied = new ArrayList<>();
        Map<String, Long> proven = new TreeMap<>();
        try (PreparedStatement read =
                reader.prepareStatement(
                        "WITH scoped AS (" + scopedEntryIds + ")"
                                + " SELECT e.idempotency_scope, a.purpose, l.direction,"
                                + "  (SELECT count(*) FROM reconciliation.expectation x"
                                + "    WHERE x.journal_entry_id = l.entry_id"
                                + "      AND x.ledger_account_id = l.ledger_account_id"
                                + "      AND x.amount_minor = l.amount_minor"
                                + "      AND x.currency = l.currency AND x.scale = l.scale"
                                + "      AND x.position_purpose = a.purpose"
                                + "      AND x.direction = CASE l.direction"
                                + "          WHEN 'DEBIT' THEN 'INBOUND' ELSE 'OUTBOUND' END"
                                + "      AND e.idempotency_scope = '"
                                + PostingService.IDEMPOTENCY_SCOPE + ":' || x.posting_key)"
                                + " FROM ledger.journal_line l"
                                + " JOIN ledger.journal_entry e ON e.id = l.entry_id"
                                + " JOIN ledger.ledger_account a ON a.id = l.ledger_account_id"
                                + " WHERE l.entry_id IN (SELECT * FROM scoped)"
                                + "   AND a.purpose IN " + CLEARINGS_SQL)) {
            bind(read, arguments);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    String key = rows.getString(1)
                            .substring(PostingService.IDEMPOTENCY_SCOPE.length() + 1);
                    long copies = rows.getLong(4);
                    if (copies != 1) {
                        uncopied.add(key + " " + rows.getString(3) + " " + rows.getString(2)
                                + ": " + copies + " copies");
                    } else {
                        proven.merge(key.substring(0, key.indexOf(':') + 1), 1L, Long::sum);
                    }
                }
            }
        }
        List<String> strays = new ArrayList<>();
        try (PreparedStatement read =
                reader.prepareStatement(
                        "WITH scoped AS (" + scopedEntryIds + ")"
                                + " SELECT x.kind || ' ' || x.operation_ref"
                                + " FROM reconciliation.expectation x"
                                + " WHERE x.journal_entry_id IN (SELECT * FROM scoped)"
                                + "   AND NOT EXISTS (SELECT 1 FROM ledger.journal_line l"
                                + "     JOIN ledger.ledger_account a"
                                + "       ON a.id = l.ledger_account_id"
                                + "    WHERE l.entry_id = x.journal_entry_id"
                                + "      AND l.ledger_account_id = x.ledger_account_id"
                                + "      AND a.purpose IN " + CLEARINGS_SQL + ")")) {
            bind(read, arguments);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    strays.add(rows.getString(1));
                }
            }
        }
        assertThat(uncopied)
                .as("%s: every clearing line has EXACTLY ONE expectation copying it (INV-SET-02,"
                        + " INV-REC-06's internal side)", description)
                .isEmpty();
        assertThat(strays)
                .as("%s: no expectation names an entry except as a clearing line's copy",
                        description)
                .isEmpty();
        return proven;
    }

    // -----------------------------------------------------------------

    private record ExpectationRow(
            UUID id,
            UUID sourceId,
            String positionPurpose,
            UUID ledgerAccountId,
            String direction,
            long amountMinor,
            String currency,
            int scale,
            UUID journalEntryId,
            LocalDate postingDate,
            String settlementCycle,
            LocalDate expectedBy,
            UUID ruleSetId,
            String postingKey,
            String status) {}

    private static ExpectationRow theOne(
            Connection app, ExpectationKind kind, String operationRef) throws SQLException {
        try (PreparedStatement read =
                app.prepareStatement(
                        "SELECT id, source_id, position_purpose, ledger_account_id, direction,"
                                + " amount_minor, currency, scale, journal_entry_id, posting_date,"
                                + " settlement_cycle, expected_by, rule_set_id, posting_key, status"
                                + " FROM reconciliation.expectation"
                                + " WHERE kind = ? AND operation_ref = ?")) {
            read.setString(1, kind.name());
            read.setString(2, operationRef);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next())
                        .as("one %s expectation for operation %s", kind, operationRef)
                        .isTrue();
                ExpectationRow result =
                        new ExpectationRow(
                                row.getObject("id", UUID.class),
                                row.getObject("source_id", UUID.class),
                                row.getString("position_purpose"),
                                row.getObject("ledger_account_id", UUID.class),
                                row.getString("direction"),
                                row.getLong("amount_minor"),
                                row.getString("currency"),
                                row.getInt("scale"),
                                row.getObject("journal_entry_id", UUID.class),
                                row.getObject("posting_date", LocalDate.class),
                                row.getString("settlement_cycle"),
                                row.getObject("expected_by", LocalDate.class),
                                row.getObject("rule_set_id", UUID.class),
                                row.getString("posting_key"),
                                row.getString("status"));
                assertThat(row.next()).as("and only one").isFalse();
                return result;
            }
        }
    }

    private static long count(Connection app, String sql, Object... arguments)
            throws SQLException {
        try (PreparedStatement read = app.prepareStatement(sql)) {
            bind(read, arguments);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static void bind(PreparedStatement statement, Object... arguments)
            throws SQLException {
        for (int i = 0; i < arguments.length; i++) {
            Object argument = arguments[i];
            if (argument instanceof UUID[] ids) {
                statement.setArray(i + 1, statement.getConnection().createArrayOf("uuid", ids));
            } else if (argument instanceof String[] texts) {
                statement.setArray(i + 1, statement.getConnection().createArrayOf("text", texts));
            } else {
                statement.setObject(i + 1, argument);
            }
        }
    }
}
