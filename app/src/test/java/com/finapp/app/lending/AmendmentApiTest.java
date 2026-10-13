package com.finapp.app.lending;

import static com.finapp.app.lending.LoanApplicationClient.field;
import static com.finapp.app.lending.LoanApplicationClient.key;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The restructuring doors' contract (`P11-TSK-027`; PHASE_11_PLAN.md sections 9 and 11, ADR-0099 points 1, 2 and 5) - the
 * route register's negatives: every operator door closed to no session ({@code 401}), to a customer's session, to a session
 * holding no role and to a {@code LENDING_TREASURY_OFFICER} ({@code 403}); the customer's doors closed to no session; the
 * operator's acts closed to a missing key, a body the door does not declare, a rate that is no decimal fraction and a term
 * out of range before the domain is reached; an unknown or malformed loan the uniform {@code 404}; a missing reason
 * {@code 422 lending.ReasonRequired}; the customer's acceptance closed to a session below {@code MULTI_FACTOR} and an
 * amendment never offered to them the uniform {@code 404}. The restructuring itself - which needs a loan disbursed and billed
 * - is {@link LoanAccountDatabaseTest}'s, in a database of its own, and {@code lending}'s {@code AmendmentDatabaseTest}'s.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the restructuring doors' contract (P11-TSK-027)")
class AmendmentApiTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String DECISION = "{\"reason\":\"affordability reviewed\"}";
    private static final String ACCEPTANCE = "{\"documentSha256\":\"" + "ab".repeat(32) + "\"}";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    @Test
    @DisplayName("every operator amendment door is 401 without a session and 403 to a customer, to a session holding no"
            + " role and to a lending treasury officer; the customer's read and acceptance are 401 without a session -"
            + " nothing reaches the domain")
    void theDoorsAreTheServicingAgentsAndTheCustomers() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String loan = UUID.randomUUID().toString();
        String amendment = UUID.randomUUID().toString();
        String proposal = proposal("0.05", 18, false, "hardship");
        CreditFixtures.Customer customer = new CreditFixtures(port).consentingCustomer();
        List<String> refused = List.of(customer.token(), sessionWith(null), sessionWith(RoleName.LENDING_TREASURY_OFFICER));
        assertThat(client.post(amendments(loan), proposal, null, key()).statusCode()).isEqualTo(401);
        assertThat(client.post(amendments(loan) + "/" + amendment + "/approval", DECISION, null, key()).statusCode())
                .isEqualTo(401);
        assertThat(client.post(amendments(loan) + "/" + amendment + "/rejection", DECISION, null, key()).statusCode())
                .isEqualTo(401);
        for (String token : refused) {
            assertThat(client.post(amendments(loan), proposal, token, key()).statusCode()).isEqualTo(403);
            assertThat(client.post(amendments(loan) + "/" + amendment + "/approval", DECISION, token, key())
                    .statusCode()).isEqualTo(403);
            assertThat(client.post(amendments(loan) + "/" + amendment + "/rejection", DECISION, token, key())
                    .statusCode()).isEqualTo(403);
        }
        assertThat(client.get(mine(loan, amendment), null).statusCode()).isEqualTo(401);
        assertThat(client.post(mine(loan, amendment) + "/acceptance", ACCEPTANCE, null, key()).statusCode()).isEqualTo(401);
    }

    @Test
    @DisplayName("to a loan servicing agent: an act without a key, with a field the body does not declare (an amount"
            + " included - an amendment never names principal), a rate that is no decimal fraction or a term out of range"
            + " is 422 before the domain; an unknown or malformed loan - a UUIDv4 included - is 404 lending.NotFound on"
            + " every door; a missing reason is 422 lending.ReasonRequired")
    void theKeyTheBodyAndTheUniformNotFound() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String agent = sessionWith(RoleName.LOAN_SERVICING_AGENT);
        String loan = IDS.next().toString();
        String proposal = proposal("0.05", 18, false, "hardship");
        HttpResponse<String> unkeyed = client.post(amendments(loan), proposal, agent, null);
        assertThat(unkeyed.statusCode()).as(unkeyed.body()).isEqualTo(422);
        assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");
        HttpResponse<String> undeclared = client.post(amendments(loan), proposal.replace("}", ",\"amount\":\"9000.00\"}"),
                agent, key());
        assertThat(undeclared.statusCode()).as("a closed body: " + undeclared.body()).isEqualTo(422);
        for (String rate : List.of("-0.01", "5%", "1.5", "0.1e1", "0.123456789")) {
            HttpResponse<String> malformed = client.post(amendments(loan), proposal(rate, 18, false, "hardship"), agent,
                    key());
            assertThat(malformed.statusCode()).as(rate + ": " + malformed.body()).isEqualTo(422);
        }
        HttpResponse<String> term = client.post(amendments(loan), proposal("0.05", 0, false, "hardship"), agent, key());
        assertThat(term.statusCode()).as(term.body()).isEqualTo(422);

        for (String id : List.of(IDS.next().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            for (HttpResponse<String> act : List.of(
                    client.post(amendments(id), proposal, agent, key()),
                    client.post(amendments(id) + "/" + IDS.next() + "/approval", DECISION, agent, key()),
                    client.post(amendments(id) + "/" + IDS.next() + "/rejection", DECISION, agent, key()))) {
                assertThat(act.statusCode()).as(id + ": " + act.body()).isEqualTo(404);
                assertThat(act.body()).contains("lending.NotFound");
            }
        }
        HttpResponse<String> unreasoned = client.post(amendments(loan), proposal("0.05", 18, false, null), agent, key());
        assertThat(unreasoned.statusCode()).as(unreasoned.body()).isEqualTo(422);
        assertThat(unreasoned.body()).contains("lending.ReasonRequired");
    }

    @Test
    @DisplayName("to a customer: an amendment of a loan that is not theirs - unknown, malformed, another party's - is the"
            + " uniform 404 on the read; the acceptance from a session below MULTI_FACTOR is 403 identity.AssuranceRequired"
            + " before the domain, and without a key 422")
    void theCustomersDoors() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        CreditFixtures.Customer customer = new CreditFixtures(port).consentingCustomer();
        for (String id : List.of(IDS.next().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            HttpResponse<String> read = client.get(mine(id, IDS.next().toString()), customer.token());
            assertThat(read.statusCode()).as(id + ": " + read.body()).isEqualTo(404);
            assertThat(read.body()).contains("lending.NotFound");
        }
        HttpResponse<String> unkeyed = client.post(mine(IDS.next().toString(), IDS.next().toString()) + "/acceptance",
                ACCEPTANCE, customer.token(), null);
        assertThat(unkeyed.statusCode()).as(unkeyed.body()).isEqualTo(422);
        CreditFixtures.Customer passwordOnly = new CreditFixtures(port).passwordOnlyCustomer(true);
        HttpResponse<String> singleFactor = client.post(mine(IDS.next().toString(), IDS.next().toString())
                + "/acceptance", ACCEPTANCE, passwordOnly.token(), key());
        assertThat(singleFactor.statusCode()).as(singleFactor.body()).isEqualTo(403);
        assertThat(singleFactor.body()).contains("identity.AssuranceRequired");
    }

    // ------------------------------------------------------------------ plumbing

    private static String amendments(String loan) {
        return "/v1/operator/loans/" + loan + "/amendments";
    }

    private static String mine(String loan, String amendment) {
        return "/v1/me/loans/" + loan + "/amendments/" + amendment;
    }

    private static String proposal(String rate, int remaining, boolean arrears, String reason) {
        return "{\"nominalAnnualRate\":\"" + rate + "\",\"remainingInstalments\":" + remaining + ",\"rescheduleArrears\":"
                + arrears + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}";
    }

    /** A signed-in session holding {@code role}, or no role at all when it is {@code null}. */
    private String sessionWith(RoleName role) throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String login = "am." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(client.post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada"
                + " Lovelace\",\"password\":\"" + PASSWORD + "\"}", null, key()).statusCode()).isEqualTo(201);
        if (role != null) {
            UUID identity;
            try (Connection app = DatabaseRoles.application();
                    PreparedStatement read = app.prepareStatement(
                            "SELECT id FROM identity.identity WHERE login_identifier = ?")) {
                read.setString(1, login);
                try (ResultSet row = read.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    identity = row.getObject("id", UUID.class);
                }
            }
            try (CorrelationContext.Scope flow = CorrelationContext.enter(
                            Correlation.startingWith(CorrelationId.generate(IDS)));
                    SecurityContext.Scope actor = SecurityContext.enterSystem();
                    Connection app = DatabaseRoles.application()) {
                app.setAutoCommit(false);
                authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
                app.commit();
            }
        }
        HttpResponse<String> session = client.post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}", null, key());
        return field(session.body(), "sessionToken");
    }
}
