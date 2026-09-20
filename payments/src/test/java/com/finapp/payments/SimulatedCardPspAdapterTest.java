package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.payments.PaymentProvider.AuthorizationRequest;
import com.finapp.payments.PaymentProvider.CaptureRequest;
import com.finapp.payments.PaymentProvider.RefundRequest;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The adapter's contract over every outbound harness mode (`P5-TSK-003`, ADR-0049) — the
 * `P0-TSK-037` harness meeting the payment caller it was built for.
 *
 * <p>The full misbehaviour matrix runs against <strong>authorize</strong>; capture, refund and
 * query are thin bindings of the same {@code PspWireClient}, so each gets its happy path plus
 * one representative failure to prove its wiring (the `P2-TSK-009` one-full-matrix precedent).
 * Two assertions are made <em>at the wire</em> through the harness's header accessor, because
 * no assertion on the caller's own records can see them: a re-dispatched operation presents the
 * <strong>same</strong> idempotency reference (`INV-PAY-04` lives in the provider's dedupe,
 * which sees headers), and the credential is actually sent (a credential nothing sends is
 * decorative).
 */
@DisplayName("SimulatedCardPspAdapter (P5-TSK-003)")
class SimulatedCardPspAdapterTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final Money TEN_USD = Money.ofMinorUnits(1000, CurrencyCode.of("USD"));
    private static final ProviderIdempotencyReference OUR_REF =
            new ProviderIdempotencyReference("pay-auth-0192a7b2");
    private static final ProviderReference PSP_AUTH = new ProviderReference("psp_auth_1");

    private static final AuthorizationRequest AUTHORIZE =
            new AuthorizationRequest(OUR_REF, InstrumentToken.of("tok_visa-4242"), TEN_USD);

    /**
     * A body deliberately hostile to trim-and-re-encode (`P0-TST-005`'s lesson): leading and
     * trailing whitespace and irregular spacing must survive verbatim retention byte for byte.
     */
    private static final String APPROVED_BODY =
            "  {\"status\" : \"approved\",  \"reference\":\"psp_auth_1\"}\n";

    private static SimulatedProvider provider;

    @BeforeAll
    static void start() {
        provider = SimulatedProvider.start();
    }

    @AfterAll
    static void stop() {
        provider.close();
    }

    @BeforeEach
    void reset() {
        provider.reset();
    }

    private SimulatedCardPspAdapter adapter() {
        return adapter(Duration.ofSeconds(2));
    }

    private SimulatedCardPspAdapter adapter(Duration timeout) {
        return new SimulatedCardPspAdapter(URI.create(provider.baseUrl()), timeout, KEY);
    }

    // ------------------------------------------------------------------
    // The full matrix, on authorize
    // ------------------------------------------------------------------

    @Test
    @DisplayName("approved: verdict, the provider's reference, and the bytes retained verbatim")
    void approvedAuthorization() {
        provider.succeedsWith(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, 200, APPROVED_BODY);

        ProviderAnswer answer = adapter().authorize(AUTHORIZE);

        assertThat(answer.verdict()).isEqualTo(ProviderAnswer.Verdict.APPROVED);
        assertThat(answer.providerReference()).contains(PSP_AUTH);
        // Byte-for-byte, not a round-trip of it: whitespace and all.
        assertThat(answer.evidence().orElseThrow())
                .isEqualTo(APPROVED_BODY.getBytes(StandardCharsets.UTF_8));
        assertThat(provider.requestCount(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH)).isEqualTo(1);
    }

    @Test
    @DisplayName("declined: knowledge, evidence retained, no reference to act on")
    void declinedAuthorization() {
        provider.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH,
                200,
                "{\"status\":\"declined\",\"reason\":\"insufficient_funds\"}");

        ProviderAnswer answer = adapter().authorize(AUTHORIZE);

        assertThat(answer.verdict()).isEqualTo(ProviderAnswer.Verdict.DECLINED);
        assertThat(answer.providerReference()).isEmpty();
        // The provider's own decline code lives in the evidence - INV-PAY-03's one home for
        // provider vocabulary - never in an enum of ours.
        assertThat(new String(answer.evidence().orElseThrow(), StandardCharsets.UTF_8))
                .contains("insufficient_funds");
    }

    @Test
    @DisplayName("approved without the provider's reference is INDETERMINATE - unactionable is not knowledge")
    void approvedWithoutReferenceIsIndeterminate() {
        provider.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, 200, "{\"status\":\"approved\"}");

        ProviderAnswer answer = adapter().authorize(AUTHORIZE);

        assertThat(answer.verdict()).isEqualTo(ProviderAnswer.Verdict.INDETERMINATE);
        assertThat(answer.evidence()).isPresent();
    }

    @Test
    @DisplayName("an unknown state maps to INDETERMINATE, never success (INV-PAY-03's default branch)")
    void unknownStateIsIndeterminate() {
        provider.returnsUnknownState(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, "approved_pending_review");

        ProviderAnswer answer = adapter().authorize(AUTHORIZE);

        assertThat(answer.verdict()).isEqualTo(ProviderAnswer.Verdict.INDETERMINATE);
        assertThat(answer.evidence()).isPresent();

        // And with a reference PRESENT, because that is the answer a default-branch-approves
        // mutation would wave through: the harness's own unknown-state body carries none, so
        // without this probe the acceptance clause would be proven against only the easy half.
        provider.reset();
        provider.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH,
                200,
                "{\"status\":\"authorized_pending\",\"reference\":\"psp_auth_9\"}");
        assertThat(adapter().authorize(AUTHORIZE).verdict())
                .isEqualTo(ProviderAnswer.Verdict.INDETERMINATE);
    }

    @Test
    @DisplayName("a malformed body is INDETERMINATE with the bytes retained (INV-HIST-02)")
    void malformedBodyIsIndeterminate() {
        provider.respondsWithMalformedBody(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH);

        ProviderAnswer answer = adapter().authorize(AUTHORIZE);

        assertThat(answer.verdict()).isEqualTo(ProviderAnswer.Verdict.INDETERMINATE);
        // The unparseable answer is exactly what an investigation of the provider wants.
        assertThat(answer.evidence()).isPresent();
    }

    @Test
    @DisplayName("garbage that is not HTTP is INDETERMINATE with no evidence - nothing usable arrived")
    void garbageIsIndeterminate() {
        provider.respondsWithGarbage(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH);

        ProviderAnswer answer = adapter().authorize(AUTHORIZE);

        assertThat(answer.verdict()).isEqualTo(ProviderAnswer.Verdict.INDETERMINATE);
        assertThat(answer.evidence()).isEmpty();
    }

    @Test
    @DisplayName("a bodyless 5xx is INDETERMINATE - an intermediary's 503 is not knowledge")
    void serverErrorIsIndeterminate() {
        provider.isUnavailable(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH);

        ProviderAnswer answer = adapter().authorize(AUTHORIZE);

        // PAYMENT_LIFECYCLES.md section 3 lists a 5xx under *_UNKNOWN by name: a 503 may be a
        // load balancer's, sent after the provider acted. Only a parsed 200 body is knowledge.
        assertThat(answer.verdict()).isEqualTo(ProviderAnswer.Verdict.INDETERMINATE);
    }

    @Test
    @DisplayName("a 5xx with a body is INDETERMINATE and the body is retained")
    void serverErrorBodyIsRetained() {
        provider.succeedsWith(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, 500, "{\"error\":\"boom\"}");

        ProviderAnswer answer = adapter().authorize(AUTHORIZE);

        assertThat(answer.verdict()).isEqualTo(ProviderAnswer.Verdict.INDETERMINATE);
        assertThat(new String(answer.evidence().orElseThrow(), StandardCharsets.UTF_8))
                .contains("boom");
    }

    @Test
    @DisplayName("a timeout is INDETERMINATE - and requestCount proves the provider received it")
    void timeoutIsIndeterminate() {
        provider.neverResponds(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH);

        ProviderAnswer answer = adapter(Duration.ofMillis(400)).authorize(AUTHORIZE);

        assertThat(answer.verdict()).isEqualTo(ProviderAnswer.Verdict.INDETERMINATE);
        assertThat(answer.evidence()).isEmpty();
        // The provider HAS the request: treating this as failure is the phase's named most
        // expensive assumption (INV-LIFE-03).
        assertThat(provider.requestCount(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH)).isEqualTo(1);
    }

    @Test
    @DisplayName("a connection refused before anything was sent is NOTHING_SENT - knowledge, not ambiguity")
    void refusedConnectionIsNothingSent() throws IOException {
        int unboundPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unboundPort = socket.getLocalPort();
        }
        SimulatedCardPspAdapter nobodyListening =
                new SimulatedCardPspAdapter(
                        URI.create("http://127.0.0.1:" + unboundPort), Duration.ofSeconds(2), KEY);

        ProviderAnswer answer = nobodyListening.authorize(AUTHORIZE);

        // An RST arrived: nothing was transmitted, the operation cannot have happened, and the
        // caller commits FAILED(PROVIDER_UNAVAILABLE) rather than an *_UNKNOWN to sweep.
        assertThat(answer.verdict()).isEqualTo(ProviderAnswer.Verdict.NOTHING_SENT);
        assertThat(answer.evidence()).isEmpty();
        assertThat(answer.providerReference()).isEmpty();
    }

    @Test
    @DisplayName("received-then-lost-response is INDETERMINATE, and requestCount is the oracle")
    void lostResponseIsIndeterminate() {
        provider.receivesTheRequestThenLosesTheResponse(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH);

        ProviderAnswer answer = adapter().authorize(AUTHORIZE);

        assertThat(answer.verdict()).isEqualTo(ProviderAnswer.Verdict.INDETERMINATE);
        // The one assertion that separates this from the refused connection: the request
        // arrived and may have been acted on before the answer was lost.
        assertThat(provider.requestCount(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH)).isEqualTo(1);
    }

    @Test
    @DisplayName("slow but within the timeout is an ordinary answer")
    void slowButInTimeIsApproved() {
        provider.respondsAfter(
                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH,
                Duration.ofMillis(150),
                200,
                APPROVED_BODY);

        ProviderAnswer answer = adapter().authorize(AUTHORIZE);

        assertThat(answer.verdict()).isEqualTo(ProviderAnswer.Verdict.APPROVED);
    }

    @Test
    @DisplayName("a body over the evidence bound is INDETERMINATE with nothing retained - the stated bound")
    void oversizedBodyIsIndeterminate() {
        String oversized =
                "{\"status\":\"approved\",\"padding\":\""
                        + "x".repeat(PspWireClient.MAX_EVIDENCE_BYTES)
                        + "\"}";
        provider.succeedsWith(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, 200, oversized);

        ProviderAnswer answer = adapter().authorize(AUTHORIZE);

        assertThat(answer.verdict()).isEqualTo(ProviderAnswer.Verdict.INDETERMINATE);
        assertThat(answer.evidence()).isEmpty();
    }

    // ------------------------------------------------------------------
    // The wire facts no caller-side assertion can see
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a re-dispatched operation presents the SAME idempotency reference (INV-PAY-04)")
    void reDispatchPresentsTheSameReference() {
        provider.succeedsWith(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, 200, APPROVED_BODY);
        SimulatedCardPspAdapter adapter = adapter();

        adapter.authorize(AUTHORIZE);
        adapter.authorize(AUTHORIZE);

        // Asserted at the wire: the provider's dedupe sees headers, not our records, so this -
        // not the request object holding what we put in it - is where the invariant lives.
        assertThat(
                        provider.headerValues(
                                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH,
                                SimulatedCardPspAdapter.IDEMPOTENCY_KEY_HEADER))
                .containsExactly(OUR_REF.value(), OUR_REF.value());
    }

    @Test
    @DisplayName("every dispatch carries the confined API credential - a credential nothing sends is decorative")
    void everyDispatchCarriesTheCredential() {
        provider.succeedsWith(SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, 200, APPROVED_BODY);
        provider.succeedsWith(SimulatedCardPspAdapter.CAPTURES_PATH, 200, APPROVED_BODY);
        String expected = "Bearer " + Base64.getEncoder().encodeToString(KEY);
        SimulatedCardPspAdapter adapter = adapter();

        adapter.authorize(AUTHORIZE);
        adapter.capture(new CaptureRequest(OUR_REF, PSP_AUTH, TEN_USD));

        assertThat(
                        provider.headerValues(
                                SimulatedCardPspAdapter.AUTHORIZATIONS_PATH, "Authorization"))
                .containsExactly(expected);
        assertThat(provider.headerValues(SimulatedCardPspAdapter.CAPTURES_PATH, "Authorization"))
                .containsExactly(expected);
    }

    // ------------------------------------------------------------------
    // Capture and refund: thin bindings of the same client, proven wired
    // ------------------------------------------------------------------

    @Test
    @DisplayName("capture: approved on its own path, carrying the same reference discipline")
    void captureIsWired() {
        provider.succeedsWith(
                SimulatedCardPspAdapter.CAPTURES_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"psp_cap_1\"}");

        ProviderAnswer answer = adapter().capture(new CaptureRequest(OUR_REF, PSP_AUTH, TEN_USD));

        assertThat(answer.verdict()).isEqualTo(ProviderAnswer.Verdict.APPROVED);
        assertThat(answer.providerReference()).contains(new ProviderReference("psp_cap_1"));
        assertThat(
                        provider.headerValues(
                                SimulatedCardPspAdapter.CAPTURES_PATH,
                                SimulatedCardPspAdapter.IDEMPOTENCY_KEY_HEADER))
                .containsExactly(OUR_REF.value());
    }

    @Test
    @DisplayName("capture: the matrix's totality holds on this path too")
    void captureFailureIsMapped() {
        provider.returnsUnknownState(SimulatedCardPspAdapter.CAPTURES_PATH, "settling");

        ProviderAnswer answer = adapter().capture(new CaptureRequest(OUR_REF, PSP_AUTH, TEN_USD));

        assertThat(answer.verdict()).isEqualTo(ProviderAnswer.Verdict.INDETERMINATE);
    }

    @Test
    @DisplayName("refund: approved and declined on its own path")
    void refundIsWired() {
        provider.succeedsWith(
                SimulatedCardPspAdapter.REFUNDS_PATH,
                200,
                "{\"status\":\"approved\",\"reference\":\"psp_ref_1\"}");

        ProviderAnswer approved =
                adapter().refund(new RefundRequest(OUR_REF, new ProviderReference("psp_cap_1"), TEN_USD));
        assertThat(approved.verdict()).isEqualTo(ProviderAnswer.Verdict.APPROVED);

        provider.reset();
        provider.succeedsWith(
                SimulatedCardPspAdapter.REFUNDS_PATH, 200, "{\"status\":\"declined\"}");
        ProviderAnswer declined =
                adapter().refund(new RefundRequest(OUR_REF, new ProviderReference("psp_cap_1"), TEN_USD));
        assertThat(declined.verdict()).isEqualTo(ProviderAnswer.Verdict.DECLINED);
    }

    // ------------------------------------------------------------------
    // The query: ADR-0046's resolution path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("query: approved with the provider's reference resolves; without it, INDETERMINATE")
    void queryApproved() {
        String path = SimulatedCardPspAdapter.OPERATIONS_PATH + OUR_REF.value();
        provider.succeedsWith(path, 200, APPROVED_BODY);

        QueryAnswer answer = adapter().query(OUR_REF);
        assertThat(answer.verdict()).isEqualTo(QueryAnswer.Verdict.APPROVED);
        assertThat(answer.providerReference()).contains(PSP_AUTH);

        provider.reset();
        provider.succeedsWith(path, 200, "{\"status\":\"approved\"}");
        assertThat(adapter().query(OUR_REF).verdict()).isEqualTo(QueryAnswer.Verdict.INDETERMINATE);
    }

    @Test
    @DisplayName("query: declined and unrecognised are explicit parsed answers")
    void queryDeclinedAndUnrecognised() {
        String path = SimulatedCardPspAdapter.OPERATIONS_PATH + OUR_REF.value();

        provider.succeedsWith(path, 200, "{\"status\":\"declined\"}");
        assertThat(adapter().query(OUR_REF).verdict()).isEqualTo(QueryAnswer.Verdict.DECLINED);

        provider.reset();
        provider.succeedsWith(path, 200, "{\"status\":\"unrecognised\"}");
        QueryAnswer unrecognised = adapter().query(OUR_REF);
        assertThat(unrecognised.verdict()).isEqualTo(QueryAnswer.Verdict.UNRECOGNISED);
        assertThat(unrecognised.evidence()).isPresent();
    }

    @Test
    @DisplayName("query: a 404 is INDETERMINATE - a status code is not an answer")
    void queryNotFoundIsIndeterminate() {
        String path = SimulatedCardPspAdapter.OPERATIONS_PATH + OUR_REF.value();
        provider.failsWith(path, 404);

        // A misrouted load balancer's 404 reading as "never happened" would resolve a live
        // operation to FAILED - the expensive direction. Only an explicit parsed answer
        // ("unrecognised") earns that resolution.
        assertThat(adapter().query(OUR_REF).verdict()).isEqualTo(QueryAnswer.Verdict.INDETERMINATE);
    }

    @Test
    @DisplayName("query: an unknown state maps to INDETERMINATE here too")
    void queryUnknownStateIsIndeterminate() {
        String path = SimulatedCardPspAdapter.OPERATIONS_PATH + OUR_REF.value();
        provider.returnsUnknownState(path, "pending_review");

        assertThat(adapter().query(OUR_REF).verdict()).isEqualTo(QueryAnswer.Verdict.INDETERMINATE);
    }
}
