package com.finapp.app.telemetry;

import com.finapp.platform.telemetry.Spans;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The tracer-backed {@link Spans} (`P8-TSK-024`, `PHASE_8_PLAN.md` §15's tracing): one
 * domain-named span around a leg's unit of work - a received delivery, a parsed or accepted
 * file, a matching chunk, a rematch batch, the ageing leg, a resolution's decision.
 *
 * <p><strong>Identifiers only.</strong> Every attribute is judged by {@link Spans#admits}: an
 * identifier key with an identifier value, or it is dropped - so no caller can put an amount, a
 * reference value or a narrative on a span (ADR-0072, `INV-AUD-02`). A failure carries only the
 * exception's class ({@code TracedDataSource}'s rule: a message may echo a value). The tracer is
 * resolved lazily, as {@code TracedDataSource} does; with none, the work runs untraced, and a
 * span that cannot be recorded never fails the work.
 */
@Slf4j
public final class TracerSpans implements Spans {

    private final ObjectProvider<Tracer> tracers;

    public TracerSpans(ObjectProvider<Tracer> tracers) {
        this.tracers = Objects.requireNonNull(tracers, "tracers must not be null");
    }

    @Override
    @SuppressWarnings("try") // The scope is used for its close side effect.
    public <T> T within(String name, Map<String, String> identifiers, Supplier<T> work) {
        Span span = start(name, identifiers);
        if (span == null) {
            return work.get();
        }
        Tracer tracer = tracers.getIfAvailable();
        try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
            return work.get();
        } catch (RuntimeException | Error failure) {
            span.tag("error.type", failure.getClass().getSimpleName());
            throw failure;
        } finally {
            span.end();
        }
    }

    private Span start(String name, Map<String, String> identifiers) {
        try {
            Tracer tracer = tracers.getIfAvailable();
            if (tracer == null) {
                return null;
            }
            Span span = tracer.nextSpan().name(name);
            identifiers.forEach(
                    (key, value) -> {
                        if (Spans.admits(key, value)) {
                            span.tag(key, value);
                        }
                    });
            return span.start();
        } catch (RuntimeException tracingFailed) {
            log.warn(
                    "A span could not be started; the work runs untraced: {}",
                    tracingFailed.getClass().getSimpleName());
            return null;
        }
    }
}
