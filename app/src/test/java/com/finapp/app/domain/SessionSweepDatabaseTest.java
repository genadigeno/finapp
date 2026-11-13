package com.finapp.app.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.SessionRetention;
import com.finapp.platform.retention.RetentionSweep;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The session sweep on PostgreSQL, as the application role (`P16-TSK-010`; identity {@code V027}). Written and <strong>owed</strong> to
 * the database pass (the owner's word of 2026-11-13; PI-023's kind): nothing in this class has run. A short container check ran the
 * function once on PostgreSQL 18.6 by hand (two rows dead for days swept; a live row, a row dead thirty seconds and a row revoked a
 * minute ago kept; a grace under a minute refused), which is not this class.
 *
 * <p>The table is shared with every suite: each case plants rows under an identity of its own and counts what is due from the table
 * rather than assuming it.
 */
@Tag("database")
@DisplayName("the session sweep on PostgreSQL: never a live session, ten sweepers disjoint (P16-TSK-010)")
class SessionSweepDatabaseTest {

    private static final Duration GRACE = Duration.ofDays(30);

    @Test
    @DisplayName("only rows dead past the grace go: a live session, one dead inside the grace and one revoked a minute ago stay")
    void neverALiveSession() throws SQLException {
        UUID identity = identity();
        UUID live = session(identity, "ACTIVE", "now() - interval '1 minute'", "now() + interval '29 minutes'", null);
        UUID deadInsideGrace = session(identity, "ACTIVE", "now() - interval '20 days'", "now() - interval '10 days'", null);
        UUID revokedRecently = session(identity, "REVOKED", "now() - interval '5 minutes'", "now() + interval '25 minutes'", "now()");
        UUID dead = session(identity, "ACTIVE", "now() - interval '60 days'", "now() - interval '59 days'", null);
        UUID revokedLongAgo = session(identity, "REVOKED", "now() - interval '60 days'", "now() - interval '59 days'", "now() - interval '60 days'");

        sweepUntilDone(SessionRetention.sweep(DatabaseRoles::application, GRACE, 1000));

        assertThat(remaining(identity)).containsExactlyInAnyOrder(live, deadInsideGrace, revokedRecently);
        assertThat(remaining(identity)).doesNotContain(dead, revokedLongAgo);
    }

    @Test
    @DisplayName("ten sweepers at once delete every due row once, between them")
    void tenSweepersDeleteDisjointBatches() throws Exception {
        UUID identity = identity();
        for (int i = 0; i < 300; i++) {
            session(identity, i % 3 == 0 ? "REVOKED" : "ACTIVE", "now() - interval '60 days'", "now() - interval '59 days'",
                    i % 3 == 0 ? "now() - interval '60 days'" : null);
        }
        long due = due();
        assertThat(due).isGreaterThanOrEqualTo(300);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Future<Integer>> sweepers = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                sweepers.add(pool.submit((Callable<Integer>) () -> {
                    start.await();
                    return sweepUntilDone(SessionRetention.sweep(DatabaseRoles::application, GRACE, 7));
                }));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> sweeper : sweepers) {
                total += sweeper.get();
            }
            assertThat((long) total).as("the ten deleted every due row once between them").isEqualTo(due);
        } finally {
            pool.shutdownNow();
        }
        assertThat(remaining(identity)).isEmpty();
    }

    @Test
    @DisplayName("a row another transaction holds is passed over at once, and a revocation racing the sweep finds nothing to revive")
    void aHeldRowIsSkipped() throws SQLException {
        UUID identity = identity();
        UUID held = session(identity, "ACTIVE", "now() - interval '60 days'", "now() - interval '59 days'", null);
        UUID free = session(identity, "ACTIVE", "now() - interval '60 days'", "now() - interval '59 days'", null);
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            try (PreparedStatement revoke = holder.prepareStatement(
                    "UPDATE identity.session SET status = 'REVOKED', revoked_at = now() WHERE id = ? AND status = 'ACTIVE'")) {
                revoke.setObject(1, held);
                revoke.executeUpdate();
            }
            sweepUntilDone(SessionRetention.sweep(DatabaseRoles::application, GRACE, 1000));
            assertThat(remaining(identity)).as("the held row skipped, the free one swept").containsExactly(held);
            holder.commit();
        }
        sweepUntilDone(SessionRetention.sweep(DatabaseRoles::application, GRACE, 1000));
        assertThat(remaining(identity)).as("revoked, it is still dead past the grace, and goes").isEmpty();
        assertThat(free).isNotNull();
    }

    // ------------------------------------------------------------------------------------------------------------------

    private static int sweepUntilDone(RetentionSweep sweep) {
        int total = 0;
        for (int deleted = sweep.sweepBatch(); deleted > 0; deleted = sweep.sweepBatch()) {
            total += deleted;
        }
        return total;
    }

    private static UUID identity() throws SQLException {
        UUID party = UUID.randomUUID();
        UUID identity = UUID.randomUUID();
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO party.party (id, kind, display_name, registered_at) VALUES (?, 'PERSON', 'Session Sweep', now())", party);
            execute(app, "INSERT INTO identity.identity (id, party_id, login_identifier, status, created_at, status_changed_at)"
                    + " VALUES (?, ?, ?, 'ACTIVE', now() - interval '90 days', now() - interval '90 days')",
                    identity, party, "sweep" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        }
        return identity;
    }

    /**
     * One session row, written as the application with its bounds stated: {@code live_from} one second before the idle bound's
     * origin, the absolute bound twelve hours after {@code live_from}, so V005's and V016's checks hold for every row planted here.
     */
    private static UUID session(UUID identity, String status, String liveFrom, String idleExpires, String revokedAt) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection app = DatabaseRoles.application()) {
            execute(app, "INSERT INTO identity.session (id, identity_id, token_hash, assurance, status, issued_at, idle_expires_at,"
                    + " absolute_expires_at, device, revoked_at, live_from)"
                    + " VALUES (?, ?, ?, 'PASSWORD', '" + status + "', " + liveFrom + ", " + idleExpires + ", greatest(" + idleExpires + ", "
                    + liveFrom + " + interval '12 hours'), NULL, " + (revokedAt == null ? "NULL" : revokedAt) + ", " + liveFrom + ")",
                    id, identity, "sweep-" + id);
        }
        return id;
    }

    private static long due() throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement count = app.prepareStatement(
                        "SELECT count(*) FROM identity.session WHERE status IN ('ACTIVE', 'REVOKED')"
                                + " AND idle_expires_at < now() - (? * INTERVAL '1 millisecond')")) {
            count.setLong(1, GRACE.toMillis());
            try (ResultSet rows = count.executeQuery()) {
                rows.next();
                return rows.getLong(1);
            }
        }
    }

    private static List<UUID> remaining(UUID identity) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement select = app.prepareStatement("SELECT id FROM identity.session WHERE identity_id = ?")) {
            select.setObject(1, identity);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getObject(1, UUID.class));
                }
            }
        }
        return ids;
    }

    private static void execute(Connection connection, String sql, Object... arguments) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 1, arguments[i]);
            }
            statement.executeUpdate();
        }
    }
}
