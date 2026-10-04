package com.finapp.app.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.fx.FixedSide;
import com.finapp.fx.FxProvider;
import com.finapp.fx.FxProvider.ExecutionAnswer;
import com.finapp.fx.FxProvider.FirmQuoteAnswer;
import com.finapp.fx.FxProvider.Indeterminacy;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The FX provider contract battery (`P9-TSK-006`, ADR-0075, ADR-0077 §4, ADR-0008): the
 * {@code fx-sim-a} adapter against the honest, stateful {@link SimulatedFxEngine} and against the
 * shared failure harness. Every case is a contract the cover (`-012`) and the quote path (`-008`)
 * will rely on; the decisive one is <strong>dedupe on our reference before validity</strong>.
 */
@DisplayName("the FX provider contract battery - fx-sim-a (P9-TSK-006)")
class SimulatedFxProviderContractTest {

    private static final byte[] KEY = "an-fx-provider-test-key-of-32-bytes!".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CALLBACK_KEY = "an-fx-callback-test-key-of-32-bytes".getBytes(StandardCharsets.UTF_8);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode USD = CurrencyCode.of("USD");

    private SimulatedFxEngine engine;
    private FxProvider provider;

    @BeforeEach
    void start() throws Exception {
        engine = SimulatedFxEngine.start(CALLBACK_KEY);
        provider = new SimulatedFxProviderAdapter(engine.baseUrl(), Duration.ofSeconds(2), KEY);
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    @Test
    @DisplayName("a firm quote carries the provider's rate, stated counter and validity exactly -"
            + " our reference on the wire, the bearer key beside it")
    void aFirmQuoteIsExact() {
        FirmQuoteAnswer.Quoted quoted = quote("QR-1");
        assertThat(quoted.quote().rate().value()).isEqualByComparingTo("1.0850240000");
        assertThat(quoted.quote().statedCounter()).isEqualTo(Money.of(new BigDecimal("1085.02"), USD));
        assertThat(quoted.quote().validFor()).isEqualTo(Duration.ofSeconds(60));
        assertThat(engine.idempotencyKeys()).containsExactly("QR-1");
        assertThat(engine.authorizations())
                .containsExactly("Bearer " + Base64.getEncoder().encodeToString(KEY));
    }

    @Test
    @DisplayName("a pair the provider does not quote is declined in so many words")
    void anUnquotedPairIsDeclined() {
        FirmQuoteAnswer answer =
                provider.firmQuote(
                        new FxProvider.FirmQuoteRequest(
                                "QR-2", USD, CurrencyCode.of("CHF"), FixedSide.FIXED_SOURCE,
                                Money.of(new BigDecimal("10.00"), USD)));
        assertThat(answer).isInstanceOf(FirmQuoteAnswer.Declined.class);
        assertThat(((FirmQuoteAnswer.Declined) answer).reason())
                .isEqualTo(FxProvider.DeclineReason.PAIR_NOT_QUOTED);
    }

    @Test
    @DisplayName("an execution within the lock executes once, with the quote's amounts and a trade"
            + " reference, and emits a signed callback that verifies - a forged one does not")
    void anExecutionExecutesOnce() {
        FirmQuoteAnswer.Quoted quoted = quote("QR-3");
        ExecutionAnswer.Executed executed = executed(provider.execute(execution("T-3", quoted)));
        assertThat(executed.sold()).isEqualTo(Money.of(new BigDecimal("1000.00"), EUR));
        assertThat(executed.bought()).isEqualTo(Money.of(new BigDecimal("1085.02"), USD));
        assertThat(executed.providerTradeReference()).startsWith("FT-");
        assertThat(engine.executions()).isEqualTo(1);
        assertThat(engine.idempotencyKeys()).contains("T-3");
        SimulatedFxEngine.SignedCallback callback = engine.callbacks().get(0);
        assertThat(SimulatedFxEngine.verifies(callback, CALLBACK_KEY)).isTrue();
        assertThat(
                        SimulatedFxEngine.verifies(
                                new SimulatedFxEngine.SignedCallback(
                                        callback.body().replace("1085.02", "9999.99"),
                                        callback.timestamp(),
                                        callback.signature()),
                                CALLBACK_KEY))
                .as("a forged body under a real signature")
                .isFalse();
    }

    @Test
    @DisplayName("refuse before send: a refused connection is NothingSent - the one piece of"
            + " transport knowledge")
    void refusedBeforeSend() throws Exception {
        int closed;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closed = probe.getLocalPort();
        }
        FxProvider nowhere =
                new SimulatedFxProviderAdapter(URI.create("http://127.0.0.1:" + closed), Duration.ofSeconds(2), KEY);
        assertThat(nowhere.firmQuote(request("QR-4"))).isInstanceOf(FirmQuoteAnswer.NothingSent.class);
        assertThat(nowhere.execute(new FxProvider.ExecutionRequest(
                                "T-4", "PQ-1", EUR, USD, FixedSide.FIXED_SOURCE,
                                Money.of(new BigDecimal("1000.00"), EUR))))
                .isInstanceOf(ExecutionAnswer.NothingSent.class);
    }

