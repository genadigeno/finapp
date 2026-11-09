package com.finapp.app.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The contributor over the real actuator (`P15-TSK-005`), read from the running application's own health groups, with the
 * version read stubbed. The database is absent and its indicator off, so readiness is what this contributor says rather
 * than hidden under {@code db}'s DOWN.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent",
            "management.health.db.enabled=false",
            "management.endpoint.health.validate-group-membership=false"
        })
@Import(SchemaVersionReadinessTest.StubbedRead.class)
@Tag("slice")
@DisplayName("the schema-version contributor is in readiness and not in liveness (P15-TSK-005)")
class SchemaVersionReadinessTest {

    @TestConfiguration
    static class StubbedRead {
        @Bean
        @Primary
        Applied stubbedAppliedSchemaVersions() {
            return new Applied();
        }
    }

    /** The versions the database is said to hold, changed by each case. */
    static final class Applied implements AppliedSchemaVersions {
        private volatile Map<String, SchemaVersion> versions = Map.of();

        @Override
        public Map<String, SchemaVersion> read() {
            return versions;
        }
    }

    @Autowired private HealthEndpointGroups groups;
    @Autowired private HealthContributorRegistry registry;
    @Autowired private ExpectedSchemaVersions expected;
    @Autowired private SchemaVersionGuard guard;
    @Autowired private Applied applied;

    @LocalManagementPort private int port;

    private void theDatabaseHolds(Map<String, SchemaVersion> versions) {
        applied.versions = versions;
        guard.refreshNow();
    }

    private Map<String, SchemaVersion> oneOlderThanExpected() {
        Map<String, SchemaVersion> versions = new HashMap<>(expected.versions());
        versions.put("platform", SchemaVersion.ZERO);
        return versions;
    }

    private Map<String, SchemaVersion> newerThanExpected() {
        Map<String, SchemaVersion> versions = new HashMap<>();
        expected.versions().keySet().forEach(schema -> versions.put(schema, SchemaVersion.parse("99999")));
        return versions;
    }

    @Test
    @DisplayName("readiness includes schemaVersion; liveness does not")
    void readinessAndNotLiveness() {
        assertThat(groups.get("readiness").isMember("schemaVersion")).as("a replica on an old schema must not take traffic").isTrue();
        assertThat(groups.get("liveness").isMember("schemaVersion")).as("a restart does not apply a migration (ADR-0016)").isFalse();
    }

    @Test
    @DisplayName("the name the readiness group includes is this contributor")
    void theContributorIsRegistered() {
        assertThat(registry.getContributor("schemaVersion")).isInstanceOf(SchemaVersionHealthIndicator.class);
    }

    @Test
    @DisplayName("the real wiring reads on the daemon thread, never the probe's")
    void theRealWiringReadsOffTheProbe() {
        assertThat(ReflectionTestUtils.getField(guard, "reader")).isSameAs(SchemaVersionGuard.ON_A_DAEMON_THREAD);
    }

    @Test
    @DisplayName("readiness answers 503 OUT_OF_SERVICE on an older schema, and liveness stays 200")
    void falseOnAnOlderSchema() throws Exception {
        theDatabaseHolds(oneOlderThanExpected());

        HttpResponse<String> readiness = get("/actuator/health/readiness");
        assertThat(readiness.statusCode()).isEqualTo(503);
        assertThat(readiness.body()).isEqualTo("{\"status\":\"OUT_OF_SERVICE\"}");
        assertThat(get("/actuator/health/liveness").statusCode()).as("liveness reads nothing of this").isEqualTo(200);
    }

    @Test
    @DisplayName("readiness answers 200 on the expected schema")
    void trueOnTheExpectedSchema() throws Exception {
        theDatabaseHolds(expected.versions());

        HttpResponse<String> readiness = get("/actuator/health/readiness");
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    @DisplayName("readiness answers 200 on a newer schema: the rollback this protects")
    void trueOnANewerSchema() throws Exception {
        theDatabaseHolds(newerThanExpected());

        assertThat(get("/actuator/health/readiness").statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newHttpClient()
                .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
    }
}
