package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.api.ApiVersion;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The operational endpoints, with PostgreSQL unreachable.
 *
 * <h2>Why the database is deliberately broken here</h2>
 *
 * <p>The acceptance criterion is that readiness <em>fails</em> when PostgreSQL is unavailable, and
 * the only way to know that is to make it unavailable. The datasource points at a closed port,
 * which is what a stopped database actually looks like to a client: connection refused.
 *
 * <p>The positive control - readiness reporting UP against a database that is genuinely there -
 * is {@code HealthReadinessDatabaseTest}. Without it this class would pass just as happily
 * against an endpoint hard-coded to report failure.
 *
 * <h2>The three properties that matter, in order</h2>
 *
 * <ol>
 *   <li><strong>The application starts anyway.</strong> One that refuses to boot without its
 *       database cannot report readiness at all: an orchestrator sees a crash-looping instance
 *       instead of a NOT_READY one, and the signal naming the broken dependency is lost at
 *       exactly the moment somebody needs it.
 *   <li><strong>Liveness stays UP.</strong> Liveness answers "would killing this process help?",
 *       and for an unreachable database the answer is no. A liveness probe that consulted
 *       PostgreSQL would restart every instance simultaneously during a thirty-second blip and
 *       leave the fleet reconnecting in a herd to a database already in trouble - a degradation
 *       converted into an outage by the health check meant to protect against one.
 *   <li><strong>Readiness goes DOWN.</strong> An instance that cannot reach the transactional
 *       source of truth can serve nothing financial and must stop being sent traffic.
 * </ol>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Port 1 is closed. Connection refused, immediately, with no dependency on anything
        // being installed - so this stays a hermetic test of a genuinely absent database.
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
@Tag("slice")
class HealthEndpointTest {

    @LocalServerPort private int port;

    // -----------------------------------------------------------------
    // Liveness and readiness answer different questions
    // -----------------------------------------------------------------

