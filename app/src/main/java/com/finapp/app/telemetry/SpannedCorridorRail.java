package com.finapp.app.telemetry;

import com.finapp.payments.CorridorRail;
import com.finapp.payments.EndToEndReference;
import com.finapp.payments.RailId;
import com.finapp.platform.telemetry.Spans;
import java.util.Map;
import java.util.Objects;

/**
 * A corridor rail's outbound legs, each inside its span (`P9-TSK-027`): the send ({@code payments.outbound.dispatch}),
 * the inquiry ({@code payments.outbound.resolve}) and the recall ({@code payments.outbound.recall}). The beneficiary
 * exchange is the registration's, outside the payment's trace. No attribute carries a reference or an amount.
 */
public final class SpannedCorridorRail implements CorridorRail {

    private final CorridorRail delegate;
    private final Spans spans;

    public SpannedCorridorRail(CorridorRail delegate, Spans spans) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.spans = Objects.requireNonNull(spans, "spans must not be null");
    }

    @Override
    public RailId id() {
        return delegate.id();
    }

    @Override
    public BeneficiaryExchange exchangeBeneficiary(BeneficiaryGrant grant) {
        return delegate.exchangeBeneficiary(grant);
    }

    @Override
    public SendAnswer send(CreditInstruction instruction) {
        return spans.within(Phase9Spans.OUTBOUND_DISPATCH, Map.of(), () -> delegate.send(instruction));
    }

    @Override
    public InquiryAnswer inquire(EndToEndReference reference) {
        return spans.within(Phase9Spans.OUTBOUND_RESOLVE, Map.of(), () -> delegate.inquire(reference));
    }

    @Override
    public RecallAnswer recall(EndToEndReference reference) {
        return spans.within(Phase9Spans.OUTBOUND_RECALL, Map.of(), () -> delegate.recall(reference));
    }

    @Override
    public String toString() {
        return "SpannedCorridorRail[" + delegate + "]";
    }
}
