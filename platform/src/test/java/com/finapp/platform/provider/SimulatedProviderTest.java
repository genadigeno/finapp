package com.finapp.platform.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.testing.provider.SimulatedProvider;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The provider harness reproduces every failure mode it claims to (`P0-TSK-037`).
 *
 * <p>The acceptance criterion — "the harness can reproduce every failure mode listed in
 * {@code CLAUDE.md} §Failure Engineering that involves a provider" — is a checkable claim rather
 * than a description, and this phase has repeatedly found such claims false when checked. So every
 * mode is driven here through a <strong>real HTTP client</strong> over a real socket, and the
 * assertion is on what the client actually experienced.
 *
 * <p>{@code ProviderFailureModeCoverageTest} is the other half: it holds this suite and the
 * document to each other, so a mode named in {@code CLAUDE.md} cannot go unclaimed.
 *
 * <p><strong>Why a JDK client rather than an adapter.</strong> Phase 0 has no provider adapter and
 * no HTTP client for one — the same question {@code P0-TSK-035} faced about Kafka and Redis, where
 * the answer was that a container nothing connects to tests nothing. It is different here: WireMock
 * is a real HTTP server, so a JDK {@link HttpClient} exercises the harness through exactly the
 * transport an adapter will use. The harness is proven; only the adapter is deferred.
 */
class SimulatedProviderTest {

    private static final Duration CLIENT_TIMEOUT = Duration.ofMillis(500);
    private static final String OK_BODY = "{\"status\":\"AUTHORISED\"}";

    private static SimulatedProvider provider;
    private static HttpClient client;

