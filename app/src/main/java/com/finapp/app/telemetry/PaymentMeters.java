package com.finapp.app.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * The payment surface's counters and the provider timer (`P5-TSK-017`, `PHASE_5_PLAN.md`
 * §15): {@code finapp.payments.attempt} by outcome, {@code finapp.payments.provider.latency}
 * by provider and operation, {@code finapp.payments.webhook} by outcome, and
 * {@code finapp.payments.refund} by outcome.
 *
 * <p><strong>Every series is registered at construction</strong> (`P1-TSK-029`): a counter
 * created on its first increment is a series an alert cannot evaluate at exactly the moment
 * it is needed, and a freshly started instance must publish healthy zeros rather than
 * absences that read as a quiet system. The outcome series are derived from enums, so a new
 * judgement registers itself.
 *
 * <p><strong>Only the ACTING judgement counts, post-commit</strong> — the discipline the
 * plan's §15 states in as many words ("replays/converges never counted") and the one this
 * phase makes harder than any before it: a payment's judgement arrives through <em>four</em>
 * doors (the synchronous Tx2, the chained capture, the webhook, the sweeper), and under a
 * ten-way race nine of them converge on a judgement they did not make. The acting bit is the
 * conditional transition's own row count, carried out of {@code PaymentOutcomes.Applied} —
 * the only place the answer exists — and the increment happens at the door <em>after</em>
 * the transaction commits, so an outcome that rolled back is an outcome that did not happen.
 *
 * <p><strong>The provider timer records every outcome</strong>, in a {@code finally} inside
 * {@link MeteredPaymentProvider}: a timer that recorded only successes would flatter exactly
 * the incident an operator is trying to see — a provider timing out is the phase's most
 * expensive condition, and it must be the most visible. No percentile histogram
 * (`P1-TSK-029`: {@code _bucket} does not exist unless asked for); the dashboard reads
 * {@code _count}, {@code _sum} and {@code _max}.
 *
 * <p>Tags carry {@code provider} and {@code operation} only — an organisation's name fixed at
 * deployment and the port's own method, both bounded at compile time. The provider's status
 * vocabulary never reaches a meter ({@code INV-PAY-03}), and no identifier, reference or
 * amount ever does ({@code INV-AUD-02}).
 */
public final class PaymentMeters {

    /** {@code finapp.payments.attempt} — acting attempt judgements, by outcome. */
    static final String ATTEMPT = "finapp.payments.attempt";

    /** {@code finapp.payments.provider.latency} — provider call duration, every outcome. */
    static final String PROVIDER_LATENCY = "finapp.payments.provider.latency";

    /** {@code finapp.payments.webhook} — webhook deliveries, by what became of them. */
    static final String WEBHOOK = "finapp.payments.webhook";

    /** {@code finapp.payments.refund} — acting refund judgements, by outcome. */
    static final String REFUND = "finapp.payments.refund";

    /** What an acting judgement decided about an attempt — the machine's own vocabulary. */
    public enum Judgement {
        AUTHORIZED,
        CAPTURED,
        FAILED,
        UNKNOWN
    }

    /** What became of a delivery at the webhook door (ADR-0047's own four states). */
    public enum WebhookOutcome {
        /** Authentic, mapped to an operation, and its effect applied. */
        PROCESSED,
        /** Authentic and already handled — the inbox absorbed it ({@code INV-IDEM-04}). */
        DUPLICATE,
        /** Unauthenticated, unfresh or outside the evidence bound: nothing written. */
        REFUSED,
        /** Authentic but naming no operation we minted, or unreadable (ADR-0047 §5). */
        UNMAPPABLE
    }

    /** What an acting judgement decided about a refund. */
    public enum RefundOutcome {
        COMPLETED,
        FAILED,
        UNKNOWN
    }

    /** The port's four methods — the {@code operation} tag's closed vocabulary. */
    public enum Operation {
        AUTHORIZE,
        CAPTURE,
        REFUND,
        QUERY
    }

