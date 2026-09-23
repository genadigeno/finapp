package com.finapp.app.telemetry;

import com.finapp.payments.PaymentProvider;
import com.finapp.payments.ProviderAnswer;
import com.finapp.payments.ProviderIdempotencyReference;
import com.finapp.payments.QueryAnswer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The provider timer's seam (`P5-TSK-017`): a decorator around the {@link PaymentProvider}
 * port, wrapping the wired adapter at the composition root.
 *
 * <h2>Why a decorator here, where the ledger chose a port</h2>
 *
 * <p>{@code PostingObserver} exists because the ledger's write path has several commands and
 * a count incremented per call site is a count a new call site silently loses. The provider
 * is the opposite shape: <strong>one interface every provider call passes through</strong>,
 * and a decorator that implements it is checked by the compiler — a fifth provider method
 * cannot be added without this class failing to compile, which is the same
 * forces-a-decision property, obtained without {@code payments} ever seeing a metrics
 * library ({@code MeteredKycCaseStore}'s precedent).
 *
 * <h2>Every outcome, in a finally</h2>
 *
 * <p>The exception propagates <strong>unchanged</strong> — a decorator that swallowed or
 * translated a provider failure would change the ambiguity semantics this whole phase rests
 * on ({@code INV-LIFE-03}) — and the duration is recorded either way. The clock is injected
 * (ADR-0014): no ambient time, here or anywhere.
 */
@RequiredArgsConstructor
public final class MeteredPaymentProvider implements PaymentProvider {

    @NonNull private final PaymentProvider delegate;
    @NonNull private final PaymentMeters meters;
    @NonNull private final Clock clock;

    /**
     * The delegate's own name, unchanged — the decorator is transparent to everything but
     * the clock. It is also the {@code provider} tag's value, which is why the meters are
     * constructed with it rather than discovering it per call: a series must exist before
     * the first call, not after it (`P1-TSK-029`).
     */
    @Override
    public String providerName() {
        return delegate.providerName();
    }

    @Override
    public ProviderAnswer authorize(AuthorizationRequest request) {
        Instant started = Instant.now(clock);
        try {
            return delegate.authorize(request);
        } finally {
            record(PaymentMeters.Operation.AUTHORIZE, started);
        }
    }

    @Override
    public ProviderAnswer capture(CaptureRequest request) {
        Instant started = Instant.now(clock);
        try {
            return delegate.capture(request);
        } finally {
            record(PaymentMeters.Operation.CAPTURE, started);
        }
    }

    @Override
    public ProviderAnswer refund(RefundRequest request) {
        Instant started = Instant.now(clock);
        try {
            return delegate.refund(request);
        } finally {
            record(PaymentMeters.Operation.REFUND, started);
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
        meters.providerCall(operation, Duration.between(started, Instant.now(clock)));
    }
}
