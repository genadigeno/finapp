package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The positive control for {@link HealthEndpointTest}: readiness reports UP against a database
 * that is genuinely reachable.
 *
 * <p>Without this, an endpoint wired to report DOWN unconditionally - or a readiness group naming
 * a health indicator that does not exist, which Spring reports as DOWN rather than as a
 * configuration error - would satisfy the acceptance criterion perfectly and be useless. "Fails
 * when PostgreSQL is unavailable" is only half a claim; the other half is that it succeeds when
 * PostgreSQL is available.
 *
 * <p>It also proves something the hermetic test cannot: that the check works <strong>through the
 * privileges the application actually has</strong>. The application connects as {@code
 * finapp_app}, which holds per-table DML and nothing else. A health check needing more than that
 * would pass in every environment where somebody had run it as a superuser and fail on the day it
 * mattered.
 *
 * <p>No connection settings are configured here. The application reads the same {@code FINAPP_DB_*}
 * environment variables in a test as it does in production, so this exercises the configuration
 * the application has rather than one a fixture chose for it.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthReadinessDatabaseTest {

    @LocalServerPort private int port;

    @Autowired private DataSource dataSource;

    @Autowired private org.springframework.context.ApplicationContext context;

    @Test
    @DisplayName("readiness is UP when PostgreSQL is reachable")
    void readinessIsUpAgainstARealDatabase() throws Exception {
        HttpResponse<String> response = get("/actuator/health/readiness");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("the aggregate health endpoint is UP as well")
    void aggregateHealthIsUp() throws Exception {
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("the health check passes as the restricted application role, not a superuser")
    void theCheckRunsWithTheApplicationPrivileges() throws Exception {
        // The precondition that makes the test above mean anything. A superuser ignores every
        // permission check, so a readiness check that happened to be running as one would pass
        // whatever the grants said - and would then fail in the one environment configured
        // correctly.
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement();
                var result = statement.executeQuery("SELECT current_user, usesuper FROM pg_user WHERE usename = current_user")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("finapp_app");
            assertThat(result.getBoolean(2)).as("the application role must not be a superuser").isFalse();
        }
    }

    @Test
    @DisplayName("the application does not run migrations on startup")
    void migrationsDoNotRunAtStartup() {
        // ADR-0011: migrations never run as a side effect of application startup. An application
        // that migrated on boot would have N instances racing to alter a schema holding financial
        // history, which is the failure ADR-0014 exists to rule out.
        //
        // Asserted two ways, because each is weak alone. The context check is direct - it looks
        // at the running application - but only sees beans that were created.
        //
        // The second check reads the module's RUNTIME classpath, supplied by the build. It used
        // to try loading the class instead, reasoning that the test classpath is a superset of
        // the runtime one - and the comment here predicted its own failure: "it would report a
        // false positive if Flyway were ever added as a test dependency of this module". That is
        // exactly what P0-TSK-035 did, because the container harness applies the real migrations.
        // The prediction was right and the fix is to assert what the claim always was: Flyway is
        // not in what the application ships.
        assertThat(context.getBeanNamesForType(Object.class))
                .as("no Flyway bean may exist in the running application")
                .noneSatisfy(name -> assertThat(name.toLowerCase(Locale.ROOT)).contains("flyway"));

        String runtime = System.getProperty("finapp.runtime.classpath");
        assertThat(runtime)
                .as("the build must supply the runtime classpath, or this assertion checks nothing")
                .isNotBlank();
        assertThat(runtime.toLowerCase(Locale.ROOT))
                .as("Flyway must not be on the application's runtime classpath (ADR-0011)")
                .doesNotContain("flyway");
    }

    // -----------------------------------------------------------------

    private static boolean canLoad(String className) {
        try {
            Class.forName(className, false, HealthReadinessDatabaseTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException absent) {
            return false;
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
    }
}
