package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.merchant.PayoutDestinationGrant;
import com.finapp.merchant.PayoutDestinationTokenisation.Exchange;
import com.finapp.merchant.PayoutDestinationTokenisation.Outcome;
import com.finapp.merchant.SimulatedPayoutDestinationTokenisation;
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
 * The simulated destination exchange's total mapping (`P6-TSK-011`, ADR-0056 §5): exactly one
 * shape is {@code TOKENISED}, one is {@code REFUSED}, and everything else — unmapped statuses,
 * missing fields, an unstorable reference, provider misbehaviour, a timeout — is
 * {@code UNAVAILABLE}, never success and never a fallback to anything rawer.
 *
 * <p>Lives in {@code app} for the provider harness, which is {@code platform}'s test fixture.
 */
@DisplayName("the simulated payout destination exchange (P6-TSK-011)")
class SimulatedPayoutDestinationTokenisationTest {

    private static final String PATH = SimulatedPayoutDestinationTokenisation.TOKENISATIONS_PATH;
    private static final PayoutDestinationGrant GRANT =
            new PayoutDestinationGrant(Sensitive.of("pdg_4f9ZkQ2mX"));

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

    private static SimulatedPayoutDestinationTokenisation exchange() {
        return new SimulatedPayoutDestinationTokenisation(
                URI.create(provider.baseUrl()), Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("a tokenised answer carries the reference and the provider's suffix")
    void aTokenisedAnswerCarriesTheDestination() {
        provider.succeedsWith(
                PATH, 200, "{\"status\":\"tokenised\",\"reference\":\"pdr_7K-x9\",\"last4\":\"3000\"}");
        Exchange answer = exchange().exchange(GRANT);
        assertThat(answer.outcome()).isEqualTo(Outcome.TOKENISED);
        assertThat(answer.destination().orElseThrow().reference().expose()).isEqualTo("pdr_7K-x9");
        assertThat(answer.destination().orElseThrow().displaySuffix()).isEqualTo("3000");
        assertThat(provider.requestCount(PATH)).isEqualTo(1);
    }

    @Test
    @DisplayName("an explicit refusal is REFUSED")
    void aRefusalIsRefused() {
        provider.succeedsWith(PATH, 200, "{\"status\":\"refused\"}");
        assertThat(exchange().exchange(GRANT).outcome()).isEqualTo(Outcome.REFUSED);
    }

    @Test
    @DisplayName("an unmapped status, a missing field or an unstorable answer is UNAVAILABLE")
    void anythingElseIsUnavailable() {
        String[] answers = {
            "{\"status\":\"pending\"}",
            "{\"status\":\"tokenised\",\"last4\":\"3000\"}",
            "{\"status\":\"tokenised\",\"reference\":\"pdr_ok\"}",
            // A reference shaped like the account it stands for is no reference.
            "{\"status\":\"tokenised\",\"reference\":\"DE89370400440532013000\",\"last4\":\"3000\"}",
            // A suffix longer than four characters would display more than a suffix.
            "{\"status\":\"tokenised\",\"reference\":\"pdr_ok\",\"last4\":\"123000\"}"
        };
        for (String answer : answers) {
            provider.succeedsWith(PATH, 200, answer);
            assertThat(exchange().exchange(GRANT).outcome())
                    .as("%s must be UNAVAILABLE", answer)
                    .isEqualTo(Outcome.UNAVAILABLE);
        }
    }

    @Test
    @DisplayName("provider misbehaviour is UNAVAILABLE: a 500, garbage, a refused connection")
    void providerMisbehaviourIsUnavailable() {
        provider.failsWith(PATH, 500);
        assertThat(exchange().exchange(GRANT).outcome()).isEqualTo(Outcome.UNAVAILABLE);
        provider.respondsWithGarbage(PATH);
        assertThat(exchange().exchange(GRANT).outcome()).isEqualTo(Outcome.UNAVAILABLE);
        SimulatedPayoutDestinationTokenisation nowhere =
                new SimulatedPayoutDestinationTokenisation(
                        URI.create("http://127.0.0.1:1"), Duration.ofSeconds(2));
        assertThat(nowhere.exchange(GRANT).outcome()).isEqualTo(Outcome.UNAVAILABLE);
    }

    @Test
    @DisplayName("a timeout is UNAVAILABLE")
    void aTimeoutIsUnavailable() {
        // The outcome only: whether the request reached the provider before the client gave up
        // is a race with the connection itself - the assertion that made the tokenisation
        // adapter's twin of this test flaky, deliberately not repeated.
        provider.neverResponds(PATH);
        SimulatedPayoutDestinationTokenisation impatient =
                new SimulatedPayoutDestinationTokenisation(
                        URI.create(provider.baseUrl()), Duration.ofMillis(300));
        assertThat(impatient.exchange(GRANT).outcome()).isEqualTo(Outcome.UNAVAILABLE);
    }
}
