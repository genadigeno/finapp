package com.finapp.app.telemetry;

import com.finapp.credit.CreditSpans;
import com.finapp.credit.Decider;
import com.finapp.credit.DecisionRequestId;
import com.finapp.platform.telemetry.Spans;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.util.Map;
import java.util.Objects;

/**
 * The platform's deciding transaction inside its span (`P10-TSK-020`): {@code credit.decision.decide}, with no attribute -
 * never the outcome's amount or a reason's figure ({@code INV-CRD-02}). What deciding did, and any failure, pass through
 * unchanged.
 */
public final class SpannedDecider implements Decider {

    private final Decider delegate;
    private final Spans spans;

    public SpannedDecider(Decider delegate, Spans spans) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.spans = Objects.requireNonNull(spans, "spans must not be null");
    }

    @Override
    public Decided decide(DecisionRequestId id, CorrelationId correlation) {
        return spans.within(CreditSpans.DECIDE, Map.of(), () -> delegate.decide(id, correlation));
    }
}
