package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.platform.telemetry.Spans;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * The tracer-backed span (`P8-TSK-024`) read back from the SDK the way {@link RecordedSpans}
 * reads the application's: a real OpenTelemetry tracer behind Micrometer's bridge, its finished
 * spans exported in memory - so what is asserted is the span as it would leave the platform,
 * not that a tracing call was made.
 *
 * <p>The rules: a span named for the leg; only {@link Spans#admits admitted} identifiers on it
 * (an amount or a malformed identifier dropped); a failure carries the exception's class and
 * never its message, and the exception propagates unchanged; no tracer, or a tracer that
 * cannot start a span, leaves the work to run untraced.
 */
@DisplayName("the tracer-backed domain spans (P8-TSK-024)")
class TracerSpansTest {

    private InMemorySpanExporter exporter;
    private SdkTracerProvider provider;
    private Tracer tracer;

    @BeforeEach
    void tracer() {
        exporter = InMemorySpanExporter.create();
        provider =
                SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build();
        tracer =
                new OtelTracer(
                        provider.get("tracer-spans-test"),
                        new OtelCurrentTraceContext(),
                        event -> {});
    }

    @AfterEach
    void close() {
        provider.close();
    }

    private static ObjectProvider<Tracer> providing(Tracer tracer) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        beans.registerSingleton("tracer", tracer);
        return beans.getBeanProvider(Tracer.class);
    }

    private static ObjectProvider<Tracer> none() {
        return new DefaultListableBeanFactory().getBeanProvider(Tracer.class);
    }

    private static Map<String, Object> attributes(SpanData span) {
        Map<String, Object> attributes = new TreeMap<>();
        span.getAttributes().forEach((key, value) -> attributes.put(key.getKey(), value));
        return attributes;
    }

    @Test
    @DisplayName("a span is recorded under the leg's name with only the admitted identifiers -"
            + " an amount and a malformed identifier are dropped")
    void recordsOnlyAdmittedIdentifiers() {
        String fileId = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        Spans spans = new TracerSpans(providing(tracer));

        String result =
                spans.within(
                        "settlement.accept",
                        Map.of(
                                "file.id", fileId,
                                "run.id", runId,
                                "amount", "12.34",
                                "source.id", "not-a-uuid"),
                        () -> "accepted");

        assertThat(result).isEqualTo("accepted");
        assertThat(exporter.getFinishedSpanItems())
                .as("one span, ended")
                .singleElement()
                .satisfies(span -> {
                    assertThat(span.getName()).isEqualTo("settlement.accept");
                    assertThat(span.hasEnded()).isTrue();
                    assertThat(attributes(span))
                            .as("identifiers only (ADR-0072): the amount and the malformed"
                                    + " source id never leave the platform")
                            .containsExactlyInAnyOrderEntriesOf(
                                    Map.of("file.id", fileId, "run.id", runId));
                });
    }

    @Test
    @DisplayName("the work runs inside the span, so its own spans are the leg's children")
    void theWorkRunsInsideTheSpan() {
        Spans spans = new TracerSpans(providing(tracer));
        AtomicReference<String> currentInside = new AtomicReference<>();

        spans.within(
                "reconciliation.chunk",
                Map.of(),
                () -> {
                    currentInside.set(tracer.currentSpan().context().spanId());
                    return null;
                });

        SpanData recorded = exporter.getFinishedSpanItems().get(0);
        assertThat(currentInside.get())
                .as("the leg's span is current while its work runs")
                .isEqualTo(recorded.getSpanId());
        assertThat(tracer.currentSpan())
                .as("and the scope is closed after it")
                .isNull();
    }

    @Test
    @DisplayName("a failing work records error.type as the exception's simple class - never its"
            + " message - ends the span, and rethrows the same exception")
    void aFailureCarriesItsClassOnly() {
        Spans spans = new TracerSpans(providing(tracer));
        IllegalStateException failure =
                new IllegalStateException("refused 12.34 for PSP-REM-123456");

        assertThatThrownBy(
                        () -> spans.within(
                                "reconciliation.resolve",
                                Map.of("resolution.id", UUID.randomUUID().toString()),
                                () -> {
                                    throw failure;
                                }))
                .isSameAs(failure);

        assertThat(exporter.getFinishedSpanItems())
                .singleElement()
                .satisfies(span -> {
                    assertThat(span.hasEnded()).as("a failed leg's span still ends").isTrue();
                    Map<String, Object> attributes = attributes(span);
                    assertThat(attributes)
                            .containsEntry("error.type", "IllegalStateException")
                            .containsKey("resolution.id")
                            .hasSize(2);
                    assertThat(attributes.values())
                            .as("the message may echo a value - it never reaches the span")
                            .noneSatisfy(value -> assertThat(String.valueOf(value))
                                    .contains("12.34"));
                    assertThat(span.getEvents())
                            .as("no exception event carrying the message either")
                            .isEmpty();
                });
    }

    @Test
    @DisplayName("without a tracer the work runs and returns, untraced")
    void noTracerRunsUntraced() {
        AtomicInteger runs = new AtomicInteger();

        Integer result =
                new TracerSpans(none())
                        .within(
                                "settlement.parse",
                                Map.of("file.id", UUID.randomUUID().toString()),
                                () -> runs.incrementAndGet());

        assertThat(result).isEqualTo(1);
        assertThat(runs).hasValue(1);
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    @DisplayName("without a tracer the work's own failure still propagates unchanged")
    void noTracerPropagatesTheFailure() {
        IllegalArgumentException failure = new IllegalArgumentException("the leg's own");

        assertThatThrownBy(
                        () -> new TracerSpans(none())
                                .within("settlement.receive", Map.of(), () -> {
                                    throw failure;
                                }))
                .isSameAs(failure);
    }

    @Test
    @DisplayName("a tracer that cannot start a span never fails the work - it runs untraced")
    void aBrokenTracerNeverFailsTheWork() {
        Tracer broken =
                (Tracer) Proxy.newProxyInstance(
                        Tracer.class.getClassLoader(),
                        new Class<?>[] {Tracer.class},
                        (proxy, method, args) -> {
                            if (method.getDeclaringClass() == Object.class) {
                                return switch (method.getName()) {
                                    case "equals" -> proxy == args[0];
                                    case "hashCode" -> System.identityHashCode(proxy);
                                    default -> "a broken tracer";
                                };
                            }
                            throw new IllegalStateException("the tracer is broken");
                        });
        AtomicInteger runs = new AtomicInteger();

        String result =
                new TracerSpans(providing(broken))
                        .within("reconciliation.rematch", Map.of(), () -> {
                            runs.incrementAndGet();
                            return "decided";
                        });

        assertThat(result).isEqualTo("decided");
        assertThat(runs).as("the work ran exactly once").hasValue(1);
    }

    @Test
    @DisplayName("a null tracer provider is refused at construction")
    void aNullProviderIsRefused() {
        assertThatThrownBy(() -> new TracerSpans(null))
                .isInstanceOf(NullPointerException.class);
    }
}
