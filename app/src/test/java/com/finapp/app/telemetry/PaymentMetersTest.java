package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.payments.BookRail;
import com.finapp.payments.DisputeResponder;
import com.finapp.payments.EndToEndReference;
import com.finapp.payments.ExchangeAnswer;
import com.finapp.payments.InitiationAnswer;
import com.finapp.payments.PaymentProvider;
import com.finapp.payments.PaymentRails;
import com.finapp.payments.ProviderAnswer;
import com.finapp.payments.ProviderIdempotencyReference;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.PushAnswer;
import com.finapp.payments.PushInquiryAnswer;
import com.finapp.payments.PushRail;
import com.finapp.payments.QueryAnswer;
import com.finapp.payments.RailId;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The payment counters and timers (`P5-TSK-017`; the rail series `P7-TSK-015`): every series
 * exists before anything happens, the vocabularies are the machines' own, the rail series are the
 * declared capabilities' own, and the timers record the failures too.
 */
@DisplayName("the payment meters (P5-TSK-017, P7-TSK-015)")
class PaymentMetersTest {

    private static final String PROVIDER = "simulated-card";
    private static final Instant FIXED = Instant.parse("2026-09-20T12:00:00Z");
    private static final RailId CARD = SimulatedCardPspAdapter.RAIL.id();
    private static final RailId INSTANT = SimulatedInstantSchemeAdapter.RAIL.id();
    private static final RailId BOOK = BookRail.RAIL.id();

    @Test
    @DisplayName("every series is registered at construction, with a healthy zero - a counter"
            + " born on first increment is a series an alert cannot evaluate")
    void everySeriesExistsBeforeAnythingHappens() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PaymentMeters(registry, PROVIDER);

        assertThat(outcomesOf(registry, PaymentMeters.ATTEMPT))
                .containsExactlyInAnyOrder(
                        // "executed" since P7-TSK-009 (EXECUTED is not CAPTURED, in the meter
                        // vocabulary too); "voided" since P7-TSK-015 - the void's judgement,
                        // counted nowhere before the appliers reported it.
                        "authorized", "captured", "failed", "unknown", "executed", "voided");
        assertThat(outcomesOf(registry, PaymentMeters.WEBHOOK))
                .containsExactlyInAnyOrder("processed", "duplicate", "refused", "unmappable");
        assertThat(outcomesOf(registry, PaymentMeters.REFUND))
                .containsExactlyInAnyOrder("completed", "failed", "unknown");
        assertThat(operationsOf(registry, PaymentMeters.PROVIDER_LATENCY, "provider", PROVIDER))
                .as("the card PSP's two ports and nothing else: withdraw and initiate are the"
                        + " instant scheme's, published under ITS name since P7-TSK-015")
                .containsExactlyInAnyOrder(
                        "authorize", "capture", "refund", "query", "void", "respond");
        assertThat(registry.find(PaymentMeters.UNMATCHED_PARKED).counter())
                .as("the suspense-parking series exists from the first scrape (P7-TSK-009)")
                .isNotNull();

