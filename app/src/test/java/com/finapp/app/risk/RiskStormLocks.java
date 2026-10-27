package com.finapp.app.risk;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The risk storm's lock observations (`P13-TST-001`; the BNPL storm's {@code BnplStormLocks} - the C17 method and the M3
 * lesson: counted, not only proven):
 *
 * <ul>
 *   <li><strong>A named lock wait seen as the act's own backend</strong> ({@link #observe}): a row held by a session of
 *       the application role, the act started, the sessions seen blocked behind the holder in a statement on the held
 *       row's table (as the application role reads them - the `P12-TSK-028` lesson), whether the act was still
 *       unanswered while the lock was held, and - once released - the transaction that committed the act's row: the
 *       waiter whose {@code backend_xid} is that row's {@code xmin} is the act's own backend.
 *   <li><strong>The waits sampled</strong> ({@link Sampler}): every 25 ms, every backend waiting on a heavyweight lock
 *       whose relation is one of risk's named lock rows - R1's {@code risk_policy_version}, R4's {@code risk_case},
 *       R7's {@code monitoring_watermark} - one wait per (backend, statement start), counted per relation.
 *   <li><strong>The deadlocks</strong> ({@link #deadlocks}): {@code pg_stat_database.deadlocks} of this database.
 * </ul>
 */
final class RiskStormLocks {

    private RiskStormLocks() {}

    /** {@code pg_stat_database.deadlocks} for the current database - read as the bootstrap role (stats). */
    static long deadlocks() throws SQLException {
        try (Connection owner = DatabaseRoles.bootstrap();
                PreparedStatement read = owner.prepareStatement(
                        "SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()");
                ResultSet row = read.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    // ================================================================== a named wait, the act's own backend

    /**
     * A session seen blocked behind a held lock: its backend, its transaction's id (null before it writes), its pool's
     * application name, its statement.
     */
    record Waiter(int pid, String xid, String application, String query) {}

    /** What one held lock showed. */
    record Sighting(String lock, List<Waiter> waiters, boolean unanswered, Object answer, String committedBy,
            String actsOn) {

        /**
         * The act's own backend among the waiters: the one whose {@code backend_xid} is the committed row's {@code xmin},
         * or - a row written inside a savepoint carries its subtransaction's id, never the top-level one the activity
         * view shows - the one waiter of the act's own instance's pool queued in the held lock's statement.
         */
        boolean own() {
            if (committedBy == null) {
                return false;
            }
            if (waiters.stream().anyMatch(waiter -> committedBy.equals(waiter.xid()))) {
                return true;
            }
            return actsOn != null && waiters.stream().filter(waiter -> actsOn.equals(waiter.application())).count() == 1;
        }

        boolean holds() {
            return own() && unanswered;
        }

        @Override
        public String toString() {
            StringBuilder seen = new StringBuilder();
            for (Waiter waiter : waiters) {
                seen.append(" [").append(waiter.pid()).append(" xid ").append(waiter.xid()).append(' ')
                        .append(waiter.application()).append(": ")
                        .append(waiter.query().substring(0, Math.min(90, waiter.query().length()))).append(']');
            }
            return lock + ": committed by " + committedBy + "; waiters " + waiters.size() + seen + "; unanswered while"
                    + " held " + unanswered + "; own " + own();
        }
    }

    /**
     * Holds {@code holdSql} ({@code ... FOR UPDATE}, one parameter) on a session of the application role, starts
     * {@code act}, waits until a session blocked behind the holder in a statement naming {@code waitedTable} holds a
     * transaction id (each act writes before its risk lock, so its blocked backend has one) or 20 s pass, records whether
     * the act is still unanswered, releases, takes the act's answer, then reads the {@code xmin} of the row
     * {@code committedXminSql} names (one parameter, {@code committedId} read off the answer by {@code idOf}).
     */
    static Sighting observe(String lock, String holdSql, Object holdId, String waitedTable, Callable<Object> act,
            java.util.function.Function<Object, Object> idOf, String committedXminSql) throws Exception {
        return observe(lock, holdSql, holdId, waitedTable, act, idOf, committedXminSql, null);
    }

    /** {@link #observe}, the act sent to the instance whose pool is named {@code actsOn}. */
    static Sighting observe(String lock, String holdSql, Object holdId, String waitedTable, Callable<Object> act,
            java.util.function.Function<Object, Object> idOf, String committedXminSql, String actsOn) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            try (PreparedStatement hold = holder.prepareStatement(holdSql)) {
                hold.setObject(1, holdId);
                try (ResultSet row = hold.executeQuery()) {
                    if (!row.next()) {
                        throw new IllegalStateException(lock + ": the row to hold is absent");
                    }
                }
            }
            Future<Object> acting = pool.submit(act);
            List<Waiter> waiting = awaitBlockedBy(pid(holder), waitedTable, acting);
            boolean unanswered = !acting.isDone();
            holder.rollback();
            Object answer = acting.get(2, TimeUnit.MINUTES);
            Object id = idOf.apply(answer);
            String committedBy = id == null ? null : RiskStormWorld.scalar(committedXminSql, id);
            return new Sighting(lock, waiting, unanswered, answer, committedBy, actsOn);
        } finally {
            pool.shutdownNow();
        }
    }

    static int pid(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement("SELECT pg_backend_pid()");
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getInt(1);
        }
    }

    static List<Waiter> awaitBlockedBy(int blocker, String table, Future<?> act) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        Map<Integer, Waiter> seen = new LinkedHashMap<>();
        long settled = Long.MAX_VALUE;
        while (System.nanoTime() < deadline && !act.isDone() && System.nanoTime() < settled) {
            try (Connection observer = DatabaseRoles.application();
                    PreparedStatement select = observer.prepareStatement("SELECT pid, backend_xid::text, query,"
                            + " application_name FROM pg_stat_activity WHERE ? = ANY (pg_blocking_pids(pid)) AND state ="
                            + " 'active' AND wait_event_type = 'Lock'")) {
                select.setInt(1, blocker);
                try (ResultSet waiting = select.executeQuery()) {
                    while (waiting.next()) {
                        String query = waiting.getString(3);
                        if (query != null && query.contains(table)) {
                            Waiter waiter = new Waiter(waiting.getInt(1), waiting.getString(2), waiting.getString(4),
                                    query);
                            seen.merge(waiter.pid(), waiter, (before, now) -> now.xid() != null ? now : before);
                        }
                    }
                }
            }
            // A sweep may queue on the held row before the act does: settle a second more once a waiter holds an xid.
            if (settled == Long.MAX_VALUE && seen.values().stream().anyMatch(waiter -> waiter.xid() != null)) {
                settled = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            }
            Thread.sleep(20);
        }
        return List.copyOf(seen.values());
    }

    /** True when {@code sql} (one parameter, {@code ... NOWAIT}) can lock its row now. */
    static boolean lockableNoWait(String sql, Object id) throws SQLException {
        try (Connection probe = DatabaseRoles.application()) {
            probe.setAutoCommit(false);
            try (PreparedStatement lock = probe.prepareStatement(sql)) {
                lock.setObject(1, id);
                lock.executeQuery().close();
                return true;
            } catch (SQLException held) {
                if ("55P03".equals(held.getSQLState())) {
                    return false;
                }
                throw held;
            } finally {
                probe.rollback();
            }
        }
    }

    // ================================================================== the waits, sampled

    /**
     * Every 25 ms, as the bootstrap role (another role's wait events are visible to a superuser only for every
     * backend), every backend waiting on a heavyweight lock of one of the named relations - one wait per (backend,
     * statement start), counted per relation and per label (the scene the storm names when it opens a window).
     */
    static final class Sampler implements AutoCloseable {

        private static final List<String> RELATIONS = List.of("risk.risk_policy_version", "risk.risk_case",
                "risk.monitoring_watermark", "risk.risk_action", "risk.velocity_window");
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread thread;
        private final Map<String, Long> seenWaits = new ConcurrentHashMap<>();
        /** relation -> waits seen. */
        final Map<String, AtomicLong> waits = new ConcurrentHashMap<>();
        volatile long samples;
        volatile long errors;

        Sampler() {
            thread = new Thread(this::sample, "risk-storm-lock-sampler");
            thread.setDaemon(true);
            thread.start();
        }

        private void sample() {
            String sql = "SELECT a.pid, a.query_start::text, l.relation::regclass::text FROM pg_locks l JOIN"
                    + " pg_stat_activity a ON a.pid = l.pid WHERE NOT l.granted AND l.relation IS NOT NULL AND"
                    + " l.relation::regclass::text = ANY (?)";
            while (running.get()) {
                try (Connection observer = DatabaseRoles.bootstrap();
                        PreparedStatement select = observer.prepareStatement(sql)) {
                    select.setArray(1, observer.createArrayOf("text", RELATIONS.toArray()));
                    while (running.get()) {
                        try (ResultSet row = select.executeQuery()) {
                            while (row.next()) {
                                String wait = row.getInt(1) + "@" + row.getString(2) + "@" + row.getString(3);
                                if (seenWaits.putIfAbsent(wait, System.nanoTime()) == null) {
                                    waits.computeIfAbsent(row.getString(3), k -> new AtomicLong()).incrementAndGet();
                                }
                            }
                        }
                        samples++;
                        Thread.sleep(25);
                    }
                } catch (InterruptedException interrupted) {
                    return;
                } catch (SQLException failure) {
                    errors++;
                }
            }
        }

        /** Waits seen on {@code relation} (schema-qualified, e.g. {@code risk.risk_case}). */
        long of(String relation) {
            AtomicLong found = waits.get(relation);
            return found == null ? 0 : found.get();
        }

        Map<String, Long> snapshot() {
            Map<String, Long> copy = new LinkedHashMap<>();
            waits.forEach((relation, count) -> copy.put(relation, count.get()));
            return copy;
        }

        @Override
        public void close() {
            running.set(false);
            thread.interrupt();
        }
    }

    /** {@code calls} at once on a barrier: their answers (or the thrown failure) in order. */
    static List<Object> race(List<Callable<Object>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return call.call();
                    } catch (Exception failure) {
                        return failure;
                    }
                }));
            }
            start.countDown();
            List<Object> answers = new ArrayList<>();
            for (Future<Object> future : futures) {
                answers.add(future.get(3, TimeUnit.MINUTES));
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }
}
