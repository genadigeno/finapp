package com.finapp.app.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.telemetry.RecordedSpans;
import com.finapp.platform.api.ApiVersion;
import com.finapp.platform.correlation.CorrelationContext;
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
import java.util.Map;
import java.util.UUID;
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
 * ADR-0034: a caller-supplied correlation value reaches no sink.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>{@code P0-TSK-033} probed {@code CorrelationFilter} and confirmed that
 * {@code jane.doe@example.com}, {@code acct:GB29NWBK60161331926819},
 * {@code customer-1990-05-14} and {@code +447700900123} were all accepted as the flow's
 * correlation identifier — which is then written to every log line as a top-level ECS field,
 * stamped on every span, stored in four durable columns and returned in every problem-detail body.
 * That is a caller placing personal and financial data into systems with different access control
 * and months of retention, which {@code INV-AUD-02} forbids.
 *
 * <p>It was recorded as debt and named risk R1 by the Phase 0 → Phase 1 transition, to be closed
 * before the first customer-facing endpoint. This is that closure.
 *
 * <h2>What it asserts, and why it asserts it there</h2>
 *
 * <p>The obvious test is "the value does not appear in a log line". That would be weaker than it
 * looks: it proves one sink on one code path, and there are four sinks plus the response.
 *
 * <p>All four read from the <strong>same</strong> place — {@link CorrelationContext}, established
 * once per request by {@code CorrelationFilter}. So the property is asserted at the source: what
 * the context holds during the request is the platform's own identifier and never the caller's,
 * which covers every sink including ones that do not exist yet. The MDC is checked alongside it
 * because the ECS encoder lifts MDC entries to top-level log fields, so it is the log sink's own
 * input rather than a proxy for it.
 *
 * <p>The span is then checked directly, because a span attribute is stamped by a span processor
 * rather than by anything that reads the context on this thread — a second mechanism, and one that
 * a context-level assertion alone would not cover.
 *
 * <h2>Hermetic</h2>
 *
 * <p>The datasource points at a closed port. Nothing here needs infrastructure, and the durable
 * columns are covered by the argument above rather than by a database: Phase 0 has no HTTP flow
 * that writes one, so a database test would assert against a row nothing produces.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/absent")
@Tag("slice")
@Import({RecordedSpans.class, CallerCorrelationIsNotPropagatedTest.ContextProbe.class})
@DisplayName("A caller's correlation value is never propagated (ADR-0034)")
class CallerCorrelationIsNotPropagatedTest {

    /**
     * The four values {@code P0-TSK-033} confirmed were accepted.
     *
     * <p>They are the test data on purpose. A synthetic {@code "client-flow-77"} would prove the
     * mechanism and not the risk; these are what a caller actually put through it.
     */
    private static final List<String> PERSONAL_DATA =
            List.of(
                    "jane.doe@example.com",
                    "acct:GB29NWBK60161331926819",
                    "customer-1990-05-14",
                    "+447700900123");

    @LocalServerPort private int port;

    @Autowired private InMemorySpanExporter spans;

    private static final HttpClient CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void reset() {
        spans.reset();
        ContextProbe.seenCorrelation = null;
        ContextProbe.seenMdc = null;
    }

    @Test
    @DisplayName("personal data in the header reaches neither the context, the MDC, nor a span")
    void personalDataReachesNoSink() throws Exception {
        for (String personal : PERSONAL_DATA) {
            reset();
            HttpResponse<String> response = get(personal);

            assertThat(response.statusCode()).as("the request still succeeds").isEqualTo(200);

            // The source every sink reads from.
            assertThat(ContextProbe.seenCorrelation)
                    .as("the correlation context must never hold %s", personal)
                    .isNotNull()
                    .doesNotContain(personal);

            // The log sink's own input: the ECS encoder lifts these to top-level fields.
            assertThat(ContextProbe.seenMdc)
                    .as("no MDC value may carry %s", personal)
                    .isNotNull();
            assertThat(ContextProbe.seenMdc.values())
                    .as("no MDC value may carry %s", personal)
                    .noneMatch(value -> value != null && value.contains(personal));

            // A second mechanism: the attribute is stamped by a span processor, not by this thread.
            assertThat(awaitCorrelationAttributes())
                    .as("no span attribute may carry %s", personal)
                    .noneMatch(value -> value.contains(personal));

            // And the response body/headers the client gets back.
            assertThat(response.headers().firstValue(CorrelationFilter.HEADER).orElseThrow())
                    .as("the issued identifier is ours, not %s", personal)
                    .doesNotContain(personal);
        }
    }

    @Test
    @DisplayName("the issued identifier is a platform-minted UUID, on every request")
    void theIssuedIdentifierIsAlwaysOurs() throws Exception {
        // The positive half. "Does not contain what the caller sent" is satisfied by a value
        // derived from it, by an empty value, and by a sanitised one - the exact weakness a
        // P0-TSK-025 mutation sweep found in the sibling assertion it replaced. Asserting the
        // shape pins what the value must BE.
        String withHeader = get("jane.doe@example.com").headers()
                .firstValue(CorrelationFilter.HEADER).orElseThrow();
        String withoutHeader = get(null).headers()
                .firstValue(CorrelationFilter.HEADER).orElseThrow();

        for (String issued : List.of(withHeader, withoutHeader)) {
            UUID parsed = UUID.fromString(issued);
            assertThat(parsed.version()).as("%s must be a UUIDv7", issued).isEqualTo(7);
        }
        assertThat(withHeader).as("two requests are two flows").isNotEqualTo(withoutHeader);
    }

