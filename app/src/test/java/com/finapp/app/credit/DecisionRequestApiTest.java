package com.finapp.app.credit;

import static com.finapp.app.credit.CreditTestClient.count;
import static com.finapp.app.credit.CreditTestClient.key;
import static com.finapp.app.credit.CreditTestClient.loan;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The decision request doors' contract (`P10-TSK-014`): the session, the key and the closed body judged before the
 * domain is reached; step-up for an MFA-enrolled customer; the domain's refusals on the error contract - each refused
 * caller writing nothing. The cases that need a policy in force - the {@code 202} shape among them - are
 * {@link DecisionRequestDatabaseTest}'s, in a database of its own.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the decision request doors' contract (P10-TSK-014)")
class DecisionRequestApiTest {

    @LocalServerPort private int port;

    @Test
    @DisplayName("no session is 401 on every door; no key is refused on both acts")
    void theSessionAndTheKey() throws Exception {
        CreditTestClient client = new CreditTestClient(port);
        String id = UUID.randomUUID().toString();
        assertThat(client.post(CreditTestClient.REQUESTS, loan("10000.00", 36), null, key()).statusCode()).isEqualTo(401);
        assertThat(client.get(CreditTestClient.REQUESTS + "/" + id, null).statusCode()).isEqualTo(401);
        assertThat(client.post(CreditTestClient.REQUESTS + "/" + id + "/cancellation", null, null, key()).statusCode())
                .isEqualTo(401);
        CreditTestClient.Customer customer = client.customer(true);
        HttpResponse<String> unkeyed = client.post(CreditTestClient.REQUESTS, loan("10000.00", 36), customer.token(), null);
        assertThat(unkeyed.statusCode()).as(unkeyed.body()).isEqualTo(422);
        assertThat(client.post(CreditTestClient.REQUESTS + "/" + id + "/cancellation", null, customer.token(), null)
                .statusCode()).isEqualTo(422);
        assertThat(count("SELECT count(*) FROM credit.decision_request WHERE party_id = ?", customer.party())).isZero();
    }

    @Test
    @DisplayName("the body is closed - a score, a rate, a limit or a decision field is refused - and its shapes judged")
    void theBodyIsClosed() throws Exception {
        CreditTestClient client = new CreditTestClient(port);
        CreditTestClient.Customer customer = client.customer(true);
        for (String field : List.of("score", "rate", "approvedAmount", "decision", "outcome", "partyId")) {
            String body = "{\"product\":\"PERSONAL_LOAN\",\"amount\":\"10000.00\",\"currency\":\"EUR\",\"termMonths\":36,\""
                    + field + "\":\"x\"}";
            HttpResponse<String> refused = client.submit(customer, body, key());
            assertThat(refused.statusCode()).as(field + " " + refused.body()).isEqualTo(422);
        }
        for (String body : List.of(
                "{\"product\":\"PERSONAL_LOAN\",\"amount\":\"1e4\",\"currency\":\"EUR\",\"termMonths\":36}",
                "{\"product\":\"PERSONAL_LOAN\",\"amount\":\"10000.001\",\"currency\":\"EUR\",\"termMonths\":36}",
                "{\"product\":\"PERSONAL_LOAN\",\"amount\":\"10000.00\",\"currency\":\"EURO\",\"termMonths\":36}",
                "{\"product\":\"PERSONAL_LOAN\",\"currency\":\"EUR\",\"termMonths\":36}")) {
            HttpResponse<String> refused = client.submit(customer, body, key());
            assertThat(refused.statusCode()).as(body + " " + refused.body()).isEqualTo(422);
        }
        HttpResponse<String> unknown = client.submit(customer,
                "{\"product\":\"MORTGAGE\",\"amount\":\"10000.00\",\"currency\":\"EUR\"}", key());
        assertThat(unknown.statusCode()).isEqualTo(422);
        assertThat(unknown.body()).contains("credit.ProductNotOffered");
        assertThat(count("SELECT count(*) FROM credit.decision_request WHERE party_id = ?", customer.party())).isZero();
    }

    @Test
    @DisplayName("an MFA-enrolled customer on a password-only session is 403 identity.AssuranceRequired - before any"
            + " judgement, nothing written")
    void anEnrolledCustomerNeedsAStepUp() throws Exception {
        CreditTestClient client = new CreditTestClient(port);
        CreditTestClient.Customer customer = client.consentingCustomer();
        client.enrolAndConfirm(customer.token());
        HttpResponse<String> refused = client.submit(customer, loan("10000.00", 36), key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(refused.body()).contains("identity.AssuranceRequired");
        // A well-formed (time-ordered) id, so the refusal is the step-up's - a malformed one is 404 before any check.
        HttpResponse<String> cancel = client.cancel(customer, CreditTestClient.IDS.next().toString(), key());
        assertThat(cancel.statusCode()).as(cancel.body()).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM credit.decision_request WHERE party_id = ?", customer.party())).isZero();
    }

    @Test
    @DisplayName("a party KYC has not approved is 409 credit.ApplicantNotEligible, whatever is in force")
    void anUnverifiedPartyIsNotEligible() throws Exception {
        CreditTestClient client = new CreditTestClient(port);
        CreditTestClient.Customer pending = client.customer(false);
        HttpResponse<String> refused = client.submit(pending, loan("10000.00", 36), key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(409);
        assertThat(refused.body()).contains("credit.ApplicantNotEligible");
        assertThat(count("SELECT count(*) FROM credit.decision_request WHERE party_id = ?", pending.party())).isZero();
    }
}
