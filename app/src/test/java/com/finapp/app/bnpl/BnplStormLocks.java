package com.finapp.app.bnpl;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The BNPL storm's lock observations (`P12-TST-001`): every named lock wait seen as the ACT'S OWN backend waiting (the
 * lending storm's C17 method, the M3 lesson - counted, not only proven), and the hot rows measured (`PHASE_12_PLAN.md`
 * section 21, owner item #21, A2-F1).
 *
 * <p><strong>Observed as the application role</strong> (`P12-TSK-028`): PostgreSQL shows another role's session's wait
 * event to no one but a superuser or {@code pg_read_all_stats}; both instances connect as the application role, so the
 * observer does too - and reads only a {@code pg_stat_activity} row that itself shows the session active and waiting on a
 * {@code Lock} (the `P11-TSK-035` stale-snapshot filter).
 */
final class BnplStormLocks {

    private BnplStormLocks() {}

    // ================================================================== a named lock wait, the act's own backend

    /** A session seen blocked behind a held lock: its backend, its transaction's id (null before it writes), its statement. */
    record Waiter(int pid, String xid, String query) {

        @Override
        public String toString() {
            String statement = query == null ? "" : query.replaceAll("\\s+", " ");
            return "[pid " + pid + ", xid " + xid + ": " + statement.substring(0, Math.min(90, statement.length())) + "]";
        }
    }

    /**
     * What one held lock showed: the waiters seen behind it in a statement on the held row's table, whether the act was
     * still unanswered while the lock was held, whether the next lock in the act's order was free (it took nothing later),
     * the act's answer and the transaction that committed it - and so whether the waiter was the act's OWN backend.
     */
    record Sighting(String lock, List<Waiter> waiters, boolean unanswered, boolean laterFree,
            BnplStormWorld.Answer answer, String committedBy) {

        boolean own() {
            return committedBy != null && waiters.stream().anyMatch(waiter -> committedBy.equals(waiter.xid()));
        }

        boolean holds() {
            return own() && unanswered && laterFree;
        }

        @Override
        public String toString() {
            return lock + ": answered " + answer.status() + " " + answer.code() + ", committed by " + committedBy
                    + "; waiters " + waiters + "; unanswered while held " + unanswered + "; the later lock free "
                    + laterFree;
        }
    }

    /** One named lock wait to observe: the row to hold, the act, and how to find what it committed. */
    record Watch(String lock, String holdSql, Object holdId, String waitedTable, String laterSql, Object laterId,
            Callable<BnplStormWorld.Answer> act, String committedXminSql, Object committedId) {}

    /**
     * The held lock: {@code watch.holdSql} taken {@code FOR UPDATE} by a session of the application role; the act started;
     * every session seen blocked behind the holder in a statement on {@code waitedTable} - polled until one holds a
     * transaction id (each act claims its key before its first lock, so its blocked backend already has one) or 20 s pass;
     * then, with the lock still held, whether the act has answered and whether the next lock in its order is free to
     * {@code NOWAIT}; released; the act's answer; and the {@code xmin} of the append-only row the act committed.
     */
    static Sighting observe(Watch watch) throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(watch.holdSql())) {
                lock.setObject(1, watch.holdId());
                try (ResultSet row = lock.executeQuery()) {
                    if (!row.next()) {
                        throw new IllegalStateException(watch.lock() + ": the row to hold is absent");
                    }
                }
            }
            Future<BnplStormWorld.Answer> acting = pool.submit(watch.act());
            List<Waiter> waiting = awaitBlockedBy(pid(holder), watch.waitedTable(), acting);
            // Free at least once while the act still waits: the act, blocked on the held lock, cannot release a later one it
            // held - a sweep that holds it for a moment can (the storm's sweepers run on both instances throughout).
            boolean laterFree = watch.laterSql() == null || freeWhileWaiting(watch.laterSql(), watch.laterId(), acting);
            boolean unanswered = !acting.isDone();
            holder.rollback();
            BnplStormWorld.Answer answer = acting.get(2, TimeUnit.MINUTES);
            String committedBy = answer.status() >= 200 && answer.status() < 300
                    ? BnplStormWorld.scalar(watch.committedXminSql(), watch.committedId()) : null;
            return new Sighting(watch.lock(), waiting, unanswered, laterFree, answer, committedBy);
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

    private static List<Waiter> awaitBlockedBy(int blocker, String table, Future<?> act) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        Map<Integer, Waiter> seen = new LinkedHashMap<>();
        while (System.nanoTime() < deadline && !act.isDone()) {
            try (Connection observer = DatabaseRoles.application();
                    PreparedStatement select = observer.prepareStatement("SELECT pid, backend_xid::text, query FROM"
                            + " pg_stat_activity WHERE ? = ANY (pg_blocking_pids(pid)) AND state = 'active'"
                            + " AND wait_event_type = 'Lock'")) {
                select.setInt(1, blocker);
                try (ResultSet waiting = select.executeQuery()) {
                    while (waiting.next()) {
                        String query = waiting.getString(3);
                        if (query != null && query.contains(table)) {
                            Waiter waiter = new Waiter(waiting.getInt(1), waiting.getString(2), query);
                            seen.merge(waiter.pid(), waiter, (before, now) -> now.xid() != null ? now : before);
                        }
                    }
                }
            }
            if (seen.values().stream().anyMatch(waiter -> waiter.xid() != null)) {
                break;
            }
            Thread.sleep(20);
        }
        return List.copyOf(seen.values());
    }

    private static boolean freeWhileWaiting(String sql, Object id, Future<?> act) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            if (lockableNoWait(sql, id)) {
                return true;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline && !act.isDone());
        return false;
    }

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

    // ================================================================== the hot rows, measured

    /**
     * The hot-row sampler: every {@code period} (20 ms), as the application role, every backend waiting on a lock whose
     * tuple lock names one of the hot rows' current versions - the currency's {@code LENDING_CAPITAL} ledger account row
     * (L6) and the {@code ledger.account_balance} projection rows of {@code BNPL_MERCHANT_FEE_INCOME},
     * {@code BNPL_FEE_INCOME} and {@code BNPL_INTEREST_INCOME} (L8). A wait is one (backend, statement start) seen in one or
     * more samples; its length is from its first sighting to its last plus one period - a sampled characterisation (the
     * design's J3), per round.
     */
    static final class HotRows implements AutoCloseable {

        private static final Duration PERIOD = Duration.ofMillis(20);
        private final Map<String, String[]> rows;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread sampler;
        /** wait key -> {row name, first seen nanos, last seen nanos}. */
        private final Map<String, Object[]> waits = new ConcurrentHashMap<>();
        private volatile String round = "preparation";
        /** round -> row -> wait lengths (ms), for every round closed. */
        final Map<String, Map<String, List<Long>>> rounds = Collections.synchronizedMap(new LinkedHashMap<>());
        volatile long samples;
        volatile long samplerErrors;

        /** {@code rows}: hot row name -> {relation, key column, key}. */
        HotRows(Map<String, String[]> rows) {
            this.rows = rows;
            this.sampler = new Thread(this::sample, "bnpl-storm-hot-rows");
            sampler.setDaemon(true);
            sampler.start();
        }

        private String query() {
            StringBuilder hot = new StringBuilder();
            for (Map.Entry<String, String[]> row : rows.entrySet()) {
                hot.append(hot.length() == 0 ? "" : " UNION ALL ").append("SELECT '").append(row.getKey())
                        .append("' AS name, '").append(row.getValue()[0]).append("'::regclass AS rel, ctid FROM ")
                        .append(row.getValue()[0]).append(" WHERE ").append(row.getValue()[1]).append(" = '")
                        .append(row.getValue()[2]).append("'::uuid");
            }
            return "SELECT h.name, a.pid, a.query_start::text FROM (" + hot + ") h JOIN pg_locks l ON l.locktype = 'tuple'"
                    + " AND l.relation = h.rel AND l.page = (h.ctid::text::point)[0]::int AND l.tuple ="
                    + " (h.ctid::text::point)[1]::int JOIN pg_stat_activity a ON a.pid = l.pid WHERE a.state = 'active'"
                    + " AND a.wait_event_type = 'Lock'";
        }

        private void sample() {
            String sql = query();
            Connection observer = null;
            while (running.get()) {
                try {
                    if (observer == null || observer.isClosed()) {
                        observer = DatabaseRoles.application();
                    }
                    long now = System.nanoTime();
                    try (PreparedStatement select = observer.prepareStatement(sql); ResultSet row = select.executeQuery()) {
                        while (row.next()) {
                            String name = row.getString(1);
                            String key = round + "|" + row.getInt(2) + "|" + row.getString(3) + "|" + name;
                            waits.compute(key, (ignored, before) -> before == null
                                    ? new Object[] {name, now, now} : new Object[] {before[0], before[1], now});
                        }
                    }
                    samples++;
                    Thread.sleep(PERIOD.toMillis());
                } catch (InterruptedException interrupted) {
                    return;
                } catch (SQLException failure) {
                    samplerErrors++;
                    BnplStormWorld.closeQuietly(observer);
                    observer = null;
                } catch (RuntimeException failure) {
                    samplerErrors++;
                }
            }
            BnplStormWorld.closeQuietly(observer);
        }

        /** Closes the current round's sightings into {@code rounds} under its name and opens {@code next}. */
        synchronized void nextRound(String next) {
            String closing = round;
            round = next;
            Map<String, List<Long>> byRow = new TreeMap<>();
            for (String name : rows.keySet()) {
                byRow.put(name, new ArrayList<>());
            }
            for (Map.Entry<String, Object[]> wait : new ArrayList<>(waits.entrySet())) {
                if (wait.getKey().startsWith(closing + "|")) {
                    Object[] seen = wait.getValue();
                    long millis = TimeUnit.NANOSECONDS.toMillis((Long) seen[2] - (Long) seen[1]) + PERIOD.toMillis();
                    byRow.get((String) seen[0]).add(millis);
                    waits.remove(wait.getKey());
                }
            }
            rounds.put(closing, byRow);
        }

        /** One round's characterisation: per row, waits and their length distribution (p50, p95, max, in ms). */
        static String describe(Map<String, List<Long>> byRow) {
            StringBuilder text = new StringBuilder();
            for (Map.Entry<String, List<Long>> row : byRow.entrySet()) {
                List<Long> lengths = new ArrayList<>(row.getValue());
                Collections.sort(lengths);
                text.append(text.length() == 0 ? "" : "; ").append(row.getKey()).append(' ').append(lengths.size())
                        .append(" waits");
                if (!lengths.isEmpty()) {
                    text.append(" (p50 ").append(percentile(lengths, 50)).append(" ms, p95 ")
                            .append(percentile(lengths, 95)).append(" ms, max ").append(lengths.get(lengths.size() - 1))
                            .append(" ms)");
                }
            }
            return text.toString();
        }

        static long percentile(List<Long> sorted, int p) {
            int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
            return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
        }

        /** The whole run's totals per row. */
        Map<String, List<Long>> totals() {
            Map<String, List<Long>> all = new TreeMap<>();
            synchronized (rounds) {
                for (Map<String, List<Long>> byRow : rounds.values()) {
                    byRow.forEach((name, lengths) -> all.computeIfAbsent(name, ignored -> new ArrayList<>())
                            .addAll(lengths));
                }
            }
            return all;
        }

        @Override
        public void close() {
            running.set(false);
            sampler.interrupt();
            try {
                sampler.join(5_000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
