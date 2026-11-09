package com.finapp.platform.testing.database;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.function.Function;

/**
 * The database's clock moved on demand, for one session (`P15-TSK-038`; lending's {@code ClockStep} and bnpl's
 * {@code BnplClockFrame}, in a schema of its own so no suite drops another's).
 *
 * <p>The stores of {@code X-TSK-008}'s five statements bind no instant any more, so a test can no longer cross a deadline by
 * handing a sweeper an offset {@code Clock}: the clock it would be moving is not the one that decides. This moves the one
 * that does. It shadows {@code now()}, {@code transaction_timestamp()}, {@code statement_timestamp()} and
 * {@code clock_timestamp()} for a session that opts in by placing {@code boundclock} before {@code pg_catalog} on its
 * {@code search_path}, so the production statements run unchanged and read the moved clock.
 *
 * <p>Only an opted-in session sees the shadow, and only for unqualified calls: a function that names {@code pg_catalog}
 * (merchant {@code V014}'s pin trigger) cannot be moved this way, which is the property that qualification buys. Since
 * {@code P15-TSK-040} the production functions of every schema name it too, so {@link #install} also opens them again in this
 * test database ({@link ReopenedClock}), and {@link #uninstall} closes them; V014's trigger stays closed. The schema is the
 * bootstrap superuser's, created by {@link #install}, never a migration's.
 */
public final class DatabaseClockFrame {

    private DatabaseClockFrame() {}

    /** Creates or replaces the shadow clock - idempotent. */
    public static void install() {
        bootstrap(
                "CREATE SCHEMA IF NOT EXISTS boundclock;"
                        + " GRANT USAGE ON SCHEMA boundclock TO PUBLIC;"
                        + " CREATE OR REPLACE FUNCTION boundclock.frame() RETURNS interval LANGUAGE sql STABLE AS"
                        + " $$ SELECT coalesce(nullif(pg_catalog.current_setting('boundclock.frame', true), ''), '0')::interval $$;"
                        + " CREATE OR REPLACE FUNCTION boundclock.transaction_timestamp() RETURNS timestamptz LANGUAGE sql STABLE AS"
                        + " $$ SELECT pg_catalog.transaction_timestamp() + boundclock.frame() $$;"
                        + " CREATE OR REPLACE FUNCTION boundclock.now() RETURNS timestamptz LANGUAGE sql STABLE AS"
                        + " $$ SELECT pg_catalog.transaction_timestamp() + boundclock.frame() $$;"
                        + " CREATE OR REPLACE FUNCTION boundclock.statement_timestamp() RETURNS timestamptz LANGUAGE sql STABLE AS"
                        + " $$ SELECT pg_catalog.statement_timestamp() + boundclock.frame() $$;"
                        + " CREATE OR REPLACE FUNCTION boundclock.clock_timestamp() RETURNS timestamptz LANGUAGE sql VOLATILE AS"
                        + " $$ SELECT pg_catalog.clock_timestamp() + boundclock.frame() $$;");
        ReopenedClock.reopen();
    }

    /** Closes every production function again, as the migrations left it. The shadow schema stays; the next install replaces it. */
    public static void uninstall() {
        ReopenedClock.restore();
    }

    /** An application-role session reading the clock moved by {@code frame}, its transaction not yet begun. */
    public static Connection applicationAt(Duration frame) throws SQLException {
        Connection connection = DatabaseRoles.application();
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path = boundclock, pg_catalog, \"$user\", public");
            statement.execute("SET boundclock.frame = '" + frame.toNanos() / 1_000 + " microseconds'");
        }
        connection.setAutoCommit(false);
        return connection;
    }

    /** {@code work} in one application-role transaction on the moved clock: committed, or rolled back on a failure. */
    public static <R> R inOneTransaction(Duration frame, Function<Connection, R> work) {
        try (Connection connection = applicationAt(frame)) {
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

    private static void bootstrap(String sql) {
        try (Connection owner = DatabaseRoles.bootstrap(); Statement statement = owner.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
