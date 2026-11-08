package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.api.CorrelationFilter;
import com.finapp.platform.api.ApiVersion;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.telemetry.TraceAttributes;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A request produces spans, and every one of them can be found from the correlation identifier a
 * customer would quote.
 *
 * <p>Hermetic: the datasource points at a closed port, so nothing here needs infrastructure. The
 * database leg of the trace is {@code TraceAcrossDatabaseTest}, which needs a real PostgreSQL.
 *
 * <h2>What is actually being asserted</h2>
 *
 * <p>Not "tracing is enabled" — that is a configuration fact and would pass against a system that
 * recorded spans nobody could use. The properties that matter are that a span exists for a real
 * HTTP request, that it carries the flow's correlation identifier, and that a caller's inbound
 * trace context is joined rather than replaced. The last one is what makes a trace survive an
 * instance hop, which {@code ADR-0014} says is the normal case rather than the exception.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
@Tag("slice")
@Import({RecordedSpans.class, TracingTest.MdcProbe.class})
class TracingTest {

    @LocalServerPort private int port;

    @Autowired private InMemorySpanExporter spans;

    @Autowired private Tracer tracer;

    @Autowired private SdkTracerProvider tracerProvider;

    /**
     * Reports the logging context as seen from inside a request, and reaches for a connection. Not production code.
     *
     * <p>The requests here are the API port's: since `P15-TSK-008` the actuator answers on the management port alone,
     * which serves the orchestrator and the scraper and is neither traced nor correlated - a probe every few seconds is
     * not a flow anyone asks about. Until then these tests drove liveness and readiness.
     */
    @com.finapp.app.session.Unauthenticated
    @RestController
    static class MdcProbe {
        static final String PATH = "/probe/log-context";
        static final String CONNECTION = "/probe/connection";
        static volatile Map<String, String> seen;

        private final javax.sql.DataSource dataSource;

        MdcProbe(javax.sql.DataSource dataSource) {
            this.dataSource = dataSource;
        }

        @GetMapping(PATH)
        String peek() {
            seen = MDC.getCopyOfContextMap();
            return "ok";
        }

        /** Reaches for a connection, as readiness did: a second span, the database being absent. */
        @GetMapping(CONNECTION)
        @SuppressWarnings("try") // The connection is opened for its span, then closed: nothing reads it.
        String connection() {
            try (java.sql.Connection ignored = dataSource.getConnection()) {
                return "connected";
            } catch (java.sql.SQLException absent) {
                return "absent";
            }
        }
    }

    @BeforeEach
    void clearRecordedSpans() {
        // Flushed first, then cleared. Spring Boot's batch processor exports every span a second time, up to its delay
        // later than the in-memory processor did, so without the flush a span from before this test lands inside it: the
        // context's own start reads the database outside any request (CounterpartyChartGuard), and that span - no parent,
        // no correlation - surfaced in whichever test ran a few seconds in (found by P15-TSK-008, whose requests moved).
        tracerProvider.forceFlush().join(10, TimeUnit.SECONDS);
        spans.reset();
    }

    @Test
    @DisplayName("a request records a span")
    void aRequestRecordsASpan() throws Exception {
        get(ApiVersion.CURRENT_PREFIX + MdcProbe.PATH);

        assertThat(recorded())
                .as("an HTTP request must be visible in a trace")
                .isNotEmpty();
    }

    @Test
    @DisplayName("every recorded span carries the flow's correlation identifier")
    void everySpanCarriesCorrelation() throws Exception {
        // A request that reaches for a connection, and so produces a second span even though the
        // database is absent. Asserting "every span" over a set of one is a claim that cannot fail
        // for the right reason. (Readiness did this until P15-TSK-008 moved it off the API port.)
        HttpResponse<String> response = get(ApiVersion.CURRENT_PREFIX + MdcProbe.CONNECTION);
        String correlationId = response.headers().firstValue(CorrelationFilter.HEADER).orElseThrow();

        List<SpanData> recorded = recorded();
        assertThat(recorded)
                .as("more than one span, or 'every span' means very little here")
                .hasSizeGreaterThan(1);

        // The property that makes a trace findable from the one value a customer has. A trace id
        // would not do: it is subject to sampling, and a sampled-out trace leaves the customer
        // holding an identifier that matches nothing.
        for (SpanData span : recorded) {
            assertThat(span.getAttributes().get(AttributeKey.stringKey(TraceAttributes.CORRELATION_ID)))
                    .as("span '%s' must carry the correlation identifier the response returned; recorded: %s",
                            span.getName(), recorded.stream().map(each -> each.getName() + " trace " + each.getTraceId()
                                    + " parent " + each.getParentSpanId() + " " + each.getAttributes()).toList())
                    .isEqualTo(correlationId);
        }
    }

