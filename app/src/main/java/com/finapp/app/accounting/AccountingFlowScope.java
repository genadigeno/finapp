package com.finapp.app.accounting;

import com.finapp.platform.telemetry.Spans;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Records accounting's step spans (`P14-TSK-001`; {@code PHASE_14_PLAN.md} section 16; risk's {@code RiskFlowScope},
 * `P13-TSK-001`). Every span is recorded with NO attribute: no amount, balance, GL figure, owner reference, period,
 * report or submission identifier reaches the trace backend ({@code INV-AUD-02}; report content is
 * {@code CONFIDENTIAL}).
 *
 * <p>Only the door leg exists so far - a span in the request's own scope. The legs that link a step to a stored
 * correlation (a close check inside its close run's flow, a report generation in its run's, a submission's inquiry in
 * the submission's) arrive with the tasks that create those rows (`-008`, `-011`, `-018`), on the {@code BnplFlowScope}
 * shape: one read on a read-only snapshot, never a lock, a lookup that fails leaving the step to run unlinked - a span is
 * telemetry, never the work's precondition. The close schedule's gauge, {@code finapp.accounting.sweeper.enabled}, is
 * reserved with its property and registered by the schedule's task (`-011`).
 */
public final class AccountingFlowScope {

    /** No spans: every step runs as it did - the suites' scope. */
    public static final AccountingFlowScope NONE = new AccountingFlowScope(Spans.NONE);

    private final Spans spans;

    public AccountingFlowScope(Spans spans) {
        this.spans = Objects.requireNonNull(spans, "spans");
    }

    /** A door: the span in the request's own scope, no attribute. */
    public <T> T door(String span, Supplier<T> work) {
        return spans.within(span, Map.of(), work);
    }
}
