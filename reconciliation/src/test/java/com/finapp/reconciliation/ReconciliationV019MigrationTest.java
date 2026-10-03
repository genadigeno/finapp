package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.security.InstrumentShapes;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Reconciliation `V019` - the Phase 8 → 9 transition's correction of the audit's {@code SEC-03}
 * and {@code SEC-04} at the database rank ({@code INV-PAY-02}, {@code INV-RAIL-03}): the PL/pgSQL
 * twin of {@link InstrumentShapes} agrees with it over a corpus, and every column that stores
 * person-written prose - the case file's notes, evidence references and narratives, and every
 * reason column - refuses a card number written with spaces or dashes and an account identifier
 * in its printed groups, by name, with nothing stored, for a raw writer past every domain guard.
 *
 * <p>Each live probe runs in one rolled-back transaction, refusals behind savepoints; the class is
 * tagged {@code database} because the taxonomy places a class by its heaviest member.
 */
@Tag("database")
@DisplayName("reconciliation V019: person-written prose holds no instrument shape (SEC-03, SEC-04)")
class ReconciliationV019MigrationTest {

    private static final String V019 =
            "db/migration/reconciliation/V019__person_written_prose_holds_no_instrument_shape.sql";

    /** The PSP's seeded rule set v1 (V002): referenced by foreign key only. */
    private static final UUID RULE_SET = UUID.fromString("01a0e2bd-8300-7001-8000-000000000001");

    private static final LocalDate BUSINESS_DATE = LocalDate.parse("2026-09-29");
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The three needles every column must refuse: SEC-03's two card spellings and the print form. */
    static final List<String> NEEDLES =
            List.of(
                    "card 4111 1111 1111 1111 per the caller",
                    "card 4111-1111-1111-1111 per the caller",
                    "refund to GB82 WEST 1234 5698 7654 32 instead");

    private static final String CLEAN = "the counterparty confirmed by phone, ticket B-42";

    // ================================================================= the text

    @Test
    @DisplayName("the twin masks exactly the Java screen's platform identifiers, scans its runs and"
            + " tokens, re-adds the six case-file screens over it and gives every reason column its"
            + " CHECK")
    void theMigrationIsTheTwin() {
        String sql = migration();
        assertThat(sql)
                .contains("'" + InstrumentShapes.PLATFORM_IDENTIFIER_REGEX + "'")
                .contains("'" + InstrumentShapes.DIGIT_RUN_REGEX + "'")
                .contains("'" + InstrumentShapes.ALPHANUMERIC_RUN_REGEX + "'");
        for (String screen : List.of("break_note_no_card_number", "break_note_no_account_shape",
                "break_evidence_link_no_card_number", "break_evidence_link_no_account_shape",
                "resolution_narrative_no_pan", "resolution_narrative_no_iban")) {
            assertThat(sql).contains("DROP CONSTRAINT " + screen + ",")
                    .contains("ADD CONSTRAINT " + screen + " CHECK (");
        }
        for (String table : REASON_TABLES) {
            assertThat(sql).contains("ADD CONSTRAINT " + table + "_reason_no_instrument_shape"
                    + " CHECK (\n        NOT reconciliation.holds_instrument_shape(reason))");
        }
        assertThat(sql).doesNotContain("GRANT").doesNotContain("DELETE FROM")
                .doesNotContain("UPDATE reconciliation.");
    }

    private static final List<String> REASON_TABLES =
            List.of("rule_set", "rule_set_event", "reconciliation_batch",
                    "reconciliation_batch_event", "external_item_event", "break_event",
                    "resolution_event");

    // ================================================================= the parity

