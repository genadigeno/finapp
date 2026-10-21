package com.finapp.app.lending;

import static com.finapp.app.lending.LoanApplicationClient.field;
import static com.finapp.app.lending.LoanApplicationClient.key;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.credit.CreditFixtures;
import com.finapp.app.database.DateShifted;
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
 * The loan servicing doors' contract (`P11-TSK-020`; PHASE_11_PLAN.md sections 9 and 11, ADR-0099 points 1, 2 and 8) - the
 * route register's negatives: every door closed to no session ({@code 401}), to a customer's session, to a session holding
 * no role and to a {@code LENDING_TREASURY_OFFICER} ({@code 403}); the acts closed to a missing key and to a body the door
 * does not declare - a decision without the proposal it names - before the domain is reached; an unknown or malformed loan
 * the uniform {@code 404} to a {@code LOAN_SERVICING_AGENT}; a missing reason {@code 422 lending.ReasonRequired}. The
 * reversal itself - which needs a loan disbursed, billed and repaid - is {@link LoanAccountDatabaseTest}'s, in a database
 * of its own, and {@code lending}'s {@code RepaymentReversalDatabaseTest}'s.
 */
@DateShifted
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the loan servicing doors' contract (P11-TSK-020)")
class RepaymentReversalApiTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String REASON = "{\"reason\":\"applied twice by the operator\"}";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    @Test
    @DisplayName("every door is 401 without a session and 403 to a customer, to a session holding no role and to a lending"
            + " treasury officer - nothing reaches the domain")
    void theDoorsAreTheServicingAgents() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String loan = UUID.randomUUID().toString();
        String repayment = UUID.randomUUID().toString();
        String decision = "{\"reversalId\":\"" + UUID.randomUUID() + "\",\"reason\":\"reviewed\"}";
        CreditFixtures.Customer customer = new CreditFixtures(port).consentingCustomer();
        List<String> refused = List.of(customer.token(), sessionWith(null), sessionWith(RoleName.LENDING_TREASURY_OFFICER));
        assertThat(client.get(operator(loan), null).statusCode()).isEqualTo(401);
        assertThat(client.post(reversal(loan, repayment), REASON, null, key()).statusCode()).isEqualTo(401);
        assertThat(client.post(reversal(loan, repayment) + "/approval", decision, null, key()).statusCode()).isEqualTo(401);
        assertThat(client.post(reversal(loan, repayment) + "/rejection", decision, null, key()).statusCode())
                .isEqualTo(401);
        for (String token : refused) {
            assertThat(client.get(operator(loan), token).statusCode()).isEqualTo(403);
            assertThat(client.post(reversal(loan, repayment), REASON, token, key()).statusCode()).isEqualTo(403);
            assertThat(client.post(reversal(loan, repayment) + "/approval", decision, token, key()).statusCode())
                    .isEqualTo(403);
            assertThat(client.post(reversal(loan, repayment) + "/rejection", decision, token, key()).statusCode())
                    .isEqualTo(403);
        }
    }

    @Test
    @DisplayName("to a loan servicing agent: an act without a key, with a field the body does not declare or a decision"
            + " naming no proposal is 422 before the domain; an unknown or malformed loan - a UUIDv4 included - is 404"
            + " lending.NotFound on every door; a missing reason is 422 lending.ReasonRequired")
    void theKeyTheBodyAndTheUniformNotFound() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String agent = sessionWith(RoleName.LOAN_SERVICING_AGENT);
        String loan = IDS.next().toString();
        String repayment = UUID.randomUUID().toString();
        HttpResponse<String> unkeyed = client.post(reversal(loan, repayment), REASON, agent, null);
        assertThat(unkeyed.statusCode()).as(unkeyed.body()).isEqualTo(422);
        assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");
        HttpResponse<String> amount = client.post(reversal(loan, repayment),
                "{\"reason\":\"x\",\"amount\":\"10.00\"}", agent, key());
        assertThat(amount.statusCode()).as("a reversal is whole - no amount field").isEqualTo(422);
        HttpResponse<String> unnamed = client.post(reversal(loan, repayment) + "/approval", "{\"reason\":\"reviewed\"}",
                agent, key());
        assertThat(unnamed.statusCode()).as("a decision names the proposal it decides: " + unnamed.body()).isEqualTo(422);

        String decision = "{\"reversalId\":\"" + UUID.randomUUID() + "\",\"reason\":\"reviewed\"}";
        for (String id : List.of(IDS.next().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            HttpResponse<String> read = client.get(operator(id), agent);
            assertThat(read.statusCode()).as(id + ": " + read.body()).isEqualTo(404);
            assertThat(read.body()).contains("lending.NotFound");
            for (HttpResponse<String> act : List.of(
                    client.post(reversal(id, repayment), REASON, agent, key()),
                    client.post(reversal(id, repayment) + "/approval", decision, agent, key()),
                    client.post(reversal(id, repayment) + "/rejection", decision, agent, key()))) {
                assertThat(act.statusCode()).as(id + ": " + act.body()).isEqualTo(404);
                assertThat(act.body()).contains("lending.NotFound");
            }
        }
        HttpResponse<String> unreasoned = client.post(reversal(loan, repayment), "{}", agent, key());
        assertThat(unreasoned.statusCode()).as(unreasoned.body()).isEqualTo(422);
        assertThat(unreasoned.body()).contains("lending.ReasonRequired");
    }

    // ------------------------------------------------------------------ plumbing

    private static String operator(String loan) {
        return "/v1/operator/loans/" + loan;
    }

    private static String reversal(String loan, String repayment) {
        return "/v1/operator/loans/" + loan + "/repayments/" + repayment + "/reversal";
    }

    /** A signed-in session holding {@code role}, or no role at all when it is {@code null}. */
    private String sessionWith(RoleName role) throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String login = "rr." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
