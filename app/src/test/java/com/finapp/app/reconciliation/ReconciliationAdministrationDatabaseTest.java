package com.finapp.app.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.app.telemetry.ReconciliationReplayMeters;
import com.finapp.identity.AssuranceLevel;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.RoleName;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionStore;
import com.finapp.identity.SessionToken;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.BreakCause;
import com.finapp.reconciliation.BreakRegister;
import com.finapp.reconciliation.BreakType;
import com.finapp.reconciliation.ExpectationDirection;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.ExpectationRegister;
import com.finapp.reconciliation.ExternalItems;
import com.finapp.reconciliation.ExternalLineType;
import com.finapp.reconciliation.ItemKeyKind;
import com.finapp.reconciliation.JdbcExternalItems;
import com.finapp.reconciliation.JdbcReconciliationRuns;
import com.finapp.reconciliation.KeyKind;
import com.finapp.reconciliation.Matching;
import com.finapp.reconciliation.MatchingStore;
import com.finapp.reconciliation.NewExpectation;
import com.finapp.reconciliation.ReconciliationRuns;
import com.finapp.reconciliation.RunKind;
import com.finapp.reconciliation.RunReadings;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SourceKind;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Operating reconciliation without editing history, over the app's own composition and real HTTP
 * (`P8-TSK-022`, ADR-0068 §§8-9; {@code INV-HIST-04}, {@code INV-REC-04}, {@code INV-REC-08},
 * {@code INV-AUD-04}): matching policy changes only forward and under four eyes - a whole
 * version proposed frozen, its proposer's activation refused, a second controller's activation
 * retiring its predecessor in the same transaction, a second pending proposal and an amount
 * tolerance refused, a withdrawal rejected by its own proposer; a new version governs only new
 * decisions - the old run replays IDENTICAL with every position and allocation byte-identical,
 * and the next rematch pins the new version; reprocessing re-decides the residual items alone,
 * allocating the parked line the new version reaches - unparked, its break closed EVIDENCED -
 * and leaving every matched line untouched, one open run per source under ten racing requests;
 * a blocked run requeued by a person and its {@code RUN_BLOCKED} break closed EVIDENCED by the
 * completion, one winner among ten racing requeues; a replay IDENTICAL after runs, rematches and
 * reprocessing, a pending rematch counted apart, and a planted contradiction caught as ONE
 * CRITICAL divergence break however many replays race; and every door's negatives.
 *
 * <p><strong>The private source, and the suite's one double.</strong> The suite owns a private
 * source and rule set (the `P8-TSK-011` suites' shape) so its commits never race another suite's,
 * and so no declared source's policy changes under the shared container: activating a version on
 * a declared source would re-pin every later opener and run of the suites that read "the ACTIVE
 * version", and reprocessing one would re-decide their residual items. The doors resolve a
 * source by its settlement CODE, and a settlement source row can be neither deleted nor added
 * here ({@code SettlementSourceRegisterDatabaseTest} pins the seeded register to the declared
 * sources). So settlement's file store answers ONE more code - this suite's - and delegates every
 * other call untouched; nothing else is doubled.
 *
 * <p>Identity discipline (the `P8-TSK-012` lesson): every item this suite births is balanced by
 * an equal open expectation remainder the moment the method ends, and at rest every item is
 * MATCHED and every expectation SETTLED and held by an allocation - so the position, suspense
 * and completeness verdicts read exactly as the shared container left them, asserted after
 * every money-moving method.
 */
@Tag("database")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("operating reconciliation without editing history, composed over HTTP (P8-TSK-022)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
class ReconciliationAdministrationDatabaseTest {

    private static final Clock CLOCK = Clock.systemUTC();
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final SecureRandom RANDOMNESS = new SecureRandom();
    private static final Actor PLATFORM = new Actor("system", ActorType.SYSTEM);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final UUID PRIVATE_SOURCE =
            UUID.fromString("01a0e2bc-8200-7022-8000-000000000022");
    private static final UUID VERSION_ONE =
            UUID.fromString("01a0e2bd-8300-7022-8000-000000000022");
    /** The one code settlement's register answers for this suite (the double above). */
    static final String PRIVATE_CODE = "p8t22-private.reconciliation";
    /** A code no register answers. */
    private static final String UNKNOWN_CODE = "p8t22-nobody.nowhere";
    private static final LocalDate SETTLED_ON = LocalDate.parse("2026-09-25");
    private static final AtomicLong SEQUENCES =
            new AtomicLong(Math.floorMod(System.nanoTime(), 80_000L) + 1);
    private static final String BASE = "/v1/operator/reconciliation";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;
    @Autowired private Matching matching;
    @Autowired private MatchingStore matchingStore;
    @Autowired private BreakRegister breakRegister;
    @Autowired private ExpectationRegister expectationRegister;
    @Autowired private PositionProof positionProof;
    @Autowired private RunReadings runReadings;
    @Autowired private MeterRegistry meterRegistry;

    private final HttpClient http = HttpClient.newHttpClient();
    private final SessionStore<Connection> sessions = new JdbcSessionStore();
    private final JdbcReconciliationRuns runs = new JdbcReconciliationRuns();
    private final JdbcExternalItems items = new JdbcExternalItems();

    private static Map<String, Boolean> baselineFlags;
    private static Map<AccountPurpose, Long> baselineUnattributed;
    private static long baselineUnowned;
    private static UUID runOne;
    private static UUID firstSettled;
    private static UUID reachedOnlyByOurRef;
    private static UUID balancer;
    private static UUID parkedItem;
    private static UUID waitingItem;
    private static String waitingKey;
    private static String balancerKey;
    private static UUID versionTwo;
    private static UUID versionFour;
    private static Map<String, String> positionsBeforeActivation;
    private static List<String> rowsBeforeActivation;
    private static UUID runThree;
    private static UUID residualItem;
    private static String residualKey;
    private static String residualBalancerKey;
    private static UUID reprocessRun;

    // ----------------------------------------------------------------- version 1's run

    @Test
    @Order(1)
    @DisplayName("version 1 decides the first run: two lines MATCHED and a balancing line beside"
            + " them, one line PARKED past the only candidate version 1 reaches, one WAITING -"
            + " every decision pinned to version 1, the shared container's proofs unmoved")
    void versionOneDecidesTheFirstRun() throws Exception {
        PositionProof.Report baseline = proofs();
        baselineFlags = explainedFlags(baseline);
        baselineUnattributed = baseline.unattributedByPurpose();
        baselineUnowned = baseline.suspenseUnowned();
        seedVersionOne();

        String firstKey = marker();
        String secondKey = marker();
        String parkedCaptureKey = marker();
        String parkedOurKey = marker();
        waitingKey = marker();
        balancerKey = marker();
        firstSettled = openExpectation(KeyKind.PSP_CAPTURE_REF, firstKey, 10_00);
        openExpectation(KeyKind.PSP_CAPTURE_REF, secondKey, 20_00);
        UUID partlyReached = openExpectation(KeyKind.PSP_CAPTURE_REF, parkedCaptureKey, 10_00);
        // Reached only through an OUR_REF rule, which version 1 does not hold. It opens BEFORE
        // the decision, so no rematch ever reaches it - only a reprocess under a version that
        // holds the rule (case 3). Until then it balances the parked excess (20.00).
        reachedOnlyByOurRef = openExpectation(KeyKind.OUR_REF, parkedOurKey, 20_00);
        // The waiting line's balancer (the P8-TSK-011 suite's shape): 16.00, 1.00 of it spent by
        // line 5, so its 15.00 stands for the waiting 15.00 and is held by its allocation.
        balancer = openExpectation(KeyKind.PSP_CAPTURE_REF, balancerKey, 16_00);
        runOne =
                seedRun(
                        VERSION_ONE,
                        line(1, 10_00, firstKey),
                        line(2, 20_00, secondKey),
                        line(3, 30_00, parkedCaptureKey, parkedOurKey),
                        line(4, 15_00, waitingKey),
                        line(5, 1_00, balancerKey));
        parkedItem = itemOf(runOne, 3);
        waitingItem = itemOf(runOne, 4);

        sweepUntilCompleted(runOne);

        assertThat(itemStatuses(runOne))
                .containsExactly("MATCHED", "MATCHED", "PARKED", "UNMATCHED", "MATCHED");
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision d JOIN"
                + " reconciliation.external_item i ON i.id = d.external_item_id WHERE i.run_id = ?"
                + " AND d.rule_set_id = ? AND d.origin = 'RUN'", runOne, VERSION_ONE))
                .as("one decision per line, each pinned to the version the run pinned")
                .isEqualTo(5);
        assertThat(string("SELECT allocated_minor || ':' || parked_minor FROM"
                + " reconciliation.external_item WHERE id = ?", parkedItem))
                .as("10.00 allocated to the one candidate version 1 reaches, 20.00 parked")
                .isEqualTo("1000:2000");
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE external_item_id = ?"
                + " AND type = 'AMOUNT_MISMATCH' AND status <> 'RESOLVED'", parkedItem))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item WHERE"
                + " external_item_id = ? AND status = 'OPEN'", parkedItem)).isEqualTo(1);
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                partlyReached)).isEqualTo("SETTLED");
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                reachedOnlyByOurRef))
                .as("version 1 holds no OUR_REF rule: the expectation is never a candidate")
                .isEqualTo("OPEN");
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                balancer)).isEqualTo("PARTIALLY_SETTLED");
        assertThat(count("SELECT count(*) FROM reconciliation.external_item WHERE id = ? AND"
                + " grace_until IS NOT NULL", waitingItem)).isEqualTo(1);
        assertTheIdentityIsUnmoved("after version 1's run");
    }

    // ----------------------------------------------------------------- case 1: four eyes

    @Test
    @Order(2)
    @DisplayName("policy changes only forward and under four eyes: a whole version proposed"
            + " frozen and replayed by its key, its proposer's activation refused, a second"
            + " pending proposal and an amount tolerance refused with nothing stored, the"
            + " operator's desk refused, a second controller's activation retiring version 1 and"
            + " converging on retry, and a withdrawal rejected by its own proposer")
    void policyChangesOnlyForwardAndUnderFourEyes() throws Exception {
        positionsBeforeActivation = positions();
        rowsBeforeActivation = suiteRows();
        Session proposer = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        Session approver = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        Session operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);

        HttpResponse<String> listed = get(proposer.token(), "/rule-sets?source=" + PRIVATE_CODE);
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        JsonNode versionOne = versionNamed(json(listed), VERSION_ONE);
        assertThat(versionOne.path("status").stringValue()).isEqualTo("ACTIVE");
        ObjectNode proposal =
                proposalFrom(versionOne, "the PSP now settles a day later than it used to");
        int widened = widenDateWindow(proposal);
        assertThat(widened).isEqualTo(dateWindowOf(versionOne) + 1);

        String key = key();
        String body = JSON.writeValueAsString(proposal);
        HttpResponse<String> proposed = post(proposer.token(), "/rule-sets", key, body);
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        JsonNode receipt = json(proposed);
        assertThat(receipt.path("status").stringValue()).isEqualTo("PROPOSED");
        assertThat(receipt.path("version").intValue()).isEqualTo(2);
        assertThat(absent(receipt.path("retiredRuleSetId"))).isTrue();
        versionTwo = UUID.fromString(receipt.path("ruleSetId").stringValue());
        HttpResponse<String> replayed = post(proposer.token(), "/rule-sets", key, body);
        assertThat(replayed.statusCode()).isEqualTo(201);
        assertThat(replayed.body()).as("a lost response replays the receipt")
                .isEqualTo(proposed.body());
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set WHERE source_id = ?",
                PRIVATE_SOURCE)).as("the replay proposed nothing new").isEqualTo(2);

        JsonNode relisted = json(get(proposer.token(), "/rule-sets?source=" + PRIVATE_CODE));
        assertThat(relisted.path("truncated").booleanValue()).isFalse();
        assertThat(relisted.path("versions").size()).isEqualTo(2);
        JsonNode pending = relisted.path("versions").get(0);
        assertThat(pending.path("ruleSetId").stringValue())
                .as("newest first").isEqualTo(versionTwo.toString());
        assertThat(pending.path("status").stringValue()).isEqualTo("PROPOSED");
        assertThat(pending.path("version").intValue()).isEqualTo(2);
        assertThat(pending.path("proposedBy").stringValue()).isEqualTo(proposer.actorId());
        assertThat(absent(pending.path("decidedBy"))).isTrue();
        assertThat(dateWindowOf(pending)).as("the one tolerance changed").isEqualTo(widened);
        assertThat(pending.path("rules")).as("the rest is version 1's, whole")
                .isEqualTo(versionOne.path("rules"));
        assertThat(pending.path("severityThresholds"))
                .isEqualTo(versionOne.path("severityThresholds"));
        assertThat(pending.path("fundingLagDays").intValue())
                .isEqualTo(versionOne.path("fundingLagDays").intValue());

        HttpResponse<String> self = post(proposer.token(),
                "/rule-sets/" + versionTwo + "/approval", null,
                reason("approving my own change"));
        assertThat(self.statusCode()).as(self.body()).isEqualTo(409);
        assertThat(self.body()).contains("reconciliation.RuleSetActivationBySameActor");
        assertThat(ruleSetStatus(versionTwo)).as("nothing changes").isEqualTo("PROPOSED");
        assertThat(ruleSetStatus(VERSION_ONE)).isEqualTo("ACTIVE");

        String secondKey = key();
        HttpResponse<String> second = post(approver.token(), "/rule-sets", secondKey, body);
        assertThat(second.statusCode()).as(second.body()).isEqualTo(409);
        assertThat(second.body()).contains("reconciliation.RuleSetProposalPending");
        ObjectNode amountTolerance =
                proposalFrom(versionOne, "absorb small principal differences silently");
        ((ArrayNode) amountTolerance.get("tolerances"))
                .addObject()
                .put("comparison", "PRINCIPAL_AMOUNT")
                .put("currency", "EUR")
                .put("absoluteMinor", 100);
        String amountKey = key();
        HttpResponse<String> amount = post(proposer.token(), "/rule-sets", amountKey,
                JSON.writeValueAsString(amountTolerance));
        assertThat(amount.statusCode()).as(amount.body()).isEqualTo(422);
        assertThat(amount.body()).contains("reconciliation.ToleranceNotPermitted");
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set WHERE source_id = ?",
                PRIVATE_SOURCE)).as("neither refusal stored a version").isEqualTo(2);
        assertThat(count("SELECT count(*) FROM platform.idempotency_record WHERE"
                + " idempotency_key IN (?, ?)", secondKey, amountKey))
                .as("a refused proposal rolls its claim back with it")
                .isZero();

        record Door(String method, String path, String key, String body) {}
        List<Door> ruleSetDoors =
                List.of(
                        new Door("GET", "/rule-sets?source=" + PRIVATE_CODE, null, null),
                        new Door("POST", "/rule-sets", key(), body),
                        new Door("POST", "/rule-sets/" + versionTwo + "/approval", key(),
                                reason("an operator activating policy")),
                        new Door("POST", "/rule-sets/" + versionTwo + "/rejection", key(),
                                reason("an operator rejecting policy")));
        for (Door door : ruleSetDoors) {
            assertThat(call(operator.token(), door.method(), door.path(), door.body(),
                            door.key()).statusCode())
                    .as("%s %s: the resolver never sets the policy its breaks answer to",
                            door.method(), door.path())
                    .isEqualTo(403);
        }
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set WHERE source_id = ?",
                PRIVATE_SOURCE)).isEqualTo(2);
        assertThat(ruleSetStatus(versionTwo)).isEqualTo("PROPOSED");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE actor_id = ? AND"
                + " operation LIKE 'reconciliation.%'", operator.actorId()))
                .as("the operator's attempts decided nothing (the denials are the session"
                        + " door's own records)")
                .isZero();

        String activationReason = "a second controller confirms the later settlement window";
        HttpResponse<String> activated = post(approver.token(),
                "/rule-sets/" + versionTwo + "/approval", null, reason(activationReason));
        assertThat(activated.statusCode()).as(activated.body()).isEqualTo(200);
        JsonNode decided = json(activated);
        assertThat(decided.path("ruleSetId").stringValue()).isEqualTo(versionTwo.toString());
        assertThat(decided.path("version").intValue()).isEqualTo(2);
        assertThat(decided.path("status").stringValue()).isEqualTo("ACTIVE");
        assertThat(decided.path("retiredRuleSetId").stringValue())
                .as("the predecessor retired in the same transaction")
                .isEqualTo(VERSION_ONE.toString());
        assertThat(ruleSetStatus(versionTwo)).isEqualTo("ACTIVE");
        assertThat(ruleSetStatus(VERSION_ONE)).isEqualTo("RETIRED");
        assertThat(string("SELECT decided_by FROM reconciliation.rule_set WHERE id = ?",
                versionTwo)).isEqualTo(approver.actorId());
        assertThat(string("SELECT decided_by FROM reconciliation.rule_set WHERE id = ?",
                VERSION_ONE)).as("a retirement keeps the activation's decision")
                .isEqualTo("test-activator");
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set WHERE source_id = ? AND"
                + " status = 'ACTIVE'", PRIVATE_SOURCE)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.RuleSetActivated' AND target_id = ? AND reason = ? AND"
                + " actor_id = ?", versionTwo.toString(), activationReason, approver.actorId()))
                .isEqualTo(1);
        assertThat(column("SELECT COALESCE(from_status, '') || '>' || to_status FROM"
                + " reconciliation.rule_set_event WHERE rule_set_id = ? ORDER BY seq", versionTwo))
                .containsExactly(">PROPOSED", "PROPOSED>ACTIVE");
        assertThat(column("SELECT COALESCE(from_status, '') || '>' || to_status FROM"
                + " reconciliation.rule_set_event WHERE rule_set_id = ? ORDER BY seq", VERSION_ONE))
                .containsExactly("ACTIVE>RETIRED");

        HttpResponse<String> retried = post(approver.token(),
                "/rule-sets/" + versionTwo + "/approval", null, reason(activationReason));
        assertThat(retried.statusCode()).as(retried.body()).isEqualTo(200);
        JsonNode converged = json(retried);
        assertThat(converged.path("ruleSetId").stringValue()).isEqualTo(versionTwo.toString());
        assertThat(converged.path("version").intValue()).isEqualTo(2);
        assertThat(converged.path("status").stringValue()).isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.RuleSetActivated' AND target_id = ?", versionTwo.toString()))
                .as("the retry converged and wrote nothing").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set_event WHERE"
                + " rule_set_id = ?", versionTwo)).isEqualTo(2);
        HttpResponse<String> late = post(proposer.token(),
                "/rule-sets/" + versionTwo + "/rejection", null, reason("too late to object"));
        assertThat(late.statusCode()).as(late.body()).isEqualTo(409);
        assertThat(late.body()).contains("reconciliation.RuleSetNotPending");

        // The withdrawal: the proposer rejects their own proposal - it changes no policy.
        JsonNode versionTwoListed =
                versionNamed(json(get(proposer.token(), "/rule-sets?source=" + PRIVATE_CODE)),
                        versionTwo);
        ObjectNode further = proposalFrom(versionTwoListed, "widen the window once more");
        widenDateWindow(further);
        HttpResponse<String> third = post(proposer.token(), "/rule-sets", key(),
                JSON.writeValueAsString(further));
        assertThat(third.statusCode()).as(third.body()).isEqualTo(201);
        assertThat(json(third).path("version").intValue()).isEqualTo(3);
        UUID versionThree = UUID.fromString(json(third).path("ruleSetId").stringValue());
        String withdrawal = "withdrawn by its proposer before review";
        HttpResponse<String> withdrawn = post(proposer.token(),
                "/rule-sets/" + versionThree + "/rejection", null, reason(withdrawal));
        assertThat(withdrawn.statusCode()).as(withdrawn.body()).isEqualTo(200);
        assertThat(json(withdrawn).path("status").stringValue()).isEqualTo("REJECTED");
        assertThat(absent(json(withdrawn).path("retiredRuleSetId"))).isTrue();
        HttpResponse<String> withdrawnAgain = post(proposer.token(),
                "/rule-sets/" + versionThree + "/rejection", null, reason(withdrawal));
        assertThat(withdrawnAgain.statusCode()).isEqualTo(200);
        assertThat(json(withdrawnAgain).path("status").stringValue()).isEqualTo("REJECTED");
        HttpResponse<String> afterWithdrawal = post(approver.token(),
                "/rule-sets/" + versionThree + "/approval", null, reason("activate it anyway"));
        assertThat(afterWithdrawal.statusCode()).as(afterWithdrawal.body()).isEqualTo(409);
        assertThat(afterWithdrawal.body()).contains("reconciliation.RuleSetNotPending");
        assertThat(ruleSetStatus(versionThree)).isEqualTo("REJECTED");
        assertThat(string("SELECT decided_by FROM reconciliation.rule_set WHERE id = ?",
                versionThree)).isEqualTo(proposer.actorId());
        assertThat(ruleSetStatus(versionTwo)).as("a withdrawal changes no policy")
                .isEqualTo("ACTIVE");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.RuleSetRejected' AND target_id = ? AND reason = ?",
                versionThree.toString(), withdrawal)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.RuleSetProposed' AND target_id IN (?, ?)",
                versionTwo.toString(), versionThree.toString()))
                .as("one proposal record each - the replayed key proposed nothing")
                .isEqualTo(2);
    }

    // ----------------------------------------------------------------- case 2: forward only

    @Test
    @Order(3)
    @DisplayName("a new version governs only new decisions: version 1's run replays IDENTICAL"
            + " with every position and allocation byte-identical, no decision is written under"
            + " version 1 again, and the next rematch and run decisions pin version 2")
    void aNewVersionGovernsOnlyNewDecisions() throws Exception {
        Session investigator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        JsonNode replay = replayOf(investigator, runOne);
        assertThat(replay.path("verdict").stringValue()).isEqualTo("IDENTICAL");
        assertThat(replay.path("divergences").intValue()).isZero();
        assertThat(replay.path("replayed").intValue()).isEqualTo(5);
        assertThat(replay.path("notReplayed").intValue()).isZero();
        assertThat(replay.path("pendingRematch").intValue()).isZero();
        assertThat(absent(replay.path("firstDivergentDecision"))).isTrue();
        assertThat(positions())
                .as("the activation and the replay moved no position")
                .isEqualTo(positionsBeforeActivation);
        assertThat(suiteRows())
                .as("every decision, allocation, item, expectation, suspense item and break of"
                        + " this suite is byte-identical")
                .isEqualTo(rowsBeforeActivation);
        assertThat(count("SELECT count(*) FROM reconciliation.run_replay WHERE run_id = ? AND"
                + " verdict = 'IDENTICAL'", runOne)).isEqualTo(1);
        assertThat(uuid("SELECT rule_set_id FROM reconciliation.reconciliation_batch WHERE"
                + " id = ?", runOne)).as("the run keeps the version that decided it")
                .isEqualTo(VERSION_ONE);

        // The waiting line's expectation, opened after its decision, and a line of a later
        // run spending its balancer's 15.00 - the two arrive together, so the residue balances.
        UUID waitingExpectation = openExpectation(KeyKind.PSP_CAPTURE_REF, waitingKey, 15_00);
        UUID runTwo = seedRun(versionTwo, line(1, 15_00, balancerKey));
        sweepUntilCompleted(runTwo);

        UUID rematch = uuid("SELECT id FROM reconciliation.match_decision WHERE"
                + " external_item_id = ? AND origin = 'REMATCH'", waitingItem);
        assertThat(rematch).as("the waiting line was rematched").isNotNull();
        assertThat(uuid("SELECT rule_set_id FROM reconciliation.match_decision WHERE id = ?",
                rematch)).as("a rematch decides under the version ACTIVE when it runs")
                .isEqualTo(versionTwo);
        assertThat(string("SELECT outcome FROM reconciliation.match_decision WHERE id = ?",
                rematch)).isEqualTo("MATCHED");
        assertThat(string("SELECT expectation_id || ':' || amount_minor FROM"
                + " reconciliation.allocation WHERE decision_id = ?", rematch))
                .isEqualTo(waitingExpectation + ":1500");
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?",
                waitingItem)).isEqualTo("MATCHED");
        assertThat(uuid("SELECT d.rule_set_id FROM reconciliation.match_decision d JOIN"
                + " reconciliation.external_item i ON i.id = d.external_item_id WHERE"
                + " i.run_id = ?", runTwo)).isEqualTo(versionTwo);
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                balancer)).isEqualTo("SETTLED");
        assertThat(string("SELECT status FROM reconciliation.break WHERE expectation_id = ? AND"
                + " type = 'AMOUNT_MISMATCH'", balancer))
                .as("the balancer's shortfall is explained to zero by its last tranche")
                .isEqualTo("RESOLVED");
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision d JOIN"
                + " reconciliation.external_item i ON i.id = d.external_item_id WHERE"
                + " i.source_id = ? AND d.rule_set_id = ?", PRIVATE_SOURCE, VERSION_ONE))
                .as("no decision is written under a retired version")
                .isEqualTo(5);
        assertTheIdentityIsUnmoved("after the rematch under version 2");
    }

    // ----------------------------------------------------------------- case 3: reprocess

    @Test
    @Order(4)
    @DisplayName("reprocessing touches residual items only: one REPROCESS run pinned to the"
            + " ACTIVE version, one decision per residual line - the parked line the new rule"
            + " reaches allocated, unparked and its break closed EVIDENCED, the waiting line"
            + " examined unchanged - no matched line touched, one open run under ten racing"
            + " requests, and the run replaying IDENTICAL")
    void reprocessingTouchesResidualItemsOnly() throws Exception {
        Session proposer = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        Session approver = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        Session investigator = sessionWith(RoleName.RECONCILIATION_OPERATOR);

        // A WAITING residual beside the PARKED one, balanced the first run's way.
        residualKey = marker();
        residualBalancerKey = marker();
        openExpectation(KeyKind.PSP_CAPTURE_REF, residualBalancerKey, 16_00);
        runThree =
                seedRun(versionTwo, line(1, 15_00, residualKey),
                        line(2, 1_00, residualBalancerKey));
        residualItem = itemOf(runThree, 1);
        sweepUntilCompleted(runThree);
        assertThat(itemStatuses(runThree)).containsExactly("UNMATCHED", "MATCHED");

        // Version 4: an OUR_REF rule ahead of the capture key - proposed whole, activated by a
        // second controller.
        JsonNode versionTwoListed =
                versionNamed(json(get(proposer.token(), "/rule-sets?source=" + PRIVATE_CODE)),
                        versionTwo);
        ObjectNode proposal =
                proposalFrom(versionTwoListed, "the PSP quotes our own reference on captures");
        ArrayNode rules = JSON.createArrayNode();
        rules.addObject()
                .put("priority", 1)
                .put("lineType", "CAPTURE")
                .put("keyKind", "OUR_REF")
                .put("expectationKind", "CARD_CAPTURE")
                .put("cardinality", "ONE_TO_ONE")
                .put("operationAnchored", false)
                .put("graceHours", 48);
        ObjectNode carried = (ObjectNode) versionTwoListed.path("rules").get(0).deepCopy();
        carried.put("priority", 2);
        rules.add(carried);
        proposal.set("rules", rules);
        HttpResponse<String> proposed = post(proposer.token(), "/rule-sets", key(),
                JSON.writeValueAsString(proposal));
        assertThat(proposed.statusCode()).as(proposed.body()).isEqualTo(201);
        versionFour = UUID.fromString(json(proposed).path("ruleSetId").stringValue());
        HttpResponse<String> activated = post(approver.token(),
                "/rule-sets/" + versionFour + "/approval", null,
                reason("confirmed with the PSP that our reference is quoted"));
        assertThat(activated.statusCode()).as(activated.body()).isEqualTo(200);
        assertThat(json(activated).path("retiredRuleSetId").stringValue())
                .isEqualTo(versionTwo.toString());

        List<String> matchedBefore = column("SELECT id::text FROM reconciliation.external_item"
                + " WHERE source_id = ? AND status = 'MATCHED' ORDER BY id", PRIVATE_SOURCE);
        Map<String, String> matchedTouchesBefore = touchesOf(matchedBefore);

        String requestKey = key();
        String requestBody = reason("re-decide the residuals under the new reference rule");
        HttpResponse<String> opened = post(proposer.token(),
                "/sources/" + PRIVATE_CODE + "/reprocessing", requestKey, requestBody);
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(202);
        JsonNode receipt = json(opened);
        assertThat(receipt.path("sourceCode").stringValue()).isEqualTo(PRIVATE_CODE);
        assertThat(receipt.path("ruleSetId").stringValue()).isEqualTo(versionFour.toString());
        assertThat(receipt.path("itemCount").intValue())
                .as("the parked line and the waiting line").isEqualTo(2);
        reprocessRun = UUID.fromString(receipt.path("runId").stringValue());
        HttpResponse<String> again = post(proposer.token(),
                "/sources/" + PRIVATE_CODE + "/reprocessing", requestKey, requestBody);
        assertThat(again.statusCode()).isEqualTo(202);
        assertThat(again.body()).as("the same key replays the receipt").isEqualTo(opened.body());
        assertThat(string("SELECT kind || ':' || status FROM"
                + " reconciliation.reconciliation_batch WHERE id = ?", reprocessRun))
                .isEqualTo("REPROCESS:OPEN");
        assertThat(uuid("SELECT rule_set_id FROM reconciliation.reconciliation_batch WHERE"
                + " id = ?", reprocessRun)).isEqualTo(versionFour);
        assertThat(string("SELECT requested_by FROM reconciliation.reconciliation_batch WHERE"
                + " id = ?", reprocessRun)).isEqualTo(proposer.actorId());
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.ReprocessingRequested' AND target_id = ?",
                reprocessRun.toString())).isEqualTo(1);
        HttpResponse<String> whileOpen = post(approver.token(),
                "/sources/" + PRIVATE_CODE + "/reprocessing", key(), requestBody);
        assertThat(whileOpen.statusCode()).as(whileOpen.body()).isEqualTo(409);
        assertThat(whileOpen.body()).contains("reconciliation.ReprocessingInProgress");
        assertThat(reprocessRunsOfPrivateSource()).isEqualTo(1);

        // Ten instances sweep the one REPROCESS run at once: the source's try-lock and the
        // anti-join on this run's decisions decide each residual once, whatever the
        // interleaving (counted below and here).
        List<Callable<Integer>> sweepers = new ArrayList<>();
        for (int racer = 0; racer < 10; racer++) {
            sweepers.add(() -> {
                matching.sweep();
                return 0;
            });
        }
        race(sweepers);
        sweepUntilCompleted(reprocessRun);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'reconciliation.RunCompleted' AND target_id = ?", reprocessRun.toString()))
                .as("the reprocess run completed once under ten sweepers").isEqualTo(1);

        UUID parkedDecision = uuid("SELECT id FROM reconciliation.match_decision WHERE"
                + " external_item_id = ? AND origin = 'REPROCESS' AND run_id = ?",
                parkedItem, reprocessRun);
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " external_item_id = ? AND origin = 'REPROCESS'", parkedItem)).isEqualTo(1);
        assertThat(string("SELECT outcome FROM reconciliation.match_decision WHERE id = ?",
                parkedDecision)).isEqualTo("MATCHED");
        assertThat(uuid("SELECT rule_set_id FROM reconciliation.match_decision WHERE id = ?",
                parkedDecision)).isEqualTo(versionFour);
        assertThat(string("SELECT expectation_id || ':' || amount_minor FROM"
                + " reconciliation.allocation WHERE decision_id = ?", parkedDecision))
                .as("the parked 20.00 allocated whole to what only the new rule reaches")
                .isEqualTo(reachedOnlyByOurRef + ":2000");
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                + " expectation_id = ?", reachedOnlyByOurRef))
                .as("allocated once under ten sweepers").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.resolution r JOIN"
                + " reconciliation.break b ON b.id = r.break_id WHERE"
                + " b.external_item_id = ? AND r.kind = 'EVIDENCED'", parkedItem))
                .as("evidenced, and so unparked, once under ten sweepers").isEqualTo(1);
        assertThat(string("SELECT status || ':' || allocated_minor || ':' || parked_minor FROM"
                + " reconciliation.external_item WHERE id = ?", parkedItem))
                .isEqualTo("MATCHED:3000:0");
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                reachedOnlyByOurRef)).isEqualTo("SETTLED");
        assertThat(string("SELECT status FROM reconciliation.suspense_item WHERE"
                + " external_item_id = ?", parkedItem)).isEqualTo("RELEASED");
        UUID parkedBreak = uuid("SELECT id FROM reconciliation.break WHERE external_item_id = ?"
                + " AND type = 'AMOUNT_MISMATCH'", parkedItem);
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", parkedBreak))
                .isEqualTo("RESOLVED");
        assertThat(string("SELECT kind || ':' || status FROM reconciliation.resolution WHERE"
                + " break_id = ?", parkedBreak)).isEqualTo("EVIDENCED:APPROVED");
        assertThat(uuid("SELECT decision_id FROM reconciliation.resolution WHERE break_id = ?",
                parkedBreak)).isEqualTo(parkedDecision);
        UUID unpark = uuid("SELECT park_id FROM reconciliation.resolution WHERE break_id = ?",
                parkedBreak);
        assertThat(string("SELECT kind FROM reconciliation.park WHERE id = ?", unpark))
                .isEqualTo("UNPARK");
        assertThat(string("SELECT j.idempotency_scope FROM reconciliation.resolution r JOIN"
                + " ledger.journal_entry j ON j.id = r.journal_entry_id WHERE r.break_id = ?",
                parkedBreak))
                .as("the unpark is the park's exact inverse, posted under its own key")
                .contains("recon-suspense:");

        UUID waitingDecision = uuid("SELECT id FROM reconciliation.match_decision WHERE"
                + " external_item_id = ? AND origin = 'REPROCESS' AND run_id = ?",
                residualItem, reprocessRun);
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " external_item_id = ? AND origin = 'REPROCESS' AND run_id = ?",
                residualItem, reprocessRun)).isEqualTo(1);
        assertThat(string("SELECT outcome FROM reconciliation.match_decision WHERE id = ?",
                waitingDecision)).isEqualTo("UNMATCHED");
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                + " decision_id = ?", waitingDecision)).isZero();
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?",
                residualItem)).as("examined, and left exactly as it was").isEqualTo("UNMATCHED");
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " run_id = ?", reprocessRun)).as("residual lines only").isEqualTo(2);
        assertThat(touchesOf(matchedBefore))
                .as("no matched line gained a decision or an allocation")
                .isEqualTo(matchedTouchesBefore);
        assertTheIdentityIsUnmoved("after reprocessing");

        // Ten racing requests, ten distinct keys: one open run per source.
        List<Callable<HttpResponse<String>>> racers = new ArrayList<>();
        for (int racer = 0; racer < 10; racer++) {
            String token = racer % 2 == 0 ? proposer.token() : approver.token();
            String racerKey = key();
            racers.add(() -> post(token, "/sources/" + PRIVATE_CODE + "/reprocessing",
                    racerKey, reason("a racing reprocess request")));
        }
        List<HttpResponse<String>> outcomes = race(racers);
        assertThat(outcomes).filteredOn(outcome -> outcome.statusCode() == 202).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome.statusCode() == 409)
                .hasSize(9)
                .allMatch(outcome -> outcome.body()
                        .contains("reconciliation.ReprocessingInProgress"));
        UUID racedRun = UUID.fromString(json(outcomes.stream()
                        .filter(outcome -> outcome.statusCode() == 202)
                        .findFirst()
                        .orElseThrow())
                .path("runId").stringValue());
        assertThat(reprocessRunsOfPrivateSource()).as("exactly one run opened").isEqualTo(2);
        sweepUntilCompleted(racedRun);
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " run_id = ?", racedRun)).as("the one residual left").isEqualTo(1);

        JsonNode replay = replayOf(investigator, reprocessRun);
        assertThat(replay.path("verdict").stringValue()).isEqualTo("IDENTICAL");
        assertThat(replay.path("replayed").intValue()).isEqualTo(2);
        assertThat(replay.path("divergences").intValue()).isZero();
        assertThat(replay.path("notReplayed").intValue()).isZero();
    }

    // ----------------------------------------------------------------- case 4: requeue

    @Test
    @Order(5)
    @DisplayName("a BLOCKED run holds its source until a person requeues it: IN_PROGRESS, the"
            + " blocked reading back to zero, the completion closing its RUN_BLOCKED break"
            + " EVIDENCED naming the run; a run not blocked is refused, and ten racing requeues"
            + " of one blocked run have one winner")
    void aBlockedRunIsRequeuedAndItsCompletionClosesItsBreak() throws Exception {
        Session controller = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        Session secondController = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        Session operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        String lineKey = marker();
        openExpectation(KeyKind.PSP_CAPTURE_REF, lineKey, 12_00);
        UUID runFour = seedRun(versionFour, line(1, 12_00, lineKey));
        UUID runBlocked = block(runFour);
        assertThat(blockedOfPrivateSource()).isEqualTo(1L);
        matching.sweep();
        assertThat(runStatus(runFour)).as("nothing resumes a blocked run but a person")
                .isEqualTo("BLOCKED");

        HttpResponse<String> refused = post(operator.token(), "/runs/" + runFour + "/requeue",
                null, reason("an operator resuming a run"));
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(runStatus(runFour)).isEqualTo("BLOCKED");

        String requeueReason = "the poisoned dependency is fixed";
        HttpResponse<String> requeued = post(controller.token(), "/runs/" + runFour + "/requeue",
                null, reason(requeueReason));
        assertThat(requeued.statusCode()).as(requeued.body()).isEqualTo(200);
        assertThat(json(requeued).path("runId").stringValue()).isEqualTo(runFour.toString());
        assertThat(json(requeued).path("status").stringValue()).isEqualTo("IN_PROGRESS");
        assertThat(string("SELECT status || ':' || failures FROM"
                + " reconciliation.reconciliation_batch WHERE id = ?", runFour))
                .isEqualTo("IN_PROGRESS:0");
        assertThat(count("SELECT count(*) FROM reconciliation.reconciliation_batch_event WHERE"
                + " run_id = ? AND from_status = 'BLOCKED' AND to_status = 'IN_PROGRESS' AND"
                + " reason = ?", runFour, requeueReason)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.RunRequeued' AND target_id = ? AND actor_id = ?",
                runFour.toString(), controller.actorId())).isEqualTo(1);
        assertThat(blockedOfPrivateSource())
                .as("finapp.reconciliation.run.blocked's reading for the source")
                .isZero();
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", runBlocked))
                .as("the break stands until the run completes").isNotEqualTo("RESOLVED");

        sweepUntilCompleted(runFour);
        assertThat(itemStatuses(runFour)).containsExactly("MATCHED");
        assertClosedByItsCompletion(runBlocked, runFour);

        HttpResponse<String> notBlocked = post(controller.token(), "/runs/" + runOne
                + "/requeue", null, reason("requeue a completed run"));
        assertThat(notBlocked.statusCode()).as(notBlocked.body()).isEqualTo(409);
        assertThat(notBlocked.body()).contains("reconciliation.RunNotBlocked");

        UUID emptyRun = seedRun(versionFour);
        UUID emptyBlocked = block(emptyRun);
        assertThat(blockedOfPrivateSource()).isEqualTo(1L);
        List<Callable<HttpResponse<String>>> racers = new ArrayList<>();
        for (int racer = 0; racer < 10; racer++) {
            String token = racer % 2 == 0 ? controller.token() : secondController.token();
            racers.add(() -> post(token, "/runs/" + emptyRun + "/requeue", null,
                    reason("a racing requeue")));
        }
        List<HttpResponse<String>> outcomes = race(racers);
        assertThat(outcomes).filteredOn(outcome -> outcome.statusCode() == 200).hasSize(1);
        assertThat(outcomes).filteredOn(outcome -> outcome.statusCode() == 409)
                .hasSize(9)
                .allMatch(outcome -> outcome.body().contains("reconciliation.RunNotBlocked"));
        assertThat(runStatus(emptyRun)).isEqualTo("IN_PROGRESS");
        assertThat(count("SELECT count(*) FROM reconciliation.reconciliation_batch_event WHERE"
                + " run_id = ? AND from_status = 'BLOCKED' AND to_status = 'IN_PROGRESS'",
                emptyRun)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.RunRequeued' AND target_id = ?", emptyRun.toString()))
                .isEqualTo(1);
        assertThat(blockedOfPrivateSource()).isZero();
        sweepUntilCompleted(emptyRun);
        assertClosedByItsCompletion(emptyBlocked, emptyRun);
        assertTheIdentityIsUnmoved("after the requeued runs completed");
    }

    // ----------------------------------------------------------------- case 5: replay

    @Test
    @Order(6)
    @DisplayName("a replay explains and never repairs: IDENTICAL after runs, rematches and"
            + " reprocessing, a pending rematch counted apart, and a planted contradiction"
            + " DIVERGED - ONE CRITICAL break on the first divergent decision however many"
            + " replays race, the diverged count rising with each")
    void replayExplainsAndCatchesAPlantedDivergence() throws Exception {
        Session investigator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        JsonNode first = replayOf(investigator, runOne);
        assertThat(first.path("verdict").stringValue()).isEqualTo("IDENTICAL");
        assertThat(first.path("replayed").intValue())
                .as("five run decisions, the waiting line's rematch, the parked line's reprocess")
                .isEqualTo(7);
        assertThat(first.path("notReplayed").intValue()).isZero();
        assertThat(first.path("divergences").intValue()).isZero();
        assertThat(first.path("pendingRematch").intValue()).isZero();

        // A newer expectation the waiting residual's key reaches, NOT swept: pending, apart.
        openExpectation(KeyKind.PSP_CAPTURE_REF, residualKey, 15_00);
        JsonNode pending = replayOf(investigator, runThree);
        assertThat(pending.path("verdict").stringValue())
                .as("a decision not yet taken cannot diverge").isEqualTo("IDENTICAL");
        assertThat(pending.path("pendingRematch").intValue()).isGreaterThanOrEqualTo(1);
        assertThat(pending.path("divergences").intValue()).isZero();
        assertThat(pending.path("replayed").intValue())
                .as("two run decisions and the waiting line's two reprocess examinations")
                .isEqualTo(4);
        // The balance restored: a later line spends the residual's balancer as the sweep
        // rematches the residual to its newer expectation.
        UUID runSix = seedRun(versionFour, line(1, 15_00, residualBalancerKey));
        sweepUntilCompleted(runSix);
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?",
                residualItem)).isEqualTo("MATCHED");
        assertThat(uuid("SELECT rule_set_id FROM reconciliation.match_decision WHERE"
                + " external_item_id = ? AND origin = 'REMATCH'", residualItem))
                .isEqualTo(versionFour);
        JsonNode settled = replayOf(investigator, runThree);
        assertThat(settled.path("verdict").stringValue()).isEqualTo("IDENTICAL");
        assertThat(settled.path("pendingRematch").intValue()).isZero();
        assertThat(settled.path("replayed").intValue()).isEqualTo(5);

        // The planted contradiction: a stored ALLOCATE whose own snapshot holds TWO candidates.
        String plantedKey = marker();
        UUID plantedCandidate = openExpectation(KeyKind.PSP_CAPTURE_REF, plantedKey, 9_00);
        UUID runFive = seedRun(versionFour, line(1, 9_00, plantedKey));
        sweepUntilCompleted(runFive);
        UUID plantedItem = itemOf(runFive, 1);
        assertThat(replayOf(investigator, runFive).path("verdict").stringValue())
                .isEqualTo("IDENTICAL");
        UUID planted = plantContradiction(runFive, plantedItem, plantedCandidate);

        double divergedBefore = divergedReplays();
        JsonNode diverged = replayOf(investigator, runFive);
        assertThat(diverged.path("verdict").stringValue()).isEqualTo("DIVERGED");
        assertThat(diverged.path("divergences").intValue()).isEqualTo(1);
        assertThat(diverged.path("replayed").intValue()).isEqualTo(2);
        assertThat(diverged.path("firstDivergentDecision").stringValue())
                .isEqualTo(planted.toString());
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE decision_id = ? AND"
                + " type = 'PROCESSING_ERROR' AND cause = 'REPLAY_DIVERGED' AND status <>"
                + " 'RESOLVED' AND severity = 'CRITICAL' AND source_id = ?",
                planted, PRIVATE_SOURCE)).isEqualTo(1);

        long replaysBefore = count("SELECT count(*) FROM reconciliation.run_replay WHERE"
                + " run_id = ?", runFive);
        long replayAuditsBefore = count("SELECT count(*) FROM platform.audit_record WHERE"
                + " operation = 'reconciliation.RunReplayed' AND target_id = ? AND outcome ="
                + " 'SUCCEEDED'", runFive.toString());
        List<Callable<HttpResponse<String>>> racers = new ArrayList<>();
        for (int racer = 0; racer < 10; racer++) {
            racers.add(() -> post(investigator.token(), "/runs/" + runFive + "/replay", null,
                    null));
        }
        List<HttpResponse<String>> outcomes = race(racers);
        assertThat(outcomes).allMatch(outcome -> outcome.statusCode() == 200);
        assertThat(outcomes)
                .allMatch(outcome -> "DIVERGED".equals(json(outcome).path("verdict")
                        .stringValue()))
                .allMatch(outcome -> planted.toString().equals(
                        json(outcome).path("firstDivergentDecision").stringValue()));
        assertThat(count("SELECT count(*) FROM reconciliation.run_replay WHERE run_id = ?",
                runFive)).as("ten verdicts appended").isEqualTo(replaysBefore + 10);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.RunReplayed' AND target_id = ? AND outcome = 'SUCCEEDED'",
                runFive.toString()))
                .as("ten racing replays write ten reconciliation.RunReplayed records, one per"
                        + " committed verdict")
                .isEqualTo(replayAuditsBefore + 10);
        assertThat(count("SELECT count(*) FROM reconciliation.run_replay WHERE run_id = ? AND"
                + " verdict = 'DIVERGED' AND first_divergent_decision = ?", runFive, planted))
                .isEqualTo(11);
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE decision_id = ? AND"
                + " type = 'PROCESSING_ERROR'", planted))
                .as("one open divergence break, converged on its one-open unique")
                .isEqualTo(1);
        assertThat(divergedReplays() - divergedBefore)
                .as("finapp.reconciliation.replay{outcome=diverged}, once per committed verdict")
                .isEqualTo(11.0);
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                + " external_item_id = ?", plantedItem)).as("a replay repairs nothing")
                .isEqualTo(1);
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?",
                plantedItem)).isEqualTo("MATCHED");
        assertTheIdentityIsUnmoved("at rest: every line MATCHED, every expectation SETTLED");

        // The pin, proven against a version that would decide differently: version 5
        // moves every capture rule off priority 1 - the priority version 1's decisions
        // fired - so a replay that read the ACTIVE version would find no rule for them
        // and conclude otherwise. It must still read version 1 (INV-HIST-04, the gate's
        // find: a replay judged under the active version survived every case above).
        Session proposer = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        Session approver = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        JsonNode versionFourListed =
                versionNamed(json(get(proposer.token(), "/rule-sets?source=" + PRIVATE_CODE)),
                        versionFour);
        ObjectNode shifted =
                proposalFrom(versionFourListed, "room for a higher-priority rule later");
        ArrayNode shiftedRules = JSON.createArrayNode();
        for (JsonNode rule : versionFourListed.path("rules")) {
            ObjectNode moved = (ObjectNode) rule.deepCopy();
            moved.put("priority", rule.path("priority").intValue() + 10);
            shiftedRules.add(moved);
        }
        shifted.set("rules", shiftedRules);
        HttpResponse<String> proposedFive = post(proposer.token(), "/rule-sets", key(),
                JSON.writeValueAsString(shifted));
        assertThat(proposedFive.statusCode()).as(proposedFive.body()).isEqualTo(201);
        UUID versionFive =
                UUID.fromString(json(proposedFive).path("ruleSetId").stringValue());
        HttpResponse<String> activatedFive = post(approver.token(),
                "/rule-sets/" + versionFive + "/approval", null,
                reason("priorities renumbered with room to spare"));
        assertThat(activatedFive.statusCode()).as(activatedFive.body()).isEqualTo(200);
        assertThat(count("SELECT count(*) FROM reconciliation.rule WHERE rule_set_id = ?"
                + " AND priority = 1", versionFive))
                .as("version 5 has no rule where version 1's decisions fired").isZero();
        long breaksBefore = count("SELECT count(*) FROM reconciliation.break WHERE"
                + " type = 'PROCESSING_ERROR'");
        JsonNode pinned = replayOf(investigator, runOne);
        assertThat(pinned.path("verdict").stringValue())
                .as("version 1's run replays under version 1, whatever is active now")
                .isEqualTo("IDENTICAL");
        assertThat(pinned.path("replayed").intValue())
                .as("its five, the rematch under version 2 and the reprocess under version 4,"
                        + " each judged under its own")
                .isEqualTo(7);
        assertThat(pinned.path("divergences").intValue()).isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE"
                + " type = 'PROCESSING_ERROR'")).isEqualTo(breaksBefore);
    }

    // ----------------------------------------------------------------- case 6: negatives

    @Test
    @Order(7)
    @DisplayName("every door holds its permission - anonymous 401, role-less 403, the"
            + " controller kept from the replay and the investigator from every policy and run"
            + " door - and unknown or malformed runs, sources and versions are one named 404"
            + " each, recording nothing")
    void theDoorsHoldTheirPermissionsAndNameTheirAbsences() throws Exception {
        Session operator = sessionWith(RoleName.RECONCILIATION_OPERATOR);
        Session controller = sessionWith(RoleName.RECONCILIATION_CONTROLLER);
        Session roleless = rolelessSession();
        String some = UUID.randomUUID().toString();
        String probe = reason("a probe of the door");
        String proposal = "{\"sourceCode\":\"" + UNKNOWN_CODE + "\",\"reason\":\"probe\","
                + "\"fundingLagDays\":2,\"gainMinAgeDays\":90,\"lagDays\":{},\"rules\":"
                + "[{\"priority\":1,\"lineType\":\"CAPTURE\",\"keyKind\":\"PSP_CAPTURE_REF\","
                + "\"expectationKind\":\"CARD_CAPTURE\",\"cardinality\":\"ONE_TO_ONE\","
                + "\"operationAnchored\":false,\"graceHours\":48}],\"tolerances\":[],"
                + "\"feeSchedules\":[],\"severityThresholds\":{}}";
        long rulesBefore = count("SELECT count(*) FROM reconciliation.rule_set WHERE"
                + " source_id = ?", PRIVATE_SOURCE);
        long reprocessBefore = reprocessRunsOfPrivateSource();

        record Door(String method, String path, String body) {}
        List<Door> controllerDoors =
                List.of(
                        new Door("GET", "/rule-sets?source=" + UNKNOWN_CODE, null),
                        new Door("POST", "/rule-sets", proposal),
                        new Door("POST", "/rule-sets/" + some + "/approval", probe),
                        new Door("POST", "/rule-sets/" + some + "/rejection", probe),
                        new Door("POST", "/sources/" + UNKNOWN_CODE + "/reprocessing", probe),
                        new Door("POST", "/runs/" + runOne + "/requeue", probe));
        Door replayDoor = new Door("POST", "/runs/" + runOne + "/replay", null);
        List<Door> every = new ArrayList<>(controllerDoors);
        every.add(replayDoor);
        for (Door door : every) {
            assertThat(call(null, door.method(), door.path(), door.body(), key()).statusCode())
                    .as("%s %s anonymous", door.method(), door.path())
                    .isEqualTo(401);
            assertThat(call(roleless.token(), door.method(), door.path(), door.body(), key())
                            .statusCode())
                    .as("%s %s role-less", door.method(), door.path())
                    .isEqualTo(403);
        }
        for (Door door : controllerDoors) {
            assertThat(call(operator.token(), door.method(), door.path(), door.body(), key())
                            .statusCode())
                    .as("%s %s: the investigator works breaks, never policy or runs",
                            door.method(), door.path())
                    .isEqualTo(403);
        }
        assertThat(call(controller.token(), replayDoor.method(), replayDoor.path(), null,
                        null).statusCode())
                .as("the replay is the investigator's: it explains, it never administers")
                .isEqualTo(403);

        record Absent(Session session, String method, String path, String body, String code) {}
        List<Absent> absents =
                List.of(
                        new Absent(operator, "POST", "/runs/" + some + "/replay", null,
                                "reconciliation.RunNotFound"),
                        new Absent(operator, "POST", "/runs/not-a-uuid/replay", null,
                                "reconciliation.RunNotFound"),
                        new Absent(controller, "POST", "/runs/" + some + "/requeue", probe,
                                "reconciliation.RunNotFound"),
                        new Absent(controller, "POST", "/runs/not-a-uuid/requeue", probe,
                                "reconciliation.RunNotFound"),
                        new Absent(controller, "POST",
                                "/sources/" + UNKNOWN_CODE + "/reprocessing", probe,
                                "reconciliation.SourceNotFound"),
                        new Absent(controller, "GET", "/rule-sets?source=" + UNKNOWN_CODE,
                                null, "reconciliation.SourceNotFound"),
                        new Absent(controller, "POST", "/rule-sets", proposal,
                                "reconciliation.SourceNotFound"),
                        new Absent(controller, "POST", "/rule-sets/" + some + "/approval",
                                probe, "reconciliation.RuleSetNotFound"),
                        new Absent(controller, "POST", "/rule-sets/not-a-uuid/approval", probe,
                                "reconciliation.RuleSetNotFound"),
                        new Absent(controller, "POST", "/rule-sets/" + some + "/rejection",
                                probe, "reconciliation.RuleSetNotFound"),
                        new Absent(controller, "POST", "/rule-sets/not-a-uuid/rejection",
                                probe, "reconciliation.RuleSetNotFound"));
        for (Absent absent : absents) {
            HttpResponse<String> answer = call(absent.session().token(), absent.method(),
                    absent.path(), absent.body(), key());
            assertThat(answer.statusCode()).as(absent.path()).isEqualTo(404);
            assertThat(answer.body()).as(absent.path()).contains(absent.code());
        }
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE actor_id IN (?, ?,"
                + " ?) AND operation LIKE 'reconciliation.%'", operator.actorId(),
                controller.actorId(), roleless.actorId()))
                .as("no refusal and no guessed identifier records a reconciliation act (the"
                        + " denials are the session door's own records)")
                .isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.rule_set WHERE source_id = ?",
                PRIVATE_SOURCE)).isEqualTo(rulesBefore);
        assertThat(reprocessRunsOfPrivateSource()).isEqualTo(reprocessBefore);
    }

    // ----------------------------------------------------------------- the one double

    /**
     * Settlement's source register answers this suite's private code, and delegates every other
     * call to the real store untouched (the class comment says why).
     */
    @TestConfiguration
    static class PrivateSourceCode {

        @Bean
        @Primary
        @SuppressWarnings("unchecked")
        SettlementFileStore<Connection> privateCodeAwareSettlementFileStore(
                @Qualifier("settlementFileStore")
                        SettlementFileStore<Connection> settlementFileStore) {
            return (SettlementFileStore<Connection>)
                    Proxy.newProxyInstance(
                            SettlementFileStore.class.getClassLoader(),
                            new Class<?>[] {SettlementFileStore.class},
                            (proxy, method, arguments) -> {
                                if ("sourceByCode".equals(method.getName())
                                        && arguments != null
                                        && arguments.length == 2
                                        && PRIVATE_CODE.equals(arguments[1])) {
                                    return Optional.of(
                                            new SettlementFileStore.SourceRow(
                                                    PRIVATE_SOURCE, PRIVATE_CODE,
                                                    SourceKind.PSP_SETTLEMENT_REPORT, true));
                                }
                                try {
                                    return method.invoke(settlementFileStore, arguments);
                                } catch (InvocationTargetException failure) {
                                    throw failure.getCause();
                                }
                            });
        }
    }

    // ----------------------------------------------------------------- seeding

    private static void seedVersionOne() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            execute(app,
                    "INSERT INTO reconciliation.rule_set (id, source_id, version, status,"
                            + " funding_lag_days, gain_min_age_days, effective_from,"
                            + " proposed_by, decided_by, reason, created_at, correlation_id)"
                            + " VALUES (?, ?, 1, 'PROPOSED', 2, 90, ?, 'test', NULL,"
                            + " 'ReconciliationAdministrationDatabaseTest private rule set',"
                            + " now(), 'p8-tsk-022-app-test') ON CONFLICT (id) DO NOTHING",
                    VERSION_ONE, PRIVATE_SOURCE, java.sql.Date.valueOf(SETTLED_ON));
            execute(app,
                    "INSERT INTO reconciliation.rule (rule_set_id, priority, line_type,"
                            + " key_kind, expectation_kind, cardinality, operation_anchored,"
                            + " grace_hours) VALUES (?, 1, 'CAPTURE', 'PSP_CAPTURE_REF',"
                            + " 'CARD_CAPTURE', 'ONE_TO_ONE', false, 48) ON CONFLICT DO NOTHING",
                    VERSION_ONE);
            execute(app,
                    "INSERT INTO reconciliation.tolerance (rule_set_id, comparison, currency,"
                            + " absolute_minor, days) VALUES (?, 'SETTLEMENT_DATE_DAYS', NULL,"
                            + " NULL, 2) ON CONFLICT DO NOTHING",
                    VERSION_ONE);
            execute(app,
                    "INSERT INTO reconciliation.severity_threshold (rule_set_id, currency,"
                            + " high_value_minor) VALUES (?, 'EUR', 100000)"
                            + " ON CONFLICT DO NOTHING",
                    VERSION_ONE);
            execute(app,
                    "UPDATE reconciliation.rule_set SET status = 'ACTIVE', decided_by ="
                            + " 'test-activator', decided_at = now() WHERE id = ? AND status ="
                            + " 'PROPOSED'",
                    VERSION_ONE);
            app.commit();
        }
    }

    /** An INBOUND card capture of the private source, under the version ACTIVE now. */
    private UUID openExpectation(KeyKind kind, String keyValue, long minor) throws SQLException {
        String operationRef = "op-p8t22-" + keyValue;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID ruleSet = (UUID) one(app, "SELECT id FROM reconciliation.rule_set WHERE"
                    + " source_id = ? AND status = 'ACTIVE'", PRIVATE_SOURCE);
            UUID position =
                    new JdbcLedgerAccountStore()
                            .findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, EUR)
                            .orElseThrow()
                            .id()
                            .value();
            expectationRegister.open(
                    app,
                    new NewExpectation(
                            ExpectationKind.CARD_CAPTURE,
                            operationRef,
                            "payment-capture:" + operationRef,
                            PRIVATE_SOURCE,
                            AccountPurpose.SETTLEMENT_CLEARING,
                            position,
                            ExpectationDirection.INBOUND,
                            Money.ofPersisted(minor, EUR, 2),
                            Optional.of(IDS.next()),
                            SETTLED_ON,
                            Optional.empty(),
                            SETTLED_ON.plusDays(3),
                            ruleSet,
                            List.of(new NewExpectation.ExpectationKey(kind, keyValue)),
                            PLATFORM,
                            Instant.now(CLOCK),
                            CorrelationId.generate(IDS)));
            app.commit();
            return (UUID) one(app, "SELECT id FROM reconciliation.expectation WHERE kind ="
                    + " 'CARD_CAPTURE' AND operation_ref = ?", operationRef);
        }
    }

    private record Line(int lineNo, long minor, Map<ItemKeyKind, String> keys) {}

    private static Line line(int lineNo, long minor, String captureRef) {
        return new Line(lineNo, minor, Map.of(ItemKeyKind.PSP_CAPTURE_REF, captureRef));
    }

    private static Line line(int lineNo, long minor, String captureRef, String ourRef) {
        return new Line(lineNo, minor,
                Map.of(ItemKeyKind.PSP_CAPTURE_REF, captureRef, ItemKeyKind.OUR_REF, ourRef));
    }

    /** A BATCH run of the private source pinned to {@code ruleSet}, born OPEN. */
    private UUID seedRun(UUID ruleSet, Line... lines) throws SQLException {
        UUID runId = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            runs.birth(
                    app,
                    new ReconciliationRuns.NewRun(
                            runId,
                            PRIVATE_SOURCE,
                            Optional.of(IDS.next()),
                            RunKind.BATCH,
                            ruleSet,
                            SETTLED_ON,
                            Optional.of(SEQUENCES.incrementAndGet()),
                            lines.length,
                            Optional.empty(),
                            Optional.empty(),
                            PLATFORM,
                            Instant.now(CLOCK),
                            CorrelationId.generate(IDS)));
            List<ExternalItems.NewItem> newItems = new ArrayList<>();
            for (Line line : lines) {
                byte[] fingerprint = new byte[32];
                RANDOMNESS.nextBytes(fingerprint);
                newItems.add(
                        new ExternalItems.NewItem(
                                IDS.next(),
                                runId,
                                PRIVATE_SOURCE,
                                IDS.next(),
                                line.lineNo(),
                                ExternalLineType.CAPTURE,
                                ExpectationDirection.INBOUND,
                                Money.ofPersisted(line.minor(), EUR, 2),
                                AccountPurpose.SETTLEMENT_CLEARING,
                                SETTLED_ON,
                                Optional.of(SETTLED_ON),
                                Optional.of(SETTLED_ON),
                                fingerprint,
                                line.keys(),
                                Instant.now(CLOCK),
                                CorrelationId.generate(IDS)));
            }
            if (!newItems.isEmpty()) {
                items.birthAll(app, PLATFORM, newItems);
            }
            app.commit();
        }
        return runId;
    }

    /**
     * The chunk's own record of a run failing its bound - the failures counted, the
     * {@code BLOCKED} edge and the CRITICAL {@code RUN_BLOCKED} break - through the matcher's
     * store and the break register, exactly the writes {@code Matching.recordFailure} makes.
     */
    private UUID block(UUID runId) throws SQLException {
        BreakRegister.Raised raised;
        try (CorrelationContext.Scope correlation = CorrelationContext.enter(flow());
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            Instant now = Instant.now(CLOCK);
            for (int failure = 0; failure < 3; failure++) {
                matchingStore.bumpRunFailures(app, runId, now);
            }
            assertThat(matchingStore.blockRun(app, runId, PLATFORM, now)).isTrue();
            raised = breakRegister.raise(
                    app,
                    new BreakRegister.NewBreak(
                            IDS.next(),
                            BreakType.PROCESSING_ERROR,
                            BreakCause.RUN_BLOCKED,
                            BreakRegister.Subject.run(runId),
                            PRIVATE_SOURCE,
                            versionFour,
                            Money.ofPersisted(0, EUR, 2),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            PLATFORM,
                            now,
                            CorrelationId.generate(IDS)));
            app.commit();
        }
        assertThat(raised.created()).isTrue();
        assertThat(raised.severity().name()).isEqualTo("CRITICAL");
        assertThat(runStatus(runId)).isEqualTo("BLOCKED");
        return raised.breakId();
    }

    /**
     * A stored conclusion its own snapshot contradicts, planted by raw SQL as the application
     * role: verdict {@code ALLOCATE} under the version's {@code ONE_TO_ONE} capture rule, with
     * TWO live candidate rows - which the pure function concludes {@code AMBIGUOUS}. No
     * allocation is planted: V005's deferred sums would make one a real, position-moving
     * allocation, and the verdict alone is the contradiction the replay must find.
     */
    private static UUID plantContradiction(UUID runId, UUID itemId, UUID candidate)
            throws SQLException {
        UUID decision = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            execute(app,
                    "INSERT INTO reconciliation.match_decision (id, external_item_id, run_id,"
                            + " origin, rule_set_id, rule_priority, strategy, matched_key_kind,"
                            + " outcome, claimant_rank, claimant_count, decided_by,"
                            + " decided_by_type, decided_at, decided_on, correlation_id,"
                            + " verdict, judged_status, judged_minor, fingerprint_seen_earlier)"
                            + " VALUES (?, ?, ?, 'RUN', ?, 2, 'ONE_TO_ONE', 'PSP_CAPTURE_REF',"
                            + " 'MATCHED', 1, 2, 'system', 'SYSTEM', ?, current_date, ?,"
                            + " 'ALLOCATE', 'PENDING', 900, false)",
                    decision, itemId, runId, versionFour,
                    // The matcher's own clock, so the plant is the run's LATEST decision.
                    java.sql.Timestamp.from(Instant.now(CLOCK)), "p8t22-planted-" + marker());
            for (UUID expectation : List.of(candidate, firstSettled)) {
                execute(app,
                        "INSERT INTO reconciliation.match_candidate (decision_id,"
                                + " expectation_id, key_kind, amount_minor, currency, scale,"
                                + " direction, remainder_before_minor, opened_at)"
                                + " SELECT ?, id, 'PSP_CAPTURE_REF', amount_minor, currency,"
                                + " scale, direction, amount_minor, opened_at"
                                + " FROM reconciliation.expectation WHERE id = ?",
                        decision, expectation);
            }
            app.commit();
        }
        assertThat(count("SELECT count(*) FROM reconciliation.match_candidate WHERE"
                + " decision_id = ?", decision)).isEqualTo(2);
        return decision;
    }

    private void sweepUntilCompleted(UUID runId) throws SQLException {
        for (int tick = 0; tick < 5 && !"COMPLETED".equals(runStatus(runId)); tick++) {
            matching.sweep();
        }
        assertThat(runStatus(runId)).as("run %s", runId).isEqualTo("COMPLETED");
    }

    private static void assertClosedByItsCompletion(UUID breakId, UUID runId)
            throws SQLException {
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", breakId))
                .isEqualTo("RESOLVED");
        assertThat(string("SELECT kind || ':' || status FROM reconciliation.resolution WHERE"
                + " break_id = ?", breakId)).isEqualTo("EVIDENCED:APPROVED");
        assertThat(string("SELECT narrative FROM reconciliation.resolution WHERE break_id = ?",
                breakId)).as("explained by the run's own completion")
                .contains("run=" + runId);
    }

    // ----------------------------------------------------------------- readings

    private JsonNode replayOf(Session investigator, UUID runId) throws Exception {
        HttpResponse<String> replayed =
                post(investigator.token(), "/runs/" + runId + "/replay", null, null);
        assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(200);
        JsonNode verdict = json(replayed);
        assertThat(verdict.path("runId").stringValue()).isEqualTo(runId.toString());
        return verdict;
    }

    private double divergedReplays() {
        return meterRegistry.get(ReconciliationReplayMeters.REPLAY)
                .tag("outcome", "diverged")
                .counter()
                .count();
    }

    private long blockedOfPrivateSource() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return runReadings.blockedCountBySource(app).getOrDefault(PRIVATE_SOURCE, 0L);
        }
    }

    private static long reprocessRunsOfPrivateSource() throws SQLException {
        return count("SELECT count(*) FROM reconciliation.reconciliation_batch WHERE"
                + " source_id = ? AND kind = 'REPROCESS'", PRIVATE_SOURCE);
    }

    private static String runStatus(UUID runId) throws SQLException {
        return string("SELECT status FROM reconciliation.reconciliation_batch WHERE id = ?",
                runId);
    }

    private static String ruleSetStatus(UUID ruleSetId) throws SQLException {
        return string("SELECT status FROM reconciliation.rule_set WHERE id = ?", ruleSetId);
    }

    private static UUID itemOf(UUID runId, int lineNo) throws SQLException {
        return uuid("SELECT id FROM reconciliation.external_item WHERE run_id = ? AND"
                + " line_no = ?", runId, lineNo);
    }

    private static List<String> itemStatuses(UUID runId) throws SQLException {
        return column("SELECT status FROM reconciliation.external_item WHERE run_id = ?"
                + " ORDER BY line_no", runId);
    }

    /** Per item: its decisions and allocations, counted. */
    private static Map<String, String> touchesOf(List<String> itemIds) throws SQLException {
        Map<String, String> touches = new LinkedHashMap<>();
        for (String itemId : itemIds) {
            touches.put(itemId,
                    count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                            + " external_item_id = ?::uuid", itemId)
                            + ":" + count("SELECT count(*) FROM reconciliation.allocation"
                            + " WHERE external_item_id = ?::uuid", itemId));
        }
        return touches;
    }

    /** Every reconciled position's ledger balance and identity terms, as the proof reads. */
    private Map<String, String> positions() throws SQLException {
        PositionProof.Report report = proofs();
        Map<String, String> balances = new TreeMap<>();
        report.verdicts().forEach(verdict -> balances.put(
                verdict.purpose() + ":" + verdict.currency().code(),
                describe(verdict.ledgerBalance()) + "|" + describe(verdict.openRemainders())
                        + "|" + describe(verdict.openItems())));
        report.suspenseVerdicts().forEach(verdict -> balances.put(
                "SUSPENSE_UNMATCHED:" + verdict.currency().code(),
                describe(verdict.ledgerBalance()) + "|" + describe(verdict.creditRemainders())
                        + "|" + describe(verdict.debitRemainders())));
        report.cashVerdicts().forEach(verdict -> balances.put(
                "CASH_AT_BANK:" + verdict.currency().code(), describe(verdict.ledgerBalance())));
        return balances;
    }

    private static String describe(Money money) {
        return money.minorUnits() + " " + money.currency().code() + "/" + money.scale();
    }

    /** This suite's own rows, whole: what a replay or an activation must never touch. */
    private static List<String> suiteRows() throws SQLException {
        List<String> rows = new ArrayList<>();
        rows.addAll(rows("allocation", "SELECT a.id, a.decision_id, a.external_item_id,"
                + " a.expectation_id, a.amount_minor, a.reverses_allocation_id FROM"
                + " reconciliation.allocation a JOIN reconciliation.external_item i ON i.id ="
                + " a.external_item_id WHERE i.source_id = ? ORDER BY a.id", PRIVATE_SOURCE));
        rows.addAll(rows("decision", "SELECT d.id, d.rule_set_id, d.origin, d.outcome,"
                + " d.verdict FROM reconciliation.match_decision d JOIN"
                + " reconciliation.external_item i ON i.id = d.external_item_id WHERE"
                + " i.source_id = ? ORDER BY d.id", PRIVATE_SOURCE));
        rows.addAll(rows("item", "SELECT id, status, allocated_minor, parked_minor FROM"
                + " reconciliation.external_item WHERE source_id = ? ORDER BY id",
                PRIVATE_SOURCE));
        rows.addAll(rows("expectation", "SELECT id, status, allocated_minor, resolved_minor,"
                + " rule_set_id FROM reconciliation.expectation WHERE source_id = ? ORDER BY id",
                PRIVATE_SOURCE));
        rows.addAll(rows("suspense", "SELECT s.id, s.status, s.released_minor FROM"
                + " reconciliation.suspense_item s JOIN reconciliation.external_item i ON i.id ="
                + " s.external_item_id WHERE i.source_id = ? ORDER BY s.id", PRIVATE_SOURCE));
        rows.addAll(rows("break", "SELECT id, type, status FROM reconciliation.break WHERE"
                + " source_id = ? ORDER BY id", PRIVATE_SOURCE));
        return rows;
    }

    private PositionProof.Report proofs() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            app.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            PositionProof.Report report = positionProof.sweep(app);
            app.rollback();
            return report;
        }
    }

    /**
     * This suite's residue moved the proofs by NOTHING: every verdict as explained as before
     * the suite began, no line left unknown, no suspense item unowned - judged against the
     * shared container's own baseline, whatever the class ordering left there.
     */
    private void assertTheIdentityIsUnmoved(String when) throws SQLException {
        PositionProof.Report now = proofs();
        assertThat(explainedFlags(now)).as("%s: no proof verdict flipped", when)
                .isEqualTo(baselineFlags);
        assertThat(now.unattributedByPurpose())
                .as("%s: every line this suite posted is known", when)
                .isEqualTo(baselineUnattributed);
        assertThat(now.suspenseUnowned()).as("%s: every parked value owned", when)
                .isEqualTo(baselineUnowned);
    }

    private static Map<String, Boolean> explainedFlags(PositionProof.Report report) {
        Map<String, Boolean> flags = new TreeMap<>();
        report.verdicts().forEach(verdict -> flags.put(
                verdict.purpose() + ":" + verdict.currency().code(), verdict.explained()));
        report.suspenseVerdicts().forEach(verdict -> flags.put(
                "SUSPENSE:" + verdict.currency().code(), verdict.explained()));
        return flags;
    }

    // ----------------------------------------------------------------- the rule-set JSON

    private static JsonNode versionNamed(JsonNode listed, UUID ruleSetId) {
        JsonNode versions = listed.path("versions");
        for (int index = 0; index < versions.size(); index++) {
            if (ruleSetId.toString().equals(versions.get(index).path("ruleSetId").stringValue())) {
                return versions.get(index);
            }
        }
        throw new AssertionError("version " + ruleSetId + " is not listed: " + listed);
    }

    /** A proposal carrying a listed version's whole content - a change is a NEW version. */
    private static ObjectNode proposalFrom(JsonNode version, String reason) {
        ObjectNode body = JSON.createObjectNode();
        body.put("sourceCode", PRIVATE_CODE);
        body.put("reason", reason);
        body.put("fundingLagDays", version.path("fundingLagDays").intValue());
        body.put("gainMinAgeDays", version.path("gainMinAgeDays").intValue());
        body.set("lagDays", copied(version.path("lagDays"), JSON.createObjectNode()));
        body.set("rules", copied(version.path("rules"), JSON.createArrayNode()));
        body.set("tolerances", copied(version.path("tolerances"), JSON.createArrayNode()));
        body.set("feeSchedules", copied(version.path("feeSchedules"), JSON.createArrayNode()));
        body.set("severityThresholds",
                copied(version.path("severityThresholds"), JSON.createObjectNode()));
        return body;
    }

    private static JsonNode copied(JsonNode node, JsonNode empty) {
        if (node.isMissingNode() || node.isNull()) {
            return empty;
        }
        JsonNode copy = node.deepCopy();
        return copy;
    }

    /** Widens the date window by a day - the one tolerance changed - returning its days. */
    private static int widenDateWindow(ObjectNode proposal) {
        JsonNode tolerances = proposal.path("tolerances");
        for (int index = 0; index < tolerances.size(); index++) {
            JsonNode tolerance = tolerances.get(index);
            if ("SETTLEMENT_DATE_DAYS".equals(tolerance.path("comparison").stringValue())) {
                int widened = tolerance.path("days").intValue() + 1;
                ((ObjectNode) tolerance).put("days", widened);
                return widened;
            }
        }
        throw new AssertionError("no date window to widen: " + proposal);
    }

    private static int dateWindowOf(JsonNode version) {
        JsonNode tolerances = version.path("tolerances");
        for (int index = 0; index < tolerances.size(); index++) {
            JsonNode tolerance = tolerances.get(index);
            if ("SETTLEMENT_DATE_DAYS".equals(tolerance.path("comparison").stringValue())) {
                return tolerance.path("days").intValue();
            }
        }
        throw new AssertionError("no date window: " + version);
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static boolean absent(JsonNode node) {
        return node.isMissingNode() || node.isNull();
    }

    // ----------------------------------------------------------------- the doors

    private HttpResponse<String> get(String token, String path) throws Exception {
        return call(token, "GET", path, null, null);
    }

    private HttpResponse<String> post(String token, String path, String key, String body)
            throws Exception {
        return call(token, "POST", path, body, key);
    }

    private HttpResponse<String> call(
            String token, String method, String path, String body, String key) throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + BASE + path));
        if ("POST".equals(method)) {
            request.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body));
            if (key != null) {
                request.header("Idempotency-Key", key);
            }
        } else {
            request.GET();
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String reason(String text) {
        return "{\"reason\":\"" + text + "\"}";
    }

    private static String key() {
        return "p8t22-" + UUID.randomUUID();
    }

    /** Starts every racer at once and gathers their answers. */
    private static <T> List<T> race(List<Callable<T>> racers) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> pending = new ArrayList<>();
            for (Callable<T> racer : racers) {
                pending.add(pool.submit(() -> {
                    start.await();
                    return racer.call();
                }));
            }
            start.countDown();
            List<T> outcomes = new ArrayList<>();
            for (Future<T> outcome : pending) {
                outcomes.add(outcome.get(2, TimeUnit.MINUTES));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private record Session(IdentityId identity, String token) {

        /** The actor the session door attributes this person's acts to. */
        String actorId() {
            return identity.value().toString();
        }
    }

    private Session rolelessSession() throws SQLException {
        IdentityId identity = givenAnIdentity();
        return new Session(identity, givenASessionFor(identity));
    }

    private Session sessionWith(RoleName role) throws SQLException {
        IdentityId identity = givenAnIdentity();
        try (CorrelationContext.Scope correlation = CorrelationContext.enter(flow());
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, identity, role, identity, "test fixture");
            app.commit();
        }
        return new Session(identity, givenASessionFor(identity));
    }

    private static Correlation flow() {
        return Correlation.startingWith(CorrelationId.of("p8t22-" + UUID.randomUUID()))
                .causing(CausationId.of("p8t22-cause"));
    }

    private static IdentityId givenAnIdentity() throws SQLException {
        UUID party = IDS.next();
        UUID identity = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            execute(app,
                    "INSERT INTO party.party (id, kind, display_name, registered_at)"
                            + " VALUES (?, 'PERSON', 'Controller Person', now())",
                    party);
            execute(app,
                    "INSERT INTO identity.identity (id, party_id, login_identifier, status,"
                            + " created_at, status_changed_at) VALUES (?, ?, ?, 'ACTIVE',"
                            + " now(), now())",
                    identity, party,
                    "ad" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        }
        return IdentityId.of(identity);
    }

    private String givenASessionFor(IdentityId identity) throws SQLException {
        byte[] bytes = new byte[32];
        RANDOMNESS.nextBytes(bytes);
        String plaintext = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        com.finapp.identity.Session session =
                com.finapp.identity.Session.issue(
                        IDS, CLOCK, identity, SessionToken.of(plaintext),
                        AssuranceLevel.PASSWORD, SessionPolicy.current());
        try (Connection app = DatabaseRoles.application()) {
            sessions.insert(app, session);
        }
        return plaintext;
    }

    // ----------------------------------------------------------------- plumbing

    /**
     * Letters only: a UUID's hex can hold a Luhn-valid digit run the screens rightly refuse as
     * a card number - the flaky-fixture class (found by P8-TSK-020's gate).
     */
    private static String marker() {
        StringBuilder letters = new StringBuilder();
        for (char hex : UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                .toCharArray()) {
            letters.append(Character.isDigit(hex)
                    ? (char) ('G' + (hex - '0'))
                    : Character.toUpperCase(hex));
        }
        return letters.toString();
    }

    private static void execute(Connection connection, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, Object... args) throws SQLException {
        for (int index = 0; index < args.length; index++) {
            statement.setObject(index + 1, args[index]);
        }
    }

    private static Object one(String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return one(app, sql, args);
        }
    }

    private static Object one(Connection connection, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getObject(1) : null;
            }
        }
    }

    private static long count(String sql, Object... args) throws SQLException {
        return ((Number) one(sql, args)).longValue();
    }

    private static String string(String sql, Object... args) throws SQLException {
        Object value = one(sql, args);
        return value == null ? null : value.toString();
    }

    private static UUID uuid(String sql, Object... args) throws SQLException {
        return (UUID) one(sql, args);
    }

    private static List<String> column(String sql, Object... args) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet result = statement.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (result.next()) {
                    values.add(result.getString(1));
                }
                return values;
            }
        }
    }

    private static List<String> rows(String label, String sql, Object... args)
            throws SQLException {
        try (Connection app = DatabaseRoles.application();
                PreparedStatement statement = app.prepareStatement(sql)) {
            bind(statement, args);
            try (ResultSet result = statement.executeQuery()) {
                ResultSetMetaData shape = result.getMetaData();
                List<String> rows = new ArrayList<>();
                while (result.next()) {
                    StringBuilder row = new StringBuilder(label);
                    for (int index = 1; index <= shape.getColumnCount(); index++) {
                        row.append('|').append(result.getObject(index));
                    }
                    rows.add(row.toString());
                }
                return rows;
            }
        }
    }
}