    @Test
    @DisplayName("execute then drop the response: indeterminate - and re-sending the same T returns"
            + " the same trade, executed once")
    void executeThenDropTheResponse() {
        FirmQuoteAnswer.Quoted quoted = quote("QR-5");
        engine.loseNextExecutionResponse();
        ExecutionAnswer lost = provider.execute(execution("T-5", quoted));
        assertThat(lost).isInstanceOf(ExecutionAnswer.Indeterminate.class);
        assertThat(engine.executions()).as("it DID execute").isEqualTo(1);

        ExecutionAnswer.Executed resent = executed(provider.execute(execution("T-5", quoted)));
        ExecutionAnswer.Executed inquired = executed(provider.inquire("T-5"));
        assertThat(resent.providerTradeReference()).isEqualTo(inquired.providerTradeReference());
        assertThat(engine.executions()).as("never a second execution").isEqualTo(1);
    }

    @Test
    @DisplayName("a duplicate T is one execution, answered identically")
    void aDuplicateIsOneExecution() {
        FirmQuoteAnswer.Quoted quoted = quote("QR-6");
        ExecutionAnswer.Executed first = executed(provider.execute(execution("T-6", quoted)));
        ExecutionAnswer.Executed second = executed(provider.execute(execution("T-6", quoted)));
        assertThat(second).isEqualTo(first);
        assertThat(engine.executions()).isEqualTo(1);
    }

    @Test
    @DisplayName("DEDUPE BEFORE VALIDITY: T executed, the lock then lapses, and re-sending T returns"
            + " the original execution - never quote_expired")
    void dedupeBeforeValidity() {
        FirmQuoteAnswer.Quoted quoted = quote("QR-7");
        ExecutionAnswer.Executed original = executed(provider.execute(execution("T-7", quoted)));
        engine.advance(Duration.ofMinutes(5));
        assertThat(provider.execute(execution("T-7", quoted))).isEqualTo(original);
        assertThat(engine.executions()).isEqualTo(1);
    }

    @Test
    @DisplayName("a late inquiry after the lock lapsed still answers the execution")
    void aLateInquiryAnswers() {
        FirmQuoteAnswer.Quoted quoted = quote("QR-8");
        engine.loseNextExecutionResponse();
        provider.execute(execution("T-8", quoted));
        engine.advance(Duration.ofHours(1));
        assertThat(provider.inquire("T-8")).isInstanceOf(ExecutionAnswer.Executed.class);
    }

    @Test
    @DisplayName("lock expiry: a NEW T after the lock lapsed is rejected QUOTE_EXPIRED, and"
            + " nothing executes")
    void lockExpiry() {
        FirmQuoteAnswer.Quoted quoted = quote("QR-9");
        engine.advance(Duration.ofSeconds(61));
        ExecutionAnswer answer = provider.execute(execution("T-9", quoted));
        assertThat(answer).isInstanceOf(ExecutionAnswer.Rejected.class);
        assertThat(((ExecutionAnswer.Rejected) answer).reason())
                .isEqualTo(FxProvider.RejectReason.QUOTE_EXPIRED);
        assertThat(engine.executions()).isZero();
    }

    @Test
    @DisplayName("a price change is a definitive rejection, PRICE_CHANGED")
    void aPriceChangeIsRejected() {
        FirmQuoteAnswer.Quoted quoted = quote("QR-10");
        engine.changePriceOnNextExecution();
        ExecutionAnswer answer = provider.execute(execution("T-10", quoted));
        assertThat(((ExecutionAnswer.Rejected) answer).reason())
                .isEqualTo(FxProvider.RejectReason.PRICE_CHANGED);
    }

