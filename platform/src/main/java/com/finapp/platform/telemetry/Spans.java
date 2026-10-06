package com.finapp.platform.telemetry;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * A domain-named span around one unit of work (`P8-TSK-024`, `PHASE_8_PLAN.md` §15's tracing):
 * the port a module records its legs through without depending on a tracer. The composition
 * root supplies the tracer-backed implementation; a module and its tests use {@link #NONE}.
 *
 * <p><strong>Identifiers only.</strong> A span leaves the platform for the trace backend, so its
 * attributes are a closed set of identifier keys ({@link #IDENTIFIER_KEYS}) whose values are
 * identifiers ({@link #admits}) — never an amount, a reference value, a name or a narrative
 * (ADR-0072, `INV-AUD-02`). An implementation drops any attribute this refuses. The correlation
 * is not an attribute: the platform's span processor stamps the flow's own.
 *
 * <p><strong>Never the leg's failure.</strong> A span that cannot be recorded leaves the work
 * to run as if untraced; the work's own exception propagates unchanged.
 */
public interface Spans {

    /** The identifier keys a span may carry. */
    Set<String> IDENTIFIER_KEYS =
            Set.of("run.id", "batch.id", "file.id", "source.id", "break.id", "resolution.id",
                    // P9-TSK-027 (PHASE_9_PLAN.md section 15): the FX and cross-border legs' identifiers.
                    "quote.id", "trade.id", "cover.id", "payment.id", "credit.id", "screening.id");

    /** A UUID's canonical shape - the one value shape an identifier key admits. */
    Pattern IDENTIFIER =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    /** The tracer-less implementation: the work runs, nothing is recorded. */
    Spans NONE =
            new Spans() {
                @Override
                public <T> T within(
                        String name, Map<String, String> identifiers, Supplier<T> work) {
                    return work.get();
                }
            };

    /** Runs {@code work} inside a span named {@code name} carrying {@code identifiers}. */
    <T> T within(String name, Map<String, String> identifiers, Supplier<T> work);

    /** Whether an attribute is an identifier this port lets leave the platform. */
    static boolean admits(String key, String value) {
        return key != null
                && value != null
                && IDENTIFIER_KEYS.contains(key)
                && IDENTIFIER.matcher(value).matches();
    }
}
