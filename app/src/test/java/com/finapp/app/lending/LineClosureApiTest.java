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
 * The closure door's contract (`P11-TSK-023`; PHASE_11_PLAN.md sections 9 and 11, A19, ADR-0100 point 8): the session and
 * the key judged before the domain is reached; the closure's {@code MULTI_FACTOR} rule for every customer, enrolled or
 * not; the uniform {@code 404} for an unknown, malformed or another party's account. The closures themselves - which need
 * terms, a line accepted and drawn - are {@link LoanAccountDatabaseTest}'s, in a database of its own, and {@code lending}'s
 * {@code CreditLineRepaymentDatabaseTest}'s.
 */
@DateShifted
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the closure door's contract (P11-TSK-023)")
class LineClosureApiTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String LOANS = "/v1/me/loans";

    @LocalServerPort private int port;

    @Test
    @DisplayName("no session is 401; a closure without a key is refused before the domain")
    void theSessionAndTheKey() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String path = LOANS + "/" + IDS.next() + "/closure";
        assertThat(client.post(path, null, null, key()).statusCode()).isEqualTo(401);
        CreditFixtures.Customer customer = new CreditFixtures(port).consentingCustomer();
        HttpResponse<String> unkeyed = client.post(path, null, customer.token(), null);
        assertThat(unkeyed.statusCode()).as(unkeyed.body()).isEqualTo(422);
        assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");
    }

    @Test
    @DisplayName("a closure requires a MULTI_FACTOR session for every customer (A19): an enrolled customer on a"
            + " password-only session and an unenrolled one are both 403 identity.AssuranceRequired")
    void theClosureIsMultiFactor() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        CreditFixtures fixtures = new CreditFixtures(port);
        CreditFixtures.Customer enrolled = fixtures.consentingCustomer();
        HttpResponse<String> passwordOnly = client.post(LOANS + "/" + IDS.next() + "/closure", null,
                fixtures.login(enrolled), key());
        assertThat(passwordOnly.statusCode()).as(passwordOnly.body()).isEqualTo(403);
        assertThat(passwordOnly.body()).contains("identity.AssuranceRequired");
        CreditFixtures.Customer unenrolled = fixtures.passwordOnlyCustomer(true);
        HttpResponse<String> never = client.post(LOANS + "/" + IDS.next() + "/closure", null, unenrolled.token(), key());
        assertThat(never.statusCode()).as(never.body()).isEqualTo(403);
        assertThat(never.body()).contains("identity.AssuranceRequired");
    }

    @Test
    @DisplayName("an unknown or malformed account is 404 lending.NotFound - the answer another party's line gets")
    void theUniformNotFound() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        CreditFixtures.Customer customer = new CreditFixtures(port).consentingCustomer();
        for (String id : List.of(IDS.next().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            HttpResponse<String> closure = client.post(LOANS + "/" + id + "/closure", null, customer.token(), key());
            assertThat(closure.statusCode()).as(id + ": " + closure.body()).isEqualTo(404);
            assertThat(closure.body()).contains("lending.NotFound");
        }
    }
}
