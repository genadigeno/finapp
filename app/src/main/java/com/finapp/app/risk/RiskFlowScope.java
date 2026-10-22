package com.finapp.app.risk;

import com.finapp.platform.telemetry.Spans;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Records risk's step spans (`P13-TSK-001`; {@code PHASE_13_PLAN.md} section 15; bnpl's {@code BnplFlowScope},
 * `P12-TSK-026`). Every span is recorded with NO attribute ({@code RiskSpans}): no score, rule name, reason text,
 * amount, party, subject, case or report identifier reaches the trace backend ({@code INV-RSK-08}, {@code INV-AUD-02}).
 *
 * <p>Only the door leg exists so far - a span in the request's own scope. The legs that link a step to a stored
 * correlation (an evaluation inside a money command's flow, a case act in the case's flow, a sweep leg in the row's)
 * arrive with the tasks that create those rows (`-004`, `-016`, `-019`), on the {@code BnplFlowScope} shape: one
 * read on a read-only snapshot, never a lock, a lookup that fails leaving the step to run unlinked - a span is
 * telemetry, never the work's precondition.
 */
public final class RiskFlowScope {

    /** No spans: every step runs as it did - the suites' scope. */
    public static final RiskFlowScope NONE = new RiskFlowScope(Spans.NONE);

    private final Spans spans;

    public RiskFlowScope(Spans spans) {
        this.spans = Objects.requireNonNull(spans, "spans");
    }

    /** A door: the span in the request's own scope, no attribute. */
    public <T> T door(String span, Supplier<T> work) {
        return spans.within(span, Map.of(), work);
    }

    /**
     * A sweep leg (`P13-TSK-019`): the span in the sweep's own scope, no attribute. The monitoring intake's batch has no
     * row of its own to carry a stored correlation - the watermark is a claim, not a flow - so the leg links to nothing
     * and names nothing: not the position, not an entry, not a subject ({@code INV-RSK-08}).
     */
    public <T> T sweep(String span, Supplier<T> work) {
        return spans.within(span, Map.of(), work);
    }
}
