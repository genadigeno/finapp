package com.finapp.app.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.fx.RateObservation;
import com.finapp.fx.RateSource;
import com.finapp.fx.ReferencePair;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code simulated-reference} adapter against a local HTTP server (`P9-TSK-005`): the bearer
 * key it sends, the strict line grammar it admits, and every failure a value.
 */
@DisplayName("the simulated-reference adapter: strict lines, bearer key, failures as values"
        + " (P9-TSK-005)")
class HttpReferenceRateSourceTest {

    private static final byte[] KEY = "a-thirty-two-byte-test-key-value!".getBytes(StandardCharsets.UTF_8);

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("a 200 is parsed line by line: admitted rows become observations, every other row"
            + " is counted as rejected - and the bearer key is the configured one")
    void aGoodAnswerIsParsedStrictly() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        String body =
                String.join(
                        "\n",
                        "EUR/USD,1.0812264160,2026-10-04T10:00:00Z",
                        "",
                        "USD/JPY,149.5,2026-10-04T10:00:00Z",
                        // The inverse of a canonical pair: never admitted (ADR-0074).
                        "USD/EUR,0.9248,2026-10-04T10:00:00Z",
                        // Eleven decimals: refused, never rounded.
                        "EUR/GBP,0.86000000001,2026-10-04T10:00:00Z",
                        // A number a careless parser would read through a double.
                        "EUR/JPY,1.6E2,2026-10-04T10:00:00Z",
                        "GBP/USD,0,2026-10-04T10:00:00Z",
                        "GBP/JPY,190.1,yesterday",
                        "CHF/USD,1.1,2026-10-04T10:00:00Z",
                        "{\"base\":\"EUR\",\"quote\":\"USD\",\"rate\":1.08}");
        URI base = serve(200, body, authorization);
        RateSource.Fetched fetched = new HttpReferenceRateSource(base, Duration.ofSeconds(5), KEY).fetch();

        assertThat(fetched).isInstanceOf(RateSource.Fetched.Rates.class);
        RateSource.Fetched.Rates rates = (RateSource.Fetched.Rates) fetched;
        assertThat(rates.observations())
                .extracting(RateObservation::pair)
                .containsExactly(ReferencePair.of("EUR", "USD"), ReferencePair.of("USD", "JPY"));
        assertThat(rates.observations().get(0).rate().value()).isEqualByComparingTo("1.0812264160");
        assertThat(rates.observations().get(0).observedAt())
                .isEqualTo(Instant.parse("2026-10-04T10:00:00Z"));
        assertThat(rates.rejected()).isEqualTo(7);
        assertThat(authorization.get())
                .isEqualTo("Bearer " + Base64.getEncoder().encodeToString(KEY));
    }

    @Test
    @DisplayName("a non-200, an oversized body, a timeout and an unreachable host are each a named"
            + " failure")
    void failuresAreValues() throws Exception {
        assertThat(new HttpReferenceRateSource(serve(500, "", null), Duration.ofSeconds(5), KEY).fetch())
                .isEqualTo(new RateSource.Fetched.Failed(RateSource.FetchFailure.REFUSED_ANSWER));
        stop();

        String huge = "EUR/USD,1.08,2026-10-04T10:00:00Z\n".repeat(3_000);
        assertThat(new HttpReferenceRateSource(serve(200, huge, null), Duration.ofSeconds(5), KEY).fetch())
                .isEqualTo(new RateSource.Fetched.Failed(RateSource.FetchFailure.REFUSED_ANSWER));
        stop();

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                HttpReferenceRateSource.RATES_PATH,
                exchange -> {
                    try {
                        Thread.sleep(3_000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                });
        server.start();
        URI slow = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        assertThat(new HttpReferenceRateSource(slow, Duration.ofMillis(300), KEY).fetch())
                .isEqualTo(new RateSource.Fetched.Failed(RateSource.FetchFailure.TIMEOUT));
        stop();
        server = null;

        int closedPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = probe.getLocalPort();
        }
        assertThat(
                        new HttpReferenceRateSource(
                                        URI.create("http://127.0.0.1:" + closedPort),
                                        Duration.ofSeconds(2),
                                        KEY)
                                .fetch())
                .isEqualTo(new RateSource.Fetched.Failed(RateSource.FetchFailure.UNAVAILABLE));
    }

    @Test
    @DisplayName("the key never reaches toString")
    void theKeyIsNeverRendered() {
        HttpReferenceRateSource source =
                new HttpReferenceRateSource(URI.create("http://127.0.0.1:1"), Duration.ofSeconds(1), KEY);
        assertThat(source.toString())
                .doesNotContain(new String(KEY, StandardCharsets.UTF_8))
                .doesNotContain(Base64.getEncoder().encodeToString(KEY));
    }

    // -----------------------------------------------------------------

    private URI serve(int status, String body, AtomicReference<String> authorization)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                HttpReferenceRateSource.RATES_PATH,
                exchange -> {
                    if (authorization != null) {
                        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    }
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
                    if (bytes.length > 0) {
                        try (OutputStream out = exchange.getResponseBody()) {
                            out.write(bytes);
                        }
                    }
                    exchange.close();
                });
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }
}
