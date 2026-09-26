package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.payments.PushRail.CreditTransfer;
import com.finapp.payments.PushRail.GrantExchange;
import com.finapp.payments.PushRail.PayInInitiation;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The instant scheme adapter's contract (`P7-TSK-006`, ADR-0062 §1): the descriptor pinned
 * field by field, and the port's totality proven under every injected fault — the card
 * contract suite's battery, run against the second wire. The acceptance criterion is this
 * file executed: <em>the adapter behaves as its descriptor says under every fault</em>.
 */
@DisplayName("SimulatedInstantSchemeAdapter (P7-TSK-006)")
class SimulatedInstantSchemeAdapterTest {

    private static final byte[] KEY =
            "abcdefabcdef0123456789abcdef012345".getBytes(StandardCharsets.UTF_8);
    private static final Money TEN_EUR = Money.ofMinorUnits(10_00, CurrencyCode.of("EUR"));
    private static final EndToEndReference OUR_REF =
            new EndToEndReference("e2e-0192a7b2c3d4");
    private static final ProviderReference DESTINATION =
            new ProviderReference("dest_opaque_77aa");

    private static final CreditTransfer SEND =
            new CreditTransfer(OUR_REF, DESTINATION, TEN_EUR);
    private static final GrantExchange EXCHANGE =
            new GrantExchange(OUR_REF, "grant-one-time-abc123");
    private static final PayInInitiation INITIATE = new PayInInitiation(OUR_REF, TEN_EUR);

    /** Whitespace-hostile on purpose: retention must be byte for byte (the card lesson). */
    private static final String ACCEPTED_BODY =
            "  {\"status\" : \"accepted\",  \"reference\":\"scheme_tx_1\","
                    + " \"cycle\":\"CYC-2026-09-26-01\"}\n";

    private static SimulatedProvider scheme;

    @BeforeAll
    static void start() {
        scheme = SimulatedProvider.start();
    }

    @AfterAll
    static void stop() {
        scheme.close();
    }

    @BeforeEach
    void reset() {
        scheme.reset();
    }

    private SimulatedInstantSchemeAdapter adapter() {
        return adapter(Duration.ofSeconds(2));
    }

    private SimulatedInstantSchemeAdapter adapter(Duration timeout) {
        return new SimulatedInstantSchemeAdapter(URI.create(scheme.baseUrl()), timeout, KEY);
    }