    private final Map<Judgement, Counter> attempts = new EnumMap<>(Judgement.class);
    private final Map<WebhookOutcome, Counter> webhooks = new EnumMap<>(WebhookOutcome.class);
    private final Map<RefundOutcome, Counter> refunds = new EnumMap<>(RefundOutcome.class);
    private final Map<Operation, Timer> latencies = new EnumMap<>(Operation.class);

    /**
     * Public because the payment slice's own suites compose the real doors (the
     * {@code TransferMetrics} bean is consumed the same way): a suite driving the webhook
     * resolver or the sweeper schedule constructs this over a {@code SimpleMeterRegistry}
     * of its own, so it exercises the WIRED counting path rather than a double.
     */
    public PaymentMeters(MeterRegistry registry, String provider) {
        for (Judgement judgement : Judgement.values()) {
            attempts.put(
                    judgement,
                    Counter.builder(ATTEMPT)
                            .tag("outcome", lower(judgement.name()))
                            .description(
                                    "Acting payment-attempt judgements by outcome, counted"
                                        + " post-commit at the door that applied them:"
                                        + " authorized, captured, failed, and the honest"
                                        + " unknown (INV-LIFE-03) that a resolver later"
                                        + " settles. A replay, a converged retry and the"
                                        + " losers of a racing resolution are never"
                                        + " throughput. Per instance; rate() and sum()"
                                        + " aggregate")
                            .register(registry));
        }
        for (WebhookOutcome outcome : WebhookOutcome.values()) {
            webhooks.put(
                    outcome,
                    Counter.builder(WEBHOOK)
                            .tag("outcome", lower(outcome.name()))
                            .description(
                                    "Provider webhook deliveries by what became of them:"
                                        + " processed applied an effect, duplicate was"
                                        + " absorbed by the inbox, refused failed"
                                        + " authentication or the evidence bound, unmappable"
                                        + " was authentic but named no operation we minted. A"
                                        + " rise in refused is somebody probing the door; a"
                                        + " rise in unmappable is an integration break. Per"
                                        + " instance; rate() and sum() aggregate")
                            .register(registry));
        }
        for (RefundOutcome outcome : RefundOutcome.values()) {
            refunds.put(
                    outcome,
                    Counter.builder(REFUND)
                            .tag("outcome", lower(outcome.name()))
                            .description(
                                    "Acting refund judgements by outcome, counted post-commit:"
                                        + " completed released the hold and posted, failed"
                                        + " released with nothing posted, unknown left the"
                                        + " customer's funds visibly reserved. A replay and a"
                                        + " converged takeover are never throughput. Per"
                                        + " instance; rate() and sum() aggregate")
                            .register(registry));
        }
        for (Operation operation : Operation.values()) {
            latencies.put(
                    operation,
                    Timer.builder(PROVIDER_LATENCY)
                            .tag("provider", provider)
                            .tag("operation", lower(operation.name()))
                            .description(
                                    "Duration of a call to the payment provider, EVERY"
                                        + " outcome - approvals, declines, timeouts and"
                                        + " dropped connections alike, recorded in a finally."
                                        + " A timer that recorded only successes would hide"
                                        + " the ambiguity this phase exists to handle. No"
                                        + " histogram buckets: read _count, _sum and _max")
                            .register(registry));
        }
    }

    private static String lower(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /** An acting attempt judgement, post-commit. */
    public void attempt(Judgement judgement) {
        attempts.get(judgement).increment();
    }

    /** A webhook delivery's fate, after its transaction committed (or refused everything). */
    public void webhook(WebhookOutcome outcome) {
        webhooks.get(outcome).increment();
    }

    /** An acting refund judgement, post-commit. */
    public void refund(RefundOutcome outcome) {
        refunds.get(outcome).increment();
    }

    /** One provider call's duration, whatever it answered — the injected clock's measure. */
    public void providerCall(Operation operation, Duration elapsed) {
        latencies.get(operation).record(elapsed);
    }
}
