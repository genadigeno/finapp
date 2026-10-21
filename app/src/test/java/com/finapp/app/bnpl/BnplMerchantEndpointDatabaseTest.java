package com.finapp.app.bnpl;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.database.DateShifted;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.platform.api.IdempotencyKeyHeader;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
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
 * The merchant BNPL doors over HTTP (`P12-TSK-006`; OQ-8, {@code INV-AUD-04}): a {@code MERCHANT_ADMINISTRATOR}'s proposal
 * refused to its own proposer ({@code 409 merchant.SelfApprovalRefused}, recorded {@code FAILED}) and approved by a
 * second administrator; withdrawn by one; every act keyed per principal so a lost response replays and a reused key for
 * another act is refused; the BNPL fee schedule assigned, converging, a retaining schedule refused; the error contract's
 * negatives; and every door closed to a role without the permission, to no session and (for the acts) to no key -
 * nothing written by a refused caller.
 */
@DateShifted
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("the merchant BNPL doors (P12-TSK-006)")
class BnplMerchantEndpointDatabaseTest {

    private static final IdGenerator IDS = new IdGenerator(Clock.systemUTC(), new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    @Test
    @DisplayName("two administrators enable a merchant; the proposer is refused their own approval (FAILED); one"
            + " withdraws it; every keyed act replays and a key reused for another act is refused")
    void twoAdministratorsEnable() throws Exception {
        String first = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        String second = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        String merchant = merchant();
        String doors = "/v1/operator/merchants/" + merchant + "/bnpl-enablement";

        String proposalKey = someKey();
        HttpResponse<String> proposed = post(doors, reason("contract signed"), first, proposalKey);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        String id = field(proposed.body(), "id");
        assertThat(field(proposed.body(), "status")).isEqualTo("PROPOSED");
        assertThat(proposed.body()).doesNotContain("contract signed");
        assertThat(post(doors, reason("contract signed"), first, proposalKey).body())
                .as("the same key replays the receipt").isEqualTo(proposed.body());
        assertThat(post(doors + "/" + id + "/approval", reason("contract signed"), first, proposalKey).statusCode())
                .as("one key, another act: refused").isEqualTo(409);

        HttpResponse<String> pending = post(doors, reason("again"), second, someKey());
        assertThat(pending.statusCode()).isEqualTo(409);
        assertThat(pending.body()).contains("merchant.BnplEnablementPending");

        HttpResponse<String> self = post(doors + "/" + id + "/approval", reason("my own"), first, someKey());
        assertThat(self.statusCode()).isEqualTo(409);
        assertThat(self.body()).contains("merchant.SelfApprovalRefused");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'merchant.BnplEnablementApprovalRefused' AND outcome = 'FAILED' AND target_id = ?", id))
                .as("recorded FAILED, once (the Phase 11 gate's R4)").isEqualTo(1);

        String approvalKey = someKey();
        HttpResponse<String> approved = post(doors + "/" + id + "/approval", reason("reviewed"), second, approvalKey);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(field(approved.body(), "status")).isEqualTo("ACTIVE");
        assertThat(post(doors + "/" + id + "/approval", reason("reviewed"), second, approvalKey).body())
                .isEqualTo(approved.body());
        HttpResponse<String> late = post(doors + "/" + id + "/rejection", reason("too late"), first, someKey());
        assertThat(late.statusCode()).isEqualTo(409);
        assertThat(late.body()).contains("merchant.BnplEnablementNotOpen");

        HttpResponse<String> withdrawn = post(doors + "/" + id + "/withdrawal", reason("risk"), first, someKey());
        assertThat(withdrawn.statusCode()).as(withdrawn.body()).isEqualTo(200);
        assertThat(field(withdrawn.body(), "status")).isEqualTo("WITHDRAWN");
        assertThat(post(doors, reason("re-enable"), second, someKey()).statusCode())
                .as("a withdrawn enablement frees the merchant for a new proposal").isEqualTo(201);
    }

    @Test
    @DisplayName("the BNPL fee schedule: assigned, converging, a retaining schedule refused 422, unknown product 404")
    void theFeeScheduleIsAssigned() throws Exception {
        String administrator = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        String merchant = merchant();
        String returning = feeSchedule(administrator, "RETURNED");
        String retaining = feeSchedule(administrator, "RETAINED");
        String door = "/v1/operator/merchants/" + merchant + "/bnpl-fee-schedules/BNPL_PAY_IN_4";

        HttpResponse<String> assigned = put(door, assignment(returning), administrator);
        assertThat(assigned.statusCode()).as(assigned.body()).isEqualTo(200);
        assertThat(field(assigned.body(), "feeScheduleId")).isEqualTo(returning);
        assertThat(put(door, assignment(returning), administrator).statusCode()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM merchant.merchant_bnpl_fee_schedule_event WHERE merchant_id = ?::uuid",
                merchant)).as("the converging retry recorded nothing").isEqualTo(1);

        HttpResponse<String> retained = put(door, assignment(retaining), administrator);
        assertThat(retained.statusCode()).isEqualTo(422);
        assertThat(retained.body()).contains("merchant.BnplScheduleNotReturning");
        assertThat(put("/v1/operator/merchants/" + merchant + "/bnpl-fee-schedules/MORTGAGE", assignment(returning),
                administrator).statusCode()).isEqualTo(404);
        assertThat(put("/v1/operator/merchants/" + UUID.randomUUID() + "/bnpl-fee-schedules/BNPL_PAY_IN_4",
                assignment(returning), administrator).statusCode()).isEqualTo(404);
        assertThat(put(door, assignment(IDS.next().toString()), administrator).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("every door is closed to a role without the permission, to no session, every act to no key - and the"
            + " unknown, the malformed and the closed are one answer each")
    void theDoorsAreClosed() throws Exception {
        String officer = sessionWith(RoleName.BNPL_OFFICER);
        String administrator = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        String merchant = merchant();
        String doors = "/v1/operator/merchants/" + merchant + "/bnpl-enablement";
        String some = "/" + UUID.randomUUID();
        for (String path : new String[] {doors, doors + some + "/approval", doors + some + "/rejection",
                doors + some + "/withdrawal"}) {
            assertThat(post(path, reason("x"), officer, someKey()).statusCode()).as("BNPL_OFFICER " + path)
                    .isEqualTo(403);
            assertThat(post(path, reason("x"), null, someKey()).statusCode()).as("no session " + path).isEqualTo(401);
            HttpResponse<String> unkeyed = post(path, reason("x"), administrator, null);
            assertThat(unkeyed.statusCode()).as("no key " + path).isEqualTo(422);
            assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");
        }
        String fee = "/v1/operator/merchants/" + merchant + "/bnpl-fee-schedules/BNPL_PAY_IN_4";
        assertThat(put(fee, assignment(UUID.randomUUID().toString()), officer).statusCode()).isEqualTo(403);
        assertThat(put(fee, assignment(UUID.randomUUID().toString()), null).statusCode()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM merchant.merchant_bnpl_enablement WHERE merchant_id = ?::uuid",
                merchant)).as("no refused caller wrote anything").isZero();

        assertThat(post(doors + some + "/approval", reason("x"), administrator, someKey()).statusCode()).isEqualTo(404);
        // Another merchant's enablement, addressed under this merchant's path: the unknown one's 404, nothing moved.
        String other = merchant();
        String foreign = field(post("/v1/operator/merchants/" + other + "/bnpl-enablement", reason("x"), administrator,
                someKey()).body(), "id");
        String reviewer = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        for (String act : new String[] {"/approval", "/rejection", "/withdrawal"}) {
            assertThat(post(doors + "/" + foreign + act, reason("x"), reviewer, someKey()).statusCode())
                    .as("another merchant's enablement " + act).isEqualTo(404);
        }
        assertThat(count("SELECT count(*) FROM merchant.merchant_bnpl_enablement WHERE merchant_id = ?::uuid"
                + " AND status = 'PROPOSED'", other)).as("the foreign enablement did not move").isEqualTo(1);
        assertThat(post(doors + "/not-a-uuid/approval", reason("x"), administrator, someKey()).statusCode())
                .isEqualTo(404);
        assertThat(post("/v1/operator/merchants/not-a-uuid/bnpl-enablement", reason("x"), administrator, someKey())
                .statusCode()).isEqualTo(404);
        assertThat(post("/v1/operator/merchants/" + IDS.next() + "/bnpl-enablement", reason("x"), administrator,
                someKey()).statusCode()).isEqualTo(404);
        assertThat(post(doors, "{}", administrator, someKey()).statusCode()).as("a reason is required").isEqualTo(422);
        assertThat(post(doors, "{\"reason\":\"x\",\"rate\":\"0.01\"}", administrator, someKey()).statusCode())
                .as("a closed body").isEqualTo(422);

        String closed = merchant();
        try (Connection migrator = DatabaseRoles.migrator();
                PreparedStatement close = migrator.prepareStatement(
                        "UPDATE merchant.merchant SET status = 'CLOSED', status_changed_at = now() WHERE id = ?")) {
            close.setObject(1, UUID.fromString(closed));
            close.executeUpdate();
        }
        HttpResponse<String> refused = post("/v1/operator/merchants/" + closed + "/bnpl-enablement", reason("x"),
                administrator, someKey());
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("merchant.IllegalTransition");
    }

    // -----------------------------------------------------------------

    private String feeSchedule(String bearer, String refundFeePolicy) throws Exception {
        HttpResponse<String> created = post("/v1/operator/fee-schedules",
                "{\"name\":\"bnpl " + UUID.randomUUID() + "\",\"currency\":\"EUR\"}", bearer, someKey());
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String id = field(created.body(), "feeScheduleId");
        HttpResponse<String> version = post("/v1/operator/fee-schedules/" + id + "/versions",
                "{\"rate\":0.0299,\"fixedAmountMinor\":0,\"roundingPolicy\":\"HALF_EVEN\",\"refundFeePolicy\":\""
                        + refundFeePolicy + "\",\"reason\":\"bnpl pricing\"}", bearer, someKey());
        assertThat(version.statusCode()).as(version.body()).isIn(200, 201);
        return id;
    }

    private static String merchant() throws Exception {
        UUID id = IDS.next();
        try (Connection app = DatabaseRoles.application();
                PreparedStatement insert = app.prepareStatement("INSERT INTO merchant.merchant (id, party_ref,"
                        + " legal_name, display_name, settlement_currency, status, created_at, status_changed_at)"
                        + " VALUES (?, ?, 'Acme GmbH', 'Acme', 'EUR', 'ACTIVE', now(), now())")) {
            insert.setObject(1, id);
            insert.setObject(2, UUID.randomUUID());
            insert.executeUpdate();
        }
        return id.toString();
    }

    private static String reason(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    private static String assignment(String feeScheduleId) {
        return "{\"feeScheduleId\":\"" + feeScheduleId + "\",\"reason\":\"contract pricing\"}";
    }

    private static long count(String sql, String parameter) throws Exception {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement select = migrator.prepareStatement(sql)) {
            select.setString(1, parameter);
            try (ResultSet row = select.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }

    private HttpResponse<String> put(String path, String body, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /** A signed-in session holding {@code role}, or no role at all when it is {@code null}. */
    private String sessionWith(RoleName role) throws Exception {
        String login = "bt." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(post("/v1/registrations",
                        "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                                + "\"password\":\"" + PASSWORD + "\"}",
                        null, someKey()).statusCode())
                .isEqualTo(201);
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

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }
}