    @Test
    @DisplayName("a provider deviating from its quote is carried verbatim - the executed amounts are"
            + " the provider's, for the cover to judge")
    void aDeviationIsCarriedVerbatim() {
        FirmQuoteAnswer.Quoted quoted = quote("QR-11");
        engine.deviateNextExecution(3);
        ExecutionAnswer.Executed executed = executed(provider.execute(execution("T-11", quoted)));
        assertThat(executed.bought()).isEqualTo(Money.of(new BigDecimal("1084.99"), USD));
    }

    @Test
    @DisplayName("an inquiry for a T the provider never saw is Unrecognised in so many words; on the"
            + " execute path that word means nothing")
    void anUnknownReferenceIsUnrecognised() {
        assertThat(provider.inquire("T-never")).isInstanceOf(ExecutionAnswer.Unrecognised.class);
        FirmQuoteAnswer.Quoted quoted = quote("QR-12");
        engine.answerUnknownStatusNext("unrecognised");
        assertThat(provider.execute(execution("T-12", quoted)))
                .isInstanceOf(ExecutionAnswer.Indeterminate.class);
    }

    @Test
    @DisplayName("an over-precise rate is refused, never rounded")
    void anOverPreciseRateIsRefused() {
        engine.overPreciseNextQuote();
        FirmQuoteAnswer answer = provider.firmQuote(request("QR-13"));
        assertThat(answer).isInstanceOf(FirmQuoteAnswer.Indeterminate.class);
        assertThat(((FirmQuoteAnswer.Indeterminate) answer).cause()).isEqualTo(Indeterminacy.OVER_PRECISE);
    }

    @Test
    @DisplayName("a malformed body and a 5xx are indeterminate, on every path, with the bytes"
            + " retained")
    void malformedAndServerErrorsAreIndeterminate() {
        engine.malformedNext();
        FirmQuoteAnswer malformed = provider.firmQuote(request("QR-14"));
        assertThat(((FirmQuoteAnswer.Indeterminate) malformed).cause()).isEqualTo(Indeterminacy.UNKNOWN_STATE);
        assertThat(((FirmQuoteAnswer.Indeterminate) malformed).evidence()).isPresent();
        engine.serverErrorNext();
        assertThat(provider.inquire("T-14")).isInstanceOf(ExecutionAnswer.Indeterminate.class);
    }

    @ParameterizedTest(name = "status \"{0}\" is indeterminate, never a success")
    @ValueSource(strings = {"", "ok", "success", "EXECUTED", "executed ", "pending", "settled", "approved"})
    @DisplayName("totality: every status the adapter does not know is indeterminate - no default is"
            + " a success")
    void everyUnknownStatusIsIndeterminate(String status) {
        FirmQuoteAnswer.Quoted quoted = quote("QR-15");
        engine.answerUnknownStatusNext(status);
        assertThat(provider.execute(execution("T-15", quoted)))
                .isInstanceOf(ExecutionAnswer.Indeterminate.class);
        engine.answerUnknownStatusNext(status);
        assertThat(provider.firmQuote(request("QR-16"))).isInstanceOf(FirmQuoteAnswer.Indeterminate.class);
        engine.answerUnknownStatusNext(status);
        assertThat(provider.inquire("T-15")).isInstanceOf(ExecutionAnswer.Indeterminate.class);
    }

