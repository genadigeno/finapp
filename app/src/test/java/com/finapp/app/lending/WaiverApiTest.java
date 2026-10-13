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
 * The waiver doors' contract (`P11-TSK-025`; PHASE_11_PLAN.md sections 9 and 11, A14, ADR-0099 points 1, 2 and 4) - the
 * route register's negatives: every door closed to no session ({@code 401}), to a customer's session, to a session holding
 * no role and to a {@code LENDING_TREASURY_OFFICER} ({@code 403}); the acts closed to a missing key, to a body the door does
 * not declare, to a kind outside the closed set and to an amount that is no exact decimal before the domain is reached; an
 * unknown or malformed loan - a UUIDv4 included - the uniform {@code 404} to a {@code LOAN_SERVICING_AGENT}; a missing reason
 * {@code 422 lending.ReasonRequired}. The waiver itself - which needs a loan disbursed, billed and charged - is
 * {@link LoanAccountDatabaseTest}'s, in a database of its own, and {@code lending}'s {@code FeeAndWaiverDatabaseTest}'s.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the waiver doors' contract (P11-TSK-025)")
class WaiverApiTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String DECISION = "{\"reason\":\"reviewed against the outage log\"}";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    @Test
    @DisplayName("every waiver door is 401 without a session and 403 to a customer, to a session holding no role and to a"
            + " lending treasury officer - nothing reaches the domain")
    void theDoorsAreTheServicingAgents() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String loan = UUID.randomUUID().toString();
        String waiver = UUID.randomUUID().toString();
        String proposal = proposal("FEE", UUID.randomUUID().toString(), "5.00", "goodwill");
        CreditFixtures.Customer customer = new CreditFixtures(port).consentingCustomer();
        List<String> refused = List.of(customer.token(), sessionWith(null), sessionWith(RoleName.LENDING_TREASURY_OFFICER));
        assertThat(client.post(waivers(loan), proposal, null, key()).statusCode()).isEqualTo(401);
        assertThat(client.post(waivers(loan) + "/" + waiver + "/approval", DECISION, null, key()).statusCode())
                .isEqualTo(401);
        assertThat(client.post(waivers(loan) + "/" + waiver + "/rejection", DECISION, null, key()).statusCode())
                .isEqualTo(401);
        for (String token : refused) {
            assertThat(client.post(waivers(loan), proposal, token, key()).statusCode()).isEqualTo(403);
            assertThat(client.post(waivers(loan) + "/" + waiver + "/approval", DECISION, token, key()).statusCode())
                    .isEqualTo(403);
            assertThat(client.post(waivers(loan) + "/" + waiver + "/rejection", DECISION, token, key()).statusCode())
                    .isEqualTo(403);
        }
    }

    @Test
    @DisplayName("to a loan servicing agent: an act without a key, with a field the body does not declare, a kind outside"
            + " FEE, FEE_REFUND and INTEREST or an amount that is no exact decimal is 422 before the domain; an unknown or"
            + " malformed loan - a UUIDv4 included - is 404 lending.NotFound on every door; a missing reason is 422"
            + " lending.ReasonRequired")
    void theKeyTheBodyAndTheUniformNotFound() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String agent = sessionWith(RoleName.LOAN_SERVICING_AGENT);
        String loan = IDS.next().toString();
        String target = IDS.next().toString();
        String proposal = proposal("FEE", target, "5.00", "goodwill");
        HttpResponse<String> unkeyed = client.post(waivers(loan), proposal, agent, null);
        assertThat(unkeyed.statusCode()).as(unkeyed.body()).isEqualTo(422);
        assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");
        HttpResponse<String> undeclared = client.post(waivers(loan), proposal.replace("}", ",\"approver\":\"me\"}"),
                agent, key());
        assertThat(undeclared.statusCode()).as("a closed body: " + undeclared.body()).isEqualTo(422);
        HttpResponse<String> kind = client.post(waivers(loan), proposal("PENALTY", target, "5.00", "goodwill"), agent,
                key());
        assertThat(kind.statusCode()).as(kind.body()).isEqualTo(422);
        assertThat(kind.body()).contains("api.ValidationFailed");
        HttpResponse<String> inexact = client.post(waivers(loan), proposal("FEE", target, "5.001", "goodwill"), agent,
                key());
        assertThat(inexact.statusCode()).as(inexact.body()).isEqualTo(422);

        for (String id : List.of(IDS.next().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            for (HttpResponse<String> act : List.of(
                    client.post(waivers(id), proposal, agent, key()),
                    client.post(waivers(id) + "/" + IDS.next() + "/approval", DECISION, agent, key()),
                    client.post(waivers(id) + "/" + IDS.next() + "/rejection", DECISION, agent, key()))) {
                assertThat(act.statusCode()).as(id + ": " + act.body()).isEqualTo(404);
                assertThat(act.body()).contains("lending.NotFound");
            }
        }
        HttpResponse<String> unreasoned = client.post(waivers(loan), proposal("FEE", target, "5.00", null), agent, key());
        assertThat(unreasoned.statusCode()).as(unreasoned.body()).isEqualTo(422);
        assertThat(unreasoned.body()).contains("lending.ReasonRequired");
    }

    // ------------------------------------------------------------------ plumbing

    private static String waivers(String loan) {
        return "/v1/operator/loans/" + loan + "/waivers";
    }

    private static String proposal(String kind, String target, String amount, String reason) {
        return "{\"kind\":\"" + kind + "\",\"targetId\":\"" + target + "\",\"amount\":\"" + amount
                + "\",\"currency\":\"EUR\"" + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}";
    }

    /** A signed-in session holding {@code role}, or no role at all when it is {@code null}. */
    private String sessionWith(RoleName role) throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String login = "wv." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
