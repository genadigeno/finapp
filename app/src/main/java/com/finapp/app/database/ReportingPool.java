package com.finapp.app.database;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Report generation's own connections, as {@code finapp_reporting} (`P15-TSK-021`; {@code PHASE_15_PLAN.md} C13;
 * {@code INV-ACC-14} at the privilege level; {@code THREAT_MODEL.md} T7, T44).
 *
 * <h2>A role that cannot write</h2>
 *
 * <p>Report code reads in a {@code REPEATABLE READ READ ONLY} snapshot ({@code JdbcReportSnapshots}), which refuses a
 * write at the transaction, and a build rule refuses one in the code ({@code ReportingNeverWritesTest}). Both hold only
 * while the connection's role is honest about them: as {@code finapp_app} a snapshot that lost its {@code READ ONLY} -
 * a driver setting, a pool default, a statement that resets it - writes with every privilege the application holds.
 * This pool connects as {@code finapp_reporting}, which holds {@code SELECT} on the tables a report run reads and
 * nothing else ({@code ReportingRoleIsSelectOnlyTest} holds the grants), so a write is refused by the role whatever
 * the transaction says.
 *
 * <h2>Never the writer's, and no fallback to it</h2>
 *
 * <p>Report generation's snapshots open connections here and nowhere else. A role that is missing, a credential that is
 * wrong, a pool that cannot connect or a role that can write ({@link #CAPABILITY_CHECK}, `P15-TSK-043`) fails {@link #open} -
 * the snapshot cannot open, the run is not generated, and
 * it stays {@code GENERATING} until its lease runs out and the orphan leg tries again. There is no path from here to
 * the application's {@code DataSource}: a fallback would be a silent return to the privilege this pool exists to
 * remove.
 *
 * <h2>Not a {@code DataSource} bean, deliberately</h2>
 *
 * <p>A second {@code DataSource} bean would make Spring's auto-configured one back off, and would join the
 * {@code db} health contributor - so a reporting role that cannot connect would take every instance out of
 * readiness, though payments need nothing from it. Report generation fails closed on its own; the instance keeps
 * serving.
 *
 * <h2>Sized and counted</h2>
 *
 * <p>Fixed-size ({@code minimum-idle} equals the maximum) and counted by {@link ConnectionPoolSizingGuard}: every
 * connection is a backend process on the same server as the writer's. Lazy: nothing connects until the first report.
 * Its connections carry {@code application_name = finapp}, as the writer's do, so a count of the fleet's backends
 * counts both pools.
 *
 * <h2>Measured as the writer's pool is</h2>
 *
 * <p>Not being a {@code DataSource} bean, it is not one Spring Boot's pool-metrics binder finds, so it binds itself
 * (`P15-TSK-032`, {@code THREAT_MODEL.md} §6 item 42 (l)): HikariCP's own {@code MicrometerMetricsTrackerFactory} on the
 * application's registry - the tracker Boot's binder ends in for the writer - so {@code hikaricp.connections.*} carry
 * {@code pool="finapp-reporting"} under the writer's names. Set on the configuration, before the pool exists: it opens
 * nothing and touches no readiness. Hikari registers the meters only once the pool has started, after its first
 * connection passed {@link #CAPABILITY_CHECK}, so a refused start registers none, and a retried one none again; closing
 * the pool removes them. The platform's saturation and starvation rules keep excluding this pool deliberately - one
 * connection is full whenever a report runs, and a pool that cannot open fails the run, which
 * {@code AccountingCloseLegFailing} tickets - but its series exist, for the dashboard and for a reader.
 */
public final class ReportingPool implements ReportingConnections, AutoCloseable {

    /** The property naming the reporting role's user. */
    public static final String USERNAME_PROPERTY = "finapp.database.reporting.username";

    /**
     * The property holding the reporting role's login secret - its name, never the value, which only ever travels as
     * the pool's own setting.
     */
    public static final String LOGIN_PROPERTY = "finapp.database.reporting.password";

    /** The property sizing each instance's reporting pool. */
    public static final String POOL_SIZE_PROPERTY = "finapp.database.reporting.pool-size";

    /**
     * What the role can do, asked of the database on every connection this pool makes, before it is handed out
     * (`P15-TSK-043`; {@code THREAT_MODEL.md} §6 item 36 (g), T44). Hikari runs it as the connection's first statement; one that
     * raises is a connection that failed, so a pool whose role can write never starts, and report generation fails closed
     * as it does when the pool cannot connect at all.
     *
     * <p><strong>Capability, not a name.</strong> {@code ReportingPoolBeans} refuses the writer's names at the application's
     * start; a role holding a writer's rights under any other name passed it. This refuses:
     * <ul>
     *   <li>a role that is a member of {@code finapp_app}, {@code pg_execute_server_program} or {@code pg_write_server_files}
     *       through any chain, inheriting or not ({@code MEMBER}: a membership that only lets the role {@code SET ROLE} to the
     *       writer counts, which {@code USAGE} would miss);</li>
     *   <li>a role that can write, outside the system schemas, by itself, through {@code PUBLIC} or through any role it is a
     *       member of: {@code INSERT} or {@code UPDATE} on any column of any table, view or foreign table
     *       ({@code has_any_column_privilege}, because a column grant is not in the table's own privileges and
     *       {@code has_table_privilege} never sees it); {@code DELETE}, {@code TRUNCATE} or {@code TRIGGER} on one;
     *       {@code UPDATE} or {@code USAGE} on a sequence (each sets or advances it; {@code SELECT} is a read); {@code CREATE}
     *       on a schema. So a writer of another name and {@code pg_write_all_data} are refused by what they can do, and a
     *       superuser by both;</li>
     *   <li>a database with no ledger table to judge: the check cannot have looked at what it exists for, so it fails closed
     *       rather than pass over an empty catalogue.</li>
     * </ul>
     * Functions a role may execute are not judged: every {@code SECURITY DEFINER} function the platform defines revokes
     * {@code EXECUTE} from {@code PUBLIC} and grants it to {@code finapp_app} alone, and a member of that role is refused above.
     *
     * <p><strong>Where the pool starts.</strong> The pool is lazy by design, so a reporting role that cannot connect never
     * takes a replica out of readiness: its start is its first connection, and the check then runs again on every
     * connection it makes, so a role changed while a replica runs is judged at its next one. Every replica judges its own.
     * Any error - a catalogue read refused, no {@code plpgsql} - fails the connection too. Reads {@code pg_catalog} only,
     * once per physical connection, while Hikari sets the connection up.
     */
    static final String CAPABILITY_CHECK = """
            DO $capability$
            DECLARE
                held text;
                ledger_tables integer;
                writable text;
            BEGIN
                SELECT writer.rolname INTO held FROM pg_catalog.pg_roles writer
                    WHERE writer.rolname IN ('finapp_app', 'pg_execute_server_program', 'pg_write_server_files')
                      AND pg_catalog.pg_has_role(current_user, writer.oid, 'MEMBER')
                    ORDER BY writer.rolname
                    LIMIT 1;
                IF held IS NOT NULL THEN
                    RAISE EXCEPTION 'finapp reporting pool refused: % can use the rights of %', current_user, held;
                END IF;
                SELECT count(*) INTO ledger_tables
                    FROM pg_catalog.pg_class judged
                    JOIN pg_catalog.pg_namespace home ON home.oid = judged.relnamespace
                    WHERE home.nspname = 'ledger' AND judged.relkind IN ('r', 'p');
                IF ledger_tables = 0 THEN
                    RAISE EXCEPTION 'finapp reporting pool refused: no ledger table to judge, so the check cannot run';
                END IF;
                WITH mine AS MATERIALIZED (
                    SELECT member.oid FROM pg_catalog.pg_roles member
                    WHERE pg_catalog.pg_has_role(current_user, member.oid, 'MEMBER')),
                nonsystem AS MATERIALIZED (
                    SELECT home.oid, home.nspname FROM pg_catalog.pg_namespace home
                    WHERE home.nspname <> 'information_schema' AND home.nspname !~ '^pg_')
                SELECT any_write.found INTO writable FROM (
                    SELECT pg_catalog.format('%I.%I (%s)', nonsystem.nspname, judged.relname, act.privilege) AS found
                        FROM mine
                        CROSS JOIN pg_catalog.pg_class judged
                        JOIN nonsystem ON nonsystem.oid = judged.relnamespace
                        CROSS JOIN unnest(ARRAY['INSERT', 'UPDATE']) AS act(privilege)
                        WHERE judged.relkind IN ('r', 'p', 'v', 'f')
                          AND pg_catalog.has_any_column_privilege(mine.oid, judged.oid, act.privilege)
                    UNION ALL
                    SELECT pg_catalog.format('%I.%I (%s)', nonsystem.nspname, judged.relname, act.privilege)
                        FROM mine
                        CROSS JOIN pg_catalog.pg_class judged
                        JOIN nonsystem ON nonsystem.oid = judged.relnamespace
                        CROSS JOIN unnest(ARRAY['DELETE', 'TRUNCATE', 'TRIGGER']) AS act(privilege)
                        WHERE judged.relkind IN ('r', 'p', 'v', 'f')
                          AND pg_catalog.has_table_privilege(mine.oid, judged.oid, act.privilege)
                    UNION ALL
                    SELECT pg_catalog.format('sequence %I.%I (%s)', nonsystem.nspname, judged.relname, act.privilege)
                        FROM mine
                        CROSS JOIN pg_catalog.pg_class judged
                        JOIN nonsystem ON nonsystem.oid = judged.relnamespace
                        CROSS JOIN unnest(ARRAY['UPDATE', 'USAGE']) AS act(privilege)
                        WHERE judged.relkind = 'S'
                          AND pg_catalog.has_sequence_privilege(mine.oid, judged.oid, act.privilege)
                    UNION ALL
                    SELECT pg_catalog.format('schema %I (CREATE)', nonsystem.nspname)
                        FROM mine
                        CROSS JOIN nonsystem
                        WHERE pg_catalog.has_schema_privilege(mine.oid, nonsystem.oid, 'CREATE')
                ) AS any_write
                LIMIT 1;
                IF writable IS NOT NULL THEN
                    RAISE EXCEPTION 'finapp reporting pool refused: % can write %', current_user, writable;
                END IF;
            END
            $capability$""";

    private final HikariDataSource pool;

    /**
     * @param jdbcUrl the database the writer's pool reaches - the same database, the same transport rules
     * @param transport the writer's driver properties ({@code spring.datasource.hikari.data-source-properties}), applied
     *     first: an {@code sslmode} set there rather than in the URL is what {@code TransportSecurityGuard} judged, and a
     *     pool that dropped it would reach the same remote database under the driver's default - unencrypted, silently. The
     *     writer's {@code socketTimeout} comes with them (ADR-0139): five minutes, above report generation's four-minute
     *     budget, so a black-holed generation read is given up rather than held until the pod is replaced
     *     ({@code DatabaseSocketTimeoutTest#theReportingPoolCarriesIt})
     * @param size connections this instance holds, at least one
     * @param registry the application's meter registry, which the pool's {@code hikaricp.connections.*} join once it starts
     */
    public ReportingPool(String jdbcUrl, Map<String, String> transport, String username, String password, int size, MeterRegistry registry) {
        this(jdbcUrl, Optional.empty(), transport, username, password, size, registry);
    }

    /**
     * As above, with the writer's driver class where one is configured ({@code spring.datasource.driver-class-name}) -
     * the same database reached through the same driver, rather than whichever one {@code DriverManager} resolves for
     * the URL. None is configured in any shipped setting, so the platform resolves by URL for both pools (`P15-REM-005`).
     *
     * @param driverClassName the writer's driver class, or empty to resolve the driver from the URL
     */
    public ReportingPool(String jdbcUrl, Optional<String> driverClassName, Map<String, String> transport, String username,
            String password, int size, MeterRegistry registry) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Objects.requireNonNull(driverClassName, "driverClassName");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(registry, "registry");
        if (size < 1) {
            throw new IllegalArgumentException("the reporting pool holds at least one connection, not " + size);
        }
        // The no-argument constructor and setters: the pool starts on the first getConnection, so building the bean
        // touches no database (a context without one starts, and the guard above runs first).
        HikariDataSource built = new HikariDataSource();
        built.setPoolName("finapp-reporting");
        built.setJdbcUrl(jdbcUrl);
        driverClassName.ifPresent(built::setDriverClassName);
        built.setUsername(username);
        built.setPassword(password);
        built.setMaximumPoolSize(size);
        built.setMinimumIdle(size);
        built.setConnectionTimeout(5_000);
        built.setValidationTimeout(2_000);
        built.setReadOnly(true);
        // Before any connection is handed out, on every one (P15-TSK-043): a role that can write never starts the pool.
        built.setConnectionInitSql(CAPABILITY_CHECK);
        transport.forEach(built::addDataSourceProperty);
        built.addDataSourceProperty("connectTimeout", "5");
        built.addDataSourceProperty("ApplicationName", "finapp");
        // Its meters, as the writer's pool has them (P15-TSK-032, item 42 (l)): registered by Hikari when the pool starts.
        built.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(registry));
        this.pool = built;
    }

    /** A driver property this pool connects with, or null - read by the transport test, never by production code. */
    String driverProperty(String name) {
        return pool.getDataSourceProperties().getProperty(name);
    }

    /** The statement every new connection runs before it is handed out - read by the capability test, never by production code. */
    String connectionInitSql() {
        return pool.getConnectionInitSql();
    }

    /**
     * A connection as {@code finapp_reporting}, or a {@link SQLException}: a pool that cannot start (the role missing,
     * the credential refused) is reported as the snapshot's failure to open, never as anything that could be mistaken
     * for a report's own.
     */
    @Override
    public Connection open() throws SQLException {
        try {
            return pool.getConnection();
        } catch (RuntimeException unavailable) {
            throw new SQLException("the reporting pool cannot open a connection", unavailable);
        }
    }

    /** The role this pool connects as. */
    public String username() {
        return pool.getUsername();
    }

    /** Connections this instance holds at most. */
    public int size() {
        return pool.getMaximumPoolSize();
    }

    @Override
    public void close() {
        pool.close();
    }
}
