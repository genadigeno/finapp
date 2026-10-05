package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.payments.CorridorRail;
import com.finapp.payments.CorridorRail.BeneficiaryExchange;
import com.finapp.payments.CorridorRail.CreditState;
import com.finapp.payments.CorridorRail.InquiryAnswer;
import com.finapp.payments.CorridorRail.RecallAnswer;
import com.finapp.payments.CorridorRail.SendAnswer;
import com.finapp.payments.EndToEndReference;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.SimulatedCorridorAdapter;
import com.finapp.sharedkernel.money.CountryCode;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The corridor provider contract battery (`P9-TSK-014`, ADR-0079, ADR-0080, ADR-0008): the
 * {@code corridor-sim-a} adapter against the honest, stateful {@link SimulatedCorridorEngine}. Every
 * case is a contract the beneficiary registration (`-017`), the outbound credit (`-019`), the recall
 * and the return (`-021`/`-023`) will rely on; the decisive one is <strong>a send deduped on our
 * E</strong> - a re-send answers the credit's current state and never creates a second credit.
 */
@DisplayName("the corridor provider contract battery - corridor-sim-a (P9-TSK-014)")
class SimulatedCorridorContractTest {

    private static final byte[] KEY = "a-corridor-provider-test-key-of-32-bytes".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CALLBACK_KEY = "a-corridor-callback-test-key-of-32-byte".getBytes(StandardCharsets.UTF_8);
    private static final CurrencyCode USD = CurrencyCode.of("USD");
    private static final SimulatedCorridorEngine.Beneficiary US_PERSON =
            new SimulatedCorridorEngine.Beneficiary("US", "USD", "individual", "match");

    private SimulatedCorridorEngine engine;
    private CorridorRail rail;

    @BeforeEach
    void start() throws Exception {
        engine = SimulatedCorridorEngine.start(CALLBACK_KEY);
        rail = new SimulatedCorridorAdapter(engine.baseUrl(), Duration.ofSeconds(2), KEY);
    }

    @AfterEach
    void stop() {
        engine.close();
    }

    @Test
    @DisplayName("the exchange answers the opaque reference and the provider-attested attributes - our"
            + " reference on the wire, the bearer key beside it - and a grant is single-use")
    void theExchangeIsAttestedAndSingleUse() {
        String grant = engine.issueGrant(US_PERSON);
        BeneficiaryExchange answer = rail.exchangeBeneficiary(new CorridorRail.BeneficiaryGrant(e("BX-1"), grant));
        assertThat(answer).isInstanceOfSatisfying(BeneficiaryExchange.Exchanged.class, exchanged -> {
            assertThat(exchanged.payeeCheck()).isEqualTo(CorridorRail.PayeeCheck.MATCH);
            assertThat(exchanged.country()).isEqualTo(CountryCode.of("US"));
            assertThat(exchanged.currency()).isEqualTo(USD);
            assertThat(exchanged.entityType()).isEqualTo(CorridorRail.EntityType.INDIVIDUAL);
            assertThat(exchanged.suffix()).hasSize(4);
            assertThat(exchanged.toString()).doesNotContain(exchanged.destination().value());
        });
        assertThat(engine.idempotencyKeys()).containsExactly("BX-1");
        assertThat(engine.authorizations()).containsExactly("Bearer " + Base64.getEncoder().encodeToString(KEY));
        // A retry of the same exchange answers the same result; a NEW exchange of the used grant is refused.
        assertThat(rail.exchangeBeneficiary(new CorridorRail.BeneficiaryGrant(e("BX-1"), grant))).isEqualTo(answer);
        assertThat(rail.exchangeBeneficiary(new CorridorRail.BeneficiaryGrant(e("BX-2"), grant)))
                .isInstanceOfSatisfying(BeneficiaryExchange.Refused.class,
                        refused -> assertThat(refused.reason()).isEqualTo(CorridorRail.ExchangeRefusal.GRANT_USED));
        assertThat(rail.exchangeBeneficiary(new CorridorRail.BeneficiaryGrant(e("BX-3"), "grant-never-issued")))
                .isInstanceOfSatisfying(BeneficiaryExchange.Refused.class,
                        refused -> assertThat(refused.reason()).isEqualTo(CorridorRail.ExchangeRefusal.GRANT_INVALID));
    }

