package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.app.database.DateShifted;
import com.finapp.identity.Authorization;
import com.finapp.identity.RoleName;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The merchant BNPL purchase route's negatives over HTTP (`P12-TSK-014`; ADR-0052, {@code RequiresMerchantKey}): no key,
 * a customer's session, an operator's session and a key-shaped string no merchant holds are each the platform's
 * {@code 401}, before any read - the route is a merchant's and never two populations' at once. The purchase, its owner and
 * another merchant are {@code BnplMerchantReadDatabaseTest}'s. Written by `P12-TSK-014` and <strong>owed a first
 * run</strong> (the owner's no-{@code databaseTest} instruction).
 */
@DateShifted
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the merchant BNPL purchase route's negatives (P12-TSK-014)")
class BnplMerchantReadApiTest {

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    @Test
    @DisplayName("no key, a customer's session, an operator's session and an unknown key are each 401")
    void onlyAMerchantKeyIsAdmitted() throws Exception {
        BnplApplicationClient client = new BnplApplicationClient(port);
        String path = BnplMerchantReadDatabaseTest.PURCHASES + "/" + UUID.randomUUID();
        CreditFixtures.Customer customer = new CreditFixtures(port).consentingCustomer();
        String operator = client.operatorSession(authorization, RoleName.MERCHANT_ADMINISTRATOR);
        for (String bearer : new String[] {null, customer.token(), operator, UUID.randomUUID() + ".not-a-secret"}) {
            HttpResponse<String> refused = client.get(path, bearer);
            assertThat(refused.statusCode()).as("%s: %s", bearer == null ? "no key" : "a non-merchant bearer",
                    refused.body()).isEqualTo(401);
            assertThat(refused.body()).doesNotContain("bnpl.NotFound");
        }
    }
}
