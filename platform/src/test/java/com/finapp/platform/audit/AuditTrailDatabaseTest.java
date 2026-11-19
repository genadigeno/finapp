package com.finapp.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The audit trail's read on PostgreSQL (`P17-TSK-017`): the plans {@code V017}'s indexes give the window and a target's history -
 * an index range newest first with no sort, at a volume where a scan would cost - recorded in {@code OPERATIONS_RUNBOOK.md} section
 * 11.48; the keyset walk serving each record once; the late commit {@link AuditTrail} states; and the application role's lack of
 * {@code UPDATE} and {@code DELETE} on the table.
 */
@Tag("database")
@DisplayName("the audit trail's read on PostgreSQL (P17-TSK-017)")
class AuditTrailDatabaseTest {

    /** A month of history the seed spreads its volume over, years away from anything another suite writes. */
    private static final Instant SEEDED_FROM = Instant.parse("2097-01-01T00:00:00Z");
    private static final int VOLUME = 60_000;

    private final JdbcAuditTrail trail = new JdbcAuditTrail();

    @BeforeAll
    static void seedAVolume() throws SQLException {
        try (Connection root = DatabaseRoles.bootstrap(); Statement statement = root.createStatement()) {
            ResultSet seeded = statement.executeQuery("SELECT count(*) FROM platform.audit_record WHERE actor_id = 'p17-017-volume'");
            seeded.next();
            if (seeded.getLong(1) == 0) {
                statement.executeUpdate("INSERT INTO platform.audit_record (audit_id, actor_id, actor_type, occurred_at, operation,"
                        + " target_type, target_id, reason, outcome, correlation_id, change_summary)"
                        + " SELECT gen_random_uuid(), 'p17-017-volume', 'EMPLOYEE',"
                        + " TIMESTAMPTZ '2097-01-01 00:00:00+00' + (n * INTERVAL '43 seconds'), 'probe.Volume',"
                        + " 'probe.Target', 'target-' || (n % 500), NULL, 'SUCCEEDED', 'p17-017-flow-' || (n % 1000), 'n=' || n"
                        + " FROM generate_series(1, " + VOLUME + ") AS n");
            }
            statement.execute("ANALYZE platform.audit_record");
        }
    }

    @Test
    @DisplayName("a window is one index range newest first, no sort; a target's history its own; plans printed for the runbook")
    void theIndexesServeTheReads() throws SQLException {
        String window = plan("SELECT audit_id FROM platform.audit_record WHERE occurred_at >= TIMESTAMPTZ '2097-01-10 00:00:00+00'"
                + " AND occurred_at < TIMESTAMPTZ '2097-01-20 00:00:00+00' ORDER BY occurred_at DESC, audit_id DESC LIMIT 51");
        String target = plan("SELECT audit_id FROM platform.audit_record WHERE occurred_at >= TIMESTAMPTZ '2097-01-01 00:00:00+00'"
                + " AND occurred_at < TIMESTAMPTZ '2097-01-31 00:00:00+00' AND target_type = 'probe.Target' AND target_id = 'target-7'"
                + " ORDER BY occurred_at DESC, audit_id DESC LIMIT 51");
        String continued = plan("SELECT audit_id FROM platform.audit_record WHERE occurred_at >= TIMESTAMPTZ '2097-01-10 00:00:00+00'"
                + " AND occurred_at < TIMESTAMPTZ '2097-01-20 00:00:00+00' AND (occurred_at, audit_id) <"
                + " (TIMESTAMPTZ '2097-01-15 00:00:00+00', '00000000-0000-0000-0000-000000000000'::uuid)"
                + " ORDER BY occurred_at DESC, audit_id DESC LIMIT 51");
        System.out.println("P17-TSK-017 plan, window:\n" + window + "\nP17-TSK-017 plan, target:\n" + target
                + "\nP17-TSK-017 plan, continued:\n" + continued);

        assertThat(window).contains("audit_record_by_time").contains("Backward").doesNotContain("Sort").doesNotContain("Seq Scan");
        assertThat(target).contains("audit_record_by_target_time").contains("Backward").doesNotContain("Sort")
                .doesNotContain("Seq Scan");
        assertThat(continued).contains("audit_record_by_time").doesNotContain("Sort").doesNotContain("Seq Scan");
    }

    @Test
    @DisplayName("a keyset walk serves each record of the window once, newest first, the id breaking the tie")
    void aWalkServesEachRecordOnce() throws SQLException {
        String actor = "p17-017-walk-" + UUID.randomUUID();
        Instant base = Instant.parse("2098-03-01T00:00:00Z");
        try (Connection root = DatabaseRoles.bootstrap()) {
            for (int i = 0; i < 23; i++) {
                // Three records share each second, so the id is what orders them.
                insert(root, actor, base.plusSeconds(i / 3), "flow-" + actor);
            }
        }
        AuditTrail.Filter filter = filter(base, base.plusSeconds(3600), Optional.of(actor), Optional.empty());

        List<AuditTrail.Row> walked = walk(filter, 4);

        assertThat(walked).hasSize(23);
        assertThat(new HashSet<>(walked.stream().map(AuditTrail.Row::auditId).toList())).hasSize(23);
        // PostgreSQL orders uuid by its bytes, which is the lowercase hex text's order - not UUID.compareTo's signed longs.
        assertThat(walked).isSortedAccordingTo(Comparator.comparing(AuditTrail.Row::occurredAt)
                .thenComparing((AuditTrail.Row row) -> row.auditId().toString()).reversed());
        assertThat(walk(filter(base, base.plusSeconds(3600), Optional.of(actor), Optional.of("probe.Other")), 4))
                .as("a filter narrows the window").isEmpty();
    }