        assertThat(registry.find(PaymentMeters.ATTEMPT).counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @Test
    @DisplayName("the rail series are the declared capabilities' own: each rail publishes exactly"
            + " the judgements its machine can produce and the operations its ports make")
    void theRailSeriesFollowTheDeclarations() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PaymentMeters(
                registry,
                PROVIDER,
                PaymentRails.of(
                        List.of(
                                SimulatedCardPspAdapter.RAIL,
                                SimulatedInstantSchemeAdapter.RAIL,
                                BookRail.RAIL)));

        assertThat(railOutcomes(registry, CARD))
                .containsExactlyInAnyOrder(
                        "payment:authorized", "payment:captured", "payment:voided",
                        "payment:failed", "payment:unknown",
                        "refund:completed", "refund:failed", "refund:unknown",
                        "dispute_response:submitted", "dispute_response:failed",
                        "dispute_response:unknown");
        assertThat(railOutcomes(registry, INSTANT))
                .as("a push rail executes, withdraws and returns - and declares no chargebacks")
                .containsExactlyInAnyOrder(
                        "payment:executed", "payment:failed", "payment:unknown",
                        "refund:completed", "refund:failed", "refund:unknown",
                        "withdrawal:completed", "withdrawal:failed", "withdrawal:unknown");
        assertThat(railOutcomes(registry, BOOK))
                .as("born EXECUTED or FAILED, refunded whole or not at all: no unknown exists")
                .containsExactlyInAnyOrder("payment:executed", "payment:failed", "refund:completed");

        assertThat(operationsOf(registry, PaymentMeters.RAIL_LATENCY, "rail", CARD.value()))
                .containsExactlyInAnyOrder(
                        "authorize", "capture", "refund", "query", "void", "respond");
        assertThat(operationsOf(registry, PaymentMeters.RAIL_LATENCY, "rail", INSTANT.value()))
                .containsExactlyInAnyOrder("initiate", "withdraw", "refund", "query");
        assertThat(operationsOf(registry, PaymentMeters.RAIL_LATENCY, "rail", BOOK.value()))
                .as("a book movement has no wire to time")
                .isEmpty();
        assertThat(registry.find(PaymentMeters.RAIL_OUTCOME).counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @Test
    @DisplayName("INV-AUD-02 at the tag: the rail series carry rail, type, operation and outcome"
            + " only - bounded vocabularies, never anything a request could influence")
    void theRailTagsAreBounded() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new PaymentMeters(registry, PROVIDER, PaymentRails.of(List.of(SimulatedCardPspAdapter.RAIL)));

        assertThat(registry.find(PaymentMeters.RAIL_OUTCOME).counters())
                .isNotEmpty()
                .allSatisfy(counter -> assertThat(tagKeys(counter))
                        .containsExactlyInAnyOrder("rail", "type", "outcome"));
        assertThat(registry.find(PaymentMeters.RAIL_LATENCY).timers())
                .isNotEmpty()
                .allSatisfy(timer -> assertThat(tagKeys(timer))
                        .containsExactlyInAnyOrder("rail", "operation"));
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
        assertThat(tagKeys(timer)).containsExactlyInAnyOrder("provider", "operation");
        assertThat(timer.getId().getTag("provider")).isEqualTo(PROVIDER);
    }

    @Test
    @DisplayName("the decorator times EVERY outcome - a throwing provider is recorded and the"
            + " exception propagates unchanged, under the provider AND the rail")
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
                    public ProviderAnswer voidAuthorization(VoidRequest request) {
                        throw new UnsupportedOperationException("not exercised here");
                    }

                    @Override
                    public QueryAnswer query(ProviderIdempotencyReference ourReference) {
                        throw new UnsupportedOperationException("not exercised here");
                    }
                };
        MeteredPaymentProvider metered =
                new MeteredPaymentProvider(
                        throwing, meters, Clock.fixed(FIXED, ZoneOffset.UTC), CARD);

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

        assertThat(count(registry, PaymentMeters.PROVIDER_LATENCY, "authorize"))
                .as("a timer that recorded only successes would hide exactly the incident an"
                        + " operator is trying to see")
                .isEqualTo(1);
        assertThat(count(registry, PaymentMeters.RAIL_LATENCY, "authorize"))
                .as("the same call, the rail's view")
                .isEqualTo(1);
        assertThat(count(registry, PaymentMeters.PROVIDER_LATENCY, "capture")).isZero();

