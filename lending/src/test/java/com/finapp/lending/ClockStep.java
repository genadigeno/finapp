package com.finapp.lending;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The database clock stepped back on demand, deterministically (`P11-TSK-036`).
 *
 * <p>The test database runs in a VM whose clock is corrected in steps - measured back by up to 1.7 s (ADR-0063,
 * `X-TSK-005`, `X-TSK-017`) - so {@code statement_timestamp()} can read EARLIER in a later statement of the same
 * transaction than in an earlier one, and {@code transaction_timestamp()} can stand after both. Waiting for a step is a
 * flake; this fixture makes one. It shadows the four clock functions in a schema of its own, {@code clockstep}, which a
 * session opts into by putting it before {@code pg_catalog} on its {@code search_path} - PostgreSQL resolves an unqualified
 * {@code statement_timestamp()} through the path, and the production code reads the shadowed clock:
 *
 * <p><strong>Since lending {@code V035} the lending functions name {@code pg_catalog} themselves</strong> (`P15-TSK-004`: a role's
 * path must never change a judgement's clock, and this fixture is exactly that), so a shadow can no longer reach them.
 * {@link #install} therefore re-opens the hole <em>in this test database only</em>, as the bootstrap superuser: every lending
 * function reading a qualified clock is replaced by itself with the qualifier removed, and {@link #uninstall} restores the
 * captured definition byte for byte. The triggers then run their production logic against the stepped clock, which is what
 * these suites are for; that the production definition is not reachable by a path is held by
 * {@code LendingClockIgnoresTheSearchPathDatabaseTest}.
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

    /** {@code V035}'s form of a clock call. */
    private static final Pattern QUALIFIED_CLOCK =
            Pattern.compile("pg_catalog\\.(now|statement_timestamp|clock_timestamp|transaction_timestamp)\\(");

    /** The definitions {@link #install} replaced, as the migrations left them. The database tier is one JVM, one suite at a time. */
    private static final List<String> REOPENED = new ArrayList<>();

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
        reopenLendingsClock();
    }

    /** Drops the shadow clock, and puts every lending function back exactly as the migrations left it. */
    static void uninstall() {
        restoreLendingsClock();
        bootstrap("DROP SCHEMA IF EXISTS clockstep CASCADE");
    }

    /** {@code V035}'s qualification undone, so a shadow on the opted-in session's path reaches lending's triggers again. */
    private static void reopenLendingsClock() {
        try (Connection owner = DatabaseRoles.bootstrap(); Statement statement = owner.createStatement()) {
            List<String> definitions = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery("SELECT pg_catalog.pg_get_functiondef(p.oid) FROM pg_catalog.pg_proc p"
                    + " JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = 'lending' AND p.prokind = 'f'")) {
                while (rows.next()) {
                    definitions.add(rows.getString(1));
                }
            }
            for (String definition : definitions) {
                Matcher qualified = QUALIFIED_CLOCK.matcher(definition);
                if (qualified.find()) {
                    if (!REOPENED.contains(definition)) {
                        REOPENED.add(definition);
                    }
                    statement.execute(qualified.replaceAll("$1("));
                }
            }
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void restoreLendingsClock() {
        try (Connection owner = DatabaseRoles.bootstrap(); Statement statement = owner.createStatement()) {
            for (String definition : REOPENED) {
                statement.execute(definition);
            }
            REOPENED.clear();
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
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

    /**
     * {@link #inOneTransaction}, but always rolled back (`P15-TSK-033`): for a case whose frame would leave a fact dated in the future in the shared
     * database - an offer stamped tomorrow ends its terms version after it under lending {@code V033}, and every later activation of the product would then
     * start tomorrow for the rest of the suite. Such a case asserts on {@code connection}, before the rollback.
     */
    static <R> R inOneRolledBackTransaction(Duration frame, Function<Connection, R> work) {
        try (Connection connection = DatabaseRoles.application()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET search_path = clockstep, pg_catalog, \"$user\", public");
                statement.execute("SET clockstep.frame = '" + micros(frame) + " microseconds'");
            }
            connection.setAutoCommit(false);
            try {
                return work.apply(connection);
            } finally {
                connection.rollback();
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
