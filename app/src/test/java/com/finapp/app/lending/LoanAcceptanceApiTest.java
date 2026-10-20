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
 * The acceptance and loan doors' contract (`P11-TSK-012`; PHASE_11_PLAN.md sections 9 and 11, A19): the session, the key
 * and the closed body judged before the domain is reached; the acceptance's {@code MULTI_FACTOR} rule for every
 * customer, enrolled or not; the uniform {@code 404} for an unknown, malformed or another party's offer or loan. The
 * acceptances themselves - which need credit's policies, lending's terms and capital in force - are
 * {@link LoanAccountDatabaseTest}'s, in a database of its own.
 */
@DateShifted
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the acceptance and loan doors' contract (P11-TSK-012)")
class LoanAcceptanceApiTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String OFFERS = "/v1/me/loan-offers";
    private static final String LOANS = "/v1/me/loans";
    private static final String BODY = "{\"termsSha256\":\"" + "ab".repeat(32) + "\"}";

    @LocalServerPort private int port;

    @Test
    @DisplayName("no session is 401 on all three doors; an acceptance without a key, or with a field the body does not"
            + " declare, is refused before the domain")
    void theSessionTheKeyAndTheBody() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String id = IDS.next().toString();
        assertThat(client.post(OFFERS + "/" + id + "/acceptance", BODY, null, key()).statusCode()).isEqualTo(401);
        assertThat(client.get(LOANS, null).statusCode()).isEqualTo(401);
        assertThat(client.get(LOANS + "/" + id, null).statusCode()).isEqualTo(401);
        CreditFixtures.Customer customer = new CreditFixtures(port).consentingCustomer();
        HttpResponse<String> unkeyed = client.post(OFFERS + "/" + id + "/acceptance", BODY, customer.token(), null);
        assertThat(unkeyed.statusCode()).as(unkeyed.body()).isEqualTo(422);
        assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");
        HttpResponse<String> open = client.post(OFFERS + "/" + id + "/acceptance",
                "{\"termsSha256\":\"" + "ab".repeat(32) + "\",\"amount\":\"1.00\"}", customer.token(), key());
        assertThat(open.statusCode()).as(open.body()).isEqualTo(422);
        HttpResponse<String> blank = client.post(OFFERS + "/" + id + "/acceptance", "{}", customer.token(), key());
        assertThat(blank.statusCode()).as(blank.body()).isEqualTo(422);
    }

    @Test
    @DisplayName("the acceptance requires a MULTI_FACTOR session for every customer (A19): an enrolled customer on a"
            + " password-only session and an unenrolled one are both 403 identity.AssuranceRequired")
    void theAcceptanceIsMultiFactor() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        CreditFixtures fixtures = new CreditFixtures(port);
        CreditFixtures.Customer enrolled = fixtures.consentingCustomer();
        HttpResponse<String> passwordOnly = client.post(OFFERS + "/" + IDS.next() + "/acceptance", BODY,
                fixtures.login(enrolled), key());
        assertThat(passwordOnly.statusCode()).as(passwordOnly.body()).isEqualTo(403);
        assertThat(passwordOnly.body()).contains("identity.AssuranceRequired");
        CreditFixtures.Customer unenrolled = fixtures.passwordOnlyCustomer(true);
        HttpResponse<String> never = client.post(OFFERS + "/" + IDS.next() + "/acceptance", BODY, unenrolled.token(),
                key());
        assertThat(never.statusCode()).as(never.body()).isEqualTo(403);
        assertThat(never.body()).contains("identity.AssuranceRequired");
    }

    @Test
    @DisplayName("an unknown or malformed id is 404 lending.NotFound - to accept an offer and to read a loan - the answer"
            + " another party's gets")
    void theUniformNotFound() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        CreditFixtures.Customer customer = new CreditFixtures(port).consentingCustomer();
        for (String path : List.of(IDS.next().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            HttpResponse<String> accept = client.post(OFFERS + "/" + path + "/acceptance", BODY, customer.token(), key());
            assertThat(accept.statusCode()).as(path + ": " + accept.body()).isEqualTo(404);
            assertThat(accept.body()).contains("lending.NotFound");
            HttpResponse<String> read = client.get(LOANS + "/" + path, customer.token());
            assertThat(read.statusCode()).as(path).isEqualTo(404);
            assertThat(read.body()).contains("lending.NotFound");
        }
        HttpResponse<String> mine = client.get(LOANS, customer.token());
        assertThat(mine.statusCode()).isEqualTo(200);
        assertThat(mine.body()).isEqualTo("{\"loans\":[]}");
    }
}