    @Test
    @DisplayName("the database's verdict is the domain's, half for half, over a corpus of grouped"
            + " card numbers, printed and corrupted account identifiers, platform UUIDs and prose")
    void theTwinAgreesWithTheDomainScreen() throws SQLException {
        List<String> corpus = new ArrayList<>(ShapeCorpus.fixed());
        corpus.addAll(ShapeCorpus.random(new Random(19L), 2500));
        long seed = RANDOM.nextLong();
        corpus.addAll(ShapeCorpus.random(new Random(seed), 1500));
        int cards = 0;
        int accounts = 0;
        try (Connection app = application()) {
            try (PreparedStatement verdicts =
                    app.prepareStatement(
                            "SELECT u.text, reconciliation.holds_card_number_shape(u.text),"
                                    + " reconciliation.holds_account_identifier_shape(u.text),"
                                    + " reconciliation.holds_instrument_shape(u.text),"
                                    + " reconciliation.holds_luhn_valid_digit_run(u.text)"
                                    + " FROM unnest(?::text[]) WITH ORDINALITY AS u(text, n)"
                                    + " ORDER BY u.n")) {
                Array texts = app.createArrayOf("text", corpus.toArray());
                verdicts.setArray(1, texts);
                try (ResultSet row = verdicts.executeQuery()) {
                    while (row.next()) {
                        String text = row.getString(1);
                        boolean card = InstrumentShapes.holdsCardNumber(text);
                        boolean account = InstrumentShapes.holdsAccountIdentifier(text);
                        assertThat(row.getBoolean(2))
                                .as("card verdict for [%s] (random seed %d)", text, seed)
                                .isEqualTo(card);
                        assertThat(row.getBoolean(3))
                                .as("account verdict for [%s] (random seed %d)", text, seed)
                                .isEqualTo(account);
                        assertThat(row.getBoolean(4)).isEqualTo(card || account);
                        assertThat(row.getBoolean(5))
                                .as("V004's name answers the corrected card scan")
                                .isEqualTo(card);
                        cards += card ? 1 : 0;
                        accounts += account ? 1 : 0;
                    }
                }
            } finally {
                app.rollback();
            }
        }
        assertThat(cards).as("the corpus exercises the card verdict both ways")
                .isBetween(200, corpus.size() - 200);
        assertThat(accounts).as("the corpus exercises the account verdict both ways")
                .isBetween(200, corpus.size() - 200);
    }

    // ================================================================= the live rank

