package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authenticator;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.identity.TotpParameters;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
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
 * The screening list doors, end to end (`P13-TSK-022`; {@code PHASE_13_PLAN.md} sections 9, 11, 12.10): a risk officer -
 * {@code CUSTOMER}-typed, as the session establishes every operator - records a list version ({@code 201}); the recorder's
 * own activation {@code 403 risk.SelfApprovalRefused} recorded {@code FAILED}; a second officer's password-only session
 * {@code 403 identity.AssuranceRequired}; the second officer's {@code MULTI_FACTOR} activation {@code 202} with the
 * rescreening's enqueue due and no permit written by the door; a second activation {@code 409 risk.PolicyStale}; the
 * refusals - an analyst {@code 403} (another desk), no session {@code 401}, an unknown version {@code 404}, a list outside
 * the vocabulary {@code 422 api.ValidationFailed} (the bean validation), no reason {@code 422 risk.ReasonRequired}. Written and owed under C5.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the screening list doors: persons, four eyes, MFA, the rescreening left to the sweep (P13-TSK-022)")
class ScreeningListApiTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String LISTS = "/v1/operator/risk/screening-lists";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    @Test
    @DisplayName("recorded 201 by a CUSTOMER-typed officer, the recorder's activation 403 recorded, a password session 403,"
            + " a second MFA officer 202 ACTIVE with the enqueue due and no permit by the door, a second activation 409")
    void theListThroughItsDoors() throws Exception {
        String label = "v-" + UUID.randomUUID().toString().substring(0, 12);
        Session recorder = sessionWith(RoleName.RISK_OFFICER, true);
        HttpResponse<String> recorded = post(LISTS, "{\"list\":\"SANCTIONS\",\"version\":\"" + label
                + "\",\"reason\":\"the provider published a new edition\"}", recorder.token());
        assertThat(recorded.statusCode()).as(recorded.body()).isEqualTo(201);
        assertThat(recorded.body()).contains("\"status\":\"RECORDED\"").contains("\"version\":\"" + label + "\"")
                .contains("\"list\":\"SANCTIONS\"");
        String id = field(recorded.body(), "id");
        assertThat(scalar("SELECT recorded_by_type FROM risk.screening_list_version WHERE id = '" + id + "'"))
                .isEqualTo("CUSTOMER");

        HttpResponse<String> own = post(LISTS + "/" + id + "/activation", "{\"reason\":\"mine\"}", recorder.token());
        assertThat(own.statusCode()).as(own.body()).isEqualTo(403);
        assertThat(own.body()).contains("risk.SelfApprovalRefused");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.SelfApprovalRefused'"
                + " AND outcome = 'FAILED' AND target_id = '" + id + "'")).isEqualTo(1);

        Session second = sessionWith(RoleName.RISK_OFFICER, false);
        HttpResponse<String> stepUp = post(LISTS + "/" + id + "/activation", "{\"reason\":\"in force\"}", second.token());
        assertThat(stepUp.statusCode()).as(stepUp.body()).isEqualTo(403);
        assertThat(stepUp.body()).contains("identity.AssuranceRequired");
        HttpResponse<String> activated = post(LISTS + "/" + id + "/activation", "{\"reason\":\"the edition is in force\"}",
                sessionOf(second, true).token());
        assertThat(activated.statusCode()).as(activated.body()).isEqualTo(202);
        assertThat(activated.body()).contains("\"status\":\"ACTIVE\"");
        assertThat(scalar("SELECT activated_by_type FROM risk.screening_list_version WHERE id = '" + id + "'"))
                .isEqualTo("CUSTOMER");
        assertThat(count("SELECT count(*) FROM risk.screening_list_version WHERE id = '" + id + "' AND enqueue_next_at"
                + " IS NOT NULL")).as("the enqueue is the sweep's, due").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM risk.rescreening_permit WHERE list_version_id = '" + id + "'"))
                .as("nothing enqueued in the door's transaction").isZero();
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation IN ('risk.ListVersionRecorded',"
                + " 'risk.ListVersionActivated') AND target_id = '" + id + "' AND actor_type = 'CUSTOMER'")).isEqualTo(2);

        Session third = sessionWith(RoleName.RISK_OFFICER, true);
        HttpResponse<String> again = post(LISTS + "/" + id + "/activation", "{\"reason\":\"again\"}", third.token());
        assertThat(again.statusCode()).as(again.body()).isEqualTo(409);
        assertThat(again.body()).contains("risk.PolicyStale");
    }

    @Test
    @DisplayName("the refusals: an analyst 403, no session 401, an unknown version 404, a list outside the vocabulary 422 api.ValidationFailed,"
            + " no reason 422")
    void theRefusals() throws Exception {
        String body = "{\"list\":\"PEP\",\"version\":\"v1\",\"reason\":\"r\"}";
        Session analyst = sessionWith(RoleName.RISK_ANALYST, true);
        assertThat(post(LISTS, body, analyst.token()).statusCode()).as("another desk").isEqualTo(403);
        assertThat(post(LISTS, body, null).statusCode()).isEqualTo(401);
        Session officer = sessionWith(RoleName.RISK_OFFICER, true);
        assertThat(post(LISTS + "/" + IDS.next() + "/activation", "{\"reason\":\"r\"}", officer.token()).statusCode())
                .isEqualTo(404);
        assertThat(post(LISTS, "{\"list\":\"WATCHLIST\",\"version\":\"v1\",\"reason\":\"r\"}", officer.token())
                .statusCode()).isEqualTo(422);
        HttpResponse<String> noReason = post(LISTS, "{\"list\":\"PEP\",\"version\":\"v-" + UUID.randomUUID()
                .toString().substring(0, 8) + "\"}", officer.token());
        assertThat(noReason.statusCode()).as(noReason.body()).isEqualTo(422);
        assertThat(noReason.body()).contains("risk.ReasonRequired");
    }

    // ------------------------------------------------------------------ fixtures (ReportApiTest's)

    private record Session(String token, UUID party, UUID identity, String login) {}

    private Session sessionWith(RoleName role, boolean multiFactor) throws Exception {
        String login = "sl." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
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