    @Test
    @DisplayName("a record committed late, stamped inside a range a walk already served, is in no later page of that walk; a"
            + " fresh walk shows it")
    void aLateCommitIsSeenByAFreshWalk() throws SQLException {
        String actor = "p17-017-late-" + UUID.randomUUID();
        Instant base = Instant.parse("2098-04-01T00:00:00Z");
        try (Connection root = DatabaseRoles.bootstrap()) {
            for (int i = 0; i < 10; i++) {
                insert(root, actor, base.plusSeconds(i * 10L), "flow-" + actor);
            }
        }
        AuditTrail.Filter filter = filter(base, base.plusSeconds(3600), Optional.of(actor), Optional.empty());
        List<AuditTrail.Row> first;
        try (Connection app = DatabaseRoles.application()) {
            first = trail.page(app, filter, Optional.empty(), 4);
        }
        AuditTrail.Row tail = first.getLast();
        UUID late;
        try (Connection root = DatabaseRoles.bootstrap()) {
            // Stamped just after the cursor: inside the range the first page already served.
            late = insert(root, actor, tail.occurredAt().plusMillis(1), "flow-" + actor);
        }

        List<AuditTrail.Row> rest = new ArrayList<>();
        Optional<AuditTrail.Cursor> after = Optional.of(new AuditTrail.Cursor(tail.occurredAt(), tail.auditId()));
        try (Connection app = DatabaseRoles.application()) {
            while (true) {
                List<AuditTrail.Row> page = trail.page(app, filter, after, 4);
                if (page.isEmpty()) {
                    break;
                }
                rest.addAll(page);
                after = Optional.of(new AuditTrail.Cursor(page.getLast().occurredAt(), page.getLast().auditId()));
            }
        }

        assertThat(rest).extracting(AuditTrail.Row::auditId).doesNotContain(late);
        assertThat(walk(filter, 4)).extracting(AuditTrail.Row::auditId).contains(late);
    }

    @Test
    @DisplayName("the application role can read the trail and append to it, never update or delete it (42501)")
    void theApplicationRoleCannotRewriteTheTrail() throws SQLException {
        try (Connection app = DatabaseRoles.application(); Statement statement = app.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE platform.audit_record SET reason = 'rewritten' WHERE actor_id = 'p17-017-volume'"))
                    .isInstanceOfSatisfying(SQLException.class, refused -> assertThat(refused.getSQLState()).isEqualTo("42501"));
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM platform.audit_record WHERE actor_id = 'p17-017-volume'"))
                    .isInstanceOfSatisfying(SQLException.class, refused -> assertThat(refused.getSQLState()).isEqualTo("42501"));
        }
    }

    // ------------------------------------------------------------------------------------------- plumbing

    private List<AuditTrail.Row> walk(AuditTrail.Filter filter, int size) throws SQLException {
        List<AuditTrail.Row> walked = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        Optional<AuditTrail.Cursor> after = Optional.empty();
        try (Connection app = DatabaseRoles.application()) {
            while (true) {
                List<AuditTrail.Row> page = trail.page(app, filter, after, size);
                assertThat(page.size()).isLessThanOrEqualTo(size);
                if (page.isEmpty()) {
                    return walked;
                }
                for (AuditTrail.Row row : page) {
                    assertThat(seen.add(row.auditId())).as("served once").isTrue();
                }
                walked.addAll(page);
                after = Optional.of(new AuditTrail.Cursor(page.getLast().occurredAt(), page.getLast().auditId()));
            }
        }
    }

    private static AuditTrail.Filter filter(Instant from, Instant to, Optional<String> actor, Optional<String> operation) {
        return new AuditTrail.Filter(from, to, actor, operation, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static UUID insert(Connection root, String actor, Instant at, String correlation) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = root.prepareStatement("INSERT INTO platform.audit_record (audit_id, actor_id, actor_type,"
                + " occurred_at, operation, target_type, target_id, reason, outcome, correlation_id, change_summary)"
                + " VALUES (?, ?, 'EMPLOYEE', ?, 'probe.Walk', 'probe.Target', 'walk', NULL, 'SUCCEEDED', ?, NULL)")) {
            insert.setObject(1, id);
            insert.setString(2, actor);
            insert.setTimestamp(3, Timestamp.from(at));
            insert.setString(4, correlation);
            insert.executeUpdate();
        }
        return id;
    }

    private static String plan(String sql) throws SQLException {
        try (Connection app = DatabaseRoles.application(); Statement statement = app.createStatement();
                ResultSet rows = statement.executeQuery("EXPLAIN " + sql)) {
            StringBuilder plan = new StringBuilder();
            while (rows.next()) {
                plan.append(rows.getString(1)).append('\n');
            }
            return plan.toString();
        }
    }
}
