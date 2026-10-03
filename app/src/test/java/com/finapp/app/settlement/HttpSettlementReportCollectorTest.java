package com.finapp.app.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.settlement.SettlementFile;
import com.finapp.settlement.SettlementReportCollector;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The HTTP pull adapter's construction (`P8-TSK-021`, the tests agent's find): it speaks HTTP
 * alone, so a source URL the transport guard admits but this adapter cannot speak — {@code sftp}
 * — refuses at startup rather than throwing on every pull; and it never prints its key. And its
 * body bound (the Phase 8 → 9 transition, SEC-07): a runaway body is aborted, never buffered
 * whole, while a body the door itself must judge still arrives verbatim.
 */
@DisplayName("the HTTP settlement report collector (P8-TSK-021)")
class HttpSettlementReportCollectorTest {

    private static final byte[] KEY = new byte[32];

    @Test
    @DisplayName("a source URL of any scheme but http or https refuses construction, naming the"
            + " scheme and never the URL")
    void onlyHttpIsSpoken() {
        for (String unspoken :
                List.of("sftp://reporter:hunter2@reports.bank.example.com/outbox",
                        "ftp://reports.bank.example.com")) {
            assertThatThrownBy(
                            () ->
                                    new HttpSettlementReportCollector(
                                            "simulated-bank.statement",
                                            URI.create(unspoken),
                                            Duration.ofSeconds(5),
                                            KEY))
                    .as(unspoken)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("simulated-bank.statement")
                    .hasMessageNotContaining("reports.bank.example.com")
                    .hasMessageNotContaining("hunter2");
        }
        for (String spoken :
                List.of("https://reports.psp.example.com", "http://localhost:8089")) {
            assertThatCode(
                            () ->
                                    new HttpSettlementReportCollector(
                                            "simulated-psp.settlement",
                                            URI.create(spoken),
                                            Duration.ofSeconds(5),
                                            KEY))
                    .as(spoken)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("a 9 MiB body - declared, and chunked with no declared length - is aborted"
            + " and answered REFUSED_ANSWER, never buffered whole (SEC-07)")
    void aRunawayBodyIsAbortedNotBuffered() throws Exception {
        int nineMiB = 9 * 1024 * 1024;
        // Declared: the Content-Length alone refuses it, before any body is subscribed.
        SettlementReportCollector.Collected declared =
                collectedFrom(
                        (exchange, body) -> {
                            try {
                                exchange.sendResponseHeaders(200, nineMiB);
                                streamZeros(exchange.getResponseBody(), nineMiB);
                            } catch (IOException cutOff) {
                                // The client aborted mid-body: exactly the point.
                            }
                        });
        assertThat(declared)
                .isEqualTo(
                        new SettlementReportCollector.Collected.Failed(
                                SettlementReportCollector.FailureOutcome.REFUSED_ANSWER));

        // Undeclared (chunked): cut off at the first byte past the bound, mid-stream.
        SettlementReportCollector.Collected chunked =
                collectedFrom(
                        (exchange, body) -> {
                            try {
                                exchange.sendResponseHeaders(200, 0);
                                streamZeros(exchange.getResponseBody(), nineMiB);
                            } catch (IOException cutOff) {
                                // The client aborted mid-body: exactly the point.
                            }
                        });
        assertThat(chunked)
                .isEqualTo(
                        new SettlementReportCollector.Collected.Failed(
                                SettlementReportCollector.FailureOutcome.REFUSED_ANSWER));
    }

    @Test
    @DisplayName("a body at the 8 MiB bound, and one a single byte over it, both arrive whole"
            + " - the byte over is the DOOR's refusal to make, not this adapter's")
    void bodiesTheDoorMustJudgeArriveWhole() throws Exception {
        for (int size :
                new int[] {
                    SettlementFile.MAX_CONTENT_LENGTH, SettlementFile.MAX_CONTENT_LENGTH + 1
                }) {
            SettlementReportCollector.Collected collected =
                    collectedFrom(
                            (exchange, body) -> {
                                try {
                                    exchange.sendResponseHeaders(200, size);
                                    streamZeros(exchange.getResponseBody(), size);
                                } catch (IOException unexpected) {
                                    throw new UncheckedIOException(unexpected);
                                }
                            });
            assertThat(collected)
                    .as("%d bytes arrive whole", size)
                    .isInstanceOf(SettlementReportCollector.Collected.Report.class);
            assertThat(((SettlementReportCollector.Collected.Report) collected).content())
                    .hasSize(size);
        }
    }

    /** One pull against a loopback stub whose handler writes whatever it pleases. */
    private static SettlementReportCollector.Collected collectedFrom(
            BiConsumer<com.sun.net.httpserver.HttpExchange, Void> handler) throws IOException {
        HttpServer stub =
                HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        stub.createContext(
                "/",
                exchange -> {
                    try (exchange) {
                        handler.accept(exchange, null);
                    }
                });
        stub.start();
        try {
            HttpSettlementReportCollector collector =
                    new HttpSettlementReportCollector(
                            "simulated-psp.settlement",
                            URI.create(
                                    "http://" + stub.getAddress().getAddress().getHostAddress()
                                            + ":" + stub.getAddress().getPort()),
                            Duration.ofSeconds(30),
                            KEY);
            return collector.collect("2026-09-28");
        } finally {
            stub.stop(0);
        }
    }

    /** Streams {@code size} zero bytes without ever holding them: the stub stays small. */
    private static void streamZeros(OutputStream out, int size) throws IOException {
        byte[] chunk = new byte[64 * 1024];
        int remaining = size;
        while (remaining > 0) {
            int step = Math.min(remaining, chunk.length);
            out.write(chunk, 0, step);
            remaining -= step;
        }
        out.flush();
    }

    @Test
    @DisplayName("its string form names the source and never the key")
    void neverPrintsItsKey() {
        byte[] key =
                "0123456789abcdef0123456789abcdef"
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        HttpSettlementReportCollector collector =
                new HttpSettlementReportCollector(
                        "simulated-psp.settlement",
                        URI.create("https://reports.psp.example.com"),
                        Duration.ofSeconds(5),
                        key);
        assertThat(collector.toString())
                .isEqualTo("HttpSettlementReportCollector[simulated-psp.settlement]")
                .doesNotContain("0123456789abcdef");
    }
}