    @Test
    @DisplayName("the payee check is total and safe: match is MATCH, unavailable UNAVAILABLE, and a close"
            + " match or any unmapped word is NO_MATCH - never MATCH")
    void thePayeeCheckIsTotal() {
        record Case(String word, CorridorRail.PayeeCheck expected) {}
        int n = 0;
        for (Case c : List.of(
                new Case("match", CorridorRail.PayeeCheck.MATCH),
                new Case("unavailable", CorridorRail.PayeeCheck.UNAVAILABLE),
                new Case("close_match", CorridorRail.PayeeCheck.NO_MATCH),
                new Case("no_match", CorridorRail.PayeeCheck.NO_MATCH),
                new Case("MATCH", CorridorRail.PayeeCheck.NO_MATCH),
                new Case("probably", CorridorRail.PayeeCheck.NO_MATCH))) {
            String grant = engine.issueGrant(new SimulatedCorridorEngine.Beneficiary("US", "USD", "business", c.word()));
            assertThat(rail.exchangeBeneficiary(new CorridorRail.BeneficiaryGrant(e("BP-" + n++), grant)))
                    .as("payee word %s", c.word())
                    .isInstanceOfSatisfying(BeneficiaryExchange.Exchanged.class,
                            exchanged -> assertThat(exchanged.payeeCheck()).isEqualTo(c.expected()));
        }
    }

    @Test
    @DisplayName("a send deduped on E: received, re-sent, accepted, re-sent again - one credit, each answer"
            + " the credit's current state, E as the idempotency key every time")
    void aSendIsDedupedOnE() {
        ProviderReference destination = beneficiary();
        CorridorRail.CreditInstruction credit =
                new CorridorRail.CreditInstruction(e("XB-1"), destination, Money.of(new BigDecimal("1079.60"), USD));
        assertThat(rail.send(credit)).isInstanceOf(SendAnswer.Received.class);
        assertThat(rail.send(credit)).as("the re-send of a received credit").isInstanceOf(SendAnswer.Received.class);
        engine.accept("XB-1");
        SendAnswer accepted = rail.send(credit);
        assertThat(accepted).isInstanceOfSatisfying(SendAnswer.Accepted.class,
                answer -> assertThat(answer.providerReference().value()).startsWith("XP-"));
        assertThat(rail.send(credit)).isEqualTo(accepted);
        assertThat(engine.creditsOf("XB-1")).as("exactly one credit, whatever was re-sent").isEqualTo(1);
        assertThat(engine.credits()).isEqualTo(1);
        assertThat(engine.idempotencyKeys()).filteredOn("XB-1"::equals).hasSize(4);
    }

    @Test
    @DisplayName("a lost response after the provider acted is Indeterminate; the re-send of the same E"
            + " answers the credit, and no second credit exists")
    void aLostResponseIsRecoveredByTheSameE() {
        ProviderReference destination = beneficiary();
        engine.acceptOnReceipt(true);
        engine.loseNextResponse();
        CorridorRail.CreditInstruction credit =
                new CorridorRail.CreditInstruction(e("XB-2"), destination, Money.of(new BigDecimal("10.00"), USD));
        assertThat(rail.send(credit)).isInstanceOf(SendAnswer.Indeterminate.class);
        assertThat(rail.send(credit)).isInstanceOf(SendAnswer.Accepted.class);
        assertThat(engine.creditsOf("XB-2")).isEqualTo(1);
    }

