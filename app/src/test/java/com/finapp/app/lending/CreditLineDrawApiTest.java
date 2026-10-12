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
 * The draw door's contract (`P11-TSK-021`; PHASE_11_PLAN.md sections 9 and 11, A19, ADR-0100 point 3): the session, the
 * key and the closed body judged before the domain is reached; the draw's {@code MULTI_FACTOR} rule for every customer,
 * enrolled or not; an amount that is not an exact positive decimal in a supported currency refused; the uniform
 * {@code 404} for an unknown, malformed or another party's account. The draws themselves - which need terms, a line
 * accepted and capital in force - are {@link LoanAccountDatabaseTest}'s, in a database of its own, and
 * {@code lending}'s {@code CreditLineDrawDatabaseTest}'s.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the draw door's contract (P11-TSK-021)")
class CreditLineDrawApiTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String LOANS = "/v1/me/loans";
    private static final String BODY = "{\"amount\":\"100.00\",\"currency\":\"EUR\"}";

    @LocalServerPort private int port;

    @Test
    @DisplayName("no session is 401; a draw without a key, with a field the body does not declare - a destination - or"
            + " with a blank body is refused before the domain")
    void theSessionTheKeyAndTheBody() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String path = LOANS + "/" + IDS.next() + "/draws";
        assertThat(client.post(path, BODY, null, key()).statusCode()).isEqualTo(401);
        CreditFixtures.Customer customer = new CreditFixtures(port).consentingCustomer();
        HttpResponse<String> unkeyed = client.post(path, BODY, customer.token(), null);
        assertThat(unkeyed.statusCode()).as(unkeyed.body()).isEqualTo(422);
        assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");
        HttpResponse<String> elsewhere = client.post(path,
                "{\"amount\":\"100.00\",\"currency\":\"EUR\",\"payoutMethodId\":\"" + UUID.randomUUID() + "\"}",
                customer.token(), key());
        assertThat(elsewhere.statusCode()).as("A25: the wallet only - no destination field").isEqualTo(422);
        HttpResponse<String> blank = client.post(path, "{}", customer.token(), key());
        assertThat(blank.statusCode()).as(blank.body()).isEqualTo(422);
    }

    @Test
    @DisplayName("a draw requires a MULTI_FACTOR session for every customer (A19): an enrolled customer on a password-only"
            + " session and an unenrolled one are both 403 identity.AssuranceRequired")
    void theDrawIsMultiFactor() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        CreditFixtures fixtures = new CreditFixtures(port);
        CreditFixtures.Customer enrolled = fixtures.consentingCustomer();
        HttpResponse<String> passwordOnly = client.post(LOANS + "/" + IDS.next() + "/draws", BODY,
                fixtures.login(enrolled), key());
        assertThat(passwordOnly.statusCode()).as(passwordOnly.body()).isEqualTo(403);
        assertThat(passwordOnly.body()).contains("identity.AssuranceRequired");
        CreditFixtures.Customer unenrolled = fixtures.passwordOnlyCustomer(true);
        HttpResponse<String> never = client.post(LOANS + "/" + IDS.next() + "/draws", BODY, unenrolled.token(), key());
        assertThat(never.statusCode()).as(never.body()).isEqualTo(403);
        assertThat(never.body()).contains("identity.AssuranceRequired");
    }

    @Test
    @DisplayName("an amount that is not an exact positive decimal at the currency's minor units, or a currency the"
            + " platform does not support, is 422 api.ValidationFailed")
    void theAmountIsExact() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        CreditFixtures.Customer customer = new CreditFixtures(port).consentingCustomer();
        String path = LOANS + "/" + IDS.next() + "/draws";
        for (String body : List.of("{\"amount\":\"100.001\",\"currency\":\"EUR\"}",
                "{\"amount\":\"1e3\",\"currency\":\"EUR\"}", "{\"amount\":\"ten\",\"currency\":\"EUR\"}",
                "{\"amount\":\"100.00\",\"currency\":\"ZZZ\"}")) {
            HttpResponse<String> refused = client.post(path, body, customer.token(), key());
            assertThat(refused.statusCode()).as(body + ": " + refused.body()).isEqualTo(422);
            assertThat(refused.body()).contains("api.ValidationFailed");
        }
    }

    @Test
    @DisplayName("an unknown or malformed account is 404 lending.NotFound - the answer another party's line gets")
    void theUniformNotFound() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        CreditFixtures.Customer customer = new CreditFixtures(port).consentingCustomer();
        for (String id : List.of(IDS.next().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            HttpResponse<String> draw = client.post(LOANS + "/" + id + "/draws", BODY, customer.token(), key());
            assertThat(draw.statusCode()).as(id + ": " + draw.body()).isEqualTo(404);
            assertThat(draw.body()).contains("lending.NotFound");
        }
    }
}
