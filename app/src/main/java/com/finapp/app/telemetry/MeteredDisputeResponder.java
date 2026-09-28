package com.finapp.app.telemetry;

import com.finapp.payments.DisputeResponder;
import com.finapp.payments.ProviderAnswer;
import com.finapp.payments.ProviderIdempotencyReference;
import com.finapp.payments.QueryAnswer;
import com.finapp.payments.RailId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The card PSP's dispute port behind the payment meters (`P7-TSK-015`, ADR-0061 §7) —
 * {@code MeteredPaymentProvider}'s shape at the port `P7-TSK-014` left unmetered: an answer is the
 * {@code RESPOND} operation, the resolution sweep's question {@code QUERY} (the same wire call the
 * payment port's query makes), each recorded in a {@code finally} under the provider and the rail
 * the composition root binds, with the answer — and any exception — passed through untouched.
 * The outcome of an answer is counted where it is judged ({@code DisputeResponseOutcomes}'
 * observer), never here: a wire answer is not yet a judgement.
 */
public final class MeteredDisputeResponder implements DisputeResponder {

    private final DisputeResponder delegate;
    private final PaymentMeters meters;
    private final Clock clock;
    private final RailId rail;

    public MeteredDisputeResponder(
            DisputeResponder delegate, PaymentMeters meters, Clock clock, RailId rail) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.rail = Objects.requireNonNull(rail, "rail must not be null");
    }

    @Override
    public String providerName() {
        return delegate.providerName();
    }

    @Override
    public ProviderAnswer respond(DisputeResponseRequest request) {
        Instant started = Instant.now(clock);
        try {
            return delegate.respond(request);
        } finally {
            record(PaymentMeters.Operation.RESPOND, started);
        }
    }

    @Override
    public QueryAnswer query(ProviderIdempotencyReference ourReference) {
        Instant started = Instant.now(clock);
        try {
            return delegate.query(ourReference);
        } finally {
            record(PaymentMeters.Operation.QUERY, started);
        }
    }

    private void record(PaymentMeters.Operation operation, Instant started) {
        meters.call(
                delegate.providerName(),
                rail,
                operation,
                Duration.between(started, Instant.now(clock)));
    }
}
