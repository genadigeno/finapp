package com.finapp.app.lending;

import static com.finapp.app.lending.LoanApplicationClient.key;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
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
 * The repayment doors' contract (`P11-TSK-018`; PHASE_11_PLAN.md sections 9 and 11, A19, ADR-0095 point 3): the session,
 * the key and the closed body judged before the domain is reached; the platform's conditional step-up - an enrolled
 * customer on a password-only session refused, an unenrolled one admitted to the domain; an amount that is not an exact
 * positive decimal in a supported currency refused; the uniform {@code 404} for an unknown, malformed or another party's
 * loan, on both doors. The repayments themselves - which need a loan disbursed and billed - are
 * {@link LoanAccountDatabaseTest}'s, in a database of its own, and {@code lending}'s {@code RepaymentDatabaseTest}'s.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the repayment doors' contract (P11-TSK-018)")
class RepaymentApiTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String LOANS = "/v1/me/loans";
    private static final String BODY = "{\"amount\":\"100.00\",\"currency\":\"EUR\"}";

    @LocalServerPort private int port;

    @Test
    @DisplayName("no session is 401 on both doors; a repayment without a key, with a field the body does not declare - a"
            + " source wallet - or with a blank body is refused before the domain")
    void theSessionTheKeyAndTheBody() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String path = LOANS + "/" + IDS.next() + "/repayments";
        assertThat(client.post(path, BODY, null, key()).statusCode()).isEqualTo(401);
        assertThat(client.get(path, null).statusCode()).isEqualTo(401);
        CreditFixtures.Customer customer = new CreditFixtures(port).consentingCustomer();
        HttpResponse<String> unkeyed = client.post(path, BODY, customer.token(), null);
        assertThat(unkeyed.statusCode()).as(unkeyed.body()).isEqualTo(422);
        assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");
        HttpResponse<String> elsewhere = client.post(path,
                "{\"amount\":\"100.00\",\"currency\":\"EUR\",\"walletId\":\"" + UUID.randomUUID() + "\"}",
                customer.token(), key());
        assertThat(elsewhere.statusCode()).as("the source is the borrower's own wallet - no source field").isEqualTo(422);
        HttpResponse<String> blank = client.post(path, "{}", customer.token(), key());
        assertThat(blank.statusCode()).as(blank.body()).isEqualTo(422);
    }

    @Test
    @DisplayName("the platform's conditional step-up (A19): an enrolled customer on a password-only session is 403"
            + " identity.AssuranceRequired; a customer with no second factor reaches the domain (404 for an unknown loan);"
            + " an enrolled customer's MULTI_FACTOR session reaches it too")
    void theConditionalStepUp() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        CreditFixtures fixtures = new CreditFixtures(port);
        CreditFixtures.Customer enrolled = fixtures.consentingCustomer();
        String path = LOANS + "/" + IDS.next() + "/repayments";
        HttpResponse<String> passwordOnly = client.post(path, BODY, fixtures.login(enrolled), key());
        assertThat(passwordOnly.statusCode()).as(passwordOnly.body()).isEqualTo(403);
        assertThat(passwordOnly.body()).contains("identity.AssuranceRequired");
        HttpResponse<String> stepped = client.post(path, BODY, enrolled.token(), key());
        assertThat(stepped.statusCode()).as(stepped.body()).isEqualTo(404);
        CreditFixtures.Customer unenrolled = fixtures.passwordOnlyCustomer(true);
        HttpResponse<String> noFactor = client.post(path, BODY, unenrolled.token(), key());
        assertThat(noFactor.statusCode()).as("no factor to step up with: the domain answers").isEqualTo(404);
        assertThat(noFactor.body()).contains("lending.NotFound");
    }

    @Test
    @DisplayName("an amount that is not an exact positive decimal at the currency's minor units, or a currency the"
            + " platform does not support, is 422 api.ValidationFailed")
    void theAmountIsExact() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        CreditFixtures.Customer customer = new CreditFixtures(port).consentingCustomer();
        String path = LOANS + "/" + IDS.next() + "/repayments";
        for (String body : List.of("{\"amount\":\"100.001\",\"currency\":\"EUR\"}",
                "{\"amount\":\"1e3\",\"currency\":\"EUR\"}", "{\"amount\":\"ten\",\"currency\":\"EUR\"}",
                "{\"amount\":\"100.00\",\"currency\":\"ZZZ\"}")) {
            HttpResponse<String> refused = client.post(path, body, customer.token(), key());
            assertThat(refused.statusCode()).as(body + ": " + refused.body()).isEqualTo(422);
            assertThat(refused.body()).contains("api.ValidationFailed");
        }
    }

    @Test
    @DisplayName("an unknown or malformed loan is 404 lending.NotFound on both doors - the answer another party's loan"
            + " gets")
    void theUniformNotFound() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        CreditFixtures.Customer customer = new CreditFixtures(port).consentingCustomer();
        for (String id : List.of(IDS.next().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            HttpResponse<String> repay = client.post(LOANS + "/" + id + "/repayments", BODY, customer.token(), key());
            assertThat(repay.statusCode()).as(id + ": " + repay.body()).isEqualTo(404);
            assertThat(repay.body()).contains("lending.NotFound");
            HttpResponse<String> read = client.get(LOANS + "/" + id + "/repayments", customer.token());
            assertThat(read.statusCode()).as(id + ": " + read.body()).isEqualTo(404);
            assertThat(read.body()).contains("lending.NotFound");
        }
    }
}
