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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * `V014` — `P8-TST-002`'s one production correction — read against its text and proven at the
 * database rank against the real schema (ADR-0071 §3 and ADR-0069 §9, both corrected;
 * {@code INV-REC-03}, {@code INV-AUD-04}): a zero-value {@code ACKNOWLEDGE} is one person's ONLY
 * on a {@code TIMING_DIFFERENCE} raised by a timing detector. A diverged replay's zero-value
 * {@code PROCESSING_ERROR} (`P8-TSK-022`, ADR-0068 §9.1) - reclassified onto the timing type or
 * not - and a zero-value {@code DUPLICATE_INTERNAL} - every other break - take two people,
 * derived from the break's type and frozen cause by an every-writer trigger because `V007`'s
 * {@code CHECK} cannot see the break; a one-person resolution is born {@code APPROVED}; and a
 * break a one-person resolution names never changes type.
 *
 * <p>Each live probe runs in one rolled-back transaction, refusals behind savepoints; the class
 * is tagged {@code database} because the taxonomy places a class by its heaviest member (the
 * `V013` test's idiom). Each probe seeds a private source.
 */
@Tag("database")
@DisplayName("reconciliation V014: a diverged replay is acknowledged four-eyes (P8-TST-002)")
class ReconciliationV014MigrationTest {

    private static final String V014 =
            "db/migration/reconciliation/V014__a_diverged_replay_is_acknowledged_four_eyes.sql";

    /** The PSP's seeded rule set v1 (V002): referenced by foreign key only. */
    private static final UUID RULE_SET = UUID.fromString("01a0e2bd-8300-7001-8000-000000000001");
    private static final LocalDate BUSINESS_DATE = LocalDate.parse("2026-09-29");
    private static final SecureRandom RANDOM = new SecureRandom();

    // ================================================================= the text

    @Test
    @DisplayName("the CHECK keeps what the row can state (EVIDENCED never four-eyes, everything but"
            + " a zero-value ACKNOWLEDGE always); an AFTER INSERT trigger derives the rest from the"
            + " break's type")
    void theDerivationMovesToTheBreak() {
        String sql = normalized(migration());
        assertThat(sql)
                .contains("DROP CONSTRAINT resolution_four_eyes_derived")
                .contains(normalized("ADD CONSTRAINT resolution_four_eyes_derived CHECK ("
                        + " CASE WHEN kind = 'EVIDENCED' THEN NOT four_eyes"
                        + " WHEN kind = 'ACKNOWLEDGE' AND proposed_amount_minor = 0 THEN true"
                        + " ELSE four_eyes END)"))
                .contains("IF NEW.kind = 'ACKNOWLEDGE' AND NEW.proposed_amount_minor = 0 THEN")
                .contains("one_person := coalesce(break_type = 'TIMING_DIFFERENCE' AND"
                        + " break_cause IN (" + timingCauses() + "), false);")
                .contains("IF NEW.four_eyes = one_person THEN")
                .contains("AFTER INSERT ON reconciliation.resolution FOR EACH ROW")
                .contains("ADD CONSTRAINT resolution_unapproved_is_four_eyes CHECK (four_eyes OR"
                        + " status = 'APPROVED');")
                .contains("BEFORE UPDATE OF type ON reconciliation.break FOR EACH ROW")
                .contains("WHERE r.break_id = NEW.id AND NOT r.four_eyes")
                .doesNotContain("UPDATE reconciliation.resolution")
                .doesNotContain("GRANT");
    }

    // ================================================================= the live rank

    @Test
    @DisplayName("a raw single-person zero ACKNOWLEDGE is admitted on a TIMING_DIFFERENCE and"
            + " refused on a diverged replay's PROCESSING_ERROR and on a DUPLICATE_INTERNAL - by"
            + " the trigger, for the application and the owner alike")
    void aSinglePersonZeroAcknowledgementStandsOnATimingDifferenceAlone() throws SQLException {
        for (boolean asOwner : new boolean[] {false, true}) {
            try (Connection writer = asOwner ? migrator() : application()) {
                try {
                    UUID source = UUID.randomUUID();
                    UUID run = seedRun(writer, source);
                    UUID timing = seedDecisionBreak(writer, source, run, 1, "TIMING_DIFFERENCE",
                            "LATE_MATCH");
                    UUID diverged = seedDecisionBreak(writer, source, run, 2, "PROCESSING_ERROR",
                            "REPLAY_DIVERGED");
                    UUID collision = seedExpectationBreak(writer, source);

                    admitted(writer, RESOLUTION, acknowledgement(timing, "TIMING_CONFIRMED",
                            false, "APPROVED", "op-proposer"));
                    assertThat(count(writer, "SELECT count(*) FROM reconciliation.resolution"
                                    + " WHERE break_id = ? AND NOT four_eyes AND status ="
                                    + " 'APPROVED' AND decided_by = proposed_by", timing))
                            .as("the one single-person path: a timing difference's zero"
                                    + " acknowledgement, born APPROVED by its proposer")
                            .isEqualTo(1);

                    assertThat((Throwable) refused(writer, RESOLUTION, acknowledgement(diverged,
                                    "INTERNAL_PROCESSING_ERROR", false, "APPROVED",
                                    "op-proposer")))
                            .as("V014 rank: a diverged replay (CRITICAL, 'the matcher's"
                                    + " decisions cannot be reproduced') never closes on one"
                                    + " person's word")
                            .hasMessageContaining("resolution_four_eyes_derived_from_the_break")
                            .hasMessageContaining("PROCESSING_ERROR");
                    assertThat((Throwable) refused(writer, RESOLUTION, acknowledgement(diverged,
                                    "INTERNAL_PROCESSING_ERROR", false, "PROPOSED", null)))
                            .as("V014 CHECK: nor proposed as a one-person act - a one-person"
                                    + " resolution is born APPROVED")
                            .hasMessageContaining("resolution_unapproved_is_four_eyes");
                    assertThat((Throwable) refused(writer, RESOLUTION, acknowledgement(collision,
                                    "INTERNAL_PROCESSING_ERROR", false, "APPROVED",
                                    "op-proposer")))
                            .as("V014 rank: a zero-value duplicate internal is two people's too")
                            .hasMessageContaining("resolution_four_eyes_derived_from_the_break")
                            .hasMessageContaining("DUPLICATE_INTERNAL");

                    admitted(writer, RESOLUTION, acknowledgement(diverged,
                            "INTERNAL_PROCESSING_ERROR", true, "PROPOSED", null));
                    assertThat((Throwable) refused(writer, "UPDATE reconciliation.resolution SET"
                                    + " status = 'APPROVED', decided_by = proposed_by,"
                                    + " decided_by_type = 'EMPLOYEE', decided_at = now(),"
                                    + " status_changed_at = now() WHERE break_id = ?", diverged))
                            .as("and its proposer never approves it (V006's distinctness)")
                            .hasMessageContaining("resolution_four_eyes_distinct");
                    admitted(writer, "UPDATE reconciliation.resolution SET status = 'APPROVED',"
                            + " decided_by = 'op-approver', decided_by_type = 'EMPLOYEE',"
                            + " decided_at = now(), status_changed_at = now() WHERE break_id = ?",
                            diverged);
                } finally {
                    writer.rollback();
                }
            }
        }
    }

    @Test
    @DisplayName("four-eyes is derived, never chosen, both ways: a four-eyes zero ACKNOWLEDGE of a"
            + " timing difference is refused by the trigger; a valued single-person ACKNOWLEDGE"
            + " and a four-eyes EVIDENCED by V006's resolution_evidenced_is_platform (the relaxed"
            + " CHECK's EVIDENCED branch restates it, redundantly)")
    void theFlagIsDerivedBothWays() throws SQLException {
        try (Connection app = application()) {
            try {
                UUID source = UUID.randomUUID();
                UUID run = seedRun(app, source);
                UUID timing = seedDecisionBreak(app, source, run, 1, "TIMING_DIFFERENCE",
                        "CYCLE_MISMATCH");
                assertThat((Throwable) refused(app, RESOLUTION, acknowledgement(timing,
                                "TIMING_CONFIRMED", true, "PROPOSED", null)))
                        .as("V014 rank: the flag follows the break, it is not the writer's")
                        .hasMessageContaining("resolution_four_eyes_derived_from_the_break")
                        .hasMessageContaining("TIMING_DIFFERENCE");
                Object[] zero = acknowledgement(timing, "TIMING_CONFIRMED", false, "PROPOSED",
                        null);
                Object[] valued = new Object[zero.length + 1];
                System.arraycopy(zero, 0, valued, 0, 5);
                valued[5] = 1L;
                System.arraycopy(zero, 5, valued, 6, zero.length - 5);
                assertThat((Throwable) refused(app, RESOLUTION_VALUED, valued))
                        .as("CHECK rank: one unit at issue is two people's, whatever the break")
                        .hasMessageContaining("resolution_four_eyes_derived");
                assertThat((Throwable) refused(app, "INSERT INTO reconciliation.resolution (id,"
                                + " break_id, kind, status, reason_code, narrative, four_eyes,"
                                + " proposed_amount_minor, currency, scale, residual_version,"
                                + " rule_set_id, proposed_by, proposed_by_type, proposed_at,"
                                + " decided_by, decided_by_type, decided_at, created_at,"
                                + " status_changed_at, correlation_id) VALUES (?, ?, 'EVIDENCED',"
                                + " 'APPROVED', 'EVIDENCE_RECEIVED', 'p8tst2 raw probe', true, 0,"
                                + " 'EUR', 2, 0, ?, 'system', 'SYSTEM', now(), 'system',"
                                + " 'SYSTEM', now(), now(), now(), 'p8tst2-v014')",
                                UUID.randomUUID(), timing, RULE_SET))
                        // V006's resolution_evidenced_is_platform and V014's derived CHECK both
                        // refuse this row; PostgreSQL reports the first by constraint name.
                        .as("CHECK rank: EVIDENCED is never four-eyes")
                        .hasMessageContaining("resolution_evidenced_is_platform");
            } finally {
                app.rollback();
            }
        }
    }

    @Test
    @DisplayName("the cause, not the type: a diverged replay reclassified onto TIMING_DIFFERENCE"
            + " keeps its acknowledgement four-eyes; a one-person resolution is born APPROVED; and"
            + " a break a one-person resolution names never changes type - for the application"
            + " and the owner alike")
    void theCauseAndTheFrozenTypeHold() throws SQLException {
        for (boolean asOwner : new boolean[] {false, true}) {
            try (Connection writer = asOwner ? migrator() : application()) {
                try {
                    UUID source = UUID.randomUUID();
                    UUID run = seedRun(writer, source);
                    UUID diverged = seedDecisionBreak(writer, source, run, 1, "PROCESSING_ERROR",
                            "REPLAY_DIVERGED");
                    admitted(writer, "UPDATE reconciliation.break SET type = 'TIMING_DIFFERENCE'"
                            + " WHERE id = ?", diverged);
                    assertThat((Throwable) refused(writer, RESOLUTION, acknowledgement(diverged,
                                    "TIMING_CONFIRMED", false, "APPROVED", "op-proposer")))
                            .as("V014 trigger: reclassified onto the timing type, a diverged"
                                    + " replay is still two people's - the cause is frozen")
                            .hasMessageContaining("resolution_four_eyes_derived_from_the_break")
                            .hasMessageContaining("REPLAY_DIVERGED");
                    admitted(writer, RESOLUTION, acknowledgement(diverged, "TIMING_CONFIRMED",
                            true, "PROPOSED", null));

                    UUID timing = seedDecisionBreak(writer, source, run, 2, "TIMING_DIFFERENCE",
                            "LATE_MATCH");
                    assertThat((Throwable) refused(writer, RESOLUTION, acknowledgement(timing,
                                    "TIMING_CONFIRMED", false, "PROPOSED", null)))
                            .as("V014 CHECK: a one-person resolution is born APPROVED")
                            .hasMessageContaining("resolution_unapproved_is_four_eyes");
                    admitted(writer, RESOLUTION, acknowledgement(timing, "TIMING_CONFIRMED",
                            false, "APPROVED", "op-proposer"));
                    assertThat((Throwable) refused(writer, "UPDATE reconciliation.break SET type ="
                                    + " 'PROCESSING_ERROR' WHERE id = ?", timing))
                            .as("V014 trigger: the type a one-person flag was derived from is"
                                    + " frozen, whatever the break's status (here still OPEN)")
                            .hasMessageContaining(
                                    "break_type_frozen_under_a_one_person_resolution");
                } finally {
                    writer.rollback();
                }
            }
        }
    }

    /** ResolutionTemplates.timingCause's members, as the trigger lists them. */
    private static String timingCauses() {
        return java.util.Arrays.stream(BreakCause.values())
                .filter(ResolutionTemplates::timingCause)
                .map(cause -> "'" + cause.name() + "'")
                .collect(java.util.stream.Collectors.joining(", "));
    }

    // ================================================================= seeding

    private static final String RESOLUTION =
            "INSERT INTO reconciliation.resolution (id, break_id, kind, status, reason_code,"
                    + " narrative, four_eyes, proposed_amount_minor, currency, scale,"
                    + " residual_version, rule_set_id, proposed_by, proposed_by_type, proposed_at,"
                    + " decided_by, decided_by_type, decided_at, created_at, status_changed_at,"
                    + " correlation_id) VALUES (?, ?, 'ACKNOWLEDGE', ?, ?, 'p8tst2 raw probe', ?,"
                    + " 0, 'EUR', 2, 0, ?, 'op-proposer', 'EMPLOYEE', now(), ?, ?, ?, now(), now(),"
                    + " 'p8tst2-v014')";

    /** {@link #RESOLUTION} with the amount as a parameter, after the flag (index 5). */
    private static final String RESOLUTION_VALUED =
            "INSERT INTO reconciliation.resolution (id, break_id, kind, status, reason_code,"
                    + " four_eyes, proposed_amount_minor, narrative, currency, scale,"
                    + " residual_version, rule_set_id, proposed_by, proposed_by_type, proposed_at,"
                    + " decided_by, decided_by_type, decided_at, created_at, status_changed_at,"
                    + " correlation_id) VALUES (?, ?, 'ACKNOWLEDGE', ?, ?, ?, ?, 'p8tst2 raw"
                    + " probe', 'EUR', 2, 0, ?, 'op-proposer', 'EMPLOYEE', now(), ?, ?, ?, now(),"
                    + " now(), 'p8tst2-v014')";

    /**
     * One acknowledgement's parameters in {@link #RESOLUTION}'s order; for
     * {@link #RESOLUTION_VALUED} the amount is inserted at index 5 and the rest shift by one.
     */
    private static Object[] acknowledgement(
            UUID breakId, String reason, boolean fourEyes, String status, String decidedBy) {
        boolean decided = decidedBy != null;
        return new Object[] {
            UUID.randomUUID(), breakId, status, reason, fourEyes, RULE_SET, decidedBy,
            decided ? "EMPLOYEE" : null, decided ? Timestamp.from(Instant.now()) : null
        };
    }

    private static UUID seedRun(Connection connection, UUID source) throws SQLException {
        UUID run = UUID.randomUUID();
        execute(connection, "INSERT INTO reconciliation.reconciliation_batch (id, source_id,"
                + " batch_id, kind, rule_set_id, business_date, source_sequence, item_count,"
                + " created_at, status_changed_at, correlation_id) VALUES (?, ?, ?, 'BATCH', ?,"
                + " ?, 1, 4, now(), now(), 'p8tst2-v014')",
                run, source, UUID.randomUUID(), RULE_SET, BUSINESS_DATE);
        return run;
    }

    /** A break standing on a RUN decision over its own item - the decision-subject shape. */
    private static UUID seedDecisionBreak(
            Connection connection, UUID source, UUID run, int lineNo, String type, String cause)
            throws SQLException {
        UUID item = UUID.randomUUID();
        execute(connection, "INSERT INTO reconciliation.external_item (id, run_id, source_id,"
                + " settlement_line_id, line_no, line_type, direction, amount_minor, currency,"
                + " scale, position_purpose, business_date, settlement_date, value_date,"
                + " canonical_fingerprint, created_at, status_changed_at, correlation_id)"
                + " VALUES (?, ?, ?, ?, ?, 'CAPTURE', 'INBOUND', 1000, 'EUR', 2,"
                + " 'SETTLEMENT_CLEARING', ?, ?, ?, ?, now(), now(), 'p8tst2-v014')",
                item, run, source, UUID.randomUUID(), lineNo, BUSINESS_DATE, BUSINESS_DATE,
                BUSINESS_DATE, digest());
        UUID decision = UUID.randomUUID();
        execute(connection, "INSERT INTO reconciliation.match_decision (id, external_item_id,"
                + " run_id, origin, rule_set_id, rule_priority, strategy, matched_key_kind,"
                + " outcome, decided_by, decided_by_type, decided_at, decided_on, correlation_id,"
                + " verdict, judged_status, judged_minor, fingerprint_seen_earlier) VALUES (?, ?,"
                + " ?, 'RUN', ?, 1, 'ONE_TO_ONE', 'PSP_CAPTURE_REF', 'MATCHED', 'system',"
                + " 'SYSTEM', now(), ?, 'p8tst2-v014', 'ALLOCATE', 'PENDING', 1000, false)",
                decision, item, run, RULE_SET, BUSINESS_DATE);
        UUID breakId = UUID.randomUUID();
        execute(connection, "INSERT INTO reconciliation.break (id, type, cause, severity,"
                + " source_id, rule_set_id, decision_id, value_at_issue_minor, currency, scale,"
                + " raised_at, status_changed_at, correlation_id) VALUES (?, ?, ?, 'LOW', ?, ?,"
                + " ?, 0, 'EUR', 2, now(), now(), 'p8tst2-v014')",
                breakId, type, cause, source, RULE_SET, decision);
        return breakId;
    }

    /** A zero-value DUPLICATE_INTERNAL on a REMITTANCE expectation (no posting to forge). */
    private static UUID seedExpectationBreak(Connection connection, UUID source)
            throws SQLException {
        UUID expectation = UUID.randomUUID();
        execute(connection, "INSERT INTO reconciliation.expectation (id, kind, operation_ref,"
                + " posting_key, source_id, position_purpose, ledger_account_id, direction,"
                + " amount_minor, currency, scale, journal_entry_id, posting_date, expected_by,"
                + " rule_set_id, opened_at, status_changed_at, correlation_id) VALUES (?,"
                + " 'REMITTANCE', ?, ?, ?, 'SETTLEMENT_CLEARING', ?, 'INBOUND', 1000, 'EUR', 2,"
                + " NULL, ?, ?, ?, now(), now(), 'p8tst2-v014')",
                expectation, "p8tst2-op-" + expectation, "p8tst2-remittance:" + expectation,
                source, UUID.randomUUID(), BUSINESS_DATE, BUSINESS_DATE.plusDays(2), RULE_SET);
        UUID breakId = UUID.randomUUID();
        execute(connection, "INSERT INTO reconciliation.break (id, type, cause, severity,"
                + " source_id, rule_set_id, expectation_id, value_at_issue_minor, currency,"
                + " scale, raised_at, status_changed_at, correlation_id) VALUES (?,"
                + " 'DUPLICATE_INTERNAL', 'KEY_COLLISION', 'HIGH', ?, ?, ?, 0, 'EUR', 2, now(),"
                + " now(), 'p8tst2-v014')",
                breakId, source, RULE_SET, expectation);
        return breakId;
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

    private static void admitted(Connection connection, String sql, Object... args)
            throws SQLException {
        execute(connection, sql, args);
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

    private static long count(Connection connection, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private static String normalized(String sql) {
        return sql.replaceAll("\\s+", " ").replaceAll("\\( ", "(").replaceAll(" \\)", ")");
    }

    private static String migration() {
        try (InputStream in =
                ReconciliationV014MigrationTest.class.getClassLoader().getResourceAsStream(V014)) {
            assertThat(in).as("the migration is on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
