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
 * The loan write-off doors' contract over HTTP (`P14-TSK-021`): who may knock, and at what assurance. OWED (C5): written and walked by
 * hand, not yet run. What the acts do is {@code LoanWriteOffDatabaseTest}'s, against a real database; the boundary's parse and receipts are
 * {@code LoanWriteOffDeskTest}'s, hermetic.
 */
@DateShifted
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the loan write-off doors' contract (P14-TSK-021)")
class LoanWriteOffApiTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String REASON = "{\"reason\":\"uncollectable after collections\"}";
    private static final String APPROVAL = "{\"currency\":\"EUR\",\"principal\":\"90.00\",\"principalDue\":\"10.00\","
            + "\"interestAccrued\":\"5.00\",\"interestDue\":\"7.00\",\"feesDue\":\"3.00\",\"revision\":42,\"reason\":\"read and agreed\"}";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    @Test
    @DisplayName("every door is 401 without a session and 403 to a customer, to a session holding no role and to a role that serves another"
            + " product - nothing reaches the domain")
    void theDoorsAreClosed() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String account = IDS.next().toString();
        String writeOff = IDS.next().toString();
        CreditFixtures.Customer customer = new CreditFixtures(port).consentingCustomer();
        assertThat(client.post(writeOffs(account), REASON, null, key()).statusCode()).isEqualTo(401);
        assertThat(client.post(writeOffs(account) + "/" + writeOff + "/approval", APPROVAL, null, key()).statusCode()).isEqualTo(401);
        assertThat(client.post(writeOffs(account) + "/" + writeOff + "/rejection", REASON, null, key()).statusCode()).isEqualTo(401);
        assertThat(client.get(writeOffs(account) + "/" + writeOff, null).statusCode()).isEqualTo(401);
        assertThat(client.get(PENDING, null).statusCode()).isEqualTo(401);
        for (String token : List.of(customer.token(), sessionWith(null), sessionWith(RoleName.BNPL_OFFICER))) {
            assertThat(client.post(writeOffs(account), REASON, token, key()).statusCode()).isEqualTo(403);
            assertThat(client.post(writeOffs(account) + "/" + writeOff + "/approval", APPROVAL, token, key()).statusCode()).isEqualTo(403);
            assertThat(client.post(writeOffs(account) + "/" + writeOff + "/rejection", REASON, token, key()).statusCode()).isEqualTo(403);
            assertThat(client.get(writeOffs(account) + "/" + writeOff, token).statusCode()).isEqualTo(403);
            assertThat(client.get(PENDING, token).statusCode()).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("no role both proposes and decides: the proposer's role cannot approve, reject or read what it attests; the approver's cannot"
            + " propose")
    void noRoleProposesAndDecides() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String account = IDS.next().toString();
        String writeOff = IDS.next().toString();
        String agent = sessionWith(RoleName.LOAN_SERVICING_AGENT);
        assertThat(client.post(writeOffs(account) + "/" + writeOff + "/approval", APPROVAL, agent, key()).statusCode()).isEqualTo(403);
        assertThat(client.post(writeOffs(account) + "/" + writeOff + "/rejection", REASON, agent, key()).statusCode()).isEqualTo(403);
        assertThat(client.get(writeOffs(account) + "/" + writeOff, agent).statusCode()).isEqualTo(403);
        assertThat(client.post(writeOffs(account), REASON, sessionWith(RoleName.LENDING_OFFICER), key()).statusCode()).isEqualTo(403);
    }

    @Test
    @DisplayName("the approval is MULTI_FACTOR: the approver on a password session is 403 identity.AssuranceRequired before the domain, while"
            + " their read, their list and their rejection reach it")
    void theApprovalIsMultiFactor() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String account = IDS.next().toString();
        String writeOff = IDS.next().toString();
        String officer = sessionWith(RoleName.LENDING_OFFICER);
        HttpResponse<String> approval = client.post(writeOffs(account) + "/" + writeOff + "/approval", APPROVAL, officer, key());
        assertThat(approval.statusCode()).as(approval.body()).isEqualTo(403);
        assertThat(approval.body()).contains("identity.AssuranceRequired");
        assertThat(client.get(writeOffs(account) + "/" + writeOff, officer).statusCode()).isEqualTo(404);
        assertThat(client.post(writeOffs(account) + "/" + writeOff + "/rejection", REASON, officer, key()).statusCode()).isEqualTo(404);
        assertThat(client.get(PENDING, officer).statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("to the agent: an act without a key or with a field the body does not declare is 422 before the domain; an unknown or malformed"
            + " account - a UUIDv4 included - the uniform 404, never a 500")
    void theKeyTheBodyAndTheUniformNotFound() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String agent = sessionWith(RoleName.LOAN_SERVICING_AGENT);
        String account = IDS.next().toString();
        HttpResponse<String> unkeyed = client.post(writeOffs(account), REASON, agent, null);
        assertThat(unkeyed.statusCode()).as(unkeyed.body()).isEqualTo(422);
        assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");
        HttpResponse<String> undeclared = client.post(writeOffs(account), REASON.replace("}", ",\"approver\":\"me\"}"), agent, key());
        assertThat(undeclared.statusCode()).as("a closed body: " + undeclared.body()).isEqualTo(422);
        for (String id : List.of(IDS.next().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            HttpResponse<String> act = client.post(writeOffs(id), REASON, agent, key());
            assertThat(act.statusCode()).as(id + ": " + act.body()).isEqualTo(404);
            assertThat(act.body()).contains("lending.NotFound");
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static final String PENDING = "/v1/operator/loans/write-offs";

    private static String writeOffs(String account) {
        return "/v1/operator/loans/" + account + "/write-offs";
    }

    /** A signed-in password session holding {@code role}, or no role at all when it is {@code null}. */
    private String sessionWith(RoleName role) throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String login = "wo." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(client.post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada"
                + " Lovelace\",\"password\":\"" + PASSWORD + "\"}", null, key()).statusCode()).isEqualTo(201);
        if (role != null) {
            UUID identity;
            try (Connection app = DatabaseRoles.application();
                    PreparedStatement read = app.prepareStatement("SELECT id FROM identity.identity WHERE login_identifier = ?")) {
                read.setString(1, login);
                try (ResultSet row = read.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    identity = row.getObject("id", UUID.class);
                }
            }
            try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
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
