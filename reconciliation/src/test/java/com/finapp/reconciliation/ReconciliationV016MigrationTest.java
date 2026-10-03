package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.LocalDate;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * `V016` — the Phase 8 -> 9 transition's late-leg corrections at the schema rank: the
 * {@code match_reach} record a rematch examination writes (append-only for every writer, insert-
 * only for the application) and the item machine's one new edge, {@code UNMATCHED -> CHECKED} (a
 * REPROCESS run's re-check of a contained fee line), read against the migration's text and proven
 * live. Every live probe rolls its transaction back; each seeds a private source.
 */
@Tag("database")
@DisplayName("reconciliation V016: the late legs judge on rows (the Phase 8 -> 9 transition)")
class ReconciliationV016MigrationTest {

    private static final String V016 =
            "db/migration/reconciliation/V016__the_late_legs_judge_on_rows.sql";
    private static final String V013 = "db/migration/reconciliation/V013__the_repudiated_batch.sql";

    /** The PSP's seeded rule set v1 (V002): referenced by foreign key only. */
    private static final UUID RULE_SET = UUID.fromString("01a0e2bd-8300-7001-8000-000000000001");
    private static final LocalDate BUSINESS_DATE = LocalDate.parse("2026-09-29");
    private static final SecureRandom RANDOM = new SecureRandom();

    // ================================================================= the text

    @Test
    @DisplayName("the reach table with its two keys, its append-only trigger and an insert-only"
            + " grant; the item trigger is ItemStatus.sqlTransitionRule(), adding to V013's"
            + " machine exactly UNMATCHED -> CHECKED and removing none")
    void theTextStatesTheTableAndTheOneEdge() {
        String sql = normalized(migration(V016));
        assertThat(sql)
                .contains("CREATE TABLE reconciliation.match_reach ( decision_id UUID NOT NULL,"
                        + " expectation_id UUID NOT NULL,")
                .contains("CONSTRAINT match_reach_pk PRIMARY KEY (decision_id, expectation_id)")
                .contains("REFERENCES reconciliation.match_decision (id)")
                .contains("REFERENCES reconciliation.expectation (id)")
                .contains("BEFORE UPDATE OR DELETE ON reconciliation.match_reach")
                .contains("GRANT SELECT, INSERT ON reconciliation.match_reach TO finapp_app;")
                .contains(normalized("AND NOT (" + ItemStatus.sqlTransitionRule() + ") THEN"));

        Set<String> after = itemEdges(migration(V016));
        Set<String> before = itemEdges(ItemStatus.sqlTransitionRuleBeforeV016());
        assertThat(itemEdges(ItemStatus.sqlTransitionRule())).isEqualTo(after);
        assertThat(normalized(migration(V013)))
                .as("V013's restatement is the rule before V016's edge")
                .contains(normalized(
                        "AND NOT (" + ItemStatus.sqlTransitionRuleBeforeV016() + ") THEN"));
        assertThat(after).as("nothing removed").containsAll(before);
        Set<String> added = new TreeSet<>(after);
        added.removeAll(before);
        assertThat(added).containsExactly("UNMATCHED->CHECKED");
        assertThat(ItemStatus.addedByV016(ItemStatus.UNMATCHED, ItemStatus.CHECKED)).isTrue();
    }

    // ================================================================= the live rank

    @Test
    @DisplayName("a reach row is insert-only for the application (no UPDATE, no DELETE by"
            + " privilege) and append-only for the owner (refused by its trigger)")
    void aReachRowIsAppendOnly() throws SQLException {
        try (Connection app = application()) {
            Seeded seeded = seed(app, "UNMATCHED");
            execute(app, "INSERT INTO reconciliation.match_reach (decision_id, expectation_id)"
                    + " VALUES (?, ?)", seeded.decision(), seeded.expectation());
            assertThat(refused(app, "UPDATE reconciliation.match_reach SET expectation_id ="
                    + " expectation_id WHERE decision_id = ?", seeded.decision()).getSQLState())
                    .as("the application holds no UPDATE").isEqualTo("42501");
            assertThat(refused(app, "DELETE FROM reconciliation.match_reach WHERE"
                    + " decision_id = ?", seeded.decision()).getSQLState())
                    .as("the application holds no DELETE").isEqualTo("42501");
            app.rollback();
        }
        try (Connection owner = migrator()) {
            Seeded seeded = seed(owner, "UNMATCHED");
            execute(owner, "INSERT INTO reconciliation.match_reach (decision_id, expectation_id)"
                    + " VALUES (?, ?)", seeded.decision(), seeded.expectation());
            SQLException refusal = refused(owner, "DELETE FROM reconciliation.match_reach WHERE"
                    + " decision_id = ?", seeded.decision());
            assertThat(refusal.getSQLState()).as("the owner's DELETE refused by the trigger")
                    .isEqualTo("P0001");
            assertThat(refusal.getMessage()).contains("append-only");
            owner.rollback();
        }
    }

