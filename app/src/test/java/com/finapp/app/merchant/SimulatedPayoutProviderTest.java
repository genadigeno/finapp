package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.merchant.PayoutAnswer;
import com.finapp.merchant.PayoutDestinationReference;
import com.finapp.merchant.PayoutProvider;
import com.finapp.merchant.PayoutQueryAnswer;
import com.finapp.merchant.PayoutReference;
import com.finapp.merchant.SimulatedPayoutProvider;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The simulated payout provider's total mapping (`P6-TSK-012`, ADR-0051 §4): exactly one shape
 * is {@code ACCEPTED} — and only with a reference the books can reconcile against — one is
 * {@code DECLINED}, a refused connection on a send is {@code NOTHING_SENT}, only an explicit
 * word is {@code UNRECOGNISED}, and everything else is indeterminate, never success.
 *
 * <p>Lives in {@code app} for the provider harness, which is {@code platform}'s test fixture.
 */
@DisplayName("the simulated payout provider (P6-TSK-012)")
class SimulatedPayoutProviderTest {

    private static final String PATH = SimulatedPayoutProvider.PAYOUTS_PATH;
    private static final byte[] KEY = new byte[32];
    private static final PayoutReference OURS = new PayoutReference("pyo-4f9Zk-Q2mX");
    private static final PayoutProvider.PayoutRequest REQUEST =
            new PayoutProvider.PayoutRequest(
                    OURS,
                    PayoutDestinationReference.of("pdr_7K-x9"),
                    Money.ofMinorUnits(2500, CurrencyCode.of("EUR")));

    private static SimulatedProvider provider;

    @BeforeAll
    static void start() {
        provider = SimulatedProvider.start();
        java.util.Arrays.fill(KEY, (byte) 5);
    }

    @AfterAll
    static void stop() {
        provider.close();
    }

    @BeforeEach
    void reset() {
        provider.reset();
    }

    private static SimulatedPayoutProvider adapter() {
        return new SimulatedPayoutProvider(URI.create(provider.baseUrl()), Duration.ofSeconds(2), KEY);
    }

    @Test
    @DisplayName("a paid answer is ACCEPTED with the provider's reference, under our reference and key")
    void aPaidAnswerIsAccepted() {
        provider.succeedsWith(PATH, 200, "{\"status\":\"paid\",\"reference\":\"po_77.a:b-c\"}");
        PayoutAnswer answer = adapter().dispatch(REQUEST);
        assertThat(answer.verdict()).isEqualTo(PayoutAnswer.Verdict.ACCEPTED);
        assertThat(answer.providerReference().orElseThrow().value()).isEqualTo("po_77.a:b-c");
        assertThat(answer.evidence()).isPresent();
        assertThat(provider.headerValues(PATH, SimulatedPayoutProvider.IDEMPOTENCY_KEY_HEADER))
                .as("our minted reference is the provider's idempotency key (INV-PAY-04)")
                .containsExactly(OURS.value());
        assertThat(provider.headerValues(PATH, "Authorization"))
                .as("the credential regime arrives with the provider that moves money")
                .containsExactly("Bearer " + Base64.getEncoder().encodeToString(KEY));
    }

    @Test
    @DisplayName("an explicit decline is DECLINED, with its bytes kept")
    void aDeclineIsDeclined() {
        provider.succeedsWith(PATH, 200, "{\"status\":\"declined\"}");
        PayoutAnswer answer = adapter().dispatch(REQUEST);
        assertThat(answer.verdict()).isEqualTo(PayoutAnswer.Verdict.DECLINED);
        assertThat(answer.evidence()).isPresent();
    }

    @Test
    @DisplayName("an unreconcilable acceptance, a pending or unmapped word, a 5xx or a 404 is indeterminate")
    void anythingElseIsIndeterminate() {
        String[] answers = {
            "{\"status\":\"paid\"}",
            "{\"status\":\"paid\",\"reference\":\"not a reference!\"}",
            "{\"status\":\"pending\"}",
            "{\"status\":\"approved\",\"reference\":\"po_1\"}",
            "{\"status\": \"PAI"
        };
        for (String body : answers) {
            provider.reset();
            provider.succeedsWith(PATH, 200, body);
            PayoutAnswer answer = adapter().dispatch(REQUEST);
            assertThat(answer.verdict()).as(body).isEqualTo(PayoutAnswer.Verdict.INDETERMINATE);
            assertThat(answer.evidence()).as("the bytes are kept: %s", body).isPresent();
        }
        for (int status : new int[] {500, 404, 202}) {
            provider.reset();
            provider.succeedsWith(PATH, status, "{\"status\":\"paid\",\"reference\":\"po_1\"}");
            assertThat(adapter().dispatch(REQUEST).verdict())
                    .as("HTTP %s is never success", status)
                    .isEqualTo(PayoutAnswer.Verdict.INDETERMINATE);
        }
    }

