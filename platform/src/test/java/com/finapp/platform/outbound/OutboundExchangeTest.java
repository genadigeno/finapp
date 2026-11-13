package com.finapp.platform.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The overall bound on a provider exchange (`P16-TSK-018`): what the JDK's request timeout leaves unbounded - a body that stops
 * after the headers - is cut, and every other answer passes through as the JDK raised it. Hermetic: a JDK HTTP server on the
 * loopback, released at the end of each test.
 */
class OutboundExchangeTest {

    /** The request's own timeout: far longer than the overall bound, so a cut here can only be the overall bound's. */
    private static final Duration HEADER_TIMEOUT = Duration.ofSeconds(20);

    private static final Duration OVERALL = Duration.ofMillis(400);

    private HttpServer server;
    private ExecutorService serverThreads;
    private final CountDownLatch released = new CountDownLatch(1);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @BeforeEach
    void startTheProvider() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        // Headers, one byte of a ten-byte body, then nothing until the test ends.
        server.createContext("/stalled", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 10);
            OutputStream out = exchange.getResponseBody();
            out.write('x');
            out.flush();
            try {
                released.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.createContext("/whole", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"status\":\"approved\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/hundred", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "0123456789".repeat(10).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopTheProvider() {
        released.countDown();
        server.stop(0);
        serverThreads.shutdownNow();
    }

    private HttpRequest request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path))
                .timeout(HEADER_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
    }

    @Test
    @DisplayName("a body that stops after the headers is cut at the overall bound, as a timeout, where the JDK's own send is still waiting")
    void aStalledBodyIsCutAtTheOverallBound() throws Exception {
        // The control first: the JDK's send, its request timeout as short as the overall bound, still waits on the body.
        HttpRequest shortTimeout = HttpRequest.newBuilder(request("/stalled").uri())
                .timeout(OVERALL)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<HttpResponse<byte[]>> plain = caller.submit(() -> client.send(shortTimeout, HttpResponse.BodyHandlers.ofByteArray()));
            assertThatExceptionOfType(java.util.concurrent.TimeoutException.class)
                    .as("the JDK's request timeout ends at the headers: the stalled body still holds the caller well past it")
                    .isThrownBy(() -> plain.get(OVERALL.multipliedBy(4).toMillis(), TimeUnit.MILLISECONDS));
            plain.cancel(true);
        } finally {
            caller.shutdownNow();
        }

        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThatExceptionOfType(HttpTimeoutException.class)
                        .isThrownBy(() -> OutboundExchange.send(client, request("/stalled"), HttpResponse.BodyHandlers.ofByteArray(), OVERALL))
                        .withMessageContaining("overall bound"));
    }

    @Test
    @DisplayName("a refused connection is still a ConnectException - the one failure that is knowledge - never widened into a timeout")
    void aRefusedConnectionStaysKnowledge() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = socket.getLocalPort();
        }
        HttpRequest refused = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + closed + "/whole"))
                .timeout(HEADER_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                assertThatExceptionOfType(ConnectException.class)
                        .isThrownBy(() -> OutboundExchange.send(client, refused, HttpResponse.BodyHandlers.ofByteArray(), Duration.ofSeconds(8))));
    }

    @Test
    @DisplayName("a whole answer within the bound passes through unchanged")
    void aWholeAnswerPassesThrough() throws Exception {
        HttpResponse<byte[]> answer = OutboundExchange.send(client, request("/whole"), HttpResponse.BodyHandlers.ofByteArray(), Duration.ofSeconds(5));
        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(new String(answer.body(), StandardCharsets.UTF_8)).isEqualTo("{\"status\":\"approved\"}");
    }

    @Test
    @DisplayName("the limited body keeps at most its limit and stops reading there; under it, the whole body")
    void theLimitedBodyStopsAtItsLimit() throws Exception {
        HttpResponse<byte[]> cut = OutboundExchange.send(client, request("/hundred"), OutboundExchange.limitedBytes(11), Duration.ofSeconds(5));
        assertThat(new String(cut.body(), StandardCharsets.UTF_8)).isEqualTo("01234567890");
        HttpResponse<byte[]> whole = OutboundExchange.send(client, request("/hundred"), OutboundExchange.limitedBytes(101), Duration.ofSeconds(5));
        assertThat(whole.body()).hasSize(100);
    }

    @Test
    @DisplayName("the limited body is inside the overall bound: a stalled body behind it is cut too")
    void theLimitedBodyIsBounded() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertThatExceptionOfType(HttpTimeoutException.class)
                        .isThrownBy(() -> OutboundExchange.send(client, request("/stalled"), OutboundExchange.limitedBytes(64), OVERALL)));
    }

    @Test
    @DisplayName("a bound that is not positive, and a limit under one byte, are refused")
    void nonsenseIsRefused() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> OutboundExchange.send(client, request("/whole"), HttpResponse.BodyHandlers.ofByteArray(), Duration.ZERO));
        assertThatExceptionOfType(IllegalArgumentException.class).isThrownBy(() -> OutboundExchange.limitedBytes(0));
    }
}