    @Test
    @DisplayName("every column that stores person-written prose refuses a spaced card number, a"
            + " dashed one and a printed account identifier by its named CHECK, nothing stored, and"
            + " admits clean prose - for a raw writer past every domain guard")
    void everyProseColumnRefusesTheNeedles() throws SQLException {
        try (Connection app = application()) {
            try {
                UUID source = UUID.randomUUID();
                UUID run = seedRun(app, source);
                UUID item = seedItem(app, source, run, 1);
                UUID timing = seedDecisionBreak(app, source, run, 2);
                UUID resolution = seedResolution(app, timing);
                UUID proposed = UUID.randomUUID();

                for (String needle : NEEDLES) {
                    boolean card = needle.contains("4111");
                    refusedBy(app, card ? "break_note_no_card_number"
                                    : "break_note_no_account_shape",
                            "INSERT INTO reconciliation.break_note (id, break_id, body, author,"
                                    + " author_type, added_at, correlation_id) VALUES (?, ?, ?,"
                                    + " 'op-1', 'EMPLOYEE', now(), 'v019')",
                            UUID.randomUUID(), timing, needle);
                    refusedBy(app, card ? "break_evidence_link_no_card_number"
                                    : "break_evidence_link_no_account_shape",
                            "INSERT INTO reconciliation.break_evidence_link (id, break_id,"
                                    + " target_kind, target_ref, added_by, added_by_type,"
                                    + " added_at, correlation_id) VALUES (?, ?, 'OPERATION', ?,"
                                    + " 'op-1', 'EMPLOYEE', now(), 'v019')",
                            UUID.randomUUID(), timing, "CARD_CAPTURE:" + needle);
                    refusedBy(app, card ? "resolution_narrative_no_pan"
                                    : "resolution_narrative_no_iban",
                            RESOLUTION, UUID.randomUUID(), seedDecisionBreak(app, source, run,
                                    10 + NEEDLES.indexOf(needle)), needle, RULE_SET,
                            Timestamp.from(Instant.now()));
                    refusedBy(app, "rule_set_reason_no_instrument_shape", RULE_SET_ROW,
                            proposed, UUID.randomUUID(), needle);
                    refusedBy(app, "rule_set_event_reason_no_instrument_shape",
                            "INSERT INTO reconciliation.rule_set_event (rule_set_id, from_status,"
                                    + " to_status, actor, actor_type, reason, occurred_at,"
                                    + " correlation_id) VALUES (?, NULL, 'PROPOSED', 'op-1',"
                                    + " 'EMPLOYEE', ?, now(), 'v019')",
                            RULE_SET, needle);
                    refusedBy(app, "reconciliation_batch_reason_no_instrument_shape", RUN_ROW,
                            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), RULE_SET,
                            BUSINESS_DATE, needle);
                    refusedBy(app, "reconciliation_batch_event_reason_no_instrument_shape",
                            "INSERT INTO reconciliation.reconciliation_batch_event (run_id,"
                                    + " from_status, to_status, actor, actor_type, reason,"
                                    + " occurred_at, correlation_id) VALUES (?, 'BLOCKED',"
                                    + " 'IN_PROGRESS', 'op-1', 'EMPLOYEE', ?, now(), 'v019')",
                            run, needle);
                    refusedBy(app, "external_item_event_reason_no_instrument_shape",
                            "INSERT INTO reconciliation.external_item_event (item_id,"
                                    + " from_status, to_status, actor, actor_type, reason,"
                                    + " occurred_at, correlation_id) VALUES (?, 'PENDING',"
                                    + " 'UNMATCHED', 'op-1', 'EMPLOYEE', ?, now(), 'v019')",
                            item, needle);
                    refusedBy(app, "break_event_reason_no_instrument_shape",
                            "INSERT INTO reconciliation.break_event (break_id, event_type, actor,"
                                    + " actor_type, reason, detail, occurred_at, correlation_id)"
                                    + " VALUES (?, 'RECLASSIFIED', 'op-1', 'EMPLOYEE', ?,"
                                    + " 'from=TIMING_DIFFERENCE, to=FEE_MISMATCH', now(), 'v019')",
                            timing, needle);
                    refusedBy(app, "resolution_event_reason_no_instrument_shape",
                            "INSERT INTO reconciliation.resolution_event (resolution_id,"
                                    + " from_status, to_status, actor, actor_type, reason,"
                                    + " occurred_at, correlation_id) VALUES (?, 'PROPOSED',"
                                    + " 'REJECTED', 'op-2', 'EMPLOYEE', ?, now(), 'v019')",
                            resolution, needle);
                }

                assertThat(count(app, "SELECT count(*) FROM reconciliation.break_note WHERE"
                                + " body LIKE '%4111%' OR body LIKE '%WEST%'")
                        + count(app, "SELECT count(*) FROM reconciliation.break_event WHERE"
                                + " reason LIKE '%4111%' OR reason LIKE '%WEST%'")
                        + count(app, "SELECT count(*) FROM reconciliation.reconciliation_batch"
                                + "_event WHERE reason LIKE '%4111%' OR reason LIKE '%WEST%'"))
                        .as("nothing stored by any refused writer")
                        .isZero();

                // The control: the same writers, clean prose - each CHECK refuses the shape, not
                // the column.
                execute(app, "INSERT INTO reconciliation.break_note (id, break_id, body, author,"
                        + " author_type, added_at, correlation_id) VALUES (?, ?, ?, 'op-1',"
                        + " 'EMPLOYEE', now(), 'v019')", UUID.randomUUID(), timing, CLEAN);
                execute(app, "INSERT INTO reconciliation.reconciliation_batch_event (run_id,"
                        + " from_status, to_status, actor, actor_type, reason, occurred_at,"
                        + " correlation_id) VALUES (?, 'BLOCKED', 'IN_PROGRESS', 'op-1',"
                        + " 'EMPLOYEE', ?, now(), 'v019')", run,
                        "requeued after run " + run + " blocked");
                execute(app, RULE_SET_ROW, proposed, UUID.randomUUID(), CLEAN);
                execute(app, "INSERT INTO reconciliation.break_event (break_id, event_type,"
                        + " actor, actor_type, reason, detail, occurred_at, correlation_id) VALUES"
                        + " (?, 'RECLASSIFIED', 'op-1', 'EMPLOYEE', ?, NULL, now(), 'v019')",
                        timing, CLEAN);
            } finally {
                app.rollback();
            }
        }
    }

    // ================================================================= seeding

    private static final String RUN_ROW =
            "INSERT INTO reconciliation.reconciliation_batch (id, source_id, batch_id, kind,"
                    + " rule_set_id, business_date, source_sequence, item_count, created_at,"
                    + " status_changed_at, correlation_id, reason) VALUES (?, ?, ?, 'BATCH', ?, ?,"
                    + " 1, 4, now(), now(), 'v019', ?)";

    private static final String RULE_SET_ROW =
            "INSERT INTO reconciliation.rule_set (id, source_id, version, status,"
                    + " funding_lag_days, gain_min_age_days, effective_from, proposed_by,"
                    + " decided_by, decided_at, reason, created_at, correlation_id) VALUES (?, ?,"
                    + " 2, 'PROPOSED', 2, 90, DATE '2026-10-02', 'op-proposer', NULL, NULL, ?,"
                    + " now(), 'v019')";

    /** A single-person zero ACKNOWLEDGE of a timing difference, born APPROVED (V014's one path). */
    private static final String RESOLUTION =
            "INSERT INTO reconciliation.resolution (id, break_id, kind, status, reason_code,"
                    + " narrative, four_eyes, proposed_amount_minor, currency, scale,"
                    + " residual_version, rule_set_id, proposed_by, proposed_by_type, proposed_at,"
                    + " decided_by, decided_by_type, decided_at, created_at, status_changed_at,"
                    + " correlation_id) VALUES (?, ?, 'ACKNOWLEDGE', 'APPROVED', 'TIMING_CONFIRMED',"
                    + " ?, false, 0, 'EUR', 2, 0, ?, 'op-proposer', 'EMPLOYEE', now(),"
                    + " 'op-proposer', 'EMPLOYEE', ?, now(), now(), 'v019')";

    private static UUID seedRun(Connection connection, UUID source) throws SQLException {
        UUID run = UUID.randomUUID();
        execute(connection, RUN_ROW, run, source, UUID.randomUUID(), RULE_SET, BUSINESS_DATE,
                null);
        return run;
    }

    private static UUID seedItem(Connection connection, UUID source, UUID run, int lineNo)
            throws SQLException {
        UUID item = UUID.randomUUID();
        execute(connection, "INSERT INTO reconciliation.external_item (id, run_id, source_id,"
                + " settlement_line_id, line_no, line_type, direction, amount_minor, currency,"
                + " scale, position_purpose, business_date, settlement_date, value_date,"
                + " canonical_fingerprint, created_at, status_changed_at, correlation_id)"
                + " VALUES (?, ?, ?, ?, ?, 'CAPTURE', 'INBOUND', 1000, 'EUR', 2,"
                + " 'SETTLEMENT_CLEARING', ?, ?, ?, ?, now(), now(), 'v019')",
                item, run, source, UUID.randomUUID(), lineNo, BUSINESS_DATE, BUSINESS_DATE,
                BUSINESS_DATE, digest());
        return item;
    }

    /** A TIMING_DIFFERENCE standing on a RUN decision over its own item. */
    private static UUID seedDecisionBreak(
            Connection connection, UUID source, UUID run, int lineNo) throws SQLException {
        UUID item = seedItem(connection, source, run, lineNo);
        UUID decision = UUID.randomUUID();
        execute(connection, "INSERT INTO reconciliation.match_decision (id, external_item_id,"
                + " run_id, origin, rule_set_id, rule_priority, strategy, matched_key_kind,"
                + " outcome, decided_by, decided_by_type, decided_at, decided_on, correlation_id,"
                + " verdict, judged_status, judged_minor, fingerprint_seen_earlier) VALUES (?, ?,"
                + " ?, 'RUN', ?, 1, 'ONE_TO_ONE', 'PSP_CAPTURE_REF', 'MATCHED', 'system',"
                + " 'SYSTEM', now(), ?, 'v019', 'ALLOCATE', 'PENDING', 1000, false)",
                decision, item, run, RULE_SET, BUSINESS_DATE);
        UUID breakId = UUID.randomUUID();
        execute(connection, "INSERT INTO reconciliation.break (id, type, cause, severity,"
                + " source_id, rule_set_id, decision_id, value_at_issue_minor, currency, scale,"
                + " raised_at, status_changed_at, correlation_id) VALUES (?, 'TIMING_DIFFERENCE',"
                + " 'LATE_MATCH', 'LOW', ?, ?, ?, 0, 'EUR', 2, now(), now(), 'v019')",
                breakId, source, RULE_SET, decision);
        return breakId;
    }

    private static UUID seedResolution(Connection connection, UUID breakId) throws SQLException {
        UUID resolution = UUID.randomUUID();
        execute(connection, RESOLUTION, resolution, breakId, CLEAN, RULE_SET,
                Timestamp.from(Instant.now()));
        return resolution;
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

    /** Refused by exactly the named CHECK (23514), its savepoint rolled back - nothing stored. */
    private static void refusedBy(
            Connection connection, String constraint, String sql, Object... args)
            throws SQLException {
        Savepoint before = connection.setSavepoint();
        try {
            execute(connection, sql, args);
        } catch (SQLException refusal) {
            connection.rollback(before);
            assertThat(refusal.getSQLState())
                    .as("a CHECK violation, not some other failure: %s", refusal.getMessage())
                    .isEqualTo("23514");
            assertThat(refusal.getMessage()).contains(constraint);
            return;
        }
        connection.rollback(before);
        throw new AssertionError("admitted, but " + constraint + " should refuse it: " + sql);
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

    private static long count(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet row = statement.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    private static String migration() {
        try (InputStream in =
                ReconciliationV019MigrationTest.class.getClassLoader().getResourceAsStream(V019)) {
            assertThat(in).as("the migration is on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
