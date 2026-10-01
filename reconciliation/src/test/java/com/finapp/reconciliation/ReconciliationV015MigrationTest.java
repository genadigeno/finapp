package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * `V015` — `P8-DOC-001`'s correction of ADR-0069's unbacked claim, read against its text and
 * proven at the database rank against the real schema ({@code INV-REC-02}, {@code INV-AUD-04}):
 * a break reaches {@code RESOLVED} only beside a {@code RESOLVED} {@code break_event} naming, by
 * its new {@code resolution_id} column, a resolution that is {@code APPROVED} at commit - for the
 * application role and the owner alike. A {@code RESOLVED} event never omits the link, and the
 * link is a real resolution.
 *
 * <p>The real closures - a primary break, a remainder sibling and an offset partner through the
 * real {@link ResolutionMachine}, a repudiation's closures through the real door - are proven
 * where they are driven: {@code ResolutionMachineDatabaseTest} and app's
 * {@code BatchRepudiationDatabaseTest}, which assert the link and commit under this migration.
 *
 * <p>Every probe here leaves the shared container untouched: a refusal at commit rolls its whole
 * transaction back, and an admission is proven with {@code SET CONSTRAINTS ALL IMMEDIATE} - the
 * deferred checks run exactly as at commit - then rolled back. Each probe seeds a private source.
 */
@Tag("database")
@DisplayName("reconciliation V015: a RESOLVED break names its APPROVED resolution (P8-DOC-001)")
class ReconciliationV015MigrationTest {

    private static final String V015 =
            "db/migration/reconciliation/V015__a_resolved_break_names_its_approved_resolution.sql";

    /** The PSP's seeded rule set v1 (V002): referenced by foreign key only. */
    private static final UUID RULE_SET = UUID.fromString("01a0e2bd-8300-7001-8000-000000000001");
    private static final LocalDate BUSINESS_DATE = LocalDate.parse("2026-09-29");
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String DEFERRED = "break_resolved_names_an_approved_resolution";
    private static final String AT_INSERT = "break_event_resolved_names_its_resolution";

    // ================================================================= the text

    @Test
    @DisplayName("the column with its deferred key, the insert trigger for new RESOLVED events"
            + " only (no validated CHECK over history), and the deferred constraint trigger"
            + " judging the resolution's status at commit; no grant, no rewrite of history")
    void theTextStatesTheThreeRules() {
        String sql = migration().replaceAll("\\s+", " ");
        assertThat(sql)
                .contains("ADD COLUMN resolution_id UUID,")
                .contains("REFERENCES reconciliation.resolution (id) DEFERRABLE INITIALLY"
                        + " DEFERRED")
                .contains("IF NEW.event_type = 'RESOLVED' AND NEW.resolution_id IS NULL THEN")
                .contains("BEFORE INSERT ON reconciliation.break_event")
                .contains("AND e.event_type = 'RESOLVED' AND r.status = 'APPROVED'")
                .contains("CREATE CONSTRAINT TRIGGER " + DEFERRED
                        + " AFTER INSERT OR UPDATE OF status ON reconciliation.break DEFERRABLE"
                        + " INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.status = 'RESOLVED')")
                .doesNotContain("ADD CONSTRAINT break_event_resolved")
                .doesNotContain("UPDATE reconciliation.break_event")
                .doesNotContain("GRANT");
    }

    // ================================================================= the live rank

    @Test
    @DisplayName("a raw OPEN -> RESOLVED with no record, and a raw row born RESOLVED, are"
            + " admitted by the statement and refused at COMMIT - for the application and the"
            + " owner alike - and nothing reaches the container")
    void aRawResolvedWithNoRecordIsRefusedAtCommit() throws SQLException {
        for (boolean asOwner : new boolean[] {false, true}) {
            UUID closed;
            try (Connection writer = asOwner ? migrator() : application()) {
                closed = seedDecisionBreak(writer, UUID.randomUUID());
                execute(writer, "UPDATE reconciliation.break SET status = 'RESOLVED',"
                        + " resolved_at = now(), status_changed_at = now() WHERE id = ?", closed);
                assertThatThrownBy(writer::commit,
                        "V015 deferred trigger: a break closed with no RESOLVED event naming an"
                                + " APPROVED resolution never commits (%s)",
                        asOwner ? "owner" : "application")
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining(DEFERRED);
                writer.rollback();
            }
            UUID born;
            try (Connection writer = asOwner ? migrator() : application()) {
                born = seedDecisionBreak(writer, UUID.randomUUID(), "RESOLVED");
                assertThatThrownBy(writer::commit,
                        "V015 deferred trigger: a break born RESOLVED with no record never"
                                + " commits (%s)", asOwner ? "owner" : "application")
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining(DEFERRED);
                writer.rollback();
            }
            try (Connection reader = application()) {
                assertThat(count(reader, "SELECT count(*) FROM reconciliation.break WHERE id IN"
                                + " (?, ?)", closed, born))
                        .as("the refused transactions rolled back whole")
                        .isZero();
                reader.rollback();
            }
        }
    }