    @Test
    @DisplayName("the item machine admits UNMATCHED -> CHECKED, for any writer - and still refuses"
            + " UNMATCHED -> OFFSET and UNMATCHED -> RESOLVED")
    void anUnmatchedItemMayBeChecked() throws SQLException {
        try (Connection app = application()) {
            Seeded seeded = seed(app, "UNMATCHED");
            for (String refusedTarget : new String[] {"OFFSET", "RESOLVED"}) {
                assertThat(refused(app, "UPDATE reconciliation.external_item SET status = ?"
                        + " WHERE id = ?", refusedTarget, seeded.item()).getMessage())
                        .contains("not an external item edge: UNMATCHED -> " + refusedTarget);
            }
            execute(app, "UPDATE reconciliation.external_item SET status = 'CHECKED' WHERE"
                    + " id = ?", seeded.item());
            app.rollback();
        }
    }

    // ================================================================= seeding

    private record Seeded(UUID item, UUID decision, UUID expectation) {}

    /** A run, one item in {@code status}, its RUN decision and an expectation of its source. */
    private static Seeded seed(Connection connection, String status) throws SQLException {
        UUID source = UUID.randomUUID();
        UUID run = UUID.randomUUID();
        execute(connection, "INSERT INTO reconciliation.reconciliation_batch (id, source_id,"
                + " batch_id, kind, rule_set_id, business_date, source_sequence, item_count,"
                + " created_at, status_changed_at, correlation_id) VALUES (?, ?, ?, 'BATCH', ?,"
                + " ?, 1, 1, now(), now(), 'p8p9-v016')",
                run, source, UUID.randomUUID(), RULE_SET, BUSINESS_DATE);
        UUID item = UUID.randomUUID();
        execute(connection, "INSERT INTO reconciliation.external_item (id, run_id, source_id,"
                + " settlement_line_id, line_no, line_type, direction, amount_minor, currency,"
                + " scale, position_purpose, business_date, settlement_date, value_date,"
                + " canonical_fingerprint, created_at, status_changed_at, correlation_id)"
                + " VALUES (?, ?, ?, ?, 1, 'PROCESSING_FEE', 'INBOUND', 175, 'EUR', 2,"
                + " 'SETTLEMENT_CLEARING', ?, ?, ?, ?, now(), now(), 'p8p9-v016')",
                item, run, source, UUID.randomUUID(), BUSINESS_DATE, BUSINESS_DATE,
                BUSINESS_DATE, digest());
        execute(connection, "UPDATE reconciliation.external_item SET status = ? WHERE id = ?",
                status, item);
        UUID decision = UUID.randomUUID();
        execute(connection, "INSERT INTO reconciliation.match_decision (id, external_item_id,"
                + " run_id, origin, rule_set_id, outcome, decided_by, decided_by_type,"
                + " decided_at, decided_on, correlation_id, verdict, judged_status, judged_minor)"
                + " VALUES (?, ?, ?, 'RUN', ?, 'ERRORED', 'system', 'SYSTEM', now(), ?,"
                + " 'p8p9-v016', 'ERRORED', 'PENDING', 175)",
                decision, item, run, RULE_SET, BUSINESS_DATE);
        UUID expectation = UUID.randomUUID();
        execute(connection, "INSERT INTO reconciliation.expectation (id, kind, operation_ref,"
                + " posting_key, source_id, position_purpose, ledger_account_id, direction,"
                + " amount_minor, currency, scale, journal_entry_id, posting_date, expected_by,"
                + " rule_set_id, opened_at, status_changed_at, correlation_id) VALUES (?,"
                + " 'CARD_CAPTURE', ?, ?, ?, 'SETTLEMENT_CLEARING', ?, 'INBOUND', 100, 'EUR', 2,"
                + " ?, ?, ?, ?, now(), now(), 'p8p9-v016')",
                expectation, expectation.toString(), "p8p9-v016:" + expectation, source,
                UUID.randomUUID(), UUID.randomUUID(), BUSINESS_DATE, BUSINESS_DATE, RULE_SET);
        return new Seeded(item, decision, expectation);
    }

    private static byte[] digest() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    // ================================================================= plumbing

    private static Connection application() throws SQLException {
        Connection connection = DatabaseRoles.application();
        connection.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(connection);
        return connection;
    }

    private static Connection migrator() throws SQLException {
        Connection connection = DatabaseRoles.migrator();
        connection.setAutoCommit(false);
        return connection;
    }

    private static SQLException refused(Connection connection, String sql, Object... args)
            throws SQLException {
        Savepoint before = connection.setSavepoint();
        try {
            execute(connection, sql, args);
        } catch (SQLException refusal) {
            connection.rollback(before);
            return refusal;
        }
        connection.rollback(before);
        throw new AssertionError("admitted, but expected a refusal: " + sql);
    }

    private static void execute(Connection connection, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        }
    }

    /** Every {@code from->to} edge a restatement's condition names. */
    private static Set<String> itemEdges(String sql) {
        Set<String> edges = new TreeSet<>();
        Matcher clause =
                Pattern.compile("OLD\\.status = '([A-Z_]+)' AND NEW\\.status IN \\(([^)]*)\\)")
                        .matcher(sql);
        while (clause.find()) {
            for (String to : clause.group(2).split(",")) {
                edges.add(clause.group(1) + "->" + to.trim().replace("'", ""));
            }
        }
        return edges;
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ");
    }

    private static String migration(String resource) {
        try (InputStream in =
                ReconciliationV016MigrationTest.class.getClassLoader()
                        .getResourceAsStream(resource)) {
            assertThat(in).as("the migration is on the classpath: %s", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
