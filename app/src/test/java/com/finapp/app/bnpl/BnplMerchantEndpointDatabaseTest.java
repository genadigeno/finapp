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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * nothing written by a refused caller. Since `P17-TSK-116` the fee assignment is four eyes (ADR-C135 decision 3): an
 * administrator proposes, keyed; a {@code FINANCIAL_CONTROLLER} who did not propose it approves, the pointer moving once,
 * or rejects - ten approvers racing, one winner; the enablement is read by its identifier under its merchant.
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
    @DisplayName("P17-TSK-116: the BNPL fee schedule by four eyes - proposed and keyed (a retry replays), nothing moved; the"
            + " proposer refused its own decision; approved by a FINANCIAL_CONTROLLER, the pointer moving once and the retried"
            + " approval replaying; converging with nothing proposed; a retaining schedule refused 422, unknown product 404")
    void theFeeScheduleIsAssigned() throws Exception {
        String administrator = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        String controller = sessionWith(RoleName.FINANCIAL_CONTROLLER);
        String merchant = merchant();
        String returning = feeSchedule(administrator, "RETURNED");
        String retaining = feeSchedule(administrator, "RETAINED");
        String door = "/v1/operator/merchants/" + merchant + "/bnpl-fee-schedules/BNPL_PAY_IN_4";

        String proposalKey = someKey();
        HttpResponse<String> proposed = put(door, assignment(returning), administrator, proposalKey);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(200);
        assertThat(field(proposed.body(), "status")).isEqualTo("PROPOSED");
        assertThat(field(proposed.body(), "feeScheduleId")).isEqualTo(returning);
        assertThat(proposed.body()).doesNotContain("contract pricing");
        String proposal = field(proposed.body(), "proposalId");
        assertThat(put(door, assignment(returning), administrator, proposalKey).body())
                .as("the same key replays the proposal").isEqualTo(proposed.body());
        assertThat(count("SELECT count(*) FROM merchant.merchant_bnpl_fee_schedule_event WHERE merchant_id = ?::uuid",
                merchant)).as("a proposal moves nothing").isZero();
        HttpResponse<String> second = put(door, assignment(returning), administrator, someKey());
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).contains("merchant.BnplFeeProposalAlreadyOpen");

        String decisions = "/v1/operator/bnpl-fee-schedule-proposals/" + proposal;
        assertThat(post(decisions + "/approval", reason("mine"), administrator, someKey()).statusCode())
                .as("the proposer's role holds no FEE_SCHEDULE_APPROVE").isEqualTo(403);

        String approvalKey = someKey();
        HttpResponse<String> approved = post(decisions + "/approval", reason("reviewed"), controller, approvalKey);
        assertThat(approved.statusCode()).as(approved.body()).isEqualTo(200);
        assertThat(field(approved.body(), "status")).isEqualTo("APPROVED");
        assertThat(post(decisions + "/approval", reason("reviewed"), controller, approvalKey).body())
                .as("the retried approval replays").isEqualTo(approved.body());
        assertThat(count("SELECT count(*) FROM merchant.merchant_bnpl_fee_schedule_event WHERE merchant_id = ?::uuid",
                merchant)).as("the pointer moved once").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM merchant.merchant_bnpl_fee_schedule WHERE merchant_id = ?::uuid"
                + " AND fee_schedule_id = '" + returning + "'::uuid", merchant)).isEqualTo(1);
        HttpResponse<String> late = post(decisions + "/rejection", reason("too late"), controller, someKey());
        assertThat(late.statusCode()).isEqualTo(409);
        assertThat(late.body()).contains("merchant.BnplFeeProposalAlreadyDecided");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'merchant.BnplFeeAssignmentApproved' AND target_id = ?", merchant)).isEqualTo(1);

        HttpResponse<String> converged = put(door, assignment(returning), administrator, someKey());
        assertThat(converged.statusCode()).as(converged.body()).isEqualTo(200);
        assertThat(field(converged.body(), "status")).as("already there: nothing proposed").isEqualTo("ASSIGNED");
        assertThat(count("SELECT count(*) FROM merchant.bnpl_fee_assignment_proposal WHERE merchant_id = ?::uuid",
                merchant)).isEqualTo(1);

        HttpResponse<String> retained = put(door, assignment(retaining), administrator, someKey());
        assertThat(retained.statusCode()).isEqualTo(422);
        assertThat(retained.body()).contains("merchant.BnplScheduleNotReturning");
        assertThat(put("/v1/operator/merchants/" + merchant + "/bnpl-fee-schedules/MORTGAGE", assignment(returning),
                administrator, someKey()).statusCode()).isEqualTo(404);
        assertThat(put("/v1/operator/merchants/" + UUID.randomUUID() + "/bnpl-fee-schedules/BNPL_PAY_IN_4",
                assignment(returning), administrator, someKey()).statusCode()).isEqualTo(404);
        assertThat(put(door, assignment(IDS.next().toString()), administrator, someKey()).statusCode()).isEqualTo(404);
        assertThat(post("/v1/operator/bnpl-fee-schedule-proposals/" + IDS.next() + "/approval", reason("x"), controller,
                someKey()).statusCode()).as("an unknown proposal").isEqualTo(404);
    }

    @Test
    @DisplayName("P17-TSK-116: a fee assignment rejected moves nothing; ten controllers racing to approve one proposal - and"
            + " an approval racing a rejection - make one winner, the pointer moved once; ten proposers racing one product"
            + " make one proposal")
    void theFeeAssignmentRacesHaveOneWinner() throws Exception {
        String administrator = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        String merchant = merchant();
        String returning = feeSchedule(administrator, "RETURNED");
        String door = "/v1/operator/merchants/" + merchant + "/bnpl-fee-schedules/BNPL_PAY_IN_4";
        List<String> controllers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            controllers.add(sessionWith(RoleName.FINANCIAL_CONTROLLER));
        }

        String rejectedProposal = field(put(door, assignment(returning), administrator, someKey()).body(), "proposalId");
        HttpResponse<String> rejected = post("/v1/operator/bnpl-fee-schedule-proposals/" + rejectedProposal + "/rejection",
                reason("not agreed"), controllers.get(0), someKey());
        assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(200);
        assertThat(field(rejected.body(), "status")).isEqualTo("REJECTED");
        assertThat(count("SELECT count(*) FROM merchant.merchant_bnpl_fee_schedule_event WHERE merchant_id = ?::uuid",
                merchant)).as("a rejection moves nothing").isZero();

        String proposal = field(put(door, assignment(returning), administrator, someKey()).body(), "proposalId");
        List<Integer> statuses = raced(controllers.stream().map(bearer -> (Callable<Integer>) () -> putOrPostRetryingShed(
                "/v1/operator/bnpl-fee-schedule-proposals/" + proposal + "/approval", reason("reviewed"), bearer,
                someKey()).statusCode()).toList());
        assertThat(statuses).as("one winner").containsOnlyOnce(200);
        assertThat(statuses).filteredOn(status -> status != 200).allMatch(status -> status == 409);
        assertThat(count("SELECT count(*) FROM merchant.merchant_bnpl_fee_schedule_event WHERE merchant_id = ?::uuid",
                merchant)).as("the pointer moved once").isEqualTo(1);

        String other = merchant();
        String otherSchedule = feeSchedule(administrator, "RETURNED");
        String contested = field(put("/v1/operator/merchants/" + other + "/bnpl-fee-schedules/BNPL_PAY_IN_N",
                assignment(otherSchedule), administrator, someKey()).body(), "proposalId");
        List<Integer> decided = raced(List.of(
                () -> putOrPostRetryingShed("/v1/operator/bnpl-fee-schedule-proposals/" + contested + "/approval",
                        reason("yes"), controllers.get(1), someKey()).statusCode(),
                () -> putOrPostRetryingShed("/v1/operator/bnpl-fee-schedule-proposals/" + contested + "/rejection",
                        reason("no"), controllers.get(2), someKey()).statusCode()));
        assertThat(decided).as("an approval racing a rejection: one winner").containsOnlyOnce(200).contains(409);
        assertThat(count("SELECT count(*) FROM merchant.bnpl_fee_assignment_proposal WHERE merchant_id = ?::uuid"
                + " AND status <> 'PROPOSED'", other)).isEqualTo(1);

        String third = merchant();
        String thirdSchedule = feeSchedule(administrator, "RETURNED");
        List<String> proposers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            proposers.add(i % 2 == 0 ? administrator : sessionWith(RoleName.MERCHANT_ADMINISTRATOR));
        }
        List<Integer> proposals = raced(proposers.stream().map(bearer -> (Callable<Integer>) () -> putRetryingShed(
                "/v1/operator/merchants/" + third + "/bnpl-fee-schedules/BNPL_PAY_IN_4", assignment(thirdSchedule), bearer,
                someKey()).statusCode()).toList());
        assertThat(proposals).as("one proposal").containsOnlyOnce(200);
        assertThat(proposals).filteredOn(status -> status != 200).allMatch(status -> status == 409);
        assertThat(count("SELECT count(*) FROM merchant.bnpl_fee_assignment_proposal WHERE merchant_id = ?::uuid",
                third)).isEqualTo(1);
    }

    @Test
    @DisplayName("P17-TSK-116: merchant V021 for every writer - a proposal decided by its proposer refused by the four-eyes"
            + " CHECK, no DELETE for the application, a decided proposal is history even for the migrator")
    void v021HoldsForEveryWriter() throws Exception {
        String administrator = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        String merchant = merchant();
        String returning = feeSchedule(administrator, "RETURNED");
        String proposal = field(put("/v1/operator/merchants/" + merchant + "/bnpl-fee-schedules/BNPL_PAY_IN_4",
                assignment(returning), administrator, someKey()).body(), "proposalId");
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            assertThat(sqlState(app, "UPDATE merchant.bnpl_fee_assignment_proposal SET status = 'APPROVED', decided_by ="
                    + " proposed_by, decided_by_type = 'CUSTOMER', decided_at = proposed_at, decision_reason = 'mine',"
                    + " decision_correlation_id = 'c' WHERE id = '" + proposal + "'"))
                    .as("four eyes for every writer (INV-AUD-04)").isEqualTo("23514");
            assertThat(sqlState(app, "DELETE FROM merchant.bnpl_fee_assignment_proposal WHERE id = '" + proposal + "'"))
                    .as("never deleted").isEqualTo("42501");
        }
        assertThat(post("/v1/operator/bnpl-fee-schedule-proposals/" + proposal + "/rejection", reason("not agreed"),
                sessionWith(RoleName.FINANCIAL_CONTROLLER), someKey()).statusCode()).isEqualTo(200);
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            assertThat(sqlState(migrator, "UPDATE merchant.bnpl_fee_assignment_proposal SET decision_reason = 'rewritten'"
                    + " WHERE id = '" + proposal + "'")).as("a decided proposal is history").isEqualTo("P0001");
        }
    }

    /** The SQLSTATE {@code sql} is refused with, the transaction rolled back after it. */
    private static String sqlState(Connection connection, String sql) throws Exception {
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.execute(sql);
            connection.rollback();
            return "admitted";
        } catch (java.sql.SQLException refused) {
            connection.rollback();
            return refused.getSQLState();
        }
    }

    @Test
    @DisplayName("P17-TSK-116: the enablement read by its identifier under its merchant - its proposer named, never a reason;"
            + " another merchant's path, the unknown and the malformed are one 404; a role without MERCHANT_ADMINISTER 403")
    void theEnablementIsReadUnderItsMerchant() throws Exception {
        String administrator = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        String merchant = merchant();
        String doors = "/v1/operator/merchants/" + merchant + "/bnpl-enablement";
        String id = field(post(doors, reason("contract signed"), administrator, someKey()).body(), "id");

        HttpResponse<String> read = get(doors + "/" + id, administrator);
        assertThat(read.statusCode()).as(read.body()).isEqualTo(200);
        assertThat(field(read.body(), "status")).isEqualTo("PROPOSED");
        assertThat(field(read.body(), "proposedBy")).isNotBlank();
        assertThat(read.body()).doesNotContain("contract signed");

        assertThat(get("/v1/operator/merchants/" + merchant() + "/bnpl-enablement/" + id, administrator).statusCode())
                .as("another merchant's path").isEqualTo(404);
        assertThat(get(doors + "/" + IDS.next(), administrator).statusCode()).isEqualTo(404);
        assertThat(get(doors + "/not-a-uuid", administrator).statusCode()).isEqualTo(404);
        assertThat(get(doors + "/" + id, sessionWith(RoleName.BNPL_OFFICER)).statusCode()).isEqualTo(403);
        assertThat(get(doors + "/" + id, null).statusCode()).isEqualTo(401);
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
        assertThat(put(fee, assignment(UUID.randomUUID().toString()), officer, someKey()).statusCode()).isEqualTo(403);
        assertThat(put(fee, assignment(UUID.randomUUID().toString()), null, someKey()).statusCode()).isEqualTo(401);
        HttpResponse<String> unkeyedFee = put(fee, assignment(UUID.randomUUID().toString()), administrator, null);
        assertThat(unkeyedFee.statusCode()).as("P17-TSK-116: the fee proposal is keyed").isEqualTo(422);
        assertThat(unkeyedFee.body()).contains("api.IdempotencyKeyRequired");
        String decision = "/v1/operator/bnpl-fee-schedule-proposals/" + IDS.next();
        for (String path : new String[] {decision + "/approval", decision + "/rejection"}) {
            assertThat(post(path, reason("x"), administrator, someKey()).statusCode()).as("FEE_ADMINISTER " + path)
                    .isEqualTo(403);
            assertThat(post(path, reason("x"), null, someKey()).statusCode()).as("no session " + path).isEqualTo(401);
        }
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

    private HttpResponse<String> put(String path, String body, String bearer, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body));
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

    /**
     * A signed-in session holding {@code role} - stepped up to {@code MULTI_FACTOR}, which every operator act declares
     * (`P17-TSK-102`) - or a password session with no role at all when it is {@code null}.
     */
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
        String token = field(session.body(), "sessionToken");
        return role == null ? token : com.finapp.app.session.OperatorStepUp.elevated(token, this::post);
    }

    private HttpResponse<String> post(String path, String body, String bearer, String key) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
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

    private HttpResponse<String> get(String path, String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .GET();
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    /** A racer's POST, a shed one ({@code 503 api.Overloaded}) retried under the same key - the memory-starved box. */
    private HttpResponse<String> putOrPostRetryingShed(String path, String body, String bearer, String key)
            throws Exception {
        HttpResponse<String> answer = post(path, body, bearer, key);
        for (int attempt = 0; attempt < 50 && answer.statusCode() == 503; attempt++) {
            Thread.sleep(100L + (long) (Math.random() * 200));
            answer = post(path, body, bearer, key);
        }
        return answer;
    }

    /** A racer's PUT, a shed one retried under the same key. */
    private HttpResponse<String> putRetryingShed(String path, String body, String bearer, String key) throws Exception {
        HttpResponse<String> answer = put(path, body, bearer, key);
        for (int attempt = 0; attempt < 50 && answer.statusCode() == 503; attempt++) {
            Thread.sleep(100L + (long) (Math.random() * 200));
            answer = put(path, body, bearer, key);
        }
        return answer;
    }

    /** Every racer released at once; their answers, in order. */
    private static <T> List<T> raced(List<Callable<T>> racers) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> racer : racers) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return racer.call();
                }));
            }
            start.countDown();
            List<T> answers = new ArrayList<>();
            for (Future<T> future : futures) {
                answers.add(future.get());
            }
            return answers;
        } finally {
            pool.shutdownNow();
        }
    }

    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(name) + "\":\"([^\"]+)\"").matcher(body);
        assertThat(matcher.find()).as("the body must carry %s: %s", name, body).isTrue();
        return matcher.group(1);
    }
}