    @Test
    @DisplayName("a RESOLVED event with no resolution_id is refused at INSERT, for the"
            + " application and the owner alike; other event types stay free of the link")
    void aResolvedEventWithoutItsResolutionIsRefusedAtInsert() throws SQLException {
        for (boolean asOwner : new boolean[] {false, true}) {
            try (Connection writer = asOwner ? migrator() : application()) {
                try {
                    UUID breakId = seedDecisionBreak(writer, UUID.randomUUID());
                    assertThat((Throwable) refused(writer, EVENT, breakId, "RESOLVED", null))
                            .as("V015 insert trigger: a RESOLVED edge names its resolution")
                            .hasMessageContaining(AT_INSERT);
                    execute(writer, EVENT, breakId, "ASSIGNED", null);
                    assertThat(count(writer, "SELECT count(*) FROM reconciliation.break_event"
                                    + " WHERE break_id = ? AND event_type = 'ASSIGNED' AND"
                                    + " resolution_id IS NULL", breakId))
                            .as("an ASSIGNED event needs no resolution")
                            .isEqualTo(1);
                } finally {
                    writer.rollback();
                }
            }
        }
    }

    @Test
    @DisplayName("a RESOLVED event naming a resolution that is still PROPOSED at commit is"
            + " refused; the same closure commits once a second person approves it")
    void theNamedResolutionMustBeApprovedAtCommit() throws SQLException {
        for (boolean asOwner : new boolean[] {false, true}) {
            try (Connection writer = asOwner ? migrator() : application()) {
                UUID breakId = seedDecisionBreak(writer, UUID.randomUUID());
                UUID resolution = proposeAcknowledgement(writer, breakId);
                resolveRaw(writer, breakId, resolution);
                assertThatThrownBy(writer::commit,
                        "V015 deferred trigger: the named resolution is PROPOSED, never"
                                + " approved - the break does not close on it (%s)",
                        asOwner ? "owner" : "application")
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining(DEFERRED);
                writer.rollback();
            }
        }
        try (Connection app = application()) {
            try {
                UUID breakId = seedDecisionBreak(app, UUID.randomUUID());
                UUID resolution = proposeAcknowledgement(app, breakId);
                resolveRaw(app, breakId, resolution);
                execute(app, "UPDATE reconciliation.resolution SET status = 'APPROVED',"
                        + " decided_by = 'op-approver', decided_by_type = 'EMPLOYEE',"
                        + " decided_at = now(), status_changed_at = now() WHERE id = ?",
                        resolution);
                immediately(app);
                assertThat(count(app, "SELECT count(*) FROM reconciliation.break WHERE id = ?"
                                + " AND status = 'RESOLVED'", breakId))
                        .as("approved later in the same transaction, the closure stands at"
                                + " commit - the repudiation's ordering")
                        .isEqualTo(1);
            } finally {
                app.rollback();
            }
        }
    }

    @Test
    @DisplayName("the link is a real resolution: an event naming no stored resolution is"
            + " refused by the deferred foreign key")
    void theLinkIsARealResolution() throws SQLException {
        try (Connection app = application()) {
            UUID breakId = seedDecisionBreak(app, UUID.randomUUID());
            execute(app, EVENT, breakId, "ASSIGNED", UUID.randomUUID());
            assertThatThrownBy(app::commit,
                    "V015 foreign key: break_event.resolution_id names a stored resolution")
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("break_event_resolution_fk");
            app.rollback();
        }
    }

    // ================================================================= seeding

    /** Columns: break_id, event_type, resolution_id. */
    private static final String EVENT =
            "INSERT INTO reconciliation.break_event (break_id, event_type, actor, actor_type,"
                    + " reason, detail, occurred_at, correlation_id, resolution_id) VALUES (?, ?,"
                    + " 'p8doc1-probe', 'EMPLOYEE', NULL, 'p8doc1 raw probe', now(),"
                    + " 'p8doc1-v015', ?)";

    /** OPEN -> RESOLVED with its RESOLVED event naming {@code resolution}. */
    private static void resolveRaw(Connection connection, UUID breakId, UUID resolution)
            throws SQLException {
        execute(connection, "UPDATE reconciliation.break SET status = 'RESOLVED', resolved_at"
                + " = now(), status_changed_at = now() WHERE id = ?", breakId);
        execute(connection, EVENT, breakId, "RESOLVED", resolution);
    }

