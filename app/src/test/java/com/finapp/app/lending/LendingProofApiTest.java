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
 * The lending investigator's doors' contract (`P11-TSK-029`; PHASE_11_PLAN.md sections 9 and 11) - the route register's
 * negatives: the explanation and the replay closed to no session ({@code 401}), to a customer's session, to a session
 * holding no role, to a {@code LOAN_SERVICING_AGENT} (who services loans but does not investigate them) and to a
 * {@code LENDING_TREASURY_OFFICER} ({@code 403}); to a {@code LENDING_OFFICER} - who holds {@code LENDING_INVESTIGATE} - an
 * unknown or malformed loan, a UUIDv4 included, is the uniform {@code 404 lending.NotFound} on both doors and records
 * nothing; a replay without a reason, with an instrument-shaped one or with a field the body does not declare is
 * {@code 422} before anything is read. A served explanation and replay - which need a disbursed loan - and their audit
 * records are {@link LoanAccountDatabaseTest}'s, in a database of its own; the proofs themselves are {@code lending}'s
 * {@code LendingProofDatabaseTest}'s.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the lending investigator's doors' contract (P11-TSK-029)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class LendingProofApiTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String REASON = "{\"reason\":\"a customer complaint about a statement\"}";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    @Test
    @DisplayName("both doors are 401 without a session and 403 to a customer, to a session holding no role, to a loan"
            + " servicing agent and to a lending treasury officer - nothing reaches the domain")
    void theDoorsAreTheInvestigators() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String loan = IDS.next().toString();
        CreditFixtures.Customer customer = new CreditFixtures(port).consentingCustomer();
        assertThat(client.get(explanation(loan), null).statusCode()).isEqualTo(401);
        assertThat(client.post(replay(loan), REASON, null, null).statusCode()).isEqualTo(401);
        for (String token : List.of(customer.token(), sessionWith(null), sessionWith(RoleName.LOAN_SERVICING_AGENT),
                sessionWith(RoleName.LENDING_TREASURY_OFFICER))) {
            assertThat(client.get(explanation(loan), token).statusCode()).isEqualTo(403);
            assertThat(client.post(replay(loan), REASON, token, null).statusCode()).isEqualTo(403);
        }
    }

    @Test
    @DisplayName("to a lending officer: an unknown or malformed loan - a UUIDv4 included - is 404 lending.NotFound on both"
            + " doors and records nothing; a replay without a reason, with a card number's shape for one or with an"
            + " undeclared field is 422 before anything is read")
    void theUniformNotFoundAndTheReason() throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String officer = sessionWith(RoleName.LENDING_OFFICER);
        long before = audits();
        for (String id : List.of(IDS.next().toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
            for (HttpResponse<String> act : List.of(client.get(explanation(id), officer),
                    client.post(replay(id), REASON, officer, null))) {
                assertThat(act.statusCode()).as(id + ": " + act.body()).isEqualTo(404);
                assertThat(act.body()).contains("lending.NotFound");
            }
        }
        assertThat(audits()).as("an unknown loan is read and recorded as nothing").isEqualTo(before);

        String loan = IDS.next().toString();
        HttpResponse<String> unreasoned = client.post(replay(loan), "{}", officer, null);
        assertThat(unreasoned.statusCode()).as(unreasoned.body()).isEqualTo(422);
        assertThat(unreasoned.body()).contains("lending.ReasonRequired");
        HttpResponse<String> card = client.post(replay(loan), "{\"reason\":\"card 4111 1111 1111 1111 disputed\"}",
                officer, null);
        assertThat(card.statusCode()).as(card.body()).isEqualTo(422);
        assertThat(card.body()).contains("lending.ReasonRequired");
        HttpResponse<String> undeclared = client.post(replay(loan),
                "{\"reason\":\"a complaint\",\"engineVersion\":\"2\"}", officer, null);
        assertThat(undeclared.statusCode()).as("a closed body: " + undeclared.body()).isEqualTo(422);
    }

    // ------------------------------------------------------------------ plumbing

    private static String explanation(String loan) {
        return "/v1/operator/loans/" + loan + "/explanation";
    }

    private static String replay(String loan) {
        return "/v1/operator/loans/" + loan + "/replay";
    }

    private static long audits() throws Exception {
        try (Connection app = DatabaseRoles.migrator();
                PreparedStatement read = app.prepareStatement("SELECT count(*) FROM platform.audit_record"
                        + " WHERE operation IN ('lending.ExplanationRead', 'lending.LoanReplayed')");
                ResultSet row = read.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    /** A signed-in session holding {@code role}, or no role at all when it is {@code null}. */
    private String sessionWith(RoleName role) throws Exception {
        LoanApplicationClient client = new LoanApplicationClient(port);
        String login = "lp." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