    @BeforeAll
    static void startProvider() {
        provider = SimulatedProvider.start();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterAll
    static void stopProvider() {
        if (provider != null) {
            provider.close();
        }
    }

    @BeforeEach
    void forgetPreviousStubs() {
        provider.reset();
    }

    @Nested
    @DisplayName("outbound - what the provider does when we call it")
    class Outbound {

        @Test
        @DisplayName("the control: a provider that works")
        void aProviderThatWorks() throws Exception {
            provider.succeedsWith("/payments/1", 200, OK_BODY);

            HttpResponse<String> response = get("/payments/1");

            // Without this, every failure test below could be passing because the harness is
            // broken rather than because it reproduced the failure.
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(OK_BODY);
            assertThat(provider.requestCount("/payments/1")).isEqualTo(1);
        }

        @Test
        @DisplayName("the request times out, and the provider received it anyway")
        void theRequestTimesOut() {
            provider.neverResponds("/payments/2");

            assertThatThrownBy(() -> get("/payments/2"))
                    .isInstanceOf(HttpTimeoutException.class);

            // The whole difficulty of a timeout, in one assertion: the provider HAS the request.
            // An adapter that recorded this as a failure would be wrong about a payment that may
            // well have been taken (INV-LIFE-03).
            assertThat(provider.requestCount("/payments/2"))
                    .as("a timeout says nothing about whether the provider acted")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the provider is unavailable")
        void theProviderIsUnavailable() throws Exception {
            provider.isUnavailable("/payments/3");

            // Distinct from a timeout, and the distinction matters: 503 is an ANSWER, and it says
            // the request was not processed. A retry here is safe; a retry after a timeout is not.
            assertThat(get("/payments/3").statusCode()).isEqualTo(503);
        }

        @Test
        @DisplayName("the provider returns a 5xx")
        void theProviderReturnsAServerError() throws Exception {
            provider.failsWith("/payments/4", 500);

            assertThat(get("/payments/4").statusCode()).isEqualTo(500);
        }

        @Test
        @DisplayName("the provider responds, slowly")
        void theProviderRespondsSlowly() throws Exception {
            Duration delay = Duration.ofMillis(300);
            provider.respondsAfter("/payments/5", delay, 200, OK_BODY);

            long startedAt = System.nanoTime();
            HttpResponse<String> response =
                    client.send(
                            HttpRequest.newBuilder(uri("/payments/5"))
                                    .timeout(Duration.ofSeconds(5))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(elapsed)
                    .as("a delayed response must actually be delayed, or the mode is decorative")
                    .isGreaterThanOrEqualTo(delay);
        }

        @Test
        @DisplayName("the provider returns a malformed body under a 200")
        void theProviderReturnsAMalformedBody() throws Exception {
            provider.respondsWithMalformedBody("/payments/6");

            HttpResponse<String> response = get("/payments/6");

            // The trap this reproduces: the status says success, so a path that checks the status
            // first has already decided the call worked before anything tries to parse the body.
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("{\"status\": \"AUTH");
        }

        @Test
        @DisplayName("the provider returns bytes that are not HTTP")
        void theProviderReturnsGarbage() {
            provider.respondsWithGarbage("/payments/7");

            // A genuinely different failure from the one above: it happens in the HTTP client,
            // before there is a body for anyone to parse.
            assertThatThrownBy(() -> get("/payments/7")).isInstanceOf(IOException.class);
        }

        @Test
        @DisplayName("the provider acts and the response is lost")
        void theResponseIsLost() {
            provider.receivesTheRequestThenLosesTheResponse("/payments/8");

            assertThatThrownBy(() -> post("/payments/8", "{}")).isInstanceOf(IOException.class);

            // "The database commits but the response is lost", in its provider form. From the
            // caller's side this is indistinguishable from a request that never arrived - and the
            // count proves the two really are different events, which is the reason the domain
            // needs an explicit indeterminate state rather than a guess.
            assertThat(provider.requestCount("/payments/8"))
                    .as("the provider acted; only the answer was lost")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("the client retries, and the provider saw every attempt")
        void theClientRetries() throws Exception {
            provider.failsThenSucceeds("/payments/9", 2, 200, OK_BODY);

            assertThat(post("/payments/9", "{}").statusCode()).isEqualTo(503);
            assertThat(post("/payments/9", "{}").statusCode()).isEqualTo(503);
            assertThat(post("/payments/9", "{}").statusCode()).isEqualTo(200);

            // The property that matters is not that the retry eventually succeeded. It is that the
            // provider received three requests: retrying a non-idempotent operation is a second
            // financial effect, not a second chance.
            assertThat(provider.requestCount("/payments/9")).isEqualTo(3);
        }

        @Test
        @DisplayName("a failure mode applies whatever verb the adapter uses")
        void aFailureModeAppliesToEveryVerb() throws Exception {
            // Found by review, by asking what the first real adapter would do. Every stub was
            // originally bound to a single HTTP method - most to GET, two to POST - so an adapter
            // POSTing to create a payment got a 404 from a stub that claimed the provider
            // SUCCEEDS. That is the worst possible shape for the failure: 404 reads as "the
            // adapter called the wrong path", so the author debugs their own code.
            //
            // A provider being unavailable is unavailable for every verb. The failure is a
            // property of the provider, not of the request method.
            provider.succeedsWith("/payments", 201, OK_BODY);
            provider.isUnavailable("/refunds");

            assertThat(post("/payments", "{}").statusCode()).isEqualTo(201);
            assertThat(get("/payments").statusCode()).isEqualTo(201);
            assertThat(post("/refunds", "{}").statusCode()).isEqualTo(503);
            assertThat(get("/refunds").statusCode()).isEqualTo(503);
        }

        @Test
        @DisplayName("the provider returns a state nobody has mapped")
        void theProviderReturnsAnUnknownState() throws Exception {
            provider.returnsUnknownState("/payments/10", "PENDING_MANUAL_REVIEW_TIER_2");

            HttpResponse<String> response = get("/payments/10");

            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("PENDING_MANUAL_REVIEW_TIER_2");
        }
    }

    @Nested
    @DisplayName("inbound - what the provider does to us")
    class Inbound {

        private CountingReceiver receiver;

        @BeforeEach
        void startReceiver() throws IOException {
            receiver = CountingReceiver.start();
        }

        @AfterEach
        void stopReceiver() {
            receiver.stop();
        }

        @Test
        @DisplayName("a webhook is delivered")
        void aWebhookIsDelivered() {
            int status = provider.deliverCallback(receiver.uri(), "{\"event\":\"captured\"}");

            assertThat(status).isEqualTo(204);
            assertThat(receiver.deliveries()).isEqualTo(1);
        }

        @Test
        @DisplayName("a webhook is duplicated")
        void aWebhookIsDuplicated() {
            provider.deliverCallback(receiver.uri(), "{\"event\":\"captured\"}", 3);

            // At-least-once is the norm, so this is the expected case rather than an edge one.
            // INV-IDEM-04 requires the second and third to produce no second effect - which is a
            // property of the CONSUMER, and this is what will let a consumer prove it.
            assertThat(receiver.deliveries()).isEqualTo(3);
            assertThat(receiver.distinctBodies())
                    .as("a duplicate is the same payload again, not a similar one")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("settlement arrives late")
        void settlementArrivesLate() {
            Duration late = Duration.ofMillis(250);

            long startedAt = System.nanoTime();
            provider.deliverCallbackAfter(receiver.uri(), "{\"event\":\"settled\"}", late);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(receiver.deliveries()).isEqualTo(1);
            assertThat(elapsed)
                    .as("a late callback must actually be late, or the mode proves nothing")
                    .isGreaterThanOrEqualTo(late);
        }
    }

    // ------------------------------------------------------------------

    private static HttpResponse<String> get(String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri(path)).timeout(CLIENT_TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri(path))
                        .timeout(CLIENT_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static URI uri(String path) {
        return URI.create(provider.baseUrl() + path);
    }

    /**
     * A receiver that counts what the provider delivered to it.
     *
     * <p>Built on the JDK's own HTTP server rather than on a second {@link SimulatedProvider},
     * deliberately: proving that WireMock delivers duplicates by asking WireMock how many it
     * received would make the harness its own oracle.
     */
    private static final class CountingReceiver {

        private final HttpServer server;
        private final AtomicInteger deliveries = new AtomicInteger();
        private final java.util.Set<String> bodies =
                java.util.concurrent.ConcurrentHashMap.newKeySet();

        private CountingReceiver(HttpServer server) {
            this.server = server;
        }

        static CountingReceiver start() throws IOException {
            HttpServer server =
                    HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            CountingReceiver receiver = new CountingReceiver(server);
            server.createContext(
                    "/callbacks",
                    exchange -> {
                        try (exchange) {
                            receiver.bodies.add(
                                    new String(
                                            exchange.getRequestBody().readAllBytes(),
                                            StandardCharsets.UTF_8));
                            receiver.deliveries.incrementAndGet();
                            exchange.sendResponseHeaders(204, -1);
                        }
                    });
            server.start();
            return receiver;
        }

        URI uri() {
            return URI.create(
                    "http://"
                            + server.getAddress().getAddress().getHostAddress()
                            + ":"
                            + server.getAddress().getPort()
                            + "/callbacks");
        }

        int deliveries() {
            return deliveries.get();
        }

        int distinctBodies() {
            return bodies.size();
        }

        void stop() {
            server.stop(0);
        }
    }

}
