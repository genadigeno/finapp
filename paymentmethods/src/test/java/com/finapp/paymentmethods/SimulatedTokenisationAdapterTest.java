package com.finapp.paymentmethods;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.paymentmethods.TokenisationProvider.Exchange;
import com.finapp.paymentmethods.TokenisationProvider.Outcome;
import com.finapp.platform.testing.provider.SimulatedProvider;
import com.finapp.sharedkernel.security.Sensitive;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The tokenisation adapter's contract over the outbound harness modes (`P5-TSK-005`): the
 * mapping is <strong>total</strong> and its default branch is {@code UNAVAILABLE}, never
 * tokenised — every provider misbehaviour is a clean failed attach, and an answer whose token
 * or fields cannot be stored is not an instrument ({@code INV-PAY-02}'s own sentence).
 */
@DisplayName("SimulatedTokenisationAdapter (P5-TSK-005)")
class SimulatedTokenisationAdapterTest {

    private static final TokenisationGrant GRANT = new TokenisationGrant(Sensitive.of("ctok_visa-4242"));

    private static final String TOKENISED_BODY =
            "{\"status\":\"tokenised\",\"token\":\"tok_visa-4242\",\"brand\":\"Visa\","
                    + "\"last4\":\"4242\",\"expiryMonth\":12,\"expiryYear\":2030}";

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

    private SimulatedTokenisationAdapter adapter() {
        return new SimulatedTokenisationAdapter(
                URI.create(provider.baseUrl()), Duration.ofSeconds(2));
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("a tokenised answer carries the instrument, parsed whole")
    void aTokenisedAnswerCarriesTheInstrument() {
        provider.succeedsWith(
                SimulatedTokenisationAdapter.TOKENISATIONS_PATH, 200, TOKENISED_BODY);

        Exchange exchange = adapter().exchange(GRANT);

        assertThat(exchange.outcome()).isEqualTo(Outcome.TOKENISED);
        TokenisationProvider.TokenisedInstrument instrument = exchange.instrument().orElseThrow();
        assertThat(instrument.token().expose()).isEqualTo("tok_visa-4242");
        assertThat(instrument.brand()).isEqualTo("Visa");
        assertThat(instrument.displaySuffix()).isEqualTo("4242");
        assertThat(instrument.expiryMonth()).isEqualTo(12);
        assertThat(instrument.expiryYear()).isEqualTo(2030);
        // And the exchange was one request - the grant travelled once.
        assertThat(provider.requestCount(SimulatedTokenisationAdapter.TOKENISATIONS_PATH))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a refusal is REFUSED - the one answer that is knowledge rather than absence")
    void aRefusalIsRefused() {
        provider.succeedsWith(
                SimulatedTokenisationAdapter.TOKENISATIONS_PATH, 200, "{\"status\":\"refused\"}");

        Exchange exchange = adapter().exchange(GRANT);

        assertThat(exchange.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(exchange.instrument()).isEmpty();
    }

    @Test
    @DisplayName("an unmapped status is UNAVAILABLE - the total mapping's default, never success")
    void anUnmappedStatusIsUnavailable() {
        // The sharp shape: a plausible-looking success word the mapping does not know, WITH a
        // usable instrument beside it - a default branch that trusted the fields would store it.
        provider.succeedsWith(
                SimulatedTokenisationAdapter.TOKENISATIONS_PATH,
                200,
                "{\"status\":\"tokenized\",\"token\":\"tok_visa-4242\",\"brand\":\"Visa\","
                        + "\"last4\":\"4242\",\"expiryMonth\":12,\"expiryYear\":2030}");

        assertThat(adapter().exchange(GRANT).outcome()).isEqualTo(Outcome.UNAVAILABLE);
    }

    @Test
    @DisplayName("a tokenised answer missing any field is UNAVAILABLE - never a partial instrument")
    void aMissingFieldIsUnavailable() {
        provider.succeedsWith(
                SimulatedTokenisationAdapter.TOKENISATIONS_PATH,
                200,
                "{\"status\":\"tokenised\",\"token\":\"tok_visa-4242\",\"brand\":\"Visa\","
                        + "\"last4\":\"4242\",\"expiryMonth\":12}");

        assertThat(adapter().exchange(GRANT).outcome()).isEqualTo(Outcome.UNAVAILABLE);
    }

    @Test
    @DisplayName("a token TokenReference refuses - a PAN-shaped one - is UNAVAILABLE, never stored")
    void anUnstorableTokenIsUnavailable() {
        // A provider answering with something card-number-shaped in the token field is exactly
        // the answer INV-PAY-02 exists to keep out of the schema; the adapter refuses it here,
        // before anything could try to store it.
        provider.succeedsWith(
                SimulatedTokenisationAdapter.TOKENISATIONS_PATH,
                200,
                "{\"status\":\"tokenised\",\"token\":\"4111-1111-1111-1111\",\"brand\":\"Visa\","
                        + "\"last4\":\"1111\",\"expiryMonth\":12,\"expiryYear\":2030}");

        assertThat(adapter().exchange(GRANT).outcome()).isEqualTo(Outcome.UNAVAILABLE);
    }

    @Test
    @DisplayName("a 5xx, garbage, a malformed body and an empty answer are each UNAVAILABLE")
    void providerMisbehaviourIsUnavailable() {
        provider.isUnavailable(SimulatedTokenisationAdapter.TOKENISATIONS_PATH);
        assertThat(adapter().exchange(GRANT).outcome()).isEqualTo(Outcome.UNAVAILABLE);

        provider.reset();
        provider.respondsWithGarbage(SimulatedTokenisationAdapter.TOKENISATIONS_PATH);
        assertThat(adapter().exchange(GRANT).outcome()).isEqualTo(Outcome.UNAVAILABLE);

        provider.reset();
        provider.respondsWithMalformedBody(SimulatedTokenisationAdapter.TOKENISATIONS_PATH);
        assertThat(adapter().exchange(GRANT).outcome()).isEqualTo(Outcome.UNAVAILABLE);

        provider.reset();
        provider.succeedsWith(SimulatedTokenisationAdapter.TOKENISATIONS_PATH, 200, "");
        assertThat(adapter().exchange(GRANT).outcome()).isEqualTo(Outcome.UNAVAILABLE);
    }

    @Test
    @DisplayName("a timeout is UNAVAILABLE, and the request provably reached the provider")
    void aTimeoutIsUnavailable() {
        provider.neverResponds(SimulatedTokenisationAdapter.TOKENISATIONS_PATH);

        SimulatedTokenisationAdapter impatient =
                new SimulatedTokenisationAdapter(
                        URI.create(provider.baseUrl()), Duration.ofMillis(200));
        assertThat(impatient.exchange(GRANT).outcome()).isEqualTo(Outcome.UNAVAILABLE);
        // The requestCount oracle: the provider RECEIVED the exchange - which is why a fresh
        // grant, not a retry loop, is the recovery (the grant is one-time on the provider side).
        assertThat(provider.requestCount(SimulatedTokenisationAdapter.TOKENISATIONS_PATH))
                .isEqualTo(1);
    }
}
