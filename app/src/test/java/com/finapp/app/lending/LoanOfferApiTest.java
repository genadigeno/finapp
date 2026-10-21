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
 * The loan offer doors' contract (`P11-TSK-011`; PHASE_11_PLAN.md sections 9 and 11): the session and the key judged
 * before the domain is reached; the decline's conditional step-up; the uniform {@code 404} for an unknown, malformed or
 * another party's offer. The offers themselves - which need credit's policies and lending's terms in force - are
 * {@link LoanOfferDatabaseTest}'s, in a database of its own.
 */
@DateShifted
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the loan offer doors' contract (P11-TSK-011)")
class LoanOfferApiTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String OFFERS = "/v1/me/loan-offers";

    @LocalServerPort private int port;

    @Test
    @DisplayName("no session is 401 on both doors; a decline without a key is refused")
    void theSessionAndTheKey() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String id = IDS.next().toString();
        assertThat(client.get(OFFERS + "/" + id, null).statusCode()).isEqualTo(401);
        assertThat(client.post(OFFERS + "/" + id + "/decline", null, null, key()).statusCode()).isEqualTo(401);
        CreditFixtures.Customer customer = new CreditFixtures(port).customer(true);
        HttpResponse<String> unkeyed = client.post(OFFERS + "/" + id + "/decline", null, customer.token(), null);
        assertThat(unkeyed.statusCode()).as(unkeyed.body()).isEqualTo(422);
        assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");
    }

    @Test
    @DisplayName("the decline keeps the conditional step-up: an enrolled customer on a password-only session is 403"
            + " identity.AssuranceRequired; an unenrolled one passes it and meets the uniform 404")
    void theDeclinesStepUp() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        CreditFixtures fixtures = new CreditFixtures(port);
        CreditFixtures.Customer assured = fixtures.consentingCustomer();
        String passwordSession = fixtures.login(assured);
        HttpResponse<String> notStepped =
                client.post(OFFERS + "/" + IDS.next() + "/decline", null, passwordSession, key());
        assertThat(notStepped.statusCode()).as(notStepped.body()).isEqualTo(403);
        assertThat(notStepped.body()).contains("identity.AssuranceRequired");
        CreditFixtures.Customer unenrolled = fixtures.passwordOnlyCustomer(true);
        assertThat(client.post(OFFERS + "/" + IDS.next() + "/decline", null, unenrolled.token(), key()).statusCode())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("an unknown or malformed id is 404 lending.NotFound to read and to decline - the answer another party's"
            + " offer gets")
    void theUniformNotFound() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        CreditFixtures.Customer customer = new CreditFixtures(port).customer(true);
        for (String path : List.of(IDS.next().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            HttpResponse<String> read = client.get(OFFERS + "/" + path, customer.token());
            assertThat(read.statusCode()).as(path).isEqualTo(404);
            assertThat(read.body()).contains("lending.NotFound");
            HttpResponse<String> decline = client.post(OFFERS + "/" + path + "/decline", null, customer.token(), key());
            assertThat(decline.statusCode()).as(path).isEqualTo(404);
            assertThat(decline.body()).contains("lending.NotFound");
        }
    }
}