    @Test
    @DisplayName("ADR-0008's modes through the shared harness: timeout, 5xx, malformed, delayed,"
            + " unknown state - each indeterminate")
    void theSharedHarnessModes() throws Exception {
        try (SimulatedProvider harness = SimulatedProvider.start()) {
            FxProvider adapter =
                    new SimulatedFxProviderAdapter(URI.create(harness.baseUrl()), Duration.ofMillis(500), KEY);
            String path = SimulatedFxProviderAdapter.QUOTES_PATH;

            harness.neverResponds(path);
            assertThat(((FirmQuoteAnswer.Indeterminate) adapter.firmQuote(request("QR-20"))).cause())
                    .isEqualTo(Indeterminacy.TIMEOUT);
            harness.reset();
            harness.failsWith(path, 503);
            assertThat(((FirmQuoteAnswer.Indeterminate) adapter.firmQuote(request("QR-21"))).cause())
                    .isEqualTo(Indeterminacy.SERVER_ERROR);
            harness.reset();
            harness.respondsWithMalformedBody(path);
            assertThat(adapter.firmQuote(request("QR-22"))).isInstanceOf(FirmQuoteAnswer.Indeterminate.class);
            harness.reset();
            harness.respondsAfter(path, Duration.ofSeconds(2), 200, "{\"status\":\"quoted\"}");
            assertThat(((FirmQuoteAnswer.Indeterminate) adapter.firmQuote(request("QR-23"))).cause())
                    .isEqualTo(Indeterminacy.TIMEOUT);
            harness.reset();
            harness.returnsUnknownState(path, "maybe");
            assertThat(((FirmQuoteAnswer.Indeterminate) adapter.firmQuote(request("QR-24"))).cause())
                    .isEqualTo(Indeterminacy.UNKNOWN_STATE);
        }
    }

    @Test
    @DisplayName("a refusal with a reason the adapter does not know is indeterminate - a definitive"
            + " Rejected licenses a new T, so an unknown word must never earn one (P9-TSK-006's"
            + " gate)")
    void anUnknownReasonIsNeverDefinitive() throws Exception {
        try (SimulatedProvider harness = SimulatedProvider.start()) {
            FxProvider adapter =
                    new SimulatedFxProviderAdapter(URI.create(harness.baseUrl()), Duration.ofSeconds(2), KEY);
            for (String reason : new String[] {"", "expired", "QUOTE_EXPIRED", "quote_expired ", "maybe"}) {
                harness.reset();
                harness.succeedsWith(
                        SimulatedFxProviderAdapter.EXECUTIONS_PATH, 200,
                        "{\"status\":\"rejected\",\"reason\":\"" + reason + "\"}");
                assertThat(adapter.execute(new FxProvider.ExecutionRequest(
                                        "T-40", "PQ-1", EUR, USD, FixedSide.FIXED_SOURCE,
                                        Money.of(new BigDecimal("1000.00"), EUR))))
                        .as("rejected with reason [%s]", reason)
                        .isInstanceOf(ExecutionAnswer.Indeterminate.class);
                harness.reset();
                harness.succeedsWith(
                        SimulatedFxProviderAdapter.QUOTES_PATH, 200,
                        "{\"status\":\"declined\",\"reason\":\"" + reason + "\"}");
                assertThat(adapter.firmQuote(request("QR-40")))
                        .as("declined with reason [%s]", reason)
                        .isInstanceOf(FirmQuoteAnswer.Indeterminate.class);
            }
        }
    }

    @Test
    @DisplayName("the key and the evidence never reach a toString")
    void nothingSensitiveIsRendered() {
        assertThat(provider.toString())
                .doesNotContain(new String(KEY, StandardCharsets.UTF_8))
                .doesNotContain(Base64.getEncoder().encodeToString(KEY));
        FirmQuoteAnswer.Quoted quoted = quote("QR-30");
        // The evidence renders as its length only; the provider's raw body never appears.
        assertThat(quoted.toString()).contains("bytes]").doesNotContain("\"status\"");
    }

    // -----------------------------------------------------------------

    private FxProvider.FirmQuoteRequest request(String reference) {
        return new FxProvider.FirmQuoteRequest(
                reference, EUR, USD, FixedSide.FIXED_SOURCE, Money.of(new BigDecimal("1000.00"), EUR));
    }

    private FirmQuoteAnswer.Quoted quote(String reference) {
        FirmQuoteAnswer answer = provider.firmQuote(request(reference));
        assertThat(answer).isInstanceOf(FirmQuoteAnswer.Quoted.class);
        return (FirmQuoteAnswer.Quoted) answer;
    }

    private static FxProvider.ExecutionRequest execution(String reference, FirmQuoteAnswer.Quoted quoted) {
        return new FxProvider.ExecutionRequest(
                reference, quoted.providerQuoteReference(), EUR, USD, FixedSide.FIXED_SOURCE,
                Money.of(new BigDecimal("1000.00"), EUR));
    }

    private static ExecutionAnswer.Executed executed(ExecutionAnswer answer) {
        assertThat(answer).isInstanceOf(ExecutionAnswer.Executed.class);
        return (ExecutionAnswer.Executed) answer;
    }
}
