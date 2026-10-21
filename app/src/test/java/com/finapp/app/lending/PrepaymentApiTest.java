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
 * The prepayment door's contract (`P11-TSK-028`; PHASE_11_PLAN.md sections 9 and 11, A19, ADR-0099 point 6): the session
 * and the key judged before the domain is reached; the {@code MULTI_FACTOR} rule for every customer, enrolled or not; a
 * closed body; the uniform {@code 404} for an unknown, malformed or another party's account. The prepayments themselves -
 * which need terms, a loan accepted and disbursed - are {@link LoanAccountDatabaseTest}'s, in a database of its own, and
 * {@code lending}'s {@code PrepaymentDatabaseTest}'s.
 */
@DateShifted
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the prepayment door's contract (P11-TSK-028)")
class PrepaymentApiTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String LOANS = "/v1/me/loans";
    private static final String BODY = "{\"amount\":\"100.00\",\"currency\":\"EUR\"}";

    @LocalServerPort private int port;

    @Test
    @DisplayName("no session is 401; a prepayment without a key is refused before the domain; a session below"
            + " MULTI_FACTOR is 403 identity.AssuranceRequired for every customer, enrolled or not (A19)")
    void theSessionTheKeyAndTheAssurance() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String prepayments = LOANS + "/" + IDS.next() + "/prepayments";
        assertThat(client.post(prepayments, BODY, null, key()).statusCode()).isEqualTo(401);
        CreditFixtures fixtures = new CreditFixtures(port);
        CreditFixtures.Customer customer = fixtures.consentingCustomer();
        HttpResponse<String> unkeyed = client.post(prepayments, BODY, customer.token(), null);
        assertThat(unkeyed.statusCode()).as(unkeyed.body()).isEqualTo(422);
        assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");

        HttpResponse<String> enrolledPassword = client.post(prepayments, BODY, fixtures.login(customer), key());
        assertThat(enrolledPassword.statusCode()).as(enrolledPassword.body()).isEqualTo(403);
        assertThat(enrolledPassword.body()).contains("identity.AssuranceRequired");
        CreditFixtures.Customer unenrolled = fixtures.passwordOnlyCustomer(true);
        HttpResponse<String> never = client.post(prepayments, BODY, unenrolled.token(), key());
        assertThat(never.statusCode()).as("unlike a repayment, never the conditional step-up: " + never.body())
                .isEqualTo(403);
        assertThat(never.body()).contains("identity.AssuranceRequired");
    }

    @Test
    @DisplayName("an unknown or malformed account is 404 lending.NotFound - the answer another party's loan gets")
    void theUniformNotFound() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        CreditFixtures.Customer customer = new CreditFixtures(port).consentingCustomer();
        for (String id : List.of(IDS.next().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            HttpResponse<String> prepaid = client.post(LOANS + "/" + id + "/prepayments", BODY, customer.token(), key());
            assertThat(prepaid.statusCode()).as(id + ": " + prepaid.body()).isEqualTo(404);
            assertThat(prepaid.body()).contains("lending.NotFound");
        }
    }

    @Test
    @DisplayName("the body is closed and judged: an unknown field, a missing currency, an exponent or an unknown currency"
            + " is 422 - the source is the borrower's own wallet, never the body's to name")
    void theBody() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        CreditFixtures.Customer customer = new CreditFixtures(port).consentingCustomer();
        String prepayments = LOANS + "/" + IDS.next() + "/prepayments";
        for (String body : List.of("{\"amount\":\"100.00\",\"currency\":\"EUR\",\"wallet\":\"x\"}",
                "{\"amount\":\"100.00\"}", "{\"amount\":\"1E2\",\"currency\":\"EUR\"}",
                "{\"amount\":\"100.00\",\"currency\":\"XXX\"}")) {
            HttpResponse<String> refused = client.post(prepayments, body, customer.token(), key());
            assertThat(refused.statusCode()).as(body + ": " + refused.body()).isEqualTo(422);
        }
    }
}
