package com.finapp.app.telemetry;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.telemetry.TraceAttributes;
import com.finapp.sharedkernel.correlation.Correlation;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.util.Optional;

/**
 * Puts the flow's correlation identifier on <strong>every</strong> span, as the span starts.
 *
 * <h2>Why here rather than at each call site</h2>
 *
 * <p>"Every span carries correlation" is a property no amount of per-component discipline
 * delivers. A component added next year, an instrumentation library nobody wrote, a span opened
 * inside the framework — each would have to remember, and the failure is silent: the span is
 * recorded, the trace looks complete, and it simply cannot be found from the identifier a customer
 * quoted. Applying it once, at the point where the SDK starts a span, makes the property hold for
 * spans this codebase does not produce.
 *
 * <p>This is the same argument as the correlation filter sitting at {@code HIGHEST_PRECEDENCE}
 * rather than in each controller, and as the version prefix being applied in the composition root
 * rather than written on each mapping.
 *
 * <h2>Why the trace id does not replace it</h2>
 *
 * <p>A trace id is subject to sampling; a correlation identifier is not. Drop the correlation
 * attribute and rely on the trace id, and every flow whose trace was sampled away becomes
 * untraceable from the one value the customer has. See {@link TraceAttributes}.
 *
 * <h2>What it deliberately does not do</h2>
 *
 * <p>It adds nothing when no correlation scope is active, rather than inventing one. A span with
 * no flow is a span started outside a request — a startup probe, a background task with no
 * ingress — and stamping it with a fabricated identifier would put a value in a dashboard that
 * matches nothing in any table.
 */
final class CorrelationSpanProcessor implements SpanProcessor {

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        Optional<Correlation> current = CorrelationContext.current();
        if (current.isEmpty()) {
            return;
        }
        Correlation correlation = current.get();
        span.setAttribute(TraceAttributes.CORRELATION_ID, correlation.correlationId().value());
        correlation
                .cause()
                .ifPresent(cause -> span.setAttribute(TraceAttributes.CAUSATION_ID, cause.value()));
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        // Nothing. The attribute is set at start so it is present even on a span that never ends -
        // which is exactly the span an operator is looking for when something hung.
    }

    @Override
    public boolean isEndRequired() {
        return false;
    }
}
