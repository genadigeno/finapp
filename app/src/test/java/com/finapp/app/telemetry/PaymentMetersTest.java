package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.payments.PaymentProvider;
import com.finapp.payments.ProviderAnswer;
import com.finapp.payments.ProviderIdempotencyReference;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.QueryAnswer;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The payment counters and the provider timer (`P5-TSK-017`): every series exists before
 * anything happens, the vocabularies are the machines' own, and the timer records the
 * failures too.
 */
@DisplayName("the payment meters (P5-TSK-017)")
class PaymentMetersTest {

    private static final String PROVIDER = "simulated-card";
    private static final Instant FIXED = Instant.parse("2026-09-20T12:00:00Z");

    @Test
    @DisplayName("every series is registered at construction, with a healthy zero - a counter"
            + " born on first increment is a series an alert cannot evaluate")
    void everySeriesExistsBeforeAnythingHappens() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PaymentMeters(registry, PROVIDER);

        assertThat(outcomesOf(registry, PaymentMeters.ATTEMPT))
                .containsExactlyInAnyOrder("authorized", "captured", "failed", "unknown");
        assertThat(outcomesOf(registry, PaymentMeters.WEBHOOK))
                .containsExactlyInAnyOrder("processed", "duplicate", "refused", "unmappable");
        assertThat(outcomesOf(registry, PaymentMeters.REFUND))
                .containsExactlyInAnyOrder("completed", "failed", "unknown");
        Set<String> operations =
                registry.find(PaymentMeters.PROVIDER_LATENCY).timers().stream()
                        .map(timer -> timer.getId().getTag("operation"))
                        .collect(Collectors.toCollection(TreeSet::new));
        assertThat(operations)
                .containsExactlyInAnyOrder("authorize", "capture", "refund", "query");

        assertThat(registry.find(PaymentMeters.ATTEMPT).counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @Test
    @DisplayName("the provider timer carries provider and operation - and nothing a request"
            + " could influence (INV-AUD-02 at the tag)")
    void theTimerTagsAreBoundedAndTwo() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PaymentMeters(registry, PROVIDER);

        Timer timer =
                registry.find(PaymentMeters.PROVIDER_LATENCY).tag("operation", "capture").timer();
        assertThat(timer).isNotNull();
        Set<String> tagKeys =
                timer.getId().getTags().stream()
                        .map(io.micrometer.core.instrument.Tag::getKey)
                        .collect(Collectors.toCollection(TreeSet::new));
        assertThat(tagKeys).containsExactlyInAnyOrder("provider", "operation");
        assertThat(timer.getId().getTag("provider")).isEqualTo(PROVIDER);
    }

    @Test
    @DisplayName("the decorator times EVERY outcome - a throwing provider is recorded and the"
            + " exception propagates unchanged")
    void aThrowingProviderIsStillTimed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PaymentMeters meters = new PaymentMeters(registry, PROVIDER);
        PaymentProvider throwing =
                new PaymentProvider() {
                    @Override
                    public String providerName() {
                        return PROVIDER;
                    }

                    @Override
                    public ProviderAnswer authorize(AuthorizationRequest request) {
                        throw new IllegalStateException("the provider is down");
                    }

                    @Override
                    public ProviderAnswer capture(CaptureRequest request) {
                        return ProviderAnswer.approved(
                                new ProviderReference("psp_x"),
                                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }

                    @Override
                    public ProviderAnswer refund(RefundRequest request) {
                        throw new UnsupportedOperationException("not exercised here");
                    }

                    @Override
                    public QueryAnswer query(ProviderIdempotencyReference ourReference) {
                        throw new UnsupportedOperationException("not exercised here");
                    }
                };
        MeteredPaymentProvider metered =
                new MeteredPaymentProvider(
                        throwing, meters, Clock.fixed(FIXED, ZoneOffset.UTC));

        assertThatThrownBy(
                        () ->
                                metered.authorize(
                                        new PaymentProvider.AuthorizationRequest(
                                                new ProviderIdempotencyReference("auth-1"),
                                                com.finapp.payments.InstrumentToken.of("tok_x"),
                                                Money.ofMinorUnits(
                                                        5_00, CurrencyCode.of("EUR")))))
                .as("the decorator changes nothing about what the caller sees")
                .isInstanceOf(IllegalStateException.class);

        assertThat(count(registry, "authorize"))
                .as("a timer that recorded only successes would hide exactly the incident an"
                        + " operator is trying to see")
                .isEqualTo(1);
        assertThat(count(registry, "capture")).isZero();

        assertThat(metered.providerName()).isEqualTo(PROVIDER);
        metered.capture(
                new PaymentProvider.CaptureRequest(
                        new ProviderIdempotencyReference("cap-1"),
                        new ProviderReference("psp_auth"),
                        Money.ofMinorUnits(5_00, CurrencyCode.of("EUR"))));
        assertThat(count(registry, "capture")).isEqualTo(1);
    }

    @Test
    @DisplayName("each judgement increments its own series, and nothing else")
    void eachJudgementIncrementsItsOwn() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PaymentMeters meters = new PaymentMeters(registry, PROVIDER);

        meters.attempt(PaymentMeters.Judgement.CAPTURED);
        meters.attempt(PaymentMeters.Judgement.CAPTURED);
        meters.attempt(PaymentMeters.Judgement.UNKNOWN);
        meters.webhook(PaymentMeters.WebhookOutcome.DUPLICATE);
        meters.refund(PaymentMeters.RefundOutcome.FAILED);
        meters.providerCall(PaymentMeters.Operation.QUERY, Duration.ofMillis(120));

        assertThat(counter(registry, PaymentMeters.ATTEMPT, "captured").count()).isEqualTo(2);
        assertThat(counter(registry, PaymentMeters.ATTEMPT, "unknown").count()).isEqualTo(1);
        assertThat(counter(registry, PaymentMeters.ATTEMPT, "authorized").count()).isZero();
        assertThat(counter(registry, PaymentMeters.WEBHOOK, "duplicate").count()).isEqualTo(1);
        assertThat(counter(registry, PaymentMeters.WEBHOOK, "processed").count()).isZero();
        assertThat(counter(registry, PaymentMeters.REFUND, "failed").count()).isEqualTo(1);
        assertThat(count(registry, "query")).isEqualTo(1);
    }

    // -----------------------------------------------------------------

    private static Set<String> outcomesOf(SimpleMeterRegistry registry, String name) {
        return registry.find(name).counters().stream()
                .map(counter -> counter.getId().getTag("outcome"))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Counter counter(SimpleMeterRegistry registry, String name, String outcome) {
        Counter counter = registry.find(name).tag("outcome", outcome).counter();
        assertThat(counter).as("%s{outcome=%s}", name, outcome).isNotNull();
        return counter;
    }

    private static long count(SimpleMeterRegistry registry, String operation) {
        Meter.Id id =
                registry.find(PaymentMeters.PROVIDER_LATENCY)
                        .tag("operation", operation)
                        .timer()
                        .getId();
        return registry.get(id.getName()).tag("operation", operation).timer().count();
    }
}