    @Test
    @DisplayName("garbage, a lost response and a timeout are indeterminate: the request may have arrived")
    void transportAmbiguityIsIndeterminate() {
        provider.respondsWithGarbage(PATH);
        assertThat(adapter().dispatch(REQUEST).verdict())
                .isEqualTo(PayoutAnswer.Verdict.INDETERMINATE);
        provider.reset();
        provider.receivesTheRequestThenLosesTheResponse(PATH);
        assertThat(adapter().dispatch(REQUEST).verdict())
                .isEqualTo(PayoutAnswer.Verdict.INDETERMINATE);
        provider.reset();
        provider.neverResponds(PATH);
        SimulatedPayoutProvider impatient =
                new SimulatedPayoutProvider(
                        URI.create(provider.baseUrl()), Duration.ofMillis(300), KEY);
        PayoutAnswer timedOut = impatient.dispatch(REQUEST);
        assertThat(timedOut.verdict()).isEqualTo(PayoutAnswer.Verdict.INDETERMINATE);
        assertThat(timedOut.evidence()).isEmpty();
    }

    @Test
    @DisplayName("a refused connection is NOTHING_SENT on a send, and simply no answer on a query")
    void aRefusedConnection() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        SimulatedPayoutProvider unreachable =
                new SimulatedPayoutProvider(
                        URI.create("http://127.0.0.1:" + closedPort), Duration.ofSeconds(2), KEY);
        PayoutAnswer sent = unreachable.dispatch(REQUEST);
        assertThat(sent.verdict()).isEqualTo(PayoutAnswer.Verdict.NOTHING_SENT);
        assertThat(sent.evidence()).as("nothing arrived").isEmpty();
        assertThat(unreachable.query(OURS).verdict())
                .isEqualTo(PayoutQueryAnswer.Verdict.INDETERMINATE);
    }

    @Test
    @DisplayName("a query maps paid, declined and only the explicit word unrecognised")
    void theQueryMapping() {
        String path = PATH + "/" + OURS.value();
        provider.succeedsWith(path, 200, "{\"status\":\"paid\",\"reference\":\"po_9\"}");
        PayoutQueryAnswer paid = adapter().query(OURS);
        assertThat(paid.verdict()).isEqualTo(PayoutQueryAnswer.Verdict.ACCEPTED);
        assertThat(paid.providerReference().orElseThrow().value()).isEqualTo("po_9");
        assertThat(provider.headerValues(path, "Authorization"))
                .containsExactly("Bearer " + Base64.getEncoder().encodeToString(KEY));

        provider.reset();
        provider.succeedsWith(path, 200, "{\"status\":\"declined\"}");
        assertThat(adapter().query(OURS).verdict()).isEqualTo(PayoutQueryAnswer.Verdict.DECLINED);

        provider.reset();
        provider.succeedsWith(path, 200, "{\"status\":\"unrecognised\"}");
        assertThat(adapter().query(OURS).verdict())
                .isEqualTo(PayoutQueryAnswer.Verdict.UNRECOGNISED);

        provider.reset();
        provider.succeedsWith(path, 404, "{\"status\":\"unrecognised\"}");
        assertThat(adapter().query(OURS).verdict())
                .as("a 404 can be a proxy or a misroute: only the explicit 200 word says no record")
                .isEqualTo(PayoutQueryAnswer.Verdict.INDETERMINATE);

        provider.reset();
        provider.succeedsWith(path, 200, "{\"status\":\"pending\"}");
        assertThat(adapter().query(OURS).verdict())
                .isEqualTo(PayoutQueryAnswer.Verdict.INDETERMINATE);
    }

    @Test
    @DisplayName("the request prints our reference and the currency, never the destination or the amount")
    void theRequestLeaksNothing() {
        assertThat(REQUEST.toString())
                .contains(OURS.value())
                .contains("EUR")
                .doesNotContain("pdr_7K-x9")
                .doesNotContain("2500");
    }
}
