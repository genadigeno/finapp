package com.finapp.lending;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.function.Function;

/**
 * The database clock stepped back on demand, deterministically (`P11-TSK-036`).
 *
 * <p>The test database runs in a VM whose clock is corrected in steps - measured back by up to 1.7 s (ADR-0063,
 * `X-TSK-005`, `X-TSK-017`) - so {@code statement_timestamp()} can read EARLIER in a later statement of the same
 * transaction than in an earlier one, and {@code transaction_timestamp()} can stand after both. Waiting for a step is a
 * flake; this fixture makes one. It shadows the four clock functions in a schema of its own, {@code clockstep}, which a
 * session opts into by putting it before {@code pg_catalog} on its {@code search_path} - PostgreSQL resolves an unqualified
 * {@code statement_timestamp()} through the path, and the lending triggers are invoker's rights with no path of their own,
 * so the production code and the production triggers run unchanged and read the shadowed clock:
 *
 * <ul>
 *   <li><strong>the frame</strong> ({@code clockstep.frame}, set for the session before its transaction begins): an
 *       interval added to every reading - {@code transaction_timestamp()}/{@code now()} included - so a transaction can be
 *       placed just after a midnight;
 *   <li><strong>the step</strong> ({@code clockstep.back}, {@code SET LOCAL} between two statements): an interval taken
 *       from every LATER {@code statement_timestamp()}/{@code clock_timestamp()} - never from
 *       {@code transaction_timestamp()}, which PostgreSQL fixed when the transaction began, exactly as a real step leaves
 *       it.
 * </ul>
 *
 * <p>A store is stepped between the step's clock read and its writes by {@link #afterRead} (the read sees the clock, every
 * later statement a clock {@code by} behind it) or before the read by {@link #beforeRead} (the transaction's start ahead of
 * every statement). Only the opted-in session sees the shadow; every other connection reads the real clock. The schema is
 * the bootstrap superuser's, created by {@link #install} and dropped by {@link #uninstall} - never a migration's.
 */
final class ClockStep {

    /** The step: larger than the largest step measured on the VM (1.7 s), so no real step can undo a planted one. */
    static final Duration STEP = Duration.ofSeconds(2);

    /** Where a frame places a transaction's start after a midnight - so a step of {@link #STEP} crosses it. */
    static final Duration AFTER_MIDNIGHT = Duration.ofMillis(500);

    private ClockStep() {}

    /** Creates (or replaces) the shadow clock - idempotent. */
    static void install() {
        bootstrap("CREATE SCHEMA IF NOT EXISTS clockstep;"
                + " GRANT USAGE ON SCHEMA clockstep TO PUBLIC;"
                + " CREATE OR REPLACE FUNCTION clockstep.frame() RETURNS interval LANGUAGE sql STABLE AS"
                + " $$ SELECT coalesce(nullif(pg_catalog.current_setting('clockstep.frame', true), ''), '0')::interval $$;"
                + " CREATE OR REPLACE FUNCTION clockstep.back() RETURNS interval LANGUAGE sql STABLE AS"
                + " $$ SELECT coalesce(nullif(pg_catalog.current_setting('clockstep.back', true), ''), '0')::interval $$;"
                + " CREATE OR REPLACE FUNCTION clockstep.transaction_timestamp() RETURNS timestamptz LANGUAGE sql STABLE AS"
                + " $$ SELECT pg_catalog.transaction_timestamp() + clockstep.frame() $$;"
                + " CREATE OR REPLACE FUNCTION clockstep.now() RETURNS timestamptz LANGUAGE sql STABLE AS"
                + " $$ SELECT pg_catalog.transaction_timestamp() + clockstep.frame() $$;"
                + " CREATE OR REPLACE FUNCTION clockstep.statement_timestamp() RETURNS timestamptz LANGUAGE sql STABLE AS"
                + " $$ SELECT pg_catalog.statement_timestamp() + clockstep.frame() - clockstep.back() $$;"
                + " CREATE OR REPLACE FUNCTION clockstep.clock_timestamp() RETURNS timestamptz LANGUAGE sql VOLATILE AS"
                + " $$ SELECT pg_catalog.clock_timestamp() + clockstep.frame() - clockstep.back() $$;");
    }

