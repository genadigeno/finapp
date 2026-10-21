package com.finapp.app.lending;

import static com.finapp.app.lending.LoanApplicationClient.count;
import static com.finapp.app.lending.LoanApplicationClient.key;
import static com.finapp.app.lending.LoanApplicationClient.loan;
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
 * The loan application doors' contract (`P11-TSK-010`; PHASE_11_PLAN.md sections 9 and 11, A19): the session, the key
 * and the closed body judged before the domain is reached; a {@code MULTI_FACTOR} session required to apply - enrolled
 * or not - and the conditional step-up to withdraw; the uniform {@code 404}; each refused caller writing nothing. The
 * cases that need credit's policies and lending's terms in force are {@link LoanApplicationDatabaseTest}'s, in a
 * database of its own.
 */
@DateShifted
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the loan application doors' contract (P11-TSK-010)")
class LoanApplicationApiTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());

    @LocalServerPort private int port;

    @Test
    @DisplayName("no session is 401 on every door; no key is refused on both acts - nothing written")
    void theSessionAndTheKey() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String id = UUID.randomUUID().toString();
        String applications = LoanApplicationClient.APPLICATIONS;
        assertThat(client.post(applications, loan("10000.00", 36), null, key()).statusCode()).isEqualTo(401);
        assertThat(client.get(applications + "/" + id, null).statusCode()).isEqualTo(401);
        assertThat(client.post(applications + "/" + id + "/withdrawal", null, null, key()).statusCode()).isEqualTo(401);
        CreditFixtures.Customer customer = new CreditFixtures(port).customer(true);
        HttpResponse<String> unkeyed = client.post(applications, loan("10000.00", 36), customer.token(), null);
        assertThat(unkeyed.statusCode()).as(unkeyed.body()).isEqualTo(422);
        assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");
        assertThat(client.post(applications + "/" + id + "/withdrawal", null, customer.token(), null).statusCode())
                .isEqualTo(422);
        assertNothingWritten(customer);
    }

    @Test
    @DisplayName("the body is closed - a rate, an offer, a score, a decision or a party field is refused - and its shapes"
            + " and vocabularies judged; an unknown product is lending.ProductNotOffered")
    void theBodyIsClosed() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        CreditFixtures.Customer customer = new CreditFixtures(port).customer(true);
        for (String field : List.of("rate", "nominalAnnualRate", "offerId", "score", "decision", "partyId", "termsVersion")) {
            String body = loan("10000.00", 36).replace("{", "{\"" + field + "\":\"x\",");
            HttpResponse<String> refused = client.submit(customer.token(), body, key());
            assertThat(refused.statusCode()).as(field + " " + refused.body()).isEqualTo(422);
        }
        for (String body : List.of(
                loan("1e4", 36), loan("10000.001", 36),
                loan("10000.00", 36).replace("\"repaymentMethod\":\"WALLET\"", "\"repaymentMethod\":\"DIRECT_DEBIT\""),
                loan("10000.00", 36).replace("\"payoutPreference\":\"WALLET\"", "\"payoutPreference\":\"CASH\""),
                loan("10000.00", 36).replace(",\"repaymentDay\":5", ""),
                loan("10000.00", 36).replace("\"repaymentDay\":5", "\"repaymentDay\":32"))) {
            HttpResponse<String> refused = client.submit(customer.token(), body, key());
            assertThat(refused.statusCode()).as(body + " " + refused.body()).isEqualTo(422);
            assertThat(refused.body()).as(body).contains("api.ValidationFailed");
        }
        HttpResponse<String> unknown = client.submit(customer.token(), loan("10000.00", 36)
                .replace("PERSONAL_LOAN", "MORTGAGE"), key());
        assertThat(unknown.statusCode()).isEqualTo(422);
        assertThat(unknown.body()).contains("lending.ProductNotOffered");
        assertNothingWritten(customer);
    }

    @Test
    @DisplayName("applying needs a MULTI_FACTOR session: a customer with no factor, and an enrolled one on a password-only"
            + " session, are 403 identity.AssuranceRequired before any judgement - nothing written")
    void applyingRequiresMultiFactor() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        CreditFixtures fixtures = new CreditFixtures(port);
        CreditFixtures.Customer unenrolled = fixtures.passwordOnlyCustomer(true);
        HttpResponse<String> refused = client.submit(unenrolled.token(), loan("10000.00", 36), key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(refused.body()).contains("identity.AssuranceRequired", "enrol one");
        assertNothingWritten(unenrolled);

        CreditFixtures.Customer assured = fixtures.consentingCustomer();
        String passwordSession = fixtures.login(assured);
        HttpResponse<String> notStepped = client.submit(passwordSession, loan("10000.00", 36), key());
        assertThat(notStepped.statusCode()).as(notStepped.body()).isEqualTo(403);
        assertThat(notStepped.body()).contains("identity.AssuranceRequired", "step up");
        // A well-formed (time-ordered) id, so the refusal is the step-up's - a malformed one is 404 before any check.
        HttpResponse<String> withdrawal = client.withdraw(passwordSession, IDS.next().toString(), key());
        assertThat(withdrawal.statusCode()).as("the withdrawal's conditional step-up: " + withdrawal.body())
                .isEqualTo(403);
        assertThat(client.withdraw(unenrolled.token(), IDS.next().toString(), key()).statusCode())
                .as("an unenrolled customer passes the conditional step-up and meets the uniform 404").isEqualTo(404);
        assertNothingWritten(assured);
    }

    @Test
    @DisplayName("an unknown or malformed id is 404 lending.NotFound to read and to withdraw - the answer another party's"
            + " application gets")
    void theUniformNotFound() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        CreditFixtures.Customer customer = new CreditFixtures(port).customer(true);
        for (String path : List.of(IDS.next().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            HttpResponse<String> read = client.read(customer.token(), path);
            assertThat(read.statusCode()).as(path).isEqualTo(404);
            assertThat(read.body()).contains("lending.NotFound");
            HttpResponse<String> withdraw = client.withdraw(customer.token(), path, key());
            assertThat(withdraw.statusCode()).as(path).isEqualTo(404);
            assertThat(withdraw.body()).contains("lending.NotFound");
        }
    }

    private static void assertNothingWritten(CreditFixtures.Customer customer) throws Exception {
        assertThat(count("SELECT count(*) FROM lending.loan_application WHERE party_id = ?", customer.party())).isZero();
        assertThat(count("SELECT count(*) FROM credit.decision_request WHERE party_id = ?", customer.party())).isZero();
    }
}
