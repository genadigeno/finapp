package com.finapp.app.accounting;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.telemetry.Spans;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Records accounting's step spans (`P14-TSK-001`; {@code PHASE_14_PLAN.md} section 16; risk's {@code RiskFlowScope},
 * `P13-TSK-001`). Every span is recorded with NO attribute: no amount, balance, GL figure, owner reference, period,
 * report or submission identifier reaches the trace backend ({@code INV-AUD-02}; report content is
 * {@code CONFIDENTIAL}).
 *
 * <p>Two legs exist: a door's span in the request's own scope, and a sweep leg's in the flow its row stored ({@link #inFlow},
 * `P14-TSK-011`: a close check in its attempt's correlation - the claim hands it over, so no lookup is made). A report generation
 * on the orphan leg runs under that leg's own span, unlinked: {@code completeOrphaned} names no run to its caller; the run's audit
 * record and event carry its stored correlation all the same. A submission's inquiry runs in its row's flow (`P14-TSK-018`).
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

    /** A sweep leg's step: the span in the flow its row stored, no attribute - so it joins the request that began it. */
    @SuppressWarnings("try") // The scope is used for its close side effect.
    public <T> T inFlow(String span, CorrelationId stored, Supplier<T> work) {
        Objects.requireNonNull(stored, "stored");
        try (CorrelationContext.Scope flow = CorrelationContext.enter(
                Correlation.startingWith(stored).causing(CausationId.of(stored.value())))) {
            return spans.within(span, Map.of(), work);
        }
    }
}