    @Test
    @DisplayName("an inbound trace context is joined, not replaced")
    void anInboundTraceIsContinued() throws Exception {
        // W3C traceparent: version-traceid-spanid-flags. A caller that already has a trace - the
        // previous instance in a chain, a gateway, a partner - must not have its trace broken into
        // two by us, because a trace that stops at a service boundary is the one thing a trace is
        // for.
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String traceparent = "00-" + traceId + "-00f067aa0ba902b7-01";

        HttpResponse<String> response =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .build()
                        .send(
                                HttpRequest.newBuilder(uri(ApiVersion.CURRENT_PREFIX + MdcProbe.PATH))
                                        .header("traceparent", traceparent)
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);

        assertThat(recorded()).isNotEmpty();
        assertThat(recorded())
                .as("the caller's trace must be continued rather than a new one started")
                .allSatisfy(span -> assertThat(span.getTraceId()).isEqualTo(traceId));
    }

    @Test
    @DisplayName("two requests are two traces")
    void separateRequestsAreSeparateTraces() throws Exception {
        get(ApiVersion.CURRENT_PREFIX + MdcProbe.PATH);
        get(ApiVersion.CURRENT_PREFIX + MdcProbe.CONNECTION);

        // The negative control for the test above. Without it, a bug that put every span in one
        // trace would satisfy "the caller's trace is continued" perfectly.
        assertThat(recorded().stream().map(SpanData::getTraceId).distinct().count())
                .as("unrelated requests must not be joined into one trace; recorded: %s", recorded().stream()
                        .map(each -> each.getName() + " trace " + each.getTraceId() + " parent " + each.getParentSpanId()
                                + " " + each.getAttributes()).toList())
                .isEqualTo(2);
    }

    @Test
    @DisplayName("a log line inside a request carries the trace, the span and the correlation")
    void logContextJoinsTracesToTheRecord() throws Exception {
        // The join, in the direction nobody tests: from a log line to a trace, and from a log line
        // to the durable record. It works because two independent mechanisms happen to agree -
        // Boot's log correlation writes traceId and spanId, CorrelationContext writes
        // correlationId - and nothing failed when this was unverified. Disable either and every
        // log line quietly stops being joinable, which is exactly the kind of loss discovered
        // during an incident rather than before one.
        MdcProbe.seen = null;

        get(ApiVersion.CURRENT_PREFIX + MdcProbe.PATH);

        assertThat(MdcProbe.seen).as("the probe endpoint must have been reached").isNotNull();
        assertThat(MdcProbe.seen)
                .as("a log line must be joinable to its trace and to the flow's record")
                .containsKeys("traceId", "spanId", CorrelationContext.CORRELATION_ID_KEY);
        assertThat(MdcProbe.seen.get("traceId")).isNotBlank();
    }

    @Test
    @DisplayName("a span started outside any flow carries no fabricated correlation")
    void spansOutsideAFlowAreNotStamped() {
        // The branch that stops a dashboard filling with identifiers that match nothing in any
        // table. A background task with no ingress has no correlation, and inventing one would be
        // worse than leaving the attribute off.
        Span span = tracer.nextSpan().name("probe.outside.any.request").start();
        span.end();

        assertThat(recorded())
                .filteredOn(recordedSpan -> recordedSpan.getName().equals("probe.outside.any.request"))
                .singleElement()
                .satisfies(
                        recordedSpan ->
                                assertThat(
                                                recordedSpan
                                                        .getAttributes()
                                                        .get(AttributeKey.stringKey(TraceAttributes.CORRELATION_ID)))
                                        .isNull());
    }

    // -----------------------------------------------------------------

    private List<SpanData> recorded() {
        return spans.getFinishedSpanItems();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build()
                .send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