    @Test
    @DisplayName("a definitive rejection carries its reason: a currency the beneficiary does not receive,"
            + " and an armed limit")
    void rejectionsAreDefinitive() {
        ProviderReference destination = beneficiary();
        assertThat(rail.send(new CorridorRail.CreditInstruction(
                        e("XB-3"), destination, Money.of(new BigDecimal("10"), CurrencyCode.of("JPY")))))
                .isInstanceOfSatisfying(SendAnswer.Rejected.class,
                        rejected -> assertThat(rejected.reason()).isEqualTo(CorridorRail.SendRejection.CURRENCY_NOT_CARRIED));
        engine.rejectNextSend("limit");
        assertThat(rail.send(new CorridorRail.CreditInstruction(e("XB-4"), destination, Money.of(new BigDecimal("10.00"), USD))))
                .isInstanceOfSatisfying(SendAnswer.Rejected.class,
                        rejected -> assertThat(rejected.reason()).isEqualTo(CorridorRail.SendRejection.LIMIT));
        assertThat(engine.credits()).isZero();
    }

    @Test
    @DisplayName("the inquiry is authoritative and carries each fact at once - accepted, delivered, returned"
            + " with the provider's return reference and the exact amount - and says unrecognised in so many words")
    void theInquiryCarriesTheFacts() {
        assertThat(rail.inquire(e("XB-never"))).isInstanceOf(InquiryAnswer.Unrecognised.class);
        ProviderReference destination = beneficiary();
        Money amount = Money.of(new BigDecimal("1079.60"), USD);
        rail.send(new CorridorRail.CreditInstruction(e("XB-5"), destination, amount));
        assertThat(rail.inquire(e("XB-5"))).isInstanceOfSatisfying(InquiryAnswer.Found.class,
                found -> assertThat(found.state()).isEqualTo(CreditState.RECEIVED));
        engine.accept("XB-5");
        engine.advance(Duration.ofHours(6));
        engine.deliver("XB-5");
        engine.advance(Duration.ofDays(3));
        engine.returnCredit("XB-5");
        assertThat(rail.inquire(e("XB-5"))).isInstanceOfSatisfying(InquiryAnswer.Found.class, found -> {
            assertThat(found.state()).isEqualTo(CreditState.ACCEPTED);
            assertThat(found.providerReference()).isPresent();
            assertThat(found.acceptedAt()).isPresent();
            assertThat(found.deliveredAt()).isPresent();
            assertThat(found.returned()).hasValueSatisfying(returned -> {
                assertThat(returned.amount()).isEqualTo(amount);
                assertThat(returned.returnReference().value()).startsWith("XR-");
                assertThat(returned.returnedAt()).isAfter(found.deliveredAt().orElseThrow());
            });
        });
    }

    @Test
    @DisplayName("a recall concludes only on RECALLED (while received); once accepted it is TooLate; an unknown"
            + " E is Unrecognised - and a repeated recall answers the same")
    void aRecallIsAskedNeverAssumed() {
        ProviderReference destination = beneficiary();
        rail.send(new CorridorRail.CreditInstruction(e("XB-6"), destination, Money.of(new BigDecimal("5.00"), USD)));
        assertThat(rail.recall(e("XB-6"))).isInstanceOf(RecallAnswer.Recalled.class);
        assertThat(rail.recall(e("XB-6"))).as("idempotent on E").isInstanceOf(RecallAnswer.Recalled.class);
        assertThat(rail.inquire(e("XB-6"))).isInstanceOfSatisfying(InquiryAnswer.Found.class,
                found -> assertThat(found.state()).isEqualTo(CreditState.RECALLED));
        engine.acceptOnReceipt(true);
        rail.send(new CorridorRail.CreditInstruction(e("XB-7"), destination, Money.of(new BigDecimal("5.00"), USD)));
        assertThat(rail.recall(e("XB-7"))).isInstanceOf(RecallAnswer.TooLate.class);
        assertThat(rail.recall(e("XB-never"))).isInstanceOf(RecallAnswer.Unrecognised.class);
    }

