package com.finapp.platform.telemetry;

/**
 * The span attributes this platform publishes, and the names dashboards are written against.
 *
 * <p>Framework-free and in {@code platform} for the same reason {@code CorrelationContext}'s MDC
 * keys are: these strings are a <strong>published contract</strong>. A trace query, a saved search
 * and an alert are all written against them, so renaming one is a change that fails silently — the
 * dashboard keeps rendering, it simply stops finding anything.
 *
 * <h2>A trace identifier is not a correlation identifier</h2>
 *
 * <p>They look interchangeable and are not, which is why correlation is carried <em>on</em> spans
 * rather than replaced by the trace id:
 *
 * <ul>
 *   <li>A <strong>trace id</strong> belongs to the tracing system, and is subject to
 *       <strong>sampling</strong>. A sampled-out trace leaves no record at all.
 *   <li>A <strong>correlation id</strong> is ours, is never sampled away, is stored {@code NOT
 *       NULL} on the idempotency, outbox, inbox and audit tables, and is the value a customer
 *       quotes to support.
 * </ul>
 *
 * <p>So telemetry is joined to the record, never substituted for it — the same relationship
 * {@code INV-EVT-02} sets out for Kafka. A trace is a diagnostic aid that may be absent; the
 * correlation identifier on a committed row is evidence that is not.
 */
public final class TraceAttributes {

    /**
     * The flow this span belongs to — {@code CorrelationId}.
     *
     * <p>Present on <em>every</em> span, applied once by a span processor rather than by each
     * component, because "every span" is a property no amount of per-component discipline
     * delivers. It is what turns "the customer quoted this identifier" into a trace.
     */
    public static final String CORRELATION_ID = "finapp.correlation_id";

    /**
     * The cause this span belongs to — {@code CausationId}, when the flow has one.
     *
     * <p>Absent rather than empty when there is no cause, so a query for "spans with a causation"
     * means what it says.
     */
    public static final String CAUSATION_ID = "finapp.causation_id";

    private TraceAttributes() {
        throw new AssertionError("not instantiable");
    }
}