        assertThat(metered.providerName()).isEqualTo(PROVIDER);
        metered.capture(
                new PaymentProvider.CaptureRequest(
                        new ProviderIdempotencyReference("cap-1"),
                        new ProviderReference("psp_auth"),
                        Money.ofMinorUnits(5_00, CurrencyCode.of("EUR"))));
        assertThat(count(registry, PaymentMeters.PROVIDER_LATENCY, "capture")).isEqualTo(1);
    }

    @Test
    @DisplayName("the push rail's calls are timed under the SCHEME's name, registered before its"
            + " first call - never as the card PSP's latency (the P7-TSK-015 mislabel, closed)")
    void thePushRailIsTimedUnderItsOwnName() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PaymentMeters meters = new PaymentMeters(registry, PROVIDER);
        MeteredPushRail metered =
                new MeteredPushRail(
                        new NamedPushRail("a-scheme"),
                        meters,
                        Clock.fixed(FIXED, ZoneOffset.UTC),
                        INSTANT);

        assertThat(operationsOf(registry, PaymentMeters.PROVIDER_LATENCY, "provider", "a-scheme"))
                .as("the scheme's series exist at construction, before any call")
                .containsExactlyInAnyOrder("initiate", "withdraw", "refund", "query");

        assertThatThrownBy(() -> metered.inquire(new EndToEndReference("e2e-1")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(timer(registry, PaymentMeters.PROVIDER_LATENCY, "provider", "a-scheme", "query")
                        .count())
                .isEqualTo(1);
        assertThat(timer(registry, PaymentMeters.PROVIDER_LATENCY, "provider", PROVIDER, "query")
                        .count())
                .as("nothing the scheme did lands in the card PSP's series")
                .isZero();
        assertThat(timer(registry, PaymentMeters.RAIL_LATENCY, "rail", INSTANT.value(), "query")
                        .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the card PSP's dispute port is timed: an answer is RESPOND, the sweep's question"
            + " QUERY, a throwing port still recorded and its exception unchanged")
    void theDisputePortIsTimed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PaymentMeters meters = new PaymentMeters(registry, PROVIDER);
        DisputeResponder throwing =
                new DisputeResponder() {
                    @Override
                    public String providerName() {
                        return PROVIDER;
                    }

                    @Override
                    public ProviderAnswer respond(DisputeResponseRequest request) {
                        throw new IllegalStateException("the PSP is down");
                    }

                    @Override
                    public QueryAnswer query(ProviderIdempotencyReference ourReference) {
                        throw new IllegalStateException("the PSP is down");
                    }
                };
        MeteredDisputeResponder metered =
                new MeteredDisputeResponder(
                        throwing, meters, Clock.fixed(FIXED, ZoneOffset.UTC), CARD);

        assertThatThrownBy(() -> metered.respond(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> metered.query(new ProviderIdempotencyReference("dsr-1")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(count(registry, PaymentMeters.PROVIDER_LATENCY, "respond")).isEqualTo(1);
        assertThat(count(registry, PaymentMeters.RAIL_LATENCY, "respond")).isEqualTo(1);
        assertThat(count(registry, PaymentMeters.RAIL_LATENCY, "query")).isEqualTo(1);
        assertThat(metered.providerName()).isEqualTo(PROVIDER);
    }

    @Test
    @DisplayName("each judgement increments its own series - the legacy one and the rail's"
            + " together, so the two can never disagree - and nothing else")
    void eachJudgementIncrementsItsOwn() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PaymentMeters meters = new PaymentMeters(registry, PROVIDER);

        meters.attemptJudged(CARD, PaymentMeters.Judgement.CAPTURED);
        meters.attemptJudged(CARD, PaymentMeters.Judgement.CAPTURED);
        meters.attemptJudged(INSTANT, PaymentMeters.Judgement.UNKNOWN);
        meters.webhook(PaymentMeters.WebhookOutcome.DUPLICATE);
        meters.refundJudged(CARD, PaymentMeters.RefundOutcome.FAILED);
        meters.withdrawalJudged(INSTANT, PaymentMeters.WithdrawalOutcome.COMPLETED);
        meters.disputeResponseJudged(CARD, PaymentMeters.ResponseOutcome.SUBMITTED);
        meters.call(PROVIDER, CARD, PaymentMeters.Operation.QUERY, Duration.ofMillis(120));

        assertThat(counter(registry, PaymentMeters.ATTEMPT, "captured").count()).isEqualTo(2);
        assertThat(counter(registry, PaymentMeters.ATTEMPT, "unknown").count()).isEqualTo(1);
        assertThat(counter(registry, PaymentMeters.ATTEMPT, "authorized").count()).isZero();
        assertThat(counter(registry, PaymentMeters.WEBHOOK, "duplicate").count()).isEqualTo(1);
        assertThat(counter(registry, PaymentMeters.WEBHOOK, "processed").count()).isZero();
        assertThat(counter(registry, PaymentMeters.REFUND, "failed").count()).isEqualTo(1);
        assertThat(railOutcome(registry, CARD, "payment", "captured")).isEqualTo(2);
        assertThat(railOutcome(registry, INSTANT, "payment", "unknown")).isEqualTo(1);
        assertThat(railOutcome(registry, CARD, "refund", "failed")).isEqualTo(1);
        assertThat(railOutcome(registry, INSTANT, "withdrawal", "completed")).isEqualTo(1);
        assertThat(railOutcome(registry, CARD, "dispute_response", "submitted")).isEqualTo(1);
        assertThat(count(registry, PaymentMeters.PROVIDER_LATENCY, "query")).isEqualTo(1);
        assertThat(count(registry, PaymentMeters.RAIL_LATENCY, "query")).isEqualTo(1);
    }

    // -----------------------------------------------------------------

    private static Set<String> outcomesOf(SimpleMeterRegistry registry, String name) {
        return registry.find(name).counters().stream()
                .map(counter -> counter.getId().getTag("outcome"))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> railOutcomes(SimpleMeterRegistry registry, RailId rail) {
        return registry.find(PaymentMeters.RAIL_OUTCOME).tag("rail", rail.value()).counters().stream()
                .map(counter -> counter.getId().getTag("type") + ":" + counter.getId().getTag("outcome"))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static double railOutcome(
            SimpleMeterRegistry registry, RailId rail, String type, String outcome) {
        Counter counter =
                registry.find(PaymentMeters.RAIL_OUTCOME)
                        .tag("rail", rail.value())
                        .tag("type", type)
                        .tag("outcome", outcome)
                        .counter();
        assertThat(counter).as("rail.outcome{%s,%s,%s}", rail.value(), type, outcome).isNotNull();
        return counter.count();
    }

    private static Set<String> operationsOf(
            SimpleMeterRegistry registry, String name, String key, String value) {
        return registry.find(name).tag(key, value).timers().stream()
                .map(timer -> timer.getId().getTag("operation"))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> tagKeys(Meter meter) {
        return meter.getId().getTags().stream()
                .map(io.micrometer.core.instrument.Tag::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Counter counter(SimpleMeterRegistry registry, String name, String outcome) {
        Counter counter = registry.find(name).tag("outcome", outcome).counter();
        assertThat(counter).as("%s{outcome=%s}", name, outcome).isNotNull();
        return counter;
    }

    private static long count(SimpleMeterRegistry registry, String name, String operation) {
        return registry.find(name).tag("operation", operation).timers().stream()
                .mapToLong(Timer::count)
                .sum();
    }

    private static Timer timer(
            SimpleMeterRegistry registry, String name, String key, String value, String operation) {
        Timer timer = registry.find(name).tag(key, value).tag("operation", operation).timer();
        assertThat(timer).as("%s{%s=%s,operation=%s}", name, key, value, operation).isNotNull();
        return timer;
    }

    /** A push rail whose calls all fail loudly — only its name and its failures matter here. */
    private static final class NamedPushRail implements PushRail {

        private final String name;

        private NamedPushRail(String name) {
            this.name = name;
        }

        @Override
        public String schemeName() {
            return name;
        }

        @Override
        public ExchangeAnswer exchange(GrantExchange request) {
            throw new IllegalStateException("not exercised");
        }

        @Override
        public PushAnswer send(CreditTransfer request) {
            throw new IllegalStateException("the scheme is down");
        }

        @Override
        public PushInquiryAnswer inquire(EndToEndReference ourReference) {
            throw new IllegalStateException("the scheme is down");
        }

        @Override
        public InitiationAnswer initiate(PayInInitiation request) {
            throw new IllegalStateException("the scheme is down");
        }

        @Override
        public PushInquiryAnswer inquireInitiation(EndToEndReference ourReference) {
            throw new IllegalStateException("the scheme is down");
        }

        @Override
        public PushAnswer sendReturn(ReturnPayment request) {
            throw new IllegalStateException("the scheme is down");
        }

        @Override
        public PushInquiryAnswer inquireReturn(EndToEndReference ourReference) {
            throw new IllegalStateException("the scheme is down");
        }
    }
}
