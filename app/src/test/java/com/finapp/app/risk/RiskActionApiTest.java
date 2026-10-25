package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authenticator;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.identity.TotpParameters;
import com.finapp.merchant.JdbcMerchantStore;
import com.finapp.merchant.Merchant;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.security.Sensitive;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The risk action doors, end to end through the real owning domains (`P13-TSK-017`; {@code PHASE_13_PLAN.md} sections 9,
 * 11, 12.8; ADR-0112; C10; the {@code RiskCaseApiTest} shape): an ATO case on a real customer proposes
 * {@code REVOKE_SESSIONS} and {@code SUSPEND_IDENTITY} ({@code 201}); the proposer's approval {@code 403
 * risk.SelfApprovalRefused} recorded {@code FAILED}; a password-only session {@code 403 identity.AssuranceRequired}; a
 * second analyst's {@code MULTI_FACTOR} approval answers {@code 202} with the action already {@code DONE} on its
 * <strong>first attempt</strong> - dispatched as the platform (the routed {@code CUSTOMER}-actor finding) through
 * identity's own commands: the customer's session revoked, the identity {@code SUSPENDED}, identity's audit naming the
 * system with the action's code; a merchant-abuse case suspends its merchant through merchant's own command; the
 * refusals - another desk {@code 403}, no session {@code 401}, a kind the case does not admit {@code 409
 * risk.ActionNotProposable}, an untaken case {@code 422 risk.CaseNotTaken}, an unknown case {@code 404}. Written and owed
 * under C5.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the risk action doors through the owning domains (P13-TSK-017)")
class RiskActionApiTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    @Test
    @DisplayName("C10 over HTTP: an ATO case's REVOKE_SESSIONS and SUSPEND_IDENTITY proposed 201, the proposer's approval"
            + " 403 recorded FAILED, a password session 403; from MULTI_FACTOR 202 DONE on the first attempt - the"
            + " customer's session ended and the identity SUSPENDED by identity, audited as the platform")
    void theAtoResponseThroughIdentity() throws Exception {
        Session alice = sessionWith(RoleName.RISK_ANALYST, false);
        Session customer = customer();
        String caseId = takenCase("{\"kind\":\"ATO\",\"subjectKind\":\"PARTY\",\"subjectRef\":\"" + customer.party()
                + "\",\"reason\":\"a recovery then a new payee\"}", alice);

        HttpResponse<String> revoke = post(door(caseId) + "/risk-actions", "{\"revision\":1,\"kind\":"
                + "\"REVOKE_SESSIONS\",\"reason\":\"the sessions are the attacker's\"}", alice.token());
        assertThat(revoke.statusCode()).as(revoke.body()).isEqualTo(201);
        assertThat(revoke.body()).contains("\"status\":\"PROPOSED\"").contains("\"targetKind\":\"PARTY\"")
                .contains("\"targetRef\":\"" + customer.party() + "\"").contains("\"caseRevision\":2")
                .doesNotContain("proposedBy");
        String revokeId = field(revoke.body(), "actionId");
        HttpResponse<String> suspend = post(door(caseId) + "/risk-actions", "{\"revision\":2,\"kind\":"
                + "\"SUSPEND_IDENTITY\",\"reason\":\"stop the logins\"}", alice.token());
        assertThat(suspend.statusCode()).as(suspend.body()).isEqualTo(201);
        String suspendId = field(suspend.body(), "actionId");

        Session aliceStepped = sessionOf(alice, true);
        HttpResponse<String> own = post(door(caseId) + "/risk-actions/" + revokeId + "/approval",
                "{\"revision\":3,\"reason\":\"mine\"}", aliceStepped.token());
        assertThat(own.statusCode()).as(own.body()).isEqualTo(403);
        assertThat(own.body()).contains("risk.SelfApprovalRefused");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.SelfApprovalRefused'"
                + " AND outcome = 'FAILED' AND target_id = '" + revokeId + "'")).isEqualTo(1);

        Session bobPassword = sessionWith(RoleName.RISK_ANALYST, false);
        HttpResponse<String> stepUp = post(door(caseId) + "/risk-actions/" + revokeId + "/approval",
                "{\"revision\":3,\"reason\":\"ok\"}", bobPassword.token());
        assertThat(stepUp.statusCode()).as(stepUp.body()).isEqualTo(403);
        assertThat(stepUp.body()).contains("identity.AssuranceRequired");

        Session bob = sessionOf(bobPassword, true);
        HttpResponse<String> revoked = post(door(caseId) + "/risk-actions/" + revokeId + "/approval",
                "{\"revision\":3,\"reason\":\"confirmed with the customer\"}", bob.token());
        assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(202);
        assertThat(revoked.body()).contains("\"status\":\"DONE\"").contains("\"outcome\":\"DONE\"")
                .contains("\"attempts\":1");
        assertThat(post("/v1/me/mfa", null, customer.token()).statusCode()).as("the customer's session ended")
                .isEqualTo(401);

        HttpResponse<String> suspended = post(door(caseId) + "/risk-actions/" + suspendId + "/approval",
                "{\"revision\":4,\"reason\":\"confirmed with the customer\"}", bob.token());
        assertThat(suspended.statusCode()).as(suspended.body()).isEqualTo(202);
        assertThat(suspended.body()).contains("\"status\":\"DONE\"").contains("\"attempts\":1");
        assertThat(scalar("SELECT status FROM identity.identity WHERE id = '" + customer.identity() + "'"))
                .isEqualTo("SUSPENDED");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'identity.IdentitySuspended'"
                + " AND target_id = '" + customer.identity() + "' AND actor_type = 'SYSTEM'"
                + " AND reason = 'RISK_CASE_ATO'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.ActionApproved'"
                + " AND target_id IN ('" + revokeId + "', '" + suspendId + "') AND actor_id = '" + bob.identity()
                + "'")).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation IN ('risk.ActionDispatched',"
                + " 'risk.ActionConcluded') AND target_id IN ('" + revokeId + "', '" + suspendId + "')"
                + " AND actor_type = 'SYSTEM'")).isEqualTo(4);
    }

    @Test
    @DisplayName("a merchant-abuse case suspends its merchant through merchant's own command (202 DONE); the refusals:"
            + " another desk 403, no session 401, a kind the case does not admit 409, an untaken case 422, an unknown"
            + " case 404")
    void theMerchantResponseAndTheRefusals() throws Exception {
        Session alice = sessionWith(RoleName.RISK_ANALYST, false);
        Merchant merchant = merchant();
        String caseId = takenCase("{\"kind\":\"MERCHANT_ABUSE\",\"subjectKind\":\"MERCHANT\",\"subjectRef\":\""
                + merchant.id().value() + "\",\"reason\":\"a refund ratio\"}", alice);
        HttpResponse<String> wrong = post(door(caseId) + "/risk-actions", "{\"revision\":1,\"kind\":"
                + "\"SUSPEND_IDENTITY\",\"reason\":\"not a party's case\"}", alice.token());
        assertThat(wrong.statusCode()).as(wrong.body()).isEqualTo(409);
        assertThat(wrong.body()).contains("risk.ActionNotProposable");
        HttpResponse<String> proposed = post(door(caseId) + "/risk-actions", "{\"revision\":1,\"kind\":"
                + "\"SUSPEND_MERCHANT\",\"reason\":\"refunds to new accounts\"}", alice.token());
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String actionId = field(proposed.body(), "actionId");
        Session bob = sessionWith(RoleName.RISK_ANALYST, true);
        HttpResponse<String> approved = post(door(caseId) + "/risk-actions/" + actionId + "/approval",
                "{\"revision\":2,\"reason\":\"confirmed\"}", bob.token());
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(202);
        assertThat(approved.body()).contains("\"status\":\"DONE\"");
        assertThat(scalar("SELECT status FROM merchant.merchant WHERE id = '" + merchant.id().value() + "'"))
                .isEqualTo("SUSPENDED");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'merchant.MerchantSuspended'"
                + " AND actor_type = 'SYSTEM' AND reason = 'RISK_CASE_MERCHANT_ABUSE'")).isGreaterThanOrEqualTo(1);

        Session officer = sessionWith(RoleName.RISK_OFFICER, false);
        String body = "{\"revision\":3,\"kind\":\"SUSPEND_MERCHANT\",\"reason\":\"again\"}";
        assertThat(post(door(caseId) + "/risk-actions", body, officer.token()).statusCode()).as("another desk")
                .isEqualTo(403);
        assertThat(post(door(caseId) + "/risk-actions", body, null).statusCode()).isEqualTo(401);
        assertThat(post(door(IDS.next().toString()) + "/risk-actions", body, alice.token()).statusCode())
                .isEqualTo(404);
        HttpResponse<String> opened = post("/v1/operator/risk/cases", "{\"kind\":\"FRAUD\",\"subjectKind\":"
                + "\"PARTY\",\"subjectRef\":\"" + IDS.next() + "\",\"reason\":\"worth a look\"}", alice.token());
        HttpResponse<String> untaken = post(door(field(opened.body(), "caseId")) + "/risk-actions",
                "{\"revision\":0,\"kind\":\"REVOKE_SESSIONS\",\"reason\":\"x\"}", alice.token());
        assertThat(untaken.statusCode()).as(untaken.body()).isEqualTo(422);
        assertThat(untaken.body()).contains("risk.CaseNotTaken");
    }

    // ------------------------------------------------------------------ fixtures

    private record Session(String token, UUID party, UUID identity, String login) {}

    private static String door(String caseId) {
        return "/v1/operator/risk/cases/" + caseId;
    }

    /** A case opened by hand with {@code body} and taken (revision 1) by {@code analyst}; its id. */
    private String takenCase(String body, Session analyst) throws Exception {
        HttpResponse<String> opened = post("/v1/operator/risk/cases", body, analyst.token());
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        String caseId = field(opened.body(), "caseId");
        HttpResponse<String> taken = post(door(caseId) + "/actions", "{\"act\":\"TAKE\",\"revision\":0}",
                analyst.token());
        assertThat(taken.statusCode()).as(taken.body()).isEqualTo(200);
        return caseId;
    }

    /** A merchant onboarded through merchant's own store (the KYB gate is onboarding's door, not this suite's). */
    private static Merchant merchant() throws Exception {
        Merchant merchant = Merchant.onboard(IDS, Clock.systemUTC(), IDS.next(), "Shop GmbH", "Shop",
                CurrencyCode.of("EUR"));
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            new JdbcMerchantStore().insert(app, merchant);
            app.commit();
        }
        return merchant;
    }

    /** A registered customer with a live session. */
    private Session customer() throws Exception {
        String login = "ra." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                + "\"password\":\"" + PASSWORD + "\"}", null).statusCode()).isEqualTo(201);
        UUID[] ids = identityOf(login);
        return sessionOf(new Session(null, ids[1], ids[0], login), false);
    }

    private Session sessionWith(RoleName role, boolean multiFactor) throws Exception {
        String login = "ra." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                + "\"password\":\"" + PASSWORD + "\"}", null).statusCode()).isEqualTo(201);
        UUID[] ids = identityOf(login);
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(ids[0]), role, IdentityId.of(ids[0]), "test fixture");
            app.commit();
        }
        return sessionOf(new Session(null, ids[1], ids[0], login), multiFactor);
    }

    /** The identity's id and its party's. */
    private static UUID[] identityOf(String login) throws Exception {
        try (Connection app = DatabaseRoles.application(); PreparedStatement read = app.prepareStatement(
                "SELECT id, party_id FROM identity.identity WHERE login_identifier = ?")) {
            read.setString(1, login);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                return new UUID[] {row.getObject("id", UUID.class), row.getObject("party_id", UUID.class)};
            }
        }
    }

    /** A fresh session of {@code who} - password only, or stepped up through a factor confirmed now. */
    private Session sessionOf(Session who, boolean multiFactor) throws Exception {
        HttpResponse<String> session = post("/v1/authentications",
                "{\"loginIdentifier\":\"" + who.login() + "\",\"password\":\"" + PASSWORD + "\"}", null);
        String token = field(session.body(), "sessionToken");
        if (!multiFactor) {
            return new Session(token, who.party(), who.identity(), who.login());
        }
        Matcher secret = Pattern.compile("secret=([A-Z2-7]+)").matcher(post("/v1/me/mfa", null, token).body());
        assertThat(secret.find()).as("the response must carry a base32 secret").isTrue();
        Sensitive<String> factor = Sensitive.of(secret.group(1));
        long step = Instant.now().getEpochSecond() / TotpParameters.current().periodSeconds();
        long confirmed = -1;
        for (long candidate : new long[] {step - 1, step, step + 1}) {
            if (post("/v1/me/mfa/confirmation", "{\"code\":\"" + codeAt(factor, candidate) + "\"}", token)
                    .statusCode() == 204) {
                confirmed = candidate;
                break;
            }
        }
        assertThat(confirmed).as("some step around this JVM's confirms the factor").isNotNegative();
        HttpResponse<String> elevated = post("/v1/authentications/mfa", "{\"code\":\"" + codeAt(factor, confirmed + 1)
                + "\"}", token);
        if (elevated.statusCode() == 401) {
            elevated = post("/v1/authentications/mfa", "{\"code\":\"" + codeAt(factor, confirmed + 2) + "\"}", token);
        }
        assertThat(elevated.statusCode()).as("the step-up: %s", elevated.body()).isEqualTo(200);
        return new Session(field(elevated.body(), "sessionToken"), who.party(), who.identity(), who.login());
    }

    private static String codeAt(Sensitive<String> secret, long step) {
        return Authenticator.codeAt(secret, TotpParameters.current(),
                Instant.ofEpochSecond(step * TotpParameters.current().periodSeconds()));
    }

    private HttpResponse<String> post(String path, String body, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .header(IdempotencyKeyHeader.NAME, UUID.randomUUID().toString())
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static long count(String sql) throws Exception {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql);
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    private static String scalar(String sql) throws Exception {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql);
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getString(1);
        }
    }

    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(body);
        assertThat(matcher.find()).as("%s in %s", name, body).isTrue();
        return matcher.group(1);
    }
}