    // ------------------------------------------------------------------
    // The declaration, pinned field by field (the card RAIL's discipline)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the declaration: push, FINAL ON ACCEPTANCE, no reversals, return-payment"
            + " refunds, scheme-reported settlement with its own clearing position, the"
            + " declared outcome deadline, no disputes")
    void theDeclarationIsPinnedFieldByField() {
        assertThat(SimulatedInstantSchemeAdapter.RAIL.id()).isEqualTo(RailId.of("instant"));
        assertThat(SimulatedInstantSchemeAdapter.RAIL.declarationVersion()).isEqualTo(1);
        RailCapabilities instant = SimulatedInstantSchemeAdapter.RAIL.capabilities();
        assertThat(instant.interactionModel()).isEqualTo(InteractionModel.PUSH);
        assertThat(instant.finality())
                .as("the payee's credit cannot be taken back - the material difference from"
                        + " the card rail (ADR-0059 §1)")
                .isEqualTo(RailCapabilities.Finality.FINAL_ON_ACCEPTANCE);
        assertThat(instant.reversals())
                .as("no void, no reversal of any kind: a return is a NEW transfer")
                .isEmpty();
        assertThat(instant.refundMode())
                .isEqualTo(RailCapabilities.RefundMode.RETURN_PAYMENT);
        assertThat(instant.settlement())
                .isEqualTo(RailCapabilities.SettlementModel.SCHEME_REPORTED);
        assertThat(instant.outcomeDeadline())
                .as("the scheme bounds its own ambiguity (ADR-0062 §3)")
                .contains(SimulatedInstantSchemeAdapter.OUTCOME_DEADLINE);
        assertThat(instant.disputes()).isEqualTo(RailCapabilities.DisputeModel.NONE);
        assertThat(instant.currencies()).isEmpty();
        assertThat(instant.perCurrencyMaximum()).isEmpty();
        assertThat(instant.clearingPurpose())
                .as("every accepted push lands in the scheme's own position (ADR-0062 §4)")
                .contains(AccountPurpose.INSTANT_CLEARING);
    }

    // ------------------------------------------------------------------
    // The full matrix, on send
    // ------------------------------------------------------------------

    @Test
    @DisplayName("accepted: verdict, the scheme's reference AND cycle, bytes verbatim")
    void acceptedSend() {
        scheme.succeedsWith(SimulatedInstantSchemeAdapter.TRANSFERS_PATH, 200, ACCEPTED_BODY);

        PushAnswer answer = adapter().send(SEND);

        assertThat(answer.verdict()).isEqualTo(PushAnswer.Verdict.ACCEPTED);
        assertThat(answer.schemeReference())
                .contains(new ProviderReference("scheme_tx_1"));
        assertThat(answer.settlementCycle())
                .as("the settlement-cycle identifier is carried in every answer - Phase 8's"
                        + " reconciliation key")
                .contains("CYC-2026-09-26-01");
        assertThat(answer.evidence().orElseThrow())
                .isEqualTo(ACCEPTED_BODY.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("rejected: knowledge, bytes retained, nothing to key")
    void rejectedSend() {
        scheme.succeedsWith(
                SimulatedInstantSchemeAdapter.TRANSFERS_PATH, 200,
                "{\"status\":\"rejected\",\"reason\":\"AC01\"}");

        PushAnswer answer = adapter().send(SEND);

        assertThat(answer.verdict()).isEqualTo(PushAnswer.Verdict.REJECTED);
        assertThat(answer.schemeReference()).isEmpty();
        assertThat(answer.evidence()).isPresent();
    }

    @Test
    @DisplayName("accepted WITHOUT the scheme reference is INDETERMINATE - an acceptance"
            + " reconciliation cannot key is not knowledge")
    void acceptedWithoutReferenceIsIndeterminate() {
        scheme.succeedsWith(
                SimulatedInstantSchemeAdapter.TRANSFERS_PATH, 200,
                "{\"status\":\"accepted\"}");
        assertThat(adapter().send(SEND).verdict())
                .isEqualTo(PushAnswer.Verdict.INDETERMINATE);
    }

    @Test
    @DisplayName("an unmapped status, a malformed body, garbage, a 5xx and a timeout are all"
            + " INDETERMINATE - the total mapping's default (INV-PAY-03, INV-LIFE-03)")
    void everyUnusableAnswerIsIndeterminate() {
        scheme.succeedsWith(
                SimulatedInstantSchemeAdapter.TRANSFERS_PATH, 200,
                "{\"status\":\"pending-ish\"}");
        assertThat(adapter().send(SEND).verdict())
                .isEqualTo(PushAnswer.Verdict.INDETERMINATE);

        scheme.reset();
        scheme.succeedsWith(
                SimulatedInstantSchemeAdapter.TRANSFERS_PATH, 200, "{not json at all");
        assertThat(adapter().send(SEND).verdict())
                .isEqualTo(PushAnswer.Verdict.INDETERMINATE);

        scheme.reset();
        scheme.succeedsWith(
                SimulatedInstantSchemeAdapter.TRANSFERS_PATH, 503, "{\"oops\":true}");
        PushAnswer serverError = adapter().send(SEND);
        assertThat(serverError.verdict()).isEqualTo(PushAnswer.Verdict.INDETERMINATE);
        assertThat(serverError.evidence()).as("a 5xx body is still evidence").isPresent();

        scheme.reset();
        scheme.neverResponds(SimulatedInstantSchemeAdapter.TRANSFERS_PATH);
        assertThat(adapter(Duration.ofMillis(300)).send(SEND).verdict())
                .isEqualTo(PushAnswer.Verdict.INDETERMINATE);
    }

    @Test
    @DisplayName("a body over the evidence bound is INDETERMINATE with nothing retained -"
            + " verbatim retention of the unboundable is a promise nobody can keep")
    void oversizedBodyIsIndeterminate() {
        scheme.succeedsWith(
                SimulatedInstantSchemeAdapter.TRANSFERS_PATH, 200,
                "{\"status\":\"accepted\",\"reference\":\"scheme_tx_big\",\"pad\":\""
                        + "x".repeat(SchemeWireClient.MAX_EVIDENCE_BYTES) + "\"}");
        PushAnswer answer = adapter().send(SEND);
        assertThat(answer.verdict()).isEqualTo(PushAnswer.Verdict.INDETERMINATE);
        assertThat(answer.evidence()).isEmpty();
    }

    @Test
    @DisplayName("received-then-lost is INDETERMINATE and requestCount is the oracle: the"
            + " scheme MAY have executed (INV-LIFE-03's whole premise)")
    void lostResponseIsIndeterminate() {
        scheme.receivesTheRequestThenLosesTheResponse(
                SimulatedInstantSchemeAdapter.TRANSFERS_PATH);
        assertThat(adapter().send(SEND).verdict())
                .isEqualTo(PushAnswer.Verdict.INDETERMINATE);
        assertThat(scheme.requestCount(SimulatedInstantSchemeAdapter.TRANSFERS_PATH))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a connection refused before anything was sent is NOTHING_SENT - the one"
            + " transport failure that is knowledge")
    void refusedConnectionIsNothingSent() throws IOException {
        int deadPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        SimulatedInstantSchemeAdapter dead =
                new SimulatedInstantSchemeAdapter(
                        URI.create("http://127.0.0.1:" + deadPort),
                        Duration.ofMillis(400),
                        KEY);
        assertThat(dead.send(SEND).verdict()).isEqualTo(PushAnswer.Verdict.NOTHING_SENT);
        assertThat(dead.exchange(EXCHANGE).outcome())
                .isEqualTo(ExchangeAnswer.Outcome.NOTHING_SENT);
        assertThat(dead.initiate(INITIATE).outcome())
                .isEqualTo(InitiationAnswer.Outcome.NOTHING_SENT);
    }

    @Test
    @DisplayName("a re-send carries the SAME end-to-end reference on the wire - INV-PAY-04's"
            + " push face, and the dedupe premise the scheme declares")
    void aResendCarriesTheSameReference() {
        scheme.succeedsWith(SimulatedInstantSchemeAdapter.TRANSFERS_PATH, 200, ACCEPTED_BODY);

        adapter().send(SEND);
        adapter().send(SEND);

        List<String> presented =
                scheme.headerValues(
                        SimulatedInstantSchemeAdapter.TRANSFERS_PATH,
                        SimulatedInstantSchemeAdapter.IDEMPOTENCY_KEY_HEADER);
        assertThat(presented).containsExactly(OUR_REF.value(), OUR_REF.value());
    }

    @Test
    @DisplayName("timeout-then-accept: the send is INDETERMINATE and the INQUIRY resolves it"
            + " - with reference and cycle, exactly as a fresh acceptance would carry")
    void timeoutThenAcceptResolvesByInquiry() {
        scheme.neverResponds(SimulatedInstantSchemeAdapter.TRANSFERS_PATH);
        assertThat(adapter(Duration.ofMillis(300)).send(SEND).verdict())
                .isEqualTo(PushAnswer.Verdict.INDETERMINATE);

        scheme.reset();
        scheme.succeedsWith(
                SimulatedInstantSchemeAdapter.TRANSFER_STATUS_PATH + OUR_REF.value(), 200,
                ACCEPTED_BODY);
        PushInquiryAnswer resolved = adapter().inquire(OUR_REF);
        assertThat(resolved.verdict()).isEqualTo(PushInquiryAnswer.Verdict.ACCEPTED);
        assertThat(resolved.schemeReference()).contains(new ProviderReference("scheme_tx_1"));
        assertThat(resolved.settlementCycle()).contains("CYC-2026-09-26-01");
    }

    @Test
    @DisplayName("duplicate and late confirmations are idempotent inquiries: asked twice,"
            + " answered the same, both received (the fault library's late-report shape)")
    void duplicateAndLateConfirmationsAreIdempotentInquiries() {
        scheme.succeedsWith(
                SimulatedInstantSchemeAdapter.TRANSFER_STATUS_PATH + OUR_REF.value(), 200,
                ACCEPTED_BODY);
        PushInquiryAnswer first = adapter().inquire(OUR_REF);
        PushInquiryAnswer late = adapter().inquire(OUR_REF);
        assertThat(first.verdict()).isEqualTo(PushInquiryAnswer.Verdict.ACCEPTED);
        assertThat(late.verdict()).isEqualTo(first.verdict());
        assertThat(late.schemeReference()).isEqualTo(first.schemeReference());
        assertThat(
                        scheme.requestCount(
                                SimulatedInstantSchemeAdapter.TRANSFER_STATUS_PATH
                                        + OUR_REF.value()))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("the inquiry's explicit 'unrecognised' is UNRECOGNISED - the word that,"
            + " past the declared deadline, licenses never-executed (ADR-0062 §3); an"
            + " unmapped word never is")
    void unknownAfterTheDeadlineIsTheExplicitWordOnly() {
        scheme.succeedsWith(
                SimulatedInstantSchemeAdapter.TRANSFER_STATUS_PATH + OUR_REF.value(), 200,
                "{\"status\":\"unrecognised\"}");
        assertThat(adapter().inquire(OUR_REF).verdict())
                .isEqualTo(PushInquiryAnswer.Verdict.UNRECOGNISED);

        scheme.reset();
        scheme.succeedsWith(
                SimulatedInstantSchemeAdapter.TRANSFER_STATUS_PATH + OUR_REF.value(), 404,
                "{\"error\":\"not found\"}");
        assertThat(adapter().inquire(OUR_REF).verdict())
                .as("a 404 is a status code, not an answer - never the licence")
                .isEqualTo(PushInquiryAnswer.Verdict.INDETERMINATE);
    }

    // ------------------------------------------------------------------
    // The grant exchange (ADR-0062 §2)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("exchanged: exactly the three stored values - opaque destination, the"
            + " four-character suffix, and every confirmation-of-payee word")
    void exchangedCarriesExactlyTheThreeValues() {
        for (String word : new String[] {"match", "close_match", "no_match", "unavailable"}) {
            scheme.reset();
            scheme.succeedsWith(
                    SimulatedInstantSchemeAdapter.EXCHANGES_PATH, 200,
                    "{\"status\":\"exchanged\",\"destination\":\"dest_opaque_9f\","
                            + "\"suffix\":\"4321\",\"payee\":\"" + word + "\"}");
            ExchangeAnswer answer = adapter().exchange(EXCHANGE);
            assertThat(answer.outcome()).as(word).isEqualTo(ExchangeAnswer.Outcome.EXCHANGED);
            assertThat(answer.destination())
                    .contains(new ProviderReference("dest_opaque_9f"));
            assertThat(answer.displaySuffix()).contains("4321");
            assertThat(answer.payee()).isPresent();
        }
    }

    @Test
    @DisplayName("a refused grant is knowledge; an exchange missing any stored value, or"
            + " with a malformed suffix, is INDETERMINATE - unactionable is not knowledge")
    void refusedAndUnusableExchanges() {
        scheme.succeedsWith(
                SimulatedInstantSchemeAdapter.EXCHANGES_PATH, 200,
                "{\"status\":\"refused\"}");
        assertThat(adapter().exchange(EXCHANGE).outcome())
                .isEqualTo(ExchangeAnswer.Outcome.REFUSED);

        scheme.reset();
        scheme.succeedsWith(
                SimulatedInstantSchemeAdapter.EXCHANGES_PATH, 200,
                "{\"status\":\"exchanged\",\"destination\":\"dest_x\",\"suffix\":\"123456\","
                        + "\"payee\":\"match\"}");
        assertThat(adapter().exchange(EXCHANGE).outcome())
                .as("a six-character suffix is not the four the platform may keep")
                .isEqualTo(ExchangeAnswer.Outcome.INDETERMINATE);

        scheme.reset();
        scheme.succeedsWith(
                SimulatedInstantSchemeAdapter.EXCHANGES_PATH, 200,
                "{\"status\":\"exchanged\",\"suffix\":\"4321\",\"payee\":\"match\"}");
        assertThat(adapter().exchange(EXCHANGE).outcome())
                .isEqualTo(ExchangeAnswer.Outcome.INDETERMINATE);
    }

    // ------------------------------------------------------------------
    // The initiation (pay-by-bank)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("initiated: the authorization handle the payer follows; refused is"
            + " knowledge; a handleless initiation is INDETERMINATE")
    void initiationOutcomes() {
        scheme.succeedsWith(
                SimulatedInstantSchemeAdapter.INITIATIONS_PATH, 200,
                "{\"status\":\"initiated\",\"handle\":\"https://payer-psp.example/auth/abc\"}");
        InitiationAnswer initiated = adapter().initiate(INITIATE);
        assertThat(initiated.outcome()).isEqualTo(InitiationAnswer.Outcome.INITIATED);
        assertThat(initiated.authorizationHandle().orElseThrow().expose())
                .isEqualTo("https://payer-psp.example/auth/abc");
        assertThat(initiated.authorizationHandle().orElseThrow().toString())
                .as("a capability URL never prints itself (INV-AUD-02)")
                .doesNotContain("payer-psp.example");

        scheme.reset();
        scheme.succeedsWith(
                SimulatedInstantSchemeAdapter.INITIATIONS_PATH, 200,
                "{\"status\":\"refused\"}");
        assertThat(adapter().initiate(INITIATE).outcome())
                .isEqualTo(InitiationAnswer.Outcome.REFUSED);

        scheme.reset();
        scheme.succeedsWith(
                SimulatedInstantSchemeAdapter.INITIATIONS_PATH, 200,
                "{\"status\":\"initiated\"}");
        assertThat(adapter().initiate(INITIATE).outcome())
                .isEqualTo(InitiationAnswer.Outcome.INDETERMINATE);
    }

    @Test
    @DisplayName("the initiation inquiry speaks the same totality on its own path")
    void initiationInquiry() {
        scheme.succeedsWith(
                SimulatedInstantSchemeAdapter.INITIATION_STATUS_PATH + OUR_REF.value(), 200,
                ACCEPTED_BODY);
        assertThat(adapter().inquireInitiation(OUR_REF).verdict())
                .isEqualTo(PushInquiryAnswer.Verdict.ACCEPTED);
    }

    // ------------------------------------------------------------------
    // The reference's own shape (ISO 20022's bound)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the end-to-end reference holds ISO 20022's 35-character bound and the"
            + " path-safe charset; out-of-shape values are refused at construction")
    void endToEndReferenceShape() {
        assertThat(new EndToEndReference("A".repeat(35)).value()).hasSize(35);
        assertThatThrownBy(() -> new EndToEndReference("A".repeat(36)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EndToEndReference("has space"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EndToEndReference(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EndToEndReference("under_score"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
