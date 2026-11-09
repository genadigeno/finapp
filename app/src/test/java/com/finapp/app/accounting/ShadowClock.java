package com.finapp.app.accounting;

import static com.finapp.app.accounting.AccountingPeriodFixture.correlation;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.function.Function;

/**
 * The database's clock read behind, on demand, on one session (`P15-TSK-033`, shared by `P14-REM-009`; lending's {@code ClockStep} and {@code MerchantBnplEnablementDatabaseTest}'s shadow, which this package's suites cannot reach): the four
 * clock functions shadowed in a schema of the bootstrap superuser's, {@code closeclock} - its own name, so no other suite's install or drop meets it - which a session opts into by putting it before {@code pg_catalog} on its {@code search_path}.
 * Accounting's triggers are invoker's rights with no path of their own, and {@code JdbcAccountingPeriodStore.TODAY_SQL} names its clock unqualified, so the production code and the production triggers run unchanged on the shadowed clock.
 * {@code closeclock.back}, set for the session before its transaction begins, is taken from every reading - the transaction's start included - so the whole transaction stands where a clock stepped back by that much would put it. Only the
 * opted-in session sees it.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
final class ShadowClock {

    /** Far beyond any real step measured (2.8 s) and any gap between two of a suite's acts, so the planted step alone decides the order. */
    static final Duration BEHIND = Duration.ofMinutes(10);

    private ShadowClock() {}

    static void install() throws SQLException {
        bootstrap("CREATE SCHEMA IF NOT EXISTS closeclock;"
                + " GRANT USAGE ON SCHEMA closeclock TO PUBLIC;"
                + " CREATE OR REPLACE FUNCTION closeclock.back() RETURNS interval LANGUAGE sql STABLE AS"
                + " $$ SELECT coalesce(nullif(pg_catalog.current_setting('closeclock.back', true), ''), '0')::interval $$;"
                + " CREATE OR REPLACE FUNCTION closeclock.transaction_timestamp() RETURNS timestamptz LANGUAGE sql STABLE"
                + " AS $$ SELECT pg_catalog.transaction_timestamp() - closeclock.back() $$;"
                + " CREATE OR REPLACE FUNCTION closeclock.now() RETURNS timestamptz LANGUAGE sql STABLE AS"
                + " $$ SELECT pg_catalog.transaction_timestamp() - closeclock.back() $$;"
                + " CREATE OR REPLACE FUNCTION closeclock.statement_timestamp() RETURNS timestamptz LANGUAGE sql STABLE"
                + " AS $$ SELECT pg_catalog.statement_timestamp() - closeclock.back() $$;"
                + " CREATE OR REPLACE FUNCTION closeclock.clock_timestamp() RETURNS timestamptz LANGUAGE sql VOLATILE AS"
                + " $$ SELECT pg_catalog.clock_timestamp() - closeclock.back() $$;");
    }

    static void uninstall() throws SQLException {
        bootstrap("DROP SCHEMA IF EXISTS closeclock CASCADE");
    }

    /** {@code work} in one transaction of a fresh application-role session whose database clock reads {@link #BEHIND} behind, as {@code actor}. */
    static <R> R behind(Actor actor, Function<Connection, R> work) {
        return behindBy(BEHIND, actor, work);
    }

    /** {@code work} on a session whose database clock reads noon, UTC, of {@code day}: a date on either side of a boundary, whatever the wall clock says. */
    static <R> R on(LocalDate day, Actor actor, Function<Connection, R> work) {
        return behindBy(Duration.between(day.atTime(LocalTime.NOON).toInstant(ZoneOffset.UTC), AccountingPeriodFixture.NOW.instant()), actor, work);
    }

    private static <R> R behindBy(Duration by, Actor actor, Function<Connection, R> work) {
        try (SecurityContext.Scope who = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(correlation()));
                Connection connection = DatabaseRoles.application()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET search_path = closeclock, pg_catalog, \"$user\", public");
                statement.execute("SET closeclock.back = '" + by.toSeconds() + " seconds'");
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

    private static void bootstrap(String sql) throws SQLException {
        try (Connection owner = DatabaseRoles.bootstrap(); Statement statement = owner.createStatement()) {
            statement.execute(sql);
        }
    }
}
