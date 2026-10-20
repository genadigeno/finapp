package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.app.database.DateShifted;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The BNPL application doors' contract (`P12-TSK-010`; PHASE_12_PLAN.md sections 9 and 11, A18): the session, the key and
 * the closed body judged before the domain is reached; a {@code MULTI_FACTOR} session required to apply - enrolled or not;
 * the uniform {@code 404} on another party's, an unknown and a malformed application id, on the read and the withdrawal
 * alike; the session token never echoed in any answer; each refused caller writing nothing. The cases that need credit's
 * BNPL policies, an enabled merchant and the terms in force are {@link BnplApplicationDatabaseTest}'s, in a database of
 * its own. Written by `P12-TSK-010` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest}
 * instruction for the task).
 */
@DateShifted
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the BNPL application doors' contract (P12-TSK-010)")
class BnplApplicationApiTest {

    private static final String TOKEN = "cs_never_echoed_" + UUID.randomUUID().toString().replace("-", "");

    @LocalServerPort private int port;

    @Test
    @DisplayName("no session is 401 on every door; no key is refused on both acts - nothing written")
    void theSessionAndTheKey() throws Exception {
        BnplApplicationClient client = new BnplApplicationClient(port);
        String id = UUID.randomUUID().toString();
        String applications = BnplApplicationClient.APPLICATIONS;
        assertThat(client.post(applications, BnplApplicationClient.payIn4(TOKEN), null, BnplApplicationClient.key())
                .statusCode()).isEqualTo(401);
        assertThat(client.get(applications + "/" + id, null).statusCode()).isEqualTo(401);
        assertThat(client.post(applications + "/" + id + "/withdrawal", null, null, BnplApplicationClient.key())
                .statusCode()).isEqualTo(401);
        CreditFixtures.Customer customer = new CreditFixtures(port).customer(true);
        HttpResponse<String> unkeyed = client.submit(customer.token(), BnplApplicationClient.payIn4(TOKEN), null);
        assertThat(unkeyed.statusCode()).as(unkeyed.body()).isEqualTo(422);
        assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired").doesNotContain(TOKEN);
        assertThat(client.post(applications + "/" + id + "/withdrawal", null, customer.token(), null).statusCode())
                .isEqualTo(422);
        assertNothingWritten(customer);
    }

    @Test
    @DisplayName("the body is closed - an amount, a merchant, a rate, an offer, a score, a decision or a party field is"
            + " refused - its plan judged, an unknown product bnpl.ProductNotOffered; the token never echoed")
    void theBodyIsClosed() throws Exception {
        BnplApplicationClient client = new BnplApplicationClient(port);
        CreditFixtures.Customer customer = new CreditFixtures(port).customer(true);
        for (String field : List.of("amount", "amountMinor", "merchantId", "rate", "offerId", "score", "decision",
                "partyId", "checkoutSessionId")) {
            String body = BnplApplicationClient.payIn4(TOKEN).replace("{", "{\"" + field + "\":\"x\",");
            HttpResponse<String> refused = client.submit(customer.token(), body, BnplApplicationClient.key());
            assertThat(refused.statusCode()).as(field + " " + refused.body()).isEqualTo(422);
            assertThat(refused.body()).doesNotContain(TOKEN);
        }
        for (String body : List.of(BnplApplicationClient.body(TOKEN, "BNPL_PAY_IN_4", "PAY_IN_3_MONTHS"),
                BnplApplicationClient.body(TOKEN, "BNPL_PAY_IN_N", "PAY_IN_5_MONTHS"),
                "{\"product\":\"BNPL_PAY_IN_4\",\"plan\":\"PAY_IN_4\"}")) {
            HttpResponse<String> refused = client.submit(customer.token(), body, BnplApplicationClient.key());
            assertThat(refused.statusCode()).as(body + " " + refused.body()).isEqualTo(422);
            assertThat(refused.body()).contains("api.ValidationFailed").doesNotContain(TOKEN);
        }
        HttpResponse<String> unknown = client.submit(customer.token(),
                BnplApplicationClient.body(TOKEN, "BNPL_PAY_IN_99", "PAY_IN_4"), BnplApplicationClient.key());
        assertThat(unknown.statusCode()).isEqualTo(422);
        assertThat(unknown.body()).contains("bnpl.ProductNotOffered").doesNotContain(TOKEN);
        assertNothingWritten(customer);
    }

