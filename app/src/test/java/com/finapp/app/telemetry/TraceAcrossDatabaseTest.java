package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.api.CorrelationFilter;
import com.finapp.platform.telemetry.TraceAttributes;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * One request, one trace, spanning HTTP and the database.
 *
 * <p>This is the part of {@code P0-TSK-028}'s acceptance criterion that can be demonstrated in
 * Phase 0. The criterion names HTTP, database, outbox and consumer; the outbox relay is not wired
 * into the application and there is no broker adapter or consumer at all, so those two legs are
 * recorded against the tasks that bring them rather than faked here. What is real is real: a live
 * PostgreSQL, a real HTTP request, and the spans the SDK actually exported.
 *
 * <p><strong>Why readiness is the request under test.</strong> It is the only endpoint in Phase 0
 * that touches the database, and it touches it the way the application will: through the pool,
 * which is what {@code ADR-0016} insists on. A test that opened its own connection would prove
 * something about JDBC rather than about this platform's request path.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RecordedSpans.class)
class TraceAcrossDatabaseTest {

    @LocalServerPort private int port;

    @Autowired private InMemorySpanExporter spans;

    @BeforeEach
    void clearRecordedSpans() {
        spans.reset();
    }

    @Test
    @DisplayName("a request that reaches PostgreSQL produces one connected trace")
    void httpAndDatabaseShareOneTrace() throws Exception {
        HttpResponse<String> response = get("/actuator/health/readiness");
        assertThat(response.statusCode()).as("the database must actually be reachable").isEqualTo(200);

        List<SpanData> recorded = spans.getFinishedSpanItems();
        assertThat(recorded).isNotEmpty();

        assertThat(recorded)
                .as("acquiring a connection must be visible, or the database leg is invisible")
                .anySatisfy(span -> assertThat(span.getName()).isEqualTo(TracedDataSource.SPAN_NAME));

        Set<String> traces = recorded.stream().map(SpanData::getTraceId).collect(Collectors.toSet());
        assertThat(traces)
                .as("one request is one trace; two would mean the database work was orphaned")
                .hasSize(1);
    }

    @Test
    @DisplayName("the database span is a child of the request, not a root of its own")
    void theDatabaseSpanIsInsideTheRequest() throws Exception {
        get("/actuator/health/readiness");

        SpanData database =
                spans.getFinishedSpanItems().stream()
                        .filter(span -> span.getName().equals(TracedDataSource.SPAN_NAME))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("no database span was recorded"));

        // The distinction the previous test cannot make. Spans sharing a trace id but with no
        // parent are a flat list, and a flat list cannot answer "what was this connection
        // acquired for?" - which is the entire question during an incident.
        assertThat(database.getParentSpanContext().isValid())
                .as("the database span must hang off the request that caused it")
                .isTrue();
    }

    @Test
    @DisplayName("every span in the trace carries the correlation the client was given")
    void correlationReachesEverySpanInTheTrace() throws Exception {
        HttpResponse<String> response = get("/actuator/health/readiness");
        String correlationId = response.headers().firstValue(CorrelationFilter.HEADER).orElseThrow();

        // The fourth correlation sink, and the last clause of P0-TSK-014's acceptance criterion -
        // which named a trace and could not be verified when it was written, because there was no
        // tracing. CorrelationSinkCoverageTest has been holding the question open ever since.
        List<SpanData> recorded = spans.getFinishedSpanItems();
        assertThat(recorded).isNotEmpty();
        for (SpanData span : recorded) {
            assertThat(span.getAttributes().get(AttributeKey.stringKey(TraceAttributes.CORRELATION_ID)))
                    .as("span '%s'", span.getName())
                    .isEqualTo(correlationId);
        }
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
