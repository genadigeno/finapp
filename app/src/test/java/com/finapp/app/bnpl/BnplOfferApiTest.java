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
 * The BNPL offer doors' and the options read's contract (`P12-TSK-011`; PHASE_12_PLAN.md sections 9 and 11): no session
 * is {@code 401} on every door; the decline without a key is refused; the uniform {@code 404 bnpl.NotFound} on another
 * party's, an unknown and a malformed offer id, on the read and the decline alike; the options read refusing a body
 * without its token, an open field and an unknown token - one {@code 422 bnpl.SessionNotEligible}, the token never
 * echoed. The offered, declined and expired paths over credit's real decision are {@link BnplOriginationDatabaseTest}'s.
 * Written by `P12-TSK-011` and <strong>owed a first run</strong> (the owner's no-{@code databaseTest} instruction).
 */
@DateShifted
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the BNPL offer doors' and the options read's contract (P12-TSK-011)")
class BnplOfferApiTest {

    private static final String OFFERS = "/v1/me/bnpl-offers";
    private static final String OPTIONS = "/v1/checkout/sessions/bnpl-options";
    private static final String TOKEN = "cs_never_echoed_" + UUID.randomUUID().toString().replace("-", "");

    @LocalServerPort private int port;

    @Test
    @DisplayName("no session is 401 on the read, the decline and the options; the decline without a key is refused")
    void theSessionAndTheKey() throws Exception {
        BnplApplicationClient client = new BnplApplicationClient(port);
        String id = UUID.randomUUID().toString();
        assertThat(client.get(OFFERS + "/" + id, null).statusCode()).isEqualTo(401);
        assertThat(client.post(OFFERS + "/" + id + "/decline", null, null, BnplApplicationClient.key()).statusCode())
                .isEqualTo(401);
        assertThat(client.post(OPTIONS, "{\"sessionToken\":\"" + TOKEN + "\"}", null, null).statusCode())
                .isEqualTo(401);
        CreditFixtures.Customer customer = new CreditFixtures(port).customer(true);
        HttpResponse<String> unkeyed = client.post(OFFERS + "/" + id + "/decline", null, customer.token(), null);
        assertThat(unkeyed.statusCode()).as(unkeyed.body()).isEqualTo(422);
        assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");
    }

    @Test
    @DisplayName("an unknown, a malformed and another party's offer id are one 404 bnpl.NotFound on the read and the"
            + " decline (ADR-0031's negative)")
    void theUniformNotFound() throws Exception {
        BnplApplicationClient client = new BnplApplicationClient(port);
        CreditFixtures.Customer customer = new CreditFixtures(port).customer(true);
        for (String id : List.of(UUID.randomUUID().toString(), "not-a-uuid",
                "01a14da7-8e74-758f-936b-484d9ce9d761")) {
            HttpResponse<String> read = client.get(OFFERS + "/" + id, customer.token());
            assertThat(read.statusCode()).as(read.body()).isEqualTo(404);
            assertThat(read.body()).contains("bnpl.NotFound");
            HttpResponse<String> decline = client.post(OFFERS + "/" + id + "/decline", null, customer.token(),
                    BnplApplicationClient.key());
            assertThat(decline.statusCode()).as(decline.body()).isEqualTo(404);
            assertThat(decline.body()).contains("bnpl.NotFound");
        }
    }

    @Test
    @DisplayName("the options read: no token or an open field is 422, an unknown token one 422 bnpl.SessionNotEligible"
            + " - the token never echoed")
    void theOptionsRead() throws Exception {
        BnplApplicationClient client = new BnplApplicationClient(port);
        CreditFixtures.Customer customer = new CreditFixtures(port).customer(true);
        assertThat(client.post(OPTIONS, "{}", customer.token(), null).statusCode()).isEqualTo(422);
        HttpResponse<String> open = client.post(OPTIONS, "{\"sessionToken\":\"" + TOKEN + "\",\"amount\":1}",
                customer.token(), null);
        assertThat(open.statusCode()).as(open.body()).isEqualTo(422);
        assertThat(open.body()).doesNotContain(TOKEN);
        HttpResponse<String> unknown = client.post(OPTIONS, "{\"sessionToken\":\"" + TOKEN + "\"}", customer.token(),
                null);
        assertThat(unknown.statusCode()).as(unknown.body()).isEqualTo(422);
        assertThat(unknown.body()).contains("bnpl.SessionNotEligible").doesNotContain(TOKEN);
    }
}