    /** A four-eyes zero ACKNOWLEDGE, PROPOSED (a diverged replay's - never one person's). */
    private static UUID proposeAcknowledgement(Connection connection, UUID breakId)
            throws SQLException {
        UUID resolution = UUID.randomUUID();
        execute(connection, "INSERT INTO reconciliation.resolution (id, break_id, kind, status,"
                + " reason_code, narrative, four_eyes, proposed_amount_minor, currency, scale,"
                + " residual_version, rule_set_id, proposed_by, proposed_by_type, proposed_at,"
                + " created_at, status_changed_at, correlation_id) VALUES (?, ?, 'ACKNOWLEDGE',"
                + " 'PROPOSED', 'INTERNAL_PROCESSING_ERROR', 'p8doc1 raw probe', true, 0, 'EUR',"
                + " 2, 0, ?, 'op-proposer', 'EMPLOYEE', now(), now(), now(), 'p8doc1-v015')",
                resolution, breakId, RULE_SET);
        return resolution;
    }

    private static UUID seedDecisionBreak(Connection connection, UUID source)
            throws SQLException {
        return seedDecisionBreak(connection, source, "OPEN");
    }

    /** A diverged replay's PROCESSING_ERROR on a RUN decision over its own item. */
    private static UUID seedDecisionBreak(Connection connection, UUID source, String status)
            throws SQLException {
        UUID run = UUID.randomUUID();
        execute(connection, "INSERT INTO reconciliation.reconciliation_batch (id, source_id,"
                + " batch_id, kind, rule_set_id, business_date, source_sequence, item_count,"
                + " created_at, status_changed_at, correlation_id) VALUES (?, ?, ?, 'BATCH', ?,"
                + " ?, 1, 1, now(), now(), 'p8doc1-v015')",
                run, source, UUID.randomUUID(), RULE_SET, BUSINESS_DATE);
        UUID item = UUID.randomUUID();
        execute(connection, "INSERT INTO reconciliation.external_item (id, run_id, source_id,"
                + " settlement_line_id, line_no, line_type, direction, amount_minor, currency,"
                + " scale, position_purpose, business_date, settlement_date, value_date,"
                + " canonical_fingerprint, created_at, status_changed_at, correlation_id)"
                + " VALUES (?, ?, ?, ?, 1, 'CAPTURE', 'INBOUND', 1000, 'EUR', 2,"
                + " 'SETTLEMENT_CLEARING', ?, ?, ?, ?, now(), now(), 'p8doc1-v015')",
                item, run, source, UUID.randomUUID(), BUSINESS_DATE, BUSINESS_DATE,
                BUSINESS_DATE, digest());
        UUID decision = UUID.randomUUID();
        execute(connection, "INSERT INTO reconciliation.match_decision (id, external_item_id,"
                + " run_id, origin, rule_set_id, rule_priority, strategy, matched_key_kind,"
                + " outcome, decided_by, decided_by_type, decided_at, decided_on, correlation_id,"
                + " verdict, judged_status, judged_minor, fingerprint_seen_earlier) VALUES (?, ?,"
                + " ?, 'RUN', ?, 1, 'ONE_TO_ONE', 'PSP_CAPTURE_REF', 'MATCHED', 'system',"
                + " 'SYSTEM', now(), ?, 'p8doc1-v015', 'ALLOCATE', 'PENDING', 1000, false)",
                decision, item, run, RULE_SET, BUSINESS_DATE);
        UUID breakId = UUID.randomUUID();
        boolean resolved = "RESOLVED".equals(status);
        execute(connection, "INSERT INTO reconciliation.break (id, type, cause, status,"
                + " severity, source_id, rule_set_id, decision_id, value_at_issue_minor,"
                + " currency, scale, raised_at, resolved_at, status_changed_at, correlation_id)"
                + " VALUES (?, 'PROCESSING_ERROR', 'REPLAY_DIVERGED', ?, 'CRITICAL', ?, ?, ?, 0,"
                + " 'EUR', 2, now(), " + (resolved ? "now()" : "NULL") + ", now(),"
                + " 'p8doc1-v015')",
                breakId, status, source, RULE_SET, decision);
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

    /** The deferred checks, run now exactly as at commit - the transaction is then rolled back. */
    private static void immediately(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET CONSTRAINTS ALL IMMEDIATE");
        }
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

    private static String migration() {
        try (InputStream in =
                ReconciliationV015MigrationTest.class.getClassLoader().getResourceAsStream(V015)) {
            assertThat(in).as("the migration is on the classpath").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