    @Test
    @DisplayName("the application starts with PostgreSQL unreachable, so it can say so")
    void theApplicationStartsWithoutItsDatabase() throws Exception {
        // If the context had failed to start, no test in this class would run and the failure
        // would read as a configuration error rather than as the design decision it is.
        assertThat(get("/actuator/health/liveness").statusCode())
                .as("the application is serving")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("liveness stays UP when the database is unreachable")
    void livenessIgnoresTheDatabase() throws Exception {
        HttpResponse<String> response = get("/actuator/health/liveness");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    @DisplayName("readiness reports DOWN when the database is unreachable")
    void readinessReflectsTheDatabase() throws Exception {
        // The acceptance criterion. Spring's default readiness group is `readinessState` alone,
        // so without the explicit group in application.yaml this returns 200 UP with no database
        // in sight - correct-looking, and worthless.
        HttpResponse<String> response = get("/actuator/health/readiness");

        assertThat(response.statusCode())
                .as("503 is what a load balancer acts on; a DOWN body behind a 200 is not")
                .isEqualTo(503);
        assertThat(response.body()).contains("\"status\":\"DOWN\"");
    }

    @Test
    @DisplayName("the aggregate health endpoint is DOWN too")
    void aggregateHealthIsDown() throws Exception {
        assertThat(get("/actuator/health").statusCode()).isEqualTo(503);
    }

    // -----------------------------------------------------------------
    // What must never be published
    // -----------------------------------------------------------------

    @Test
    @DisplayName("a health response is a status and nothing else, member for member")
    void healthPublishesStatusAndNothingElse() throws Exception {
        // An ALLOW-LIST, after a deny-list of known-bad strings was written first and found
        // wanting. Turning details on and reading the body it would actually publish showed the
        // deny-list missing a whole class of leak: the disk-space indicator reports
        // `"path":"C:\Users\<name>\..."`, so the response would carry a username and the
        // directory layout of the host - and no list of forbidden substrings anticipates the
        // next indicator somebody adds.
        //
        // It also showed three of the deny-list's seven entries never firing, which is the
        // familiar failure: assertions that look like protection and are not.
        //
        // What the detailed body does contain, for the record: the failing component, a Spring
        // exception class name, that filesystem path, and the SSL and disk-space indicators.
        // This is the same argument ProblemDetailBody makes - specify what is published, because
        // anything else publishes itself.
        assertThat(get("/actuator/health/readiness").body())
                .as("readiness")
                .isEqualTo("{\"status\":\"DOWN\"}");
        assertThat(get("/actuator/health/liveness").body())
                .as("liveness")
                .isEqualTo("{\"status\":\"UP\"}");

        // The aggregate also names its groups, which the deny-list version of this test never
        // saw and would never have questioned. It is not sensitive - they are the two groups
        // configured here - but pinning it means adding a third group is a visible change to
        // what the platform publishes rather than a silent one.
        assertThat(get("/actuator/health").body())
                .as("aggregate")
                .isEqualTo("{\"groups\":[\"liveness\",\"readiness\"],\"status\":\"DOWN\"}");
    }

    @Test
    @DisplayName("only health and info are exposed; everything the allow-list holds back is absent")
    void theEndpointAllowListHolds() throws Exception {
        // Every one of these was verified to return 200 with the allow-list widened to `*`, so
        // each assertion below is held up by the allow-list and nothing else. /env is the
        // configuration; /configprops is it again, annotated; /beans and /mappings are a map of
        // the application; /loggers accepts writes; /threaddump is every stack in the process.
        for (String endpoint :
                new String[] {
                    "env", "beans", "configprops", "threaddump", "loggers", "mappings", "metrics",
                    "conditions", "scheduledtasks"
                }) {
            assertThat(get("/actuator/" + endpoint).statusCode())
                    .as("/actuator/%s is served when the allow-list is widened, so it must 404 here",
                            endpoint)
                    .isEqualTo(404);
        }
    }

    @Test
    @DisplayName("the endpoints that need more than exposure are absent too")
    void theEndpointsBehindASecondGateAreAlsoAbsent() throws Exception {
        // Separated from the list above because these three do NOT become reachable by widening
        // the allow-list alone, and grouping them with the ones that do would have made three
        // assertions look like protection they were not providing. Kept, because each becomes
        // live the moment its second gate opens, and the failure would be silent:
        //
        //   heapdump  - also needs `management.endpoint.heapdump.access`. Probed with both set:
        //               200, and 55 MB of process memory. Every secret the JVM has ever held.
        //   shutdown  - also needs its own access property.
        //   caches    - also needs a CacheManager bean, which Phase 15 may well introduce.
        for (String endpoint : new String[] {"heapdump", "shutdown", "caches"}) {
            assertThat(get("/actuator/" + endpoint).statusCode())
                    .as("/actuator/%s must not be served", endpoint)
                    .isEqualTo(404);
        }
    }

    @Test
    @DisplayName("the info endpoint carries build identity and nothing about the machine")
    void infoCarriesBuildIdentityOnly() throws Exception {
        HttpResponse<String> response = get("/actuator/info");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).as("which build is running").contains("\"build\"").contains("finapp");
        assertThat(response.body())
                .as("the JVM and the OS are free reconnaissance for anyone matching a CVE")
                .doesNotContain("\"java\"")
                .doesNotContain("\"os\"");
        assertThat(response.body())
                .as("a build timestamp would defeat reproducible archives")
                .doesNotContain("\"time\"");
    }

    // -----------------------------------------------------------------
    // The claim ADR-0015 made before it could be checked
    // -----------------------------------------------------------------

    @Test
    @DisplayName("operational endpoints are not versioned, which ADR-0015 asserted before it could")
    void actuatorIsNotVersioned() throws Exception {
        // ADR-0015 and ApiVersion both state that actuator is untouched by the /v1 prefix
        // because it is served by its own handler mapping. Nothing could verify that when it was
        // written - there was no actuator. If it were wrong, every liveness probe would break on
        // the first version bump, which is the worst possible time to find out.
        assertThat(get("/actuator/health/liveness").statusCode()).isEqualTo(200);
        assertThat(get(ApiVersion.CURRENT_PREFIX + "/actuator/health/liveness").statusCode())
                .as("the versioned path must not be an alias for an operational endpoint")
                .isEqualTo(404);
    }

    // -----------------------------------------------------------------

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()
                .send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
    }
}