    @Test
    @DisplayName("a well-formed caller value is echoed back, and only echoed")
    void aWellFormedCallerValueIsEchoed() throws Exception {
        // The capability ADR-0034 keeps: a gateway or an asynchronous caller can match a response
        // to a request. It is safe precisely because it goes back to whoever sent it and nowhere
        // else - which is what the first test asserts.
        String reference = "client-flow-77";
        HttpResponse<String> response = get(reference);

        assertThat(response.headers().firstValue(CorrelationFilter.CLIENT_HEADER))
                .as("the caller's own value comes back untouched")
                .contains(reference);
        assertThat(ContextProbe.seenCorrelation)
                .as("but it is still not the flow's identifier")
                .doesNotContain(reference);
    }

    @Test
    @DisplayName("a malformed caller value is not echoed, and does not fail the request")
    void aMalformedCallerValueIsNotEchoed() throws Exception {
        HttpResponse<String> response = get("bad value with spaces!");

        assertThat(response.statusCode())
                .as("a malformed diagnostic hint is not a reason to decline a request")
                .isEqualTo(200);
        assertThat(response.headers().firstValue(CorrelationFilter.CLIENT_HEADER))
                .as("nothing derived from a value we refused")
                .isEmpty();
        assertThat(UUID.fromString(
                        response.headers().firstValue(CorrelationFilter.HEADER).orElseThrow())
                        .version())
                .isEqualTo(7);
    }

    @Test
    @DisplayName("the guard is not vacuous: the probe and the span attribute are really observed")
    void theGuardIsNotVacuous() throws Exception {
        // Every assertion above is of the form "X does not contain Y". All of them pass over a
        // probe that never ran and a span list that is empty. This asserts the observations are
        // real, which is the failure mode this repository has met four times.
        get(null);

        assertThat(ContextProbe.seenCorrelation).as("the probe must have run").isNotNull();
        assertThat(ContextProbe.seenMdc).as("the MDC must have been captured").isNotEmpty();
        assertThat(awaitCorrelationAttributes())
                .as("at least one span must carry the correlation attribute")
                .isNotEmpty();
    }

    // -----------------------------------------------------------------

    /**
     * The correlation attributes on recorded spans, waited for rather than sampled.
     *
     * <p><strong>Why a wait, and why this shape.</strong> The server span ends after the response
     * has been written, so the client can hold a complete response while the span it produced has
     * not yet reached the exporter. Asserting immediately therefore races, and it races in the
     * worst direction: {@code isNotEmpty} on an empty list fails, but a {@code noneMatch} over an
     * empty list <em>passes</em> — so the guard would silently stop checking the span sink on a
     * loaded machine while still reporting green.
     *
     * <p>This was not theoretical. It passed locally on every run and **failed on the first CI
     * run**, which is precisely the difference the CI gate exists to expose. {@code RecordedSpans}'
     * own javadoc predicted it: *"a test that waits for telemetry is a test that fails
     * intermittently on a loaded machine"* — the answer is to wait on the **condition** rather
     * than for a duration, which is what this does. The bound is generous because exceeding it is
     * a failure, never a pass.
     */
    private List<String> awaitCorrelationAttributes() throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        List<String> attributes = correlationAttributes();
        while (attributes.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
            attributes = correlationAttributes();
        }
        assertThat(attributes)
                .as("a span carrying the correlation attribute must be recorded, or this asserts nothing")
                .isNotEmpty();
        return attributes;
    }

    private List<String> correlationAttributes() {
        AttributeKey<String> key = AttributeKey.stringKey(TraceAttributes.CORRELATION_ID);
        return spans.getFinishedSpanItems().stream()
                .map(SpanData::getAttributes)
                .map(attributes -> attributes.get(key))
                .filter(value -> value != null)
                .toList();
    }

    private HttpResponse<String> get(String correlationHeader) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(
                                URI.create(
                                "http://127.0.0.1:"
                                        + port
                                        // Unversioned in the fixture, versioned on the wire: the
                                        // /v1 prefix is applied once in the composition root to
                                        // every handler under com.finapp (ADR-0015), which
                                        // includes a probe controller.
                                        + ApiVersion.CURRENT_PREFIX
                                        + ContextProbe.PATH))
                        .GET();
        if (correlationHeader != null) {
            request.header(CorrelationFilter.HEADER, correlationHeader);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Reports what the flow's context and MDC actually held while the request was being served. */
    @RestController
    static class ContextProbe {

        static final String PATH = "/probe/correlation-context";

        static volatile String seenCorrelation;
        static volatile Map<String, String> seenMdc;

        @GetMapping(PATH)
        String peek() {
            // Read inside the request, on the request thread. Reading from the test thread would
            // find an empty context and assert nothing - the defect the P0-TSK-028 review found.
            seenCorrelation = CorrelationContext.current().orElseThrow().correlationId().value();
            seenMdc = MDC.getCopyOfContextMap();
            return "ok";
        }
    }
}
