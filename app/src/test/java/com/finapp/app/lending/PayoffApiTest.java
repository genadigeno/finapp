package com.finapp.app.lending;

import static com.finapp.app.lending.LoanApplicationClient.key;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.app.database.DateShifted;
import com.finapp.sharedkernel.id.IdGenerator;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The payoff doors' contract (`P11-TSK-026`; PHASE_11_PLAN.md sections 9 and 11, A19, ADR-0095 point 8): the session and
 * the key judged before the domain is reached; the quote's conditional step-up and the execution's {@code MULTI_FACTOR} rule
 * for every customer; closed bodies; the uniform {@code 404} for an unknown, malformed or another party's account and for a
 * malformed quote. The payoffs themselves - which need terms, a loan accepted and disbursed - are
 * {@link LoanAccountDatabaseTest}'s, in a database of its own, and {@code lending}'s {@code PayoffDatabaseTest}'s.
 */
@DateShifted
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the payoff doors' contract (P11-TSK-026)")
class PayoffApiTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String LOANS = "/v1/me/loans";

    @LocalServerPort private int port;

    @Test
    @DisplayName("no session is 401; a quote or a payoff without a key is refused before the domain")
    void theSessionAndTheKey() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String quotes = LOANS + "/" + IDS.next() + "/payoff-quotes";
        String payoff = LOANS + "/" + IDS.next() + "/payoff";
        assertThat(client.post(quotes, "{}", null, key()).statusCode()).isEqualTo(401);
        assertThat(client.post(payoff, quoteBody(UUID.randomUUID()), null, key()).statusCode()).isEqualTo(401);
        CreditFixtures.Customer customer = new CreditFixtures(port).consentingCustomer();
        for (HttpResponse<String> unkeyed : List.of(client.post(quotes, "{}", customer.token(), null),
                client.post(payoff, quoteBody(UUID.randomUUID()), customer.token(), null))) {
            assertThat(unkeyed.statusCode()).as(unkeyed.body()).isEqualTo(422);
            assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");
        }
    }

    @Test
    @DisplayName("the quote follows the platform's conditional step-up - an enrolled customer on a password-only session is"
            + " 403, an unenrolled one reaches the domain; the payoff requires MULTI_FACTOR for every customer (A19)")
    void theAssurance() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        CreditFixtures fixtures = new CreditFixtures(port);
        CreditFixtures.Customer enrolled = fixtures.consentingCustomer();
        String enrolledPassword = fixtures.login(enrolled);
        HttpResponse<String> quote = client.post(LOANS + "/" + IDS.next() + "/payoff-quotes", "{}", enrolledPassword,
                key());
        assertThat(quote.statusCode()).as(quote.body()).isEqualTo(403);
        assertThat(quote.body()).contains("identity.AssuranceRequired");
        HttpResponse<String> payoff = client.post(LOANS + "/" + IDS.next() + "/payoff", quoteBody(UUID.randomUUID()),
                enrolledPassword, key());
        assertThat(payoff.statusCode()).as(payoff.body()).isEqualTo(403);
        assertThat(payoff.body()).contains("identity.AssuranceRequired");

        CreditFixtures.Customer unenrolled = fixtures.passwordOnlyCustomer(true);
        HttpResponse<String> reached = client.post(LOANS + "/" + IDS.next() + "/payoff-quotes", "{}",
                unenrolled.token(), key());
        assertThat(reached.statusCode()).as("the domain answered: " + reached.body()).isEqualTo(404);
        HttpResponse<String> never = client.post(LOANS + "/" + IDS.next() + "/payoff", quoteBody(UUID.randomUUID()),
                unenrolled.token(), key());
        assertThat(never.statusCode()).as(never.body()).isEqualTo(403);
        assertThat(never.body()).contains("identity.AssuranceRequired");
    }

    @Test
    @DisplayName("an unknown or malformed account, or a malformed quote, is 404 lending.NotFound - the answer another"
            + " party's loan gets")
    void theUniformNotFound() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        CreditFixtures.Customer customer = new CreditFixtures(port).consentingCustomer();
        for (String id : List.of(IDS.next().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            HttpResponse<String> quote = client.post(LOANS + "/" + id + "/payoff-quotes", "{}", customer.token(), key());
            assertThat(quote.statusCode()).as(id + ": " + quote.body()).isEqualTo(404);
            assertThat(quote.body()).contains("lending.NotFound");
            HttpResponse<String> payoff = client.post(LOANS + "/" + id + "/payoff", quoteBody(UUID.randomUUID()),
                    customer.token(), key());
            assertThat(payoff.statusCode()).as(id + ": " + payoff.body()).isEqualTo(404);
            assertThat(payoff.body()).contains("lending.NotFound");
        }
        HttpResponse<String> malformedQuote = client.post(LOANS + "/" + IDS.next() + "/payoff",
                "{\"quoteId\":\"not-a-uuid\"}", customer.token(), key());
        assertThat(malformedQuote.statusCode()).as(malformedQuote.body()).isEqualTo(404);
    }

    @Test
    @DisplayName("the bodies are closed and judged: an unknown field, a malformed good-through date or no quote is 422")
    void theBodies() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        CreditFixtures.Customer customer = new CreditFixtures(port).consentingCustomer();
        String quotes = LOANS + "/" + IDS.next() + "/payoff-quotes";
        for (String body : List.of("{\"goodThrough\":\"2027-01-01\",\"amount\":\"1.00\"}", "{\"goodThrough\":\"01/02/2027\"}")) {
            HttpResponse<String> refused = client.post(quotes, body, customer.token(), key());
            assertThat(refused.statusCode()).as(body + ": " + refused.body()).isEqualTo(422);
        }
        HttpResponse<String> noQuote = client.post(LOANS + "/" + IDS.next() + "/payoff", "{}", customer.token(), key());
        assertThat(noQuote.statusCode()).as(noQuote.body()).isEqualTo(422);
    }

    private static String quoteBody(UUID quote) {
        return "{\"quoteId\":\"" + quote + "\"}";
    }
}
