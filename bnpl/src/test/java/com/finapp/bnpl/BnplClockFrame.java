package com.finapp.bnpl;

import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;

/**
 * The database clock moved on demand for bnpl's servicing suites (`P12-TSK-015`; lending's {@code ClockStep},
 * `P11-TSK-036`, in a schema of bnpl's own, {@code bnplclock}, so neither module's suite drops the other's). It shadows the
 * four clock functions; a session opts in by putting {@code bnplclock} before {@code pg_catalog} on its
 * {@code search_path} - bnpl's triggers are invoker's rights with no path of their own, so the production code and the
 * production triggers run unchanged and read the shadowed clock:
 *
 * <ul>
 *   <li><strong>the frame</strong> ({@code bnplclock.frame}, set for the session): an interval added to every reading, so a
 *       transaction can stand on an instalment's due date, a fortnight or a month after the purchase;
 *   <li><strong>the step</strong> ({@code bnplclock.back}, {@code SET LOCAL} between two statements): an interval taken from
 *       every LATER {@code statement_timestamp()}/{@code clock_timestamp()} - a clock stepped back across a midnight.
 * </ul>
 *
 * <p>Only the opted-in session sees the shadow. The schema is the bootstrap superuser's, created by {@link #install} -
 * never a migration's.
 */
final class BnplClockFrame {

    private BnplClockFrame() {}

    /** Creates (or replaces) the shadow clock - idempotent. */
    static void install() {
        bootstrap("CREATE SCHEMA IF NOT EXISTS bnplclock;"
                + " GRANT USAGE ON SCHEMA bnplclock TO PUBLIC;"
                + " CREATE OR REPLACE FUNCTION bnplclock.frame() RETURNS interval LANGUAGE sql STABLE AS"
                + " $$ SELECT coalesce(nullif(pg_catalog.current_setting('bnplclock.frame', true), ''), '0')::interval $$;"
                + " CREATE OR REPLACE FUNCTION bnplclock.back() RETURNS interval LANGUAGE sql STABLE AS"
                + " $$ SELECT coalesce(nullif(pg_catalog.current_setting('bnplclock.back', true), ''), '0')::interval $$;"
                + " CREATE OR REPLACE FUNCTION bnplclock.transaction_timestamp() RETURNS timestamptz LANGUAGE sql STABLE AS"
                + " $$ SELECT pg_catalog.transaction_timestamp() + bnplclock.frame() $$;"
                + " CREATE OR REPLACE FUNCTION bnplclock.now() RETURNS timestamptz LANGUAGE sql STABLE AS"
                + " $$ SELECT pg_catalog.transaction_timestamp() + bnplclock.frame() $$;"
                + " CREATE OR REPLACE FUNCTION bnplclock.statement_timestamp() RETURNS timestamptz LANGUAGE sql STABLE AS"
                + " $$ SELECT pg_catalog.statement_timestamp() + bnplclock.frame() - bnplclock.back() $$;"
                + " CREATE OR REPLACE FUNCTION bnplclock.clock_timestamp() RETURNS timestamptz LANGUAGE sql VOLATILE AS"
                + " $$ SELECT pg_catalog.clock_timestamp() + bnplclock.frame() - bnplclock.back() $$;");
    }

    /** A fresh session of {@code role} reading the shadowed clock in {@code frame}, its transaction not yet begun. */
    static Connection framed(Connection connection, Duration frame) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path = bnplclock, pg_catalog, \"$user\", public");
            statement.execute("SET bnplclock.frame = '" + micros(frame) + " microseconds'");
        }
        connection.setAutoCommit(false);
        return connection;
    }

    /** {@code work} in one application-role transaction reading the clock in {@code frame} - committed, or rolled back. */
    static <R> R inOneTransaction(Duration frame, Function<Connection, R> work) {
        try (Connection connection = framed(DatabaseRoles.application(), frame)) {
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
            statement.execute("SET LOCAL bnplclock.back = '" + micros(by) + " microseconds'");
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** The frame that places a transaction begun now at {@code instant} on the real database clock. */
    static Duration frameTo(Instant instant) {
        try (Connection connection = DatabaseRoles.application(); Statement statement = connection.createStatement();
                ResultSet row = statement.executeQuery("SELECT floor(extract(epoch FROM (TIMESTAMPTZ '" + instant
                        + "' - pg_catalog.clock_timestamp())) * 1000000)::bigint")) {
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