    @Test
    @DisplayName("every unreadable answer is Indeterminate - an unknown status, a malformed body, a 5xx, an"
            + " over-precise return - and a refused connection is NothingSent, the one piece of knowledge")
    void totality() {
        ProviderReference destination = beneficiary();
        CorridorRail.CreditInstruction credit =
                new CorridorRail.CreditInstruction(e("XB-8"), destination, Money.of(new BigDecimal("5.00"), USD));
        engine.answerUnknownStatusNext("settled_maybe");
        assertThat(rail.send(credit)).isInstanceOfSatisfying(SendAnswer.Indeterminate.class,
                answer -> assertThat(answer.cause()).isEqualTo(CorridorRail.Indeterminacy.UNKNOWN_STATE));
        engine.malformedNext();
        assertThat(rail.inquire(e("XB-8"))).isInstanceOfSatisfying(InquiryAnswer.Indeterminate.class,
                answer -> assertThat(answer.cause()).isEqualTo(CorridorRail.Indeterminacy.UNKNOWN_STATE));
        engine.serverErrorNext();
        assertThat(rail.recall(e("XB-8"))).isInstanceOfSatisfying(RecallAnswer.Indeterminate.class,
                answer -> assertThat(answer.cause()).isEqualTo(CorridorRail.Indeterminacy.SERVER_ERROR));
        engine.acceptOnReceipt(true);
        rail.send(credit);
        engine.returnCredit("XB-8");
        engine.overPreciseNextReturn();
        assertThat(rail.inquire(e("XB-8"))).isInstanceOfSatisfying(InquiryAnswer.Indeterminate.class,
                answer -> assertThat(answer.cause()).isEqualTo(CorridorRail.Indeterminacy.OVER_PRECISE));
        CorridorRail nobody = new SimulatedCorridorAdapter(URI.create("http://127.0.0.1:1"), Duration.ofMillis(500), KEY);
        assertThat(nobody.send(credit)).isInstanceOf(SendAnswer.NothingSent.class);
        assertThat(nobody.inquire(e("XB-8"))).isInstanceOf(InquiryAnswer.NothingSent.class);
        assertThat(nobody.recall(e("XB-8"))).isInstanceOf(RecallAnswer.NothingSent.class);
        assertThat(nobody.toString()).doesNotContain(new String(KEY, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("every state change emits a callback signed over its own bytes - a hint naming our E, never"
            + " the outcome; a tampered body fails the signature")
    void callbacksAreSignedHints() {
        ProviderReference destination = beneficiary();
        rail.send(new CorridorRail.CreditInstruction(e("XB-9"), destination, Money.of(new BigDecimal("5.00"), USD)));
        engine.accept("XB-9");
        engine.deliver("XB-9");
        List<SimulatedCorridorEngine.SignedCallback> callbacks = engine.callbacks();
        assertThat(callbacks).hasSize(3).allSatisfy(callback -> {
            assertThat(callback.body()).contains("\"endToEndRef\":\"XB-9\"");
            assertThat(SimulatedCorridorEngine.verifies(callback, CALLBACK_KEY)).isTrue();
            assertThat(SimulatedCorridorEngine.verifies(callback, KEY)).as("not the API key").isFalse();
        });
        SimulatedCorridorEngine.SignedCallback tampered = new SimulatedCorridorEngine.SignedCallback(
                callbacks.get(0).body().replace("received", "accepted"), callbacks.get(0).timestamp(),
                callbacks.get(0).signature());
        assertThat(SimulatedCorridorEngine.verifies(tampered, CALLBACK_KEY)).isFalse();
    }

    // -----------------------------------------------------------------

    private ProviderReference beneficiary() {
        String grant = engine.issueGrant(US_PERSON);
        return ((BeneficiaryExchange.Exchanged) rail.exchangeBeneficiary(
                        new CorridorRail.BeneficiaryGrant(e("BX-" + grant.hashCode() % 1000 + "x"), grant)))
                .destination();
    }

    private static EndToEndReference e(String value) {
        return new EndToEndReference(value);
    }
}