    @Test
    @DisplayName("applying needs a MULTI_FACTOR session, enrolled or not - 403 identity.AssuranceRequired, nothing written")
    void applyingNeedsMultiFactor() throws Exception {
        BnplApplicationClient client = new BnplApplicationClient(port);
        CreditFixtures.Customer passwordOnly = new CreditFixtures(port).passwordOnlyCustomer(true);
        HttpResponse<String> refused = client.submit(passwordOnly.token(), BnplApplicationClient.payIn4(TOKEN),
                BnplApplicationClient.key());
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(403);
        assertThat(refused.body()).contains("identity.AssuranceRequired").doesNotContain(TOKEN);
        CreditFixtures.Customer enrolled = new CreditFixtures(port).customer(true);
        HttpResponse<String> stepDown = client.submit(new CreditFixtures(port).login(enrolled),
                BnplApplicationClient.payIn4(TOKEN), BnplApplicationClient.key());
        assertThat(stepDown.statusCode()).as(stepDown.body()).isEqualTo(403);
        assertNothingWritten(passwordOnly);
        assertNothingWritten(enrolled);
    }

    @Test
    @DisplayName("an unknown token on an MFA session is 422 bnpl.SessionNotEligible - the token never echoed")
    void anUnknownTokenIsNotEligible() throws Exception {
        BnplApplicationClient client = new BnplApplicationClient(port);
        CreditFixtures.Customer customer = new CreditFixtures(port).customer(true);
        HttpResponse<String> refused = client.submit(customer.token(), BnplApplicationClient.payIn4(TOKEN),
                BnplApplicationClient.key());
        // Standing first: an unverified customer would be credit.ApplicantNotEligible; this one is verified.
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
        assertThat(refused.body()).contains("bnpl.SessionNotEligible").doesNotContain(TOKEN);
        assertNothingWritten(customer);
    }

    @Test
    @DisplayName("another party's, an unknown and a malformed application id are one 404 bnpl.NotFound - read and"
            + " withdrawal alike")
    void theUniformNotFound() throws Exception {
        BnplApplicationClient client = new BnplApplicationClient(port);
        CreditFixtures.Customer customer = new CreditFixtures(port).customer(true);
        String someoneElses = BnplApplicationClient.scalar(
                "SELECT id::text FROM bnpl.bnpl_application WHERE party_id <> ? LIMIT 1", customer.party());
        String before = someoneElses == null ? null
                : BnplApplicationClient.scalar("SELECT status FROM bnpl.bnpl_application WHERE id = ?::uuid", someoneElses);
        List<String> ids = someoneElses == null ? List.of(UUID.randomUUID().toString(), "not-a-uuid")
                : List.of(someoneElses, UUID.randomUUID().toString(), "not-a-uuid");
        for (String id : ids) {
            HttpResponse<String> read = client.read(customer.token(), id);
            assertThat(read.statusCode()).as(id + " " + read.body()).isEqualTo(404);
            assertThat(read.body()).contains("bnpl.NotFound");
            HttpResponse<String> withdrawal = client.withdraw(customer.token(), id, BnplApplicationClient.key());
            assertThat(withdrawal.statusCode()).as(id + " " + withdrawal.body()).isEqualTo(404);
        }
        if (someoneElses != null) {
            assertThat(BnplApplicationClient.scalar("SELECT status FROM bnpl.bnpl_application WHERE id = ?::uuid",
                    someoneElses)).as("another's application unmoved").isEqualTo(before);
        }
    }

    private static void assertNothingWritten(CreditFixtures.Customer customer) throws Exception {
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM bnpl.bnpl_application WHERE party_id = ?",
                customer.party())).isZero();
        assertThat(BnplApplicationClient.count("SELECT count(*) FROM credit.decision_request WHERE party_id = ?",
                customer.party())).isZero();
    }
}
