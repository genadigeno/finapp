package com.finapp.app.abuse;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The entry's counted case (`P15-TSK-022`): three real application contexts, each with its own Tomcat and its own pool, one registration
 * window on one source, and exactly the limit admitted. Owed to the exit's database pass (C14); the multi-instance answer rests on it.
 *
 * <p>{@code own-container}, for the storms' reason: it starts contexts of its own, and they would otherwise add up on the tier's shared JVM.
 * The password and body are {@code RegistrationConcurrencyDatabaseTest}'s, the shape registration accepts. The counter row is cleared first by
 * the migrator, because anything else that registered from {@code 127.0.0.1} shares the same bucket and window.
 */
@Tag("database")
@Tag("own-container")
@DisplayName("three real instances, one window: exactly the limit admitted (P15-TSK-022)")
class SourceLimitClusterDatabaseTest {

    private static final int LIMIT = 10;
    private static final int INSTANCES = 3;
    private static final int ATTEMPTS_EACH = 12;

    private static final List<ConfigurableApplicationContext> CONTEXTS = new ArrayList<>();
    private static final List<Integer> PORTS = new ArrayList<>();

    @BeforeAll
    static void startThreeInstances() {
        for (int i = 0; i < INSTANCES; i++) {
            ConfigurableApplicationContext context = new SpringApplicationBuilder(com.finapp.app.FinappApplication.class)
                    .run(
                            "--server.port=0",
                            "--spring.datasource.hikari.data-source-properties.ApplicationName=finapp-limit-" + i,
                            "--finapp.abuse.registration.limit=" + LIMIT,
                            // The cap would otherwise shed this test's deliberate burst: what is counted here is the store's arbitration.
                            "--finapp.abuse.max-concurrent-counts=64");
            CONTEXTS.add(context);
            PORTS.add(Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port")));
        }
    }

    @AfterAll
    static void stopThreeInstances() {
        CONTEXTS.forEach(ConfigurableApplicationContext::close);
        CONTEXTS.clear();
        PORTS.clear();
    }

    @Test
    @DisplayName("a burst across three instances admits exactly the limit; every refusal is 429 with a Retry-After")
    void exactlyTheLimitAcrossThreeContexts() throws Exception {
        awayFromAWindowEnd();
        clearTheRegistrationCount();
        int attempts = INSTANCES * ATTEMPTS_EACH;
        ExecutorService threads = Executors.newFixedThreadPool(attempts);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<HttpResponse<String>>> racing = new ArrayList<>();
            for (int instance = 0; instance < INSTANCES; instance++) {
                int port = PORTS.get(instance);
                for (int i = 0; i < ATTEMPTS_EACH; i++) {
                    Callable<HttpResponse<String>> attempt = () -> {
                        start.await();
                        return register(port);
                    };
                    racing.add(threads.submit(attempt));
                }
            }
            start.countDown();
            int admitted = 0;
            int refused = 0;
            for (Future<HttpResponse<String>> attempt : racing) {
                HttpResponse<String> response = attempt.get();
                if (response.statusCode() == 201) {
                    admitted++;
                } else {
                    assertThat(response.statusCode()).as(response.body()).isEqualTo(429);
                    long retryAfter = Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow());
                    assertThat(retryAfter).isBetween(1L, 3600L);
                    assertThat(response.body()).contains("api.TooManyRequests");
                    refused++;
                }
            }
            assertThat(admitted).as("exactly the limit across three contexts").isEqualTo(LIMIT);
            assertThat(refused).isEqualTo(attempts - LIMIT);
            assertThat(countInTheRow()).as("what the row holds is what was admitted").isEqualTo(LIMIT);
        } finally {
            threads.shutdownNow();
        }
    }

    private static HttpResponse<String> register(int port) throws Exception {
        String login = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/registrations"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header(IdempotencyKeyHeader.NAME, UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\",\"password\":\"not-a-real-password\"}"))
                .build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static void awayFromAWindowEnd() throws Exception {
        while (true) {
            try (Connection connection = DatabaseRoles.application();
                    Statement statement = connection.createStatement();
                    ResultSet result = statement.executeQuery("SELECT 3600 - (EXTRACT(EPOCH FROM now())::bigint % 3600)")) {
                result.next();
                if (result.getLong(1) >= 60) {
                    return;
                }
            }
            Thread.sleep(1000);
        }
    }

    private static void clearTheRegistrationCount() throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement()) {
            statement.executeUpdate("DELETE FROM platform.window_counter WHERE scope = 'door.registration'");
        }
    }

    private static int countInTheRow() throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator();
                Statement statement = migrator.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT COALESCE(SUM(admitted), 0) FROM platform.window_counter WHERE scope = 'door.registration'"
                                + " AND window_start = date_bin(INTERVAL '1 hour', now(), TIMESTAMPTZ 'epoch')")) {
            result.next();
            return result.getInt(1);
        }
    }
}
