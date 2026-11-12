package com.finapp.app.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Meter;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * The reporting pool's own meters (`P15-TSK-032`, {@code THREAT_MODEL.md} §6 item 42 (l)): not a {@code DataSource} bean, so Spring Boot's
 * pool-metrics binder never finds it, and until this it had no {@code hikaricp_*} series at all - the platform's pool rules excluded a
 * {@code pool="finapp-reporting"} nothing emitted. Hermetic: a stub driver stands in for PostgreSQL, counting every connection asked of it,
 * and answers the capability check, or refuses it as a role that can write would be refused.
 */
@DisplayName("the reporting pool's hikaricp meters are the writer pool's, from its start, and building it opens nothing (P15-TSK-032, item 42 (l))")
class ReportingPoolMetricsTest {

    private static final String POOL = "finapp-reporting";

    /** What the platform's pool rules and the dashboard read, as the scrape names them. */
    private static final List<String> READ_BY_THE_RULES = List.of("hikaricp_connections_active", "hikaricp_connections_max",
            "hikaricp_connections_pending");

    private static final StubDriver DRIVER = new StubDriver();

    @BeforeAll
    static void register() throws SQLException {
        DriverManager.registerDriver(DRIVER);
    }

    @AfterAll
    static void deregister() throws SQLException {
        DriverManager.deregisterDriver(DRIVER);
    }

    @Test
    @DisplayName("building the pool opens no connection and registers nothing yet: the pool is lazy, and its meters arrive with its start")
    void buildingThePoolOpensNothing() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        int before = DRIVER.connections.get();
        try (ReportingPool pool = new ReportingPoolBeans().reportingPool(settings("open"), registry)) {
            assertThat(DRIVER.connections.get()).as("no connection asked of the database").isEqualTo(before);
            assertThat(reportingMeters(registry)).as("nothing registered before the pool starts").isEmpty();
            assertThat(pool.size()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("once started, the pool's meters are the writer pool's - every name, tagged pool=\"finapp-reporting\" - and the scrape"
            + " carries the three the pool rules read; closing it removes them")
    void itsMetersAreTheWritersOnes() throws SQLException {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try (ReportingPool pool = new ReportingPoolBeans().reportingPool(settings("open"), registry)) {
            try (Connection connection = pool.open()) {
                assertThat(connection.isValid(1)).isTrue();
            }
            Set<String> reporting = names(reportingMeters(registry));
            assertThat(reporting).as("the pool's meters").contains("hikaricp.connections", "hikaricp.connections.active",
                    "hikaricp.connections.idle", "hikaricp.connections.pending", "hikaricp.connections.max", "hikaricp.connections.min",
                    "hikaricp.connections.timeout", "hikaricp.connections.acquire", "hikaricp.connections.usage", "hikaricp.connections.creation");
            assertThat(reporting).as("the names a pool bound as Spring Boot binds the writer's carries").isEqualTo(writerPoolMeterNames());
            String scrape = registry.scrape();
            for (String series : READ_BY_THE_RULES) {
                assertThat(scrape).as(series).contains(series + "{pool=\"" + POOL + "\"}");
            }
            assertThat(scrape).as("its size, as the rules divide by it").contains("hikaricp_connections_max{pool=\"" + POOL + "\"} 1");
        }
        assertThat(reportingMeters(registry)).as("closing the pool removes its meters: none is left to read a closed pool").isEmpty();
    }

    @Test
    @DisplayName("a refused start - the capability check failing - registers no meter, and a retry none again: nothing half-registered"
            + " and nothing leaking per attempt")
    void aRefusedStartRegistersNothing() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try (ReportingPool pool = new ReportingPoolBeans().reportingPool(settings("refuse"), registry)) {
            for (int attempt = 1; attempt <= 3; attempt++) {
                int before = DRIVER.connections.get();
                assertThatExceptionOfType(SQLException.class).as("attempt %s", attempt).isThrownBy(pool::open);
                assertThat(DRIVER.connections.get()).as("attempt %s asked the database", attempt).isGreaterThan(before);
                assertThat(reportingMeters(registry)).as("attempt %s left no meter behind", attempt).isEmpty();
            }
            assertThat(registry.getMeters()).as("and no meter of any pool").noneMatch(meter -> meter.getId().getName().startsWith("hikaricp"));
        }
    }

    // ------------------------------------------------------------------------------------ the world

    private static MockEnvironment settings(String mode) {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:finapp-reporting-stub:" + mode)
                .withProperty("spring.datasource.username", "finapp_app")
                .withProperty(ReportingPool.USERNAME_PROPERTY, "finapp_reporting")
                .withProperty(ReportingPool.LOGIN_PROPERTY, "supplied-by-the-test")
                .withProperty(ReportingPool.POOL_SIZE_PROPERTY, "1");
    }

    private static List<Meter> reportingMeters(PrometheusMeterRegistry registry) {
        return registry.getMeters().stream().filter(meter -> POOL.equals(meter.getId().getTag("pool"))).toList();
    }

    private static Set<String> names(List<Meter> meters) {
        return meters.stream().map(meter -> meter.getId().getName()).collect(Collectors.toCollection(TreeSet::new));
    }

    /** A pool bound as Spring Boot's binder binds the writer's: {@code setMetricRegistry} with the application's registry, then started. */
    private static Set<String> writerPoolMeterNames() throws SQLException {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        try (HikariDataSource writer = new HikariDataSource()) {
            writer.setPoolName("writer-as-boot-binds-it");
            writer.setJdbcUrl("jdbc:finapp-reporting-stub:open");
            writer.setMetricRegistry(registry);
            try (Connection connection = writer.getConnection()) {
                assertThat(connection).isNotNull();
            }
            return names(registry.getMeters().stream().filter(meter -> "writer-as-boot-binds-it".equals(meter.getId().getTag("pool"))).toList());
        }
    }

    /**
     * A JDBC driver for {@code jdbc:finapp-reporting-stub:open} and {@code ...:refuse}: every connection counted, every call answered as an
     * idle PostgreSQL connection would answer it, and in {@code refuse} the pool's first statement - the capability check - raised.
     */
    private static final class StubDriver implements Driver {

        private final AtomicInteger connections = new AtomicInteger();

        @Override
        public Connection connect(String url, Properties info) {
            if (!acceptsURL(url)) {
                return null;
            }
            connections.incrementAndGet();
            boolean refuse = url.endsWith(":refuse");
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "createStatement" -> statement(refuse);
                        case "isValid", "isReadOnly", "getAutoCommit" -> true;
                        case "isClosed", "isWrapperFor" -> false;
                        case "getTransactionIsolation" -> Connection.TRANSACTION_READ_COMMITTED;
                        case "getNetworkTimeout" -> 0;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "stub connection";
                        default -> null;
                    });
        }

        private static Statement statement(boolean refuse) {
            return (Statement) Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[] {Statement.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "execute" -> {
                            if (refuse) {
                                throw new SQLException("finapp reporting pool refused: the stub's role can write");
                            }
                            yield false;
                        }
                        case "isClosed", "isWrapperFor" -> false;
                        case "getUpdateCount" -> -1;
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        case "toString" -> "stub statement";
                        default -> null;
                    });
        }

        @Override
        public boolean acceptsURL(String url) {
            return url != null && url.startsWith("jdbc:finapp-reporting-stub:");
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }
    }
}
