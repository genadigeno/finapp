package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The unconfigured deployment keeps a stable contract (`P5-TSK-011`): with no
 * {@code finapp.payments.provider.url} there is no {@code PaymentConfirmation} bean, and a
 * confirmation answers the honest {@code 503 payments.ProviderUnavailable} — the
 * {@code ObjectProvider} decision recorded in {@code PaymentBeans}, the
 * {@code paymentmethods.TokenisationUnavailable} shape.
 *
 * <p>Since `P5-TSK-012` the test overlay supplies {@code finapp.payments.provider.url} to
 * every app context (so the conditional webhook door is published and route-scanned — the
 * `P2-TSK-011` mechanism), this suite opts back OUT explicitly: {@code "false"} is
 * {@code @ConditionalOnProperty}'s own designated non-match value, so the property below is
 * the one honest way to simulate the unconfigured deployment against the real condition.
 *
 * <p>The 503 deliberately precedes resource resolution: whether the named payment exists is
 * not readable from a deployment-level refusal, so answering it first disloses nothing — and a
 * deployment that cannot confirm any payment has one honest answer for all of them.
 */
@Tag("database")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "finapp.payments.provider.url=false")
@DisplayName("the payment surface without a configured provider (P5-TSK-011)")
class PaymentSurfaceUnconfiguredDatabaseTest {

    private static final String PASSWORD = "a-perfectly-fine-pw-7";

    @LocalServerPort private int port;

    @Test
    @DisplayName("a confirmation on an unconfigured deployment is the honest 503, never a 500")
    void confirmationWithoutAProviderIsTheHonest503() throws Exception {
        String login = "payer." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(register(login).statusCode()).isEqualTo(201);
        String token = tokenFrom(authenticate(login).body());

        // A well-formed platform identifier (UUIDv7): a malformed one is the controller's 404
        // fold before the deployment answer can matter, and this test is about the deployment.
        UUID wellFormed =
                new com.finapp.sharedkernel.id.IdGenerator(
                                java.time.Clock.systemUTC(), new java.security.SecureRandom())
                        .next();
        HttpResponse<String> refused =
                post("/v1/payments/" + wellFormed + "/confirmation", token);
        assertThat(refused.statusCode()).isEqualTo(503);
        assertThat(refused.body()).contains("payments.ProviderUnavailable");
    }

    // -----------------------------------------------------------------

    private HttpResponse<String> register(String login) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/v1/registrations"))
                        .header("Content-Type", "application/json")
                        .header(
                                com.finapp.platform.api.IdempotencyKeyHeader.NAME,
                                UUID.randomUUID().toString())
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"loginIdentifier\":\"" + login
                                                + "\",\"displayName\":\"Ada Lovelace\","
                                                + "\"password\":\"" + PASSWORD + "\"}"))
                        .build();
        return send(request);
    }

    private HttpResponse<String> authenticate(String login) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/v1/authentications"))
                        .header("Content-Type", "application/json")
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        "{\"loginIdentifier\":\"" + login + "\",\"password\":\""
                                                + PASSWORD + "\"}"))
                        .build();
        return send(request);
    }

    private HttpResponse<String> post(String path, String token) throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + token)
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build();
        return send(request);
    }

    private static HttpResponse<String> send(HttpRequest request) throws Exception {
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static String tokenFrom(String body) {
        Matcher matcher = Pattern.compile("\"sessionToken\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry sessionToken: %s", body).isTrue();
        return matcher.group(1);
    }
}
