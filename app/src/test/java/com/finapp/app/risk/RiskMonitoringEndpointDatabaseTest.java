package com.finapp.app.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.risk.MonitoringWatermark;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The risk officer's monitoring door, end to end (`P13-TSK-019`; {@code PHASE_13_PLAN.md} section 9; bnpl's
 * {@code BnplTermsEndpointDatabaseTest} shape): a reprocessing ordered {@code 202} with its receipt and replayed on the
 * same key; the reason required ({@code 422 risk.ReasonRequired}); a point ahead of the watermark refused
 * ({@code 422 risk.ReprocessingNotBehind}); another desk refused ({@code 403}); the act audited
 * {@code risk.ReprocessingOrdered} with the reason and never the reason in the summary.
 *
 * <p>Written and owed under C5 (the standing criterion-7 deviation): the database tier is not run per task.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the risk monitoring reprocessing door (P13-TSK-019)")
class RiskMonitoringEndpointDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";
    private static final String DOOR = "/v1/operator/risk/monitoring/reprocessing";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    @Test
    @Order(1)
    @DisplayName("an officer orders a reprocessing from an hour ago: 202 with the receipt, the same key replays it, the"
            + " watermark stands at the millisecond's floor, and the act is audited with the reason - the summary"
            + " carrying the positions only")
    void ordered() throws Exception {
        String officer = sessionWith(RoleName.RISK_OFFICER);
        // At or before the watermark, as the door requires: "an hour ago" held only once another suite's sweep had moved
        // the watermark past it - a fresh container's sits at the floor, and the door rightly answered 422
        // ReprocessingNotBehind (P13-TST-001's first run of this owed suite).
        long watermark = count("SELECT last_sequence FROM risk.monitoring_watermark WHERE source = 'ledger.journal'");
        long from = Math.min(Instant.now().minus(Duration.ofHours(1)).toEpochMilli(), watermark);
        String key = someKey();

        HttpResponse<String> ordered = post(DOOR, body(from, "policy v2 applied to history"), officer, key);
        assertThat(ordered.statusCode()).as(ordered.body()).isEqualTo(202);
        assertThat(field(ordered.body(), "source")).isEqualTo(MonitoringWatermark.LEDGER_JOURNAL);
        assertThat(ordered.body()).contains("\"fromSequence\":" + from).doesNotContain("policy v2");
        assertThat(post(DOOR, body(from, "policy v2 applied to history"), officer, key).body())
                .as("the same key replays the receipt").isEqualTo(ordered.body());

        try (Connection app = DatabaseRoles.application(); PreparedStatement read = app.prepareStatement(
                "SELECT last_entry_id::text, last_sequence FROM risk.monitoring_watermark WHERE source = ?")) {
            read.setString(1, MonitoringWatermark.LEDGER_JOURNAL);
            try (ResultSet row = read.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo(MonitoringWatermark.positionAt(from).toString());
                assertThat(row.getLong(2)).isEqualTo(from);
            }
        }
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.ReprocessingOrdered'"
                + " AND outcome = 'SUCCEEDED' AND target_type = 'monitoring_watermark' AND target_id = 'ledger.journal'"
                + " AND reason = 'policy v2 applied to history' AND change_summary NOT LIKE '%policy v2%'"
                + " AND change_summary LIKE 'monitoring reprocessing ordered from sequence " + from + ";%'"))
                .as("audited once with the reason; the summary the positions only").isEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("the refusals: no reason 422 risk.ReasonRequired; a point ahead of the watermark 422"
            + " risk.ReprocessingNotBehind; an analyst 403; no key 422 api.IdempotencyKeyRequired; an undeclared field"
            + " 422 api.ValidationFailed - and none of them is audited or moves the watermark")
    void theRefusals() throws Exception {
        String officer = sessionWith(RoleName.RISK_OFFICER);
        String analyst = sessionWith(RoleName.RISK_ANALYST);
        long auditedBefore = count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.ReprocessingOrdered'");
        long sequenceBefore = count("SELECT last_sequence FROM risk.monitoring_watermark WHERE source = 'ledger.journal'");

        HttpResponse<String> unreasoned = post(DOOR, body(0, ""), officer, someKey());
        assertThat(unreasoned.statusCode()).as(unreasoned.body()).isEqualTo(422);
        assertThat(unreasoned.body()).contains("risk.ReasonRequired");

        HttpResponse<String> instrument = post(DOOR, body(0, "card 4111 1111 1111 1111 seen"), officer, someKey());
        assertThat(instrument.statusCode()).isEqualTo(422);
        assertThat(instrument.body()).contains("risk.ReasonRequired");

        long ahead = Instant.now().plus(Duration.ofHours(1)).toEpochMilli();
        HttpResponse<String> notBehind = post(DOOR, body(ahead, "skip ahead"), officer, someKey());
        assertThat(notBehind.statusCode()).as(notBehind.body()).isEqualTo(422);
        assertThat(notBehind.body()).contains("risk.ReprocessingNotBehind");

        HttpResponse<String> wrongDesk = post(DOOR, body(0, "an analyst's try"), analyst, someKey());
        assertThat(wrongDesk.statusCode()).isEqualTo(403);

        // A missing key is the platform's 422 api.IdempotencyKeyRequired (@RequiresIdempotencyKey), never a 400
        // (P13-TST-001's first run of this owed suite).
        HttpResponse<String> unkeyed = post(DOOR, body(0, "no key"), officer, null);
        assertThat(unkeyed.statusCode()).as(unkeyed.body()).isEqualTo(422);
        assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");

        HttpResponse<String> unknownField = post(DOOR, "{\"fromSequence\":0,\"reason\":\"x\",\"extra\":1}", officer,
                someKey());
        // An undeclared field is @ClosedBody's 422 api.ValidationFailed, never a 400 (P13-TST-001's first run of this owed
        // suite).
        assertThat(unknownField.statusCode()).as("closed body: " + unknownField.body()).isEqualTo(422);
        assertThat(unknownField.body()).contains("api.ValidationFailed");

        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'risk.ReprocessingOrdered'"))
                .isEqualTo(auditedBefore);
        assertThat(count("SELECT last_sequence FROM risk.monitoring_watermark WHERE source = 'ledger.journal'"))
                .isEqualTo(sequenceBefore);
    }

    // ------------------------------------------------------------------ fixtures

    private static String body(long fromSequence, String reason) {
        return "{\"fromSequence\":" + fromSequence + ",\"reason\":\"" + reason + "\"}";
    }

    private String sessionWith(RoleName role) throws Exception {
        String login = "rm." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post("/v1/registrations",
                        "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                                + "\"password\":\"" + PASSWORD + "\"}",
                        null, someKey()).statusCode())
                .isEqualTo(201);
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
        HttpResponse<String> session = post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}", null, someKey());
        return field(session.body(), "sessionToken");
    }

    private HttpResponse<String> post(String path, String body, String bearer, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (key != null) {
            request.header(IdempotencyKeyHeader.NAME, key);
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static long count(String sql) throws Exception {
        try (Connection app = DatabaseRoles.application(); PreparedStatement select = app.prepareStatement(sql);
                ResultSet row = select.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(body);
        assertThat(matcher.find()).as("%s in %s", name, body).isTrue();
        return matcher.group(1);
    }
}
