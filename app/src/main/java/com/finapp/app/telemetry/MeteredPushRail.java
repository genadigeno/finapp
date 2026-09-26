package com.finapp.app.telemetry;

import com.finapp.payments.EndToEndReference;
import com.finapp.payments.ExchangeAnswer;
import com.finapp.payments.InitiationAnswer;
import com.finapp.payments.PushAnswer;
import com.finapp.payments.PushInquiryAnswer;
import com.finapp.payments.PushRail;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The push rail behind the payment meters (`P7-TSK-008`) — {@code MeteredPaymentProvider}'s
 * shape at the second port: latency per operation, tags from the closed vocabulary, and the
 * answer passed through untouched. {@code send} is the {@code WITHDRAW} operation, both
 * inquiries are {@code QUERY}; the grant exchange and the pay-in initiation pass through
 * un-timed, deliberately — they are not payment operations, and their meters (if ever
 * wanted) are a vocabulary decision, not a default.
 */
public final class MeteredPushRail implements PushRail {

    private final PushRail delegate;
    private final PaymentMeters meters;
    private final Clock clock;

    public MeteredPushRail(PushRail delegate, PaymentMeters meters, Clock clock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.meters = Objects.requireNonNull(meters, "meters must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public String schemeName() {
        return delegate.schemeName();
    }

    @Override
    public ExchangeAnswer exchange(GrantExchange request) {
        return delegate.exchange(request);
    }

    @Override
    public PushAnswer send(CreditTransfer request) {
        Instant started = Instant.now(clock);
        try {
            return delegate.send(request);
        } finally {
            record(PaymentMeters.Operation.WITHDRAW, started);
        }
    }

    @Override
    public PushInquiryAnswer inquire(EndToEndReference ourReference) {
        Instant started = Instant.now(clock);
        try {
            return delegate.inquire(ourReference);
        } finally {
            record(PaymentMeters.Operation.QUERY, started);
        }
    }

    @Override
    public InitiationAnswer initiate(PayInInitiation request) {
        return delegate.initiate(request);
    }

    @Override
    public PushInquiryAnswer inquireInitiation(EndToEndReference ourReference) {
        Instant started = Instant.now(clock);
        try {
            return delegate.inquireInitiation(ourReference);
        } finally {
            record(PaymentMeters.Operation.QUERY, started);
        }
    }

    private void record(PaymentMeters.Operation operation, Instant started) {
        meters.providerCall(operation, Duration.between(started, Instant.now(clock)));
    }
}
