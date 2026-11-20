package com.finapp.app.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * A fee schedule version and a merchant's fee assignment by four eyes, over real HTTP and against the real schema
 * (`P17-TSK-123`; ADR-0154's shape, finapp-admin-panel ADR-C135 decision 3, ADR-0125 decision 4): a holder of
 * {@code FEE_ADMINISTER} proposes, keyed, and nothing is priced; a holder of {@code FEE_SCHEDULE_APPROVE} who did not
 * propose it approves, and the existing effect runs once in the decision's transaction - or rejects, and nothing changes.
 * The races - ten approvers, an approval against a rejection, ten proposers - each have one winner; the approver's queue
 * and read serve what is decided.
 */
@Tag("database")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("fee schedule versions and merchant fee assignment by four eyes (P17-TSK-123)")
class FeeScheduleFourEyesDatabaseTest {

    private static final Clock CLOCK = Clock.system(ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final String PASSWORD = "a-perfectly-fine-pw-7";

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    /** A signed-in, stepped-up operator: its token and its identity. */
    private record Operator(String token, String identity) {}

    // ------------------------------------------------------------------ the version

    @Test
    @DisplayName("a version: proposed and keyed (a retry replays), nothing minted; the proposer refused its own decision"
            + " - 403 without the permission, 409 holding both roles; approved by a second person, minted once under the"
            + " approver and named on the proposal, the retried approval replaying; decided once")
    void aVersionIsMintedOnlyByASecondPerson() throws Exception {
        Operator administrator = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        Operator both = sessionWith(RoleName.MERCHANT_ADMINISTRATOR, RoleName.FINANCIAL_CONTROLLER);
        Operator controller = sessionWith(RoleName.FINANCIAL_CONTROLLER);
        String schedule = schedule(administrator);

        String proposalKey = someKey();
        String terms = FeePricing.versionBody("0.029", 30, "HALF_EVEN", "RETAINED", null, "annual repricing");
        FeePricing.Reply proposed = send("POST", "/v1/operator/fee-schedules/" + schedule + "/versions", terms,
                administrator, proposalKey);
        assertThat(proposed.status()).as(proposed.body()).isEqualTo(201);
        assertThat(field(proposed.body(), "status")).isEqualTo("PROPOSED");
        assertThat(proposed.body()).contains("\"feeScheduleVersionId\":null").doesNotContain("annual repricing");
        String proposal = field(proposed.body(), "proposalId");
        assertThat(send("POST", "/v1/operator/fee-schedules/" + schedule + "/versions", terms, administrator, proposalKey)
                .body()).as("the same key replays the proposal").isEqualTo(proposed.body());
        assertThat(send("POST", "/v1/operator/fee-schedules/" + schedule + "/versions",
                FeePricing.versionBody("0.031", 30, "HALF_EVEN", "RETAINED", null, "annual repricing"), administrator,
                proposalKey).status()).as("another request under the key").isEqualTo(409);
        assertThat(versions(schedule)).as("a proposal mints nothing").isZero();
        FeePricing.Reply second = send("POST", "/v1/operator/fee-schedules/" + schedule + "/versions", terms,
                administrator, someKey());
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.body()).contains("merchant.FeeProposalAlreadyOpen");

        String decisions = "/v1/operator/fee-schedule-version-proposals/" + proposal;
        assertThat(send("POST", decisions + "/approval", reason("mine"), administrator, someKey()).status())
                .as("the proposer's role holds no FEE_SCHEDULE_APPROVE").isEqualTo(403);

        String ownProposal = field(send("POST", "/v1/operator/fee-schedules/" + schedule(both) + "/versions", terms, both,
                someKey()).body(), "proposalId");
        FeePricing.Reply self = send("POST", "/v1/operator/fee-schedule-version-proposals/" + ownProposal + "/approval",
                reason("mine"), both, someKey());
        assertThat(self.status()).as(self.body()).isEqualTo(409);
        assertThat(self.body()).contains("merchant.FeeProposalSelfDecision");
        assertThat(count("SELECT count(*) FROM merchant.fee_schedule_version_proposal WHERE id = ?::uuid AND status ="
                + " 'PROPOSED'", ownProposal)).as("the refusal wrote nothing").isEqualTo(1);

        String approvalKey = someKey();
        FeePricing.Reply approved = send("POST", decisions + "/approval", reason("reviewed"), controller, approvalKey);
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(field(approved.body(), "status")).isEqualTo("APPROVED");
        String version = field(approved.body(), "feeScheduleVersionId");
        assertThat(send("POST", decisions + "/approval", reason("reviewed"), controller, approvalKey).body())
                .as("the retried approval replays").isEqualTo(approved.body());
        assertThat(versions(schedule)).as("minted once").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM merchant.fee_schedule_version WHERE id = ?::uuid AND created_by = '"
                + controller.identity() + "' AND rate = 0.029", version)).as("minted under the approver").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM merchant.fee_schedule_version_proposal WHERE id = ?::uuid AND status ="
                + " 'APPROVED' AND fee_schedule_version_id = '" + version + "' AND decided_by = '" + controller.identity()
                + "'", proposal)).as("the proposal names the version it minted").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'merchant.FeeScheduleVersionApproved' AND target_id = ? AND actor_id = '" + controller.identity()
                + "'", schedule)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'merchant.FeeScheduleVersionCreated' AND target_id = ? AND reason = 'annual repricing'", version))
                .as("the effect's own record, the proposer's reason").isEqualTo(1);

        FeePricing.Reply again = send("POST", decisions + "/approval", reason("again"), controller, someKey());
        assertThat(again.status()).isEqualTo(409);
        assertThat(again.body()).contains("merchant.FeeProposalAlreadyDecided");
        assertThat(send("POST", decisions + "/rejection", reason("too late"), controller, someKey()).status())
                .isEqualTo(409);
        assertThat(versions(schedule)).isEqualTo(1);
        assertThat(send("POST", "/v1/operator/fee-schedule-version-proposals/" + IDS.next() + "/approval", reason("x"),
                controller, someKey()).status()).as("an unknown proposal").isEqualTo(404);
        assertThat(send("POST", "/v1/operator/fee-schedule-version-proposals/not-a-uuid/approval", reason("x"),
                controller, someKey()).status()).as("a malformed one").isEqualTo(404);
    }

    @Test
    @DisplayName("a version named to start soon and approved after it: the effect refuses it 422 (INV-MER-03), nothing"
            + " minted, the proposal left open - and rejected, it mints nothing")
    void aStartPassedBeforeTheApprovalLeavesItOpen() throws Exception {
        Operator administrator = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        Operator controller = sessionWith(RoleName.FINANCIAL_CONTROLLER);
        String schedule = schedule(administrator);
        FeePricing.Reply proposed = send("POST", "/v1/operator/fee-schedules/" + schedule + "/versions",
                FeePricing.versionBody("0.029", 30, "HALF_EVEN", "RETAINED", Instant.now(CLOCK).plusSeconds(2),
                        "from the top of the minute"), administrator, someKey());
        assertThat(proposed.status()).as(proposed.body()).isEqualTo(201);
        String decisions = "/v1/operator/fee-schedule-version-proposals/" + field(proposed.body(), "proposalId");
        Thread.sleep(3_000);

        FeePricing.Reply late = send("POST", decisions + "/approval", reason("reviewed"), controller, someKey());
        assertThat(late.status()).as(late.body()).isEqualTo(422);
        assertThat(late.body()).contains("merchant.FeeScheduleNotForward");
        assertThat(versions(schedule)).isZero();
        assertThat(count("SELECT count(*) FROM merchant.fee_schedule_version_proposal WHERE fee_schedule_id = ?::uuid"
                + " AND status = 'PROPOSED'", schedule)).as("open, for a rejection").isEqualTo(1);

        FeePricing.Reply rejected = send("POST", decisions + "/rejection", reason("its start has passed"), controller,
                someKey());
        assertThat(rejected.status()).as(rejected.body()).isEqualTo(200);
        assertThat(field(rejected.body(), "status")).isEqualTo("REJECTED");
        assertThat(rejected.body()).contains("\"feeScheduleVersionId\":null");
        assertThat(versions(schedule)).isZero();
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'merchant.FeeScheduleVersionRejected' AND target_id = ?", schedule)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the assignment

    @Test
    @DisplayName("an assignment: proposed and keyed, the pointer unmoved; the proposer refused its own decision; approved"
            + " by a second person, the pointer moved once with the proposer's reason; converging with nothing proposed;"
            + " a rejection moves nothing")
    void anAssignmentMovesOnlyByASecondPerson() throws Exception {
        Operator administrator = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        Operator both = sessionWith(RoleName.MERCHANT_ADMINISTRATOR, RoleName.FINANCIAL_CONTROLLER);
        Operator controller = sessionWith(RoleName.FINANCIAL_CONTROLLER);
        String merchant = merchant();
        String schedule = schedule(administrator);
        String door = "/v1/operator/merchants/" + merchant + "/fee-schedule";

        String proposalKey = someKey();
        FeePricing.Reply proposed = send("PUT", door, assignment(schedule, "negotiated tier"), administrator, proposalKey);
        assertThat(proposed.status()).as(proposed.body()).isEqualTo(200);
        assertThat(field(proposed.body(), "status")).isEqualTo("PROPOSED");
        assertThat(field(proposed.body(), "feeScheduleId")).isEqualTo(schedule);
        String proposal = field(proposed.body(), "proposalId");
        assertThat(send("PUT", door, assignment(schedule, "negotiated tier"), administrator, proposalKey).body())
                .as("the same key replays the proposal").isEqualTo(proposed.body());
        assertThat(send("GET", door, null, administrator, null).status()).as("nothing priced yet").isEqualTo(404);

        String decisions = "/v1/operator/fee-assignment-proposals/" + proposal;
        assertThat(send("POST", decisions + "/approval", reason("mine"), administrator, someKey()).status())
                .isEqualTo(403);
        String ownMerchant = merchant();
        String ownProposal = field(send("PUT", "/v1/operator/merchants/" + ownMerchant + "/fee-schedule",
                assignment(schedule, "mine"), both, someKey()).body(), "proposalId");
        FeePricing.Reply self = send("POST", "/v1/operator/fee-assignment-proposals/" + ownProposal + "/approval",
                reason("mine"), both, someKey());
        assertThat(self.status()).as(self.body()).isEqualTo(409);
        assertThat(self.body()).contains("merchant.FeeProposalSelfDecision");
        assertThat(pointers(ownMerchant)).isZero();

        String approvalKey = someKey();
        FeePricing.Reply approved = send("POST", decisions + "/approval", reason("reviewed"), controller, approvalKey);
        assertThat(approved.status()).as(approved.body()).isEqualTo(200);
        assertThat(field(approved.body(), "status")).isEqualTo("APPROVED");
        assertThat(send("POST", decisions + "/approval", reason("reviewed"), controller, approvalKey).body())
                .as("the retried approval replays").isEqualTo(approved.body());
        assertThat(count("SELECT count(*) FROM merchant.merchant_fee_schedule_event WHERE merchant_id = ?::uuid AND"
                + " to_fee_schedule_id = '" + schedule + "' AND reason = 'negotiated tier' AND actor_id = '"
                + controller.identity() + "'", merchant)).as("moved once, under the approver").isEqualTo(1);
        FeePricing.Reply read = send("GET", door, null, administrator, null);
        assertThat(field(read.body(), "status")).isEqualTo("ASSIGNED");
        assertThat(field(read.body(), "feeScheduleId")).isEqualTo(schedule);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'merchant.FeeAssignmentApproved'"
                + " AND target_id = ?", merchant)).isEqualTo(1);
        assertThat(send("POST", decisions + "/rejection", reason("too late"), controller, someKey()).body())
                .contains("merchant.FeeProposalAlreadyDecided");

        FeePricing.Reply converged = send("PUT", door, assignment(schedule, "same again"), administrator, someKey());
        assertThat(field(converged.body(), "status")).as("already there: nothing proposed").isEqualTo("ASSIGNED");
        assertThat(count("SELECT count(*) FROM merchant.fee_assignment_proposal WHERE merchant_id = ?::uuid", merchant))
                .isEqualTo(1);

        String other = schedule(administrator);
        String rejectedProposal = field(send("PUT", door, assignment(other, "cheaper"), administrator, someKey()).body(),
                "proposalId");
        FeePricing.Reply rejected = send("POST", "/v1/operator/fee-assignment-proposals/" + rejectedProposal
                + "/rejection", reason("not agreed"), controller, someKey());
        assertThat(rejected.status()).as(rejected.body()).isEqualTo(200);
        assertThat(field(rejected.body(), "status")).isEqualTo("REJECTED");
        assertThat(field(send("GET", door, null, administrator, null).body(), "feeScheduleId"))
                .as("a rejection moves nothing").isEqualTo(schedule);
        assertThat(send("PUT", "/v1/operator/merchants/" + UUID.randomUUID() + "/fee-schedule",
                assignment(schedule, "x"), administrator, someKey()).status()).isEqualTo(404);
        assertThat(send("PUT", door, assignment(IDS.next().toString(), "x"), administrator, someKey()).status())
                .isEqualTo(404);
    }

    @Test
    @DisplayName("V022 holds for every writer: a self-decided row is unstorable, a decided row is history, an approval"
            + " names its version, nothing is deleted")
    void v022HoldsForEveryWriter() throws Exception {
        Operator administrator = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        String schedule = schedule(administrator);
        String proposal = field(send("POST", "/v1/operator/fee-schedules/" + schedule + "/versions",
                FeePricing.versionBody("0.029", 30, "HALF_EVEN", "RETAINED", null, "raw"), administrator, someKey())
                .body(), "proposalId");
        String merchant = merchant();
        String assignment = field(send("PUT", "/v1/operator/merchants/" + merchant + "/fee-schedule",
                assignment(schedule, "raw"), administrator, someKey()).body(), "proposalId");

        try (Connection app = DatabaseRoles.application()) {
            assertThat(rawUpdate(app, "UPDATE merchant.fee_assignment_proposal SET status = 'REJECTED', decided_by ="
                    + " proposed_by, decided_by_type = 'EMPLOYEE', decided_at = proposed_at, decision_reason = 'mine',"
                    + " decision_correlation_id = 'c' WHERE id = ?::uuid", assignment))
                    .as("a self-decision, for every writer").contains("fee_assignment_proposal_four_eyes");
            assertThat(rawUpdate(app, "UPDATE merchant.fee_schedule_version_proposal SET status = 'REJECTED',"
                    + " decided_by = proposed_by, decided_by_type = 'EMPLOYEE', decided_at = proposed_at,"
                    + " decision_reason = 'mine', decision_correlation_id = 'c' WHERE id = ?::uuid", proposal))
                    .contains("fee_schedule_version_proposal_four_eyes");
            assertThat(rawUpdate(app, "UPDATE merchant.fee_schedule_version_proposal SET status = 'APPROVED',"
                    + " decided_by = 'someone-else', decided_by_type = 'EMPLOYEE', decided_at = proposed_at,"
                    + " decision_reason = 'ok', decision_correlation_id = 'c' WHERE id = ?::uuid", proposal))
                    .as("an approval that minted nothing").contains("approval_names_its_version");
            assertThat(rawUpdate(app, "UPDATE merchant.fee_schedule_version_proposal SET rate = 0.5 WHERE id = ?::uuid",
                    proposal)).as("the proposal's terms: no grant").contains("permission denied");
            assertThat(rawUpdate(app, "DELETE FROM merchant.fee_assignment_proposal WHERE id = ?::uuid", assignment))
                    .contains("permission denied");
            assertThat(rawUpdate(app, "UPDATE merchant.fee_assignment_proposal SET status = 'REJECTED', decided_by ="
                    + " 'someone-else', decided_by_type = 'EMPLOYEE', decided_at = proposed_at, decision_reason = 'no',"
                    + " decision_correlation_id = 'c' WHERE id = ?::uuid", assignment)).as("a whole decision").isNull();
            assertThat(rawUpdate(app, "UPDATE merchant.fee_assignment_proposal SET status = 'APPROVED', decided_by ="
                    + " 'a-third', decision_reason = 'again' WHERE id = ?::uuid", assignment))
                    .as("a decided proposal is history").contains("a decided proposal is history");
        }
    }

    /** {@code sql} with {@code id}, autocommitted: null when it was stored, the refusal's message when not. */
    private static String rawUpdate(Connection connection, String sql, String id) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            statement.executeUpdate();
            return null;
        } catch (java.sql.SQLException refused) {
            return refused.getMessage();
        }
    }

    // ------------------------------------------------------------------ the races

    @Test
    @DisplayName("TEN controllers racing to approve one version proposal mint ONE version; an approval racing a"
            + " rejection of one assignment proposal makes one winner; TEN proposers racing one schedule make one"
            + " proposal")
    void eachRaceHasOneWinner() throws Exception {
        Operator administrator = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        List<Operator> controllers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            controllers.add(sessionWith(RoleName.FINANCIAL_CONTROLLER));
        }

        String schedule = schedule(administrator);
        String proposal = field(send("POST", "/v1/operator/fee-schedules/" + schedule + "/versions",
                FeePricing.versionBody("0.029", 30, "HALF_EVEN", "RETAINED", null, "raced"), administrator, someKey())
                .body(), "proposalId");
        List<FeePricing.Reply> approvals = race(controllers.size(), i -> send("POST",
                "/v1/operator/fee-schedule-version-proposals/" + proposal + "/approval", reason("reviewed"),
                controllers.get(i), someKey()));
        assertThat(approvals.stream().filter(reply -> reply.status() == 200)).hasSize(1);
        assertThat(approvals.stream().filter(reply -> reply.status() == 409
                && reply.body().contains("merchant.FeeProposalAlreadyDecided"))).hasSize(9);
        assertThat(versions(schedule)).as("one version, however many approvers").isEqualTo(1);

        String merchant = merchant();
        String assignment = field(send("PUT", "/v1/operator/merchants/" + merchant + "/fee-schedule",
                assignment(schedule, "raced"), administrator, someKey()).body(), "proposalId");
        List<FeePricing.Reply> decisions = race(2, i -> send("POST", "/v1/operator/fee-assignment-proposals/"
                + assignment + (i == 0 ? "/approval" : "/rejection"), reason("decided"), controllers.get(i), someKey()));
        assertThat(decisions.stream().filter(reply -> reply.status() == 200)).hasSize(1);
        assertThat(decisions.stream().filter(reply -> reply.status() == 409)).hasSize(1);
        String outcome = decisions.stream().filter(reply -> reply.status() == 200).map(reply -> field(reply.body(),
                "status")).findFirst().orElseThrow();
        assertThat(pointers(merchant)).as("the pointer moved exactly when the approval won")
                .isEqualTo("APPROVED".equals(outcome) ? 1 : 0);

        String contended = schedule(administrator);
        List<FeePricing.Reply> proposals = race(10, i -> send("POST", "/v1/operator/fee-schedules/" + contended
                + "/versions", FeePricing.versionBody("0.029", 30, "HALF_EVEN", "RETAINED", null, "racer " + i),
                administrator, someKey()));
        assertThat(proposals.stream().filter(reply -> reply.status() == 201)).hasSize(1);
        assertThat(proposals.stream().filter(reply -> reply.status() == 409
                && reply.body().contains("merchant.FeeProposalAlreadyOpen"))).hasSize(9);
        assertThat(count("SELECT count(*) FROM merchant.fee_schedule_version_proposal WHERE fee_schedule_id = ?::uuid",
                contended)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ the approver's reads

    @Test
    @DisplayName("each kind's queue in P17-TSK-040's pending-decision shape, oldest first through its cursor; a decided one"
            + " leaves it; the read serves any kind with its terms and schedule; the proposers' role is refused both, the"
            + " approvers' role every proposal door")
    void theApproverReadsWhatTheyDecide() throws Exception {
        Operator administrator = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        Operator both = sessionWith(RoleName.MERCHANT_ADMINISTRATOR, RoleName.FINANCIAL_CONTROLLER);
        Operator controller = sessionWith(RoleName.FINANCIAL_CONTROLLER);
        String schedule = schedule(administrator);
        String merchant = merchant();
        String bnplMerchant = merchant();

        String version = field(send("POST", "/v1/operator/fee-schedules/" + schedule + "/versions",
                FeePricing.versionBody("0.0175", 25, "CEILING", "RETURNED", Instant.now(CLOCK).plus(Duration.ofDays(2)),
                        "next month's pricing"), administrator, someKey()).body(), "proposalId");
        String card = field(send("PUT", "/v1/operator/merchants/" + merchant + "/fee-schedule",
                assignment(schedule, "standard terms"), both, someKey()).body(), "proposalId");
        String bnpl = field(send("PUT", "/v1/operator/merchants/" + bnplMerchant + "/bnpl-fee-schedules/BNPL_PAY_IN_4",
                assignment(schedule, "bnpl terms"), administrator, someKey()).body(), "proposalId");

        Map<String, String> versions = queue(VERSIONS, both, 2);
        assertThat(versions).containsKey(version).doesNotContainKeys(card, bnpl);
        assertThat(versions.get(version)).contains("\"kind\":\"merchant.FeeScheduleVersion\"")
                .contains("\"subjectId\":\"" + schedule + "\"")
                .contains("\"proposedBy\":\"" + administrator.identity() + "\"")
                .contains("\"reasonSummary\":\"next month's pricing\"");
        Map<String, String> assignments = queue(ASSIGNMENTS, both, 2);
        assertThat(assignments).containsKey(card).doesNotContainKeys(version, bnpl);
        assertThat(assignments.get(card)).contains("\"kind\":\"merchant.FeeAssignment\"")
                .contains("\"subjectId\":\"" + merchant + "\"").contains("\"proposedBy\":\"" + both.identity() + "\"");

        FeePricing.Reply read = send("GET", "/v1/operator/fee-schedule-proposals/" + version, null, controller, null);
        assertThat(read.status()).as(read.body()).isEqualTo(200);
        assertThat(read.body()).contains("\"status\":\"PROPOSED\"").contains("\"rate\":\"0.017500\"")
                .contains("\"fixedAmountMinor\":25").contains("\"roundingPolicy\":\"CEILING\"")
                .contains("\"refundFeePolicy\":\"RETURNED\"").contains("\"effectiveFrom\":\"")
                .contains("\"feeSchedule\":{\"feeScheduleId\":\"" + schedule + "\"");
        assertThat(send("GET", "/v1/operator/fee-schedule-proposals/" + card, null, controller, null).body())
                .contains("\"kind\":\"merchant.FeeAssignment\"").doesNotContain("currentFeeScheduleId");
        assertThat(send("GET", "/v1/operator/fee-schedule-proposals/" + bnpl, null, controller, null).body())
                .contains("\"kind\":\"merchant.BnplFeeAssignment\"").contains("\"product\":\"BNPL_PAY_IN_4\"");
        assertThat(send("GET", "/v1/operator/fee-schedule-proposals/" + IDS.next(), null, controller, null).status())
                .isEqualTo(404);
        assertThat(send("GET", "/v1/operator/fee-schedule-proposals/not-a-uuid", null, controller, null).status())
                .isEqualTo(404);

        assertThat(send("POST", "/v1/operator/fee-schedule-version-proposals/" + version + "/rejection",
                reason("not this month"), controller, someKey()).status()).isEqualTo(200);
        assertThat(queue(VERSIONS, controller, 50)).doesNotContainKey(version);
        assertThat(queue(ASSIGNMENTS, controller, 50)).containsKey(card);
        assertThat(send("GET", "/v1/operator/fee-schedule-proposals/" + version, null, controller, null).body())
                .contains("\"status\":\"REJECTED\"").contains("\"decidedBy\":\"" + controller.identity() + "\"");

        assertThat(send("GET", VERSIONS + "?status=APPROVED", null, controller, null).status()).isEqualTo(422);
        assertThat(send("GET", VERSIONS + "?cursor=garbage", null, controller, null).status()).isEqualTo(422);
        assertThat(send("GET", ASSIGNMENTS + "?limit=201", null, controller, null).status()).isEqualTo(422);

        assertThat(send("GET", VERSIONS, null, administrator, null).status())
                .as("the proposers' role reads no queue").isEqualTo(403);
        assertThat(send("GET", ASSIGNMENTS, null, administrator, null).status()).isEqualTo(403);
        assertThat(send("GET", "/v1/operator/fee-schedule-proposals/" + card, null, administrator, null).status())
                .isEqualTo(403);
        long proposalsBefore = count("SELECT count(*) FROM merchant.fee_schedule_version_proposal WHERE"
                + " fee_schedule_id = ?::uuid", schedule);
        assertThat(send("POST", "/v1/operator/fee-schedules", "{\"name\":\"x " + UUID.randomUUID()
                + "\",\"currency\":\"EUR\"}", controller, someKey()).status()).as("the approvers' role proposes nothing")
                .isEqualTo(403);
        assertThat(send("POST", "/v1/operator/fee-schedules/" + schedule + "/versions", FeePricing.standardTerms(),
                controller, someKey()).status()).isEqualTo(403);
        assertThat(send("PUT", "/v1/operator/merchants/" + bnplMerchant + "/fee-schedule", assignment(schedule, "x"),
                controller, someKey()).status()).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM merchant.fee_schedule_version_proposal WHERE fee_schedule_id = ?::uuid",
                schedule)).isEqualTo(proposalsBefore);
    }

    @Test
    @DisplayName("a schedule's creation is one person's, keyed: a retry answers the schedule created first, another"
            + " request under the key is 409, and no key is 422")
    void aScheduleIsCreatedKeyed() throws Exception {
        Operator administrator = sessionWith(RoleName.MERCHANT_ADMINISTRATOR);
        String name = "Keyed | " + UUID.randomUUID();
        String body = "{\"name\":\"" + name + "\",\"currency\":\"EUR\"}";
        String key = someKey();
        FeePricing.Reply created = send("POST", "/v1/operator/fee-schedules", body, administrator, key);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(field(created.body(), "name")).as("a name may hold the stored answer's separator").isEqualTo(name);
        FeePricing.Reply retried = send("POST", "/v1/operator/fee-schedules", body, administrator, key);
        assertThat(retried.status()).isEqualTo(201);
        assertThat(retried.body()).isEqualTo(created.body());
        assertThat(count("SELECT count(*) FROM merchant.fee_schedule WHERE name = ?", name)).isEqualTo(1);
        assertThat(send("POST", "/v1/operator/fee-schedules", "{\"name\":\"other " + UUID.randomUUID()
                + "\",\"currency\":\"EUR\"}", administrator, key).status()).isEqualTo(409);
        FeePricing.Reply unkeyed = send("POST", "/v1/operator/fee-schedules", "{\"name\":\"unkeyed " + UUID.randomUUID()
                + "\",\"currency\":\"EUR\"}", administrator, null);
        assertThat(unkeyed.status()).isEqualTo(422);
        assertThat(unkeyed.body()).contains("api.IdempotencyKeyRequired");
    }

    // ------------------------------------------------------------------ fixtures

    private static final String VERSIONS = "/v1/operator/pending-decisions/merchant/fee-schedule-versions";
    private static final String ASSIGNMENTS = "/v1/operator/pending-decisions/merchant/fee-assignments";

    /** Every page of {@code route}'s queue read by {@code reader}, {@code limit} at a time: each item by id, in order. */
    private Map<String, String> queue(String route, Operator reader, int limit) throws Exception {
        Map<String, String> items = new HashMap<>();
        Set<String> order = new HashSet<>();
        String cursor = null;
        String lastStamp = "";
        Pattern item = Pattern.compile("\\{\"id\":\"([^\"]+)\",\"kind\":[^}]*\"proposedAt\":\"([^\"]+)\"[^}]*\\}");
        do {
            FeePricing.Reply page = send("GET", route + "?status=PROPOSED&limit=" + limit
                    + (cursor == null ? "" : "&cursor=" + cursor), null, reader, null);
            assertThat(page.status()).as(page.body()).isEqualTo(200);
            Matcher matcher = item.matcher(page.body());
            while (matcher.find()) {
                assertThat(order.add(matcher.group(1))).as("served once").isTrue();
                assertThat(Instant.parse(matcher.group(2))).as("oldest first")
                        .isAfterOrEqualTo(lastStamp.isEmpty() ? Instant.EPOCH : Instant.parse(lastStamp));
                lastStamp = matcher.group(2);
                items.put(matcher.group(1), matcher.group());
            }
            Matcher next = Pattern.compile("\"nextCursor\":\"([^\"]+)\"").matcher(page.body());
            cursor = next.find() ? next.group(1) : null;
        } while (cursor != null);
        return items;
    }

    @FunctionalInterface
    private interface Racer {
        FeePricing.Reply run(int index) throws Exception;
    }

    private static List<FeePricing.Reply> race(int racers, Racer racer) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<FeePricing.Reply>> results = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(racers);
        try {
            for (int i = 0; i < racers; i++) {
                int index = i;
                results.add(pool.submit(() -> {
                    start.await();
                    // The door sheds load under a burst (ADR-0137): a shed racer retries under the same request.
                    FeePricing.Reply reply = racer.run(index);
                    for (int attempt = 0; attempt < 20 && reply.status() == 503; attempt++) {
                        Thread.sleep(200);
                        reply = racer.run(index);
                    }
                    return reply;
                }));
            }
            start.countDown();
            List<FeePricing.Reply> replies = new ArrayList<>();
            for (Future<FeePricing.Reply> result : results) {
                replies.add(result.get(90, TimeUnit.SECONDS));
            }
            return replies;
        } finally {
            pool.shutdownNow();
        }
    }

    private String schedule(Operator administrator) throws Exception {
        return new FeePricing(door(), administrator.token(), null).schedule("Four eyes " + UUID.randomUUID(), "EUR");
    }

    private FeePricing.Reply send(String method, String path, String body, Operator bearer, String key)
            throws Exception {
        return door().send(method, path, body, bearer == null ? null : bearer.token(), key);
    }

    private FeePricing.Door door() {
        return FeePricing.overHttp(port);
    }

    /** An ACTIVE merchant settling in EUR, written as the onboarding door would. */
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

    /** A signed-in session holding every role in {@code roles}, on one identity, stepped up to {@code MULTI_FACTOR}. */
    private Operator sessionWith(RoleName... roles) throws Exception {
        String login = "fe." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        FeePricing.Reply registered = door().send("POST", "/v1/registrations", "{\"loginIdentifier\":\"" + login
                + "\",\"displayName\":\"Ada Lovelace\",\"password\":\"" + PASSWORD + "\"}", null, someKey());
        assertThat(registered.status()).as(registered.body()).isEqualTo(201);
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
            for (RoleName role : roles) {
                authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            }
            app.commit();
        }
        FeePricing.Reply session = door().send("POST", "/v1/authentications", "{\"loginIdentifier\":\"" + login
                + "\",\"password\":\"" + PASSWORD + "\"}", null, someKey());
        String token = com.finapp.app.session.OperatorStepUp.elevated(field(session.body(), "sessionToken"), this::post);
        return new Operator(token, identity.toString());
    }

    private java.net.http.HttpResponse<String> post(String path, String body, String bearer, String key) throws Exception {
        java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(body == null ? java.net.http.HttpRequest.BodyPublishers.noBody()
                        : java.net.http.HttpRequest.BodyPublishers.ofString(body));
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        if (key != null) {
            request.header(com.finapp.platform.api.IdempotencyKeyHeader.NAME, key);
        }
        try (java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build()) {
            return client.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        }
    }

    private static long versions(String schedule) throws Exception {
        return count("SELECT count(*) FROM merchant.fee_schedule_version WHERE fee_schedule_id = ?::uuid", schedule);
    }

    private static long pointers(String merchant) throws Exception {
        return count("SELECT count(*) FROM merchant.merchant_fee_schedule WHERE merchant_id = ?::uuid", merchant);
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

    private static String reason(String reason) {
        return "{\"reason\":\"" + reason + "\"}";
    }

    private static String assignment(String feeScheduleId, String reason) {
        return "{\"feeScheduleId\":\"" + feeScheduleId + "\",\"reason\":\"" + reason + "\"}";
    }

    private static String someKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(String body, String name) {
        return FeePricing.field(body, name);
    }
}