    /** Drops the shadow clock. */
    static void uninstall() {
        bootstrap("DROP SCHEMA IF EXISTS clockstep CASCADE");
    }

    /**
     * {@code work} in one transaction of a fresh application-role session reading the shadowed clock in {@code frame} -
     * committed, or rolled back and rethrown.
     */
    static <R> R inOneTransaction(Duration frame, Function<Connection, R> work) {
        try (Connection connection = DatabaseRoles.application()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET search_path = clockstep, pg_catalog, \"$user\", public");
                statement.execute("SET clockstep.frame = '" + micros(frame) + " microseconds'");
            }
            connection.setAutoCommit(false);
            try {
                R result = work.apply(connection);
                connection.commit();
                return result;
            } catch (RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Steps {@code connection}'s clock back by {@code by} for every later statement of its transaction. */
    static void stepBack(Connection connection, Duration by) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL clockstep.back = '" + micros(by) + " microseconds'");
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /**
     * {@code delegate}, its {@code databaseNow(Connection)} answered from the clock and the clock then stepped back by
     * {@code by}: the step falls between the step's clock read and everything it writes after it.
     */
    static <S> S afterRead(Class<S> type, S delegate, Duration by) {
        return stepping(type, delegate, by, false);
    }

    /**
     * {@code delegate}, the clock stepped back by {@code by} just before its {@code databaseNow(Connection)}: the step falls
     * between the transaction's start and the step's clock read.
     */
    static <S> S beforeRead(Class<S> type, S delegate, Duration by) {
        return stepping(type, delegate, by, true);
    }

    private static <S> S stepping(Class<S> type, S delegate, Duration by, boolean before) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            boolean read = method.getName().equals("databaseNow") && args != null && args.length == 1
                    && args[0] instanceof Connection;
            if (read && before) {
                stepBack((Connection) args[0], by);
            }
            Object answer;
            try {
                answer = method.invoke(delegate, args);
            } catch (InvocationTargetException thrown) {
                throw thrown.getCause();
            }
            if (read && !before) {
                stepBack((Connection) args[0], by);
            }
            return answer;
        }));
    }

    /**
     * The frame that places a transaction begun now {@link #AFTER_MIDNIGHT} after the LAST midnight UTC: dates stay the
     * real clock's (a fixture's dates hold), and a step of {@link #STEP} after the start lands on the day before.
     */
    static Duration afterTodaysMidnight() {
        return frameTo("date_trunc('day', pg_catalog.clock_timestamp() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'");
    }

    /**
     * The frame that places a transaction begun now {@link #AFTER_MIDNIGHT} after the NEXT midnight UTC: every stamp later
     * than the real clock's, and a step of {@link #STEP} before the clock's read lands back on today.
     */
    static Duration afterTomorrowsMidnight() {
        return frameTo("(date_trunc('day', pg_catalog.clock_timestamp() AT TIME ZONE 'UTC') + interval '1 day')"
                + " AT TIME ZONE 'UTC'");
    }

    private static Duration frameTo(String midnightSql) {
        try (Connection connection = DatabaseRoles.application(); Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT floor(extract(epoch FROM (" + midnightSql
                        + " + interval '" + AFTER_MIDNIGHT.toMillis() + " milliseconds') - pg_catalog.clock_timestamp())"
                        + " * 1000000)::bigint")) {
            row.next();
            return Duration.ofNanos(row.getLong(1) * 1_000);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static long micros(Duration duration) {
        return duration.toNanos() / 1_000;
    }

    private static void bootstrap(String sql) {
        try (Connection owner = DatabaseRoles.bootstrap(); Statement statement = owner.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
