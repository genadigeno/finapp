package com.finapp.app.database;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;

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
 * wrong or a pool that cannot connect fails {@link #open} - the snapshot cannot open, the run is not generated, and
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

    private final HikariDataSource pool;

    /**
     * @param jdbcUrl the database the writer's pool reaches - the same database, the same transport rules
     * @param transport the writer's driver properties ({@code spring.datasource.hikari.data-source-properties}), applied
     *     first: an {@code sslmode} set there rather than in the URL is what {@code TransportSecurityGuard} judged, and a
     *     pool that dropped it would reach the same remote database under the driver's default - unencrypted, silently
     * @param size connections this instance holds, at least one
     */
    public ReportingPool(String jdbcUrl, Map<String, String> transport, String username, String password, int size) {
        Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        Objects.requireNonNull(transport, "transport");
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        if (size < 1) {
            throw new IllegalArgumentException("the reporting pool holds at least one connection, not " + size);
        }
        // The no-argument constructor and setters: the pool starts on the first getConnection, so building the bean
        // touches no database (a context without one starts, and the guard above runs first).
        HikariDataSource built = new HikariDataSource();
        built.setPoolName("finapp-reporting");
        built.setJdbcUrl(jdbcUrl);
        built.setUsername(username);
        built.setPassword(password);
        built.setMaximumPoolSize(size);
        built.setMinimumIdle(size);
        built.setConnectionTimeout(5_000);
        built.setValidationTimeout(2_000);
        built.setReadOnly(true);
        transport.forEach(built::addDataSourceProperty);
        built.addDataSourceProperty("connectTimeout", "5");
        built.addDataSourceProperty("ApplicationName", "finapp");
        this.pool = built;
    }

    /** A driver property this pool connects with, or null - read by the transport test, never by production code. */
    String driverProperty(String name) {
        return pool.getDataSourceProperties().getProperty(name);
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
