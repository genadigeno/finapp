package com.finapp.app.credit;

import static com.finapp.app.credit.CreditWorld.BALANCES;
import static com.finapp.app.credit.CreditWorld.TRANSACTIONS;
import static com.finapp.app.credit.CreditWorld.count;
import static com.finapp.app.credit.CreditWorld.decide;
import static com.finapp.app.credit.CreditWorld.deciding;
import static com.finapp.app.credit.CreditWorld.eur;
import static com.finapp.app.credit.CreditWorld.evaluated;
import static com.finapp.app.credit.CreditWorld.scalar;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.finapp.credit.CreditAttributeCode;
import com.finapp.credit.CreditDecisionId;
import com.finapp.credit.CreditPolicy;
import com.finapp.credit.CreditPolicyAdministration;
import com.finapp.credit.CreditProduct;
import com.finapp.credit.CreditReplayProof;
import com.finapp.credit.Decider;
import com.finapp.credit.DecisionOutcome;
import com.finapp.credit.DecisionReplayer;
import com.finapp.credit.DecisionReplayer.Divergence;
import com.finapp.credit.DecisionReplayer.Verdict;
import com.finapp.credit.EngineVersions;
import com.finapp.credit.EvaluationOutcome;
import com.finapp.credit.EvaluationResult;
import com.finapp.credit.JdbcCreditAssessmentStore;
import com.finapp.credit.JdbcCreditDecisions;
import com.finapp.credit.JdbcCreditProfiles;
import com.finapp.credit.JdbcDecisionSnapshotStore;
import com.finapp.credit.JdbcPolicyEvaluationStore;
import com.finapp.credit.JdbcUnderwritingCaseStore;
import com.finapp.credit.PolicyEffect;
import com.finapp.credit.PolicyEvaluator;
import com.finapp.credit.PolicyEvaluatorV1;
import com.finapp.credit.PolicyFigure;
import com.finapp.credit.PolicyOperator;
import com.finapp.credit.ReasonCode;
import com.finapp.credit.UnavailableFallback;
import com.finapp.credit.UnderwritingCaseId;
import com.finapp.credit.UnderwritingCases;
import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityId;
import com.finapp.identity.RoleName;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Decision replay against a real database (`P10-TSK-019`; PHASE_10_PLAN.md section 12.8, ADR-0087; {@code INV-CRD-01},
 * {@code INV-CRD-07}, {@code INV-CRD-05}, {@code INV-HIST-04}): every decision the suite makes - the platform's
 * approvals, capped approval and decline, and a person's approval and decline on a referral - replays {@code IDENTICAL}
 * from its sealed snapshot and pinned versions, even after newer versions are activated; every seeded tamper
 * {@code DIVERGED}. Tampers run inside the owner's own transaction, rolled back, so no other case sees them.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): the suite brings its own policies into force.
 */
@Tag("database")
@Tag("own-container")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings("try")
@DisplayName("decision replay and verification (P10-TSK-019)")
class DecisionReplayDatabaseTest {

    private static final Duration WEEK = Duration.ofDays(7);
    private static final List<ReasonCode> REVIEW_REASONS = List.of(ReasonCode.RISK_REFERRAL);
    private static boolean policies;

    @LocalServerPort private int port;
    @Autowired private Authorization authorization;

    private CreditTestClient client;

    @BeforeEach
    void world() {
        client = new CreditTestClient(port);
        if (!policies) {
            CreditWorld.seedsInForce();
            CreditWorld.inForce(CreditProduct.CREDIT_LINE, 900, false);
            referringLoan();
            policies = true;
        }
    }

    // ------------------------------------------------------------------ IDENTICAL

    @Test
    @DisplayName("every decision replays IDENTICAL - the platform's and a person's - and still does after newer versions are"
            + " activated: the replayer reads what was pinned, never what is in force")
    void everyDecisionReplaysIdentical() {
        List<UUID> decided = new ArrayList<>(List.of(
                systemDecided(UUID.randomUUID(), eur(200_000)),
                systemDecided(UUID.randomUUID(), eur(300_000)),
                declinedLine(),
                personDecided(approve(eur(500_000))),
                personDecided(decline()),
                fourEyesDecided()));
        assertThat(outcomes(decided))
                .containsExactlyInAnyOrder("APPROVED", "APPROVED", "DECLINED", "APPROVED", "DECLINED", "APPROVED");

        // Newer versions in force for both products and the scorecard - each DECLINING every request, so a replayer
        // that read what is in force, rather than what was pinned, could not reach one of the outcomes above.
        try {
            decliningEverything(CreditProduct.CREDIT_LINE);
            decliningEverything(CreditProduct.PERSONAL_LOAN);
            CreditWorld.scorecardInForce();

            DecisionReplayer replayer = replayer(EngineVersions.STANDARD);
            for (UUID request : decided) {
                DecisionReplayer.Replay replay = read(uow -> replayer.replay(uow, decisionOf(request))).orElseThrow();
                assertThat(replay.verdict()).as("decision of " + request + " " + replay.divergences())
                        .isEqualTo(Verdict.IDENTICAL);
            }
            CreditReplayProof.Report report = read(new CreditReplayProof(replayer)::prove);
            assertThat(report.count(Verdict.DIVERGED)).as(report.diverged().toString()).isZero();
            assertThat(report.count(Verdict.IDENTICAL)).isGreaterThanOrEqualTo(decided.size());
        } finally {
            // The suite's own versions again, for every case after this one.
            CreditWorld.inForce(CreditProduct.CREDIT_LINE, 900, false);
            referringLoan();
        }
    }

    @Test
    @DisplayName("a person's decision is verified against its case, not re-derived - an altered case record DIVERGES by amount")
    void aPersonsDecisionVerifiesAgainstItsCase() throws SQLException {
        UUID request = personDecided(approve(eur(400_000)));
        CreditDecisionId decision = decisionOf(request);
        DecisionReplayer replayer = replayer(EngineVersions.STANDARD);
        DecisionReplayer.Replay clean = read(uow -> replayer.replay(uow, decision)).orElseThrow();
        assertThat(clean.byPerson()).isTrue();
        assertThat(clean.verdict()).as(clean.divergences().toString()).isEqualTo(Verdict.IDENTICAL);

        DecisionReplayer.Replay tampered = tamperedReplay(replayer, decision,
                "ALTER TABLE credit.underwriting_case DISABLE TRIGGER USER",
                "UPDATE credit.underwriting_case SET first_approved_minor = first_approved_minor - 100"
                        + " WHERE decision_request_id = '" + request + "'");
        assertThat(tampered.verdict()).isEqualTo(Verdict.DIVERGED);
        assertThat(tampered.divergences()).containsExactly(Divergence.AMOUNT);
    }

    // ------------------------------------------------------------------ DIVERGED

    @Test
    @DisplayName("a tampered snapshot DIVERGES by hash - its text changed under the stored digest, or re-digested away from"
            + " the decision's")
    void aTamperedSnapshotDivergesByHash() throws SQLException {
        UUID request = systemDecided(UUID.randomUUID(), eur(200_000));
        CreditDecisionId decision = decisionOf(request);
        String snapshot = "(SELECT snapshot_id FROM credit.credit_decision WHERE decision_request_id = '" + request + "')";
        DecisionReplayer replayer = replayer(EngineVersions.STANDARD);

        // A valid canonical edit - the risk signal's code changed - so the strict parser reads it: only the seal sees it.
        String edit = "replace(canonical, '\"NOT_ASSESSED\"', '\"NOT_ASSESSEX\"')";
        assertThat(scalar("SELECT position('\"NOT_ASSESSED\"' IN canonical)::text FROM credit.decision_snapshot WHERE id = "
                + snapshot.replace("'" + request + "'", "?"), request)).isNotEqualTo("0");
        DecisionReplayer.Replay underTheDigest = tamperedReplay(replayer, decision,
                "ALTER TABLE credit.decision_snapshot DISABLE TRIGGER USER",
                "ALTER TABLE credit.decision_snapshot DROP CONSTRAINT decision_snapshot_hash_is_the_contents",
                "UPDATE credit.decision_snapshot SET canonical = " + edit + " WHERE id = " + snapshot);
        assertThat(underTheDigest.divergences()).containsExactly(Divergence.HASH);

        DecisionReplayer.Replay reDigested = tamperedReplay(replayer, decision,
                "ALTER TABLE credit.decision_snapshot DISABLE TRIGGER USER",
                // the text and its digest changed together, so the table's own hash CHECK holds
                "UPDATE credit.decision_snapshot SET canonical = " + edit + ","
                        + " content_sha256 = sha256(convert_to(" + edit + ", 'UTF8')) WHERE id = " + snapshot);
        assertThat(reDigested.verdict()).isEqualTo(Verdict.DIVERGED);
        assertThat(reDigested.divergences()).containsExactly(Divergence.HASH);
    }

    @Test
    @DisplayName("a pinned rule forced past its trigger DIVERGES - the decline it explained is no longer re-derived")
    void aForcedRuleChangeDiverges() throws SQLException {
        UUID request = declinedLine();
        CreditDecisionId decision = decisionOf(request);
        String pinned = "(SELECT policy_version_id FROM credit.decision_snapshot WHERE id ="
                + " (SELECT snapshot_id FROM credit.credit_decision WHERE decision_request_id = '" + request + "'))";
        DecisionReplayer.Replay forced = tamperedReplay(replayer(EngineVersions.STANDARD), decision,
                "ALTER TABLE credit.credit_policy_rule DISABLE TRIGGER USER",
                "UPDATE credit.credit_policy_rule SET effect = 'REFER' WHERE policy_version_id = " + pinned
                        + " AND effect IN ('DECLINE', 'HARD_DECLINE')");
        assertThat(forced.verdict()).isEqualTo(Verdict.DIVERGED);
        assertThat(forced.divergences()).contains(Divergence.OUTCOME);
    }

    @Test
    @DisplayName("a newer engine held beside engine 1 leaves old decisions IDENTICAL - each replays under the engine it pinned")
    void aNewEngineVersionLeavesOldDecisionsIdentical() {
        List<UUID> decided = List.of(systemDecided(UUID.randomUUID(), eur(200_000)), declinedLine(),
                personDecided(approve(eur(300_000))));
        PolicyEvaluator declinesEverything = new PolicyEvaluator() {
            @Override
            public int engineVersion() {
                return 2;
            }

            @Override
            public EvaluationResult evaluate(
                    com.finapp.credit.SnapshotContent snapshot, com.finapp.credit.CreditAssessment assessment,
                    CreditPolicy policy) {
                EvaluationResult v1 = new PolicyEvaluatorV1().evaluate(snapshot, assessment, policy);
                return new EvaluationResult(2, EvaluationOutcome.DECLINE, v1.requested(), Optional.empty(),
                        List.of(ReasonCode.EXPOSURE_LIMIT),
                        false, v1.rules());
            }
        };
        DecisionReplayer withTwo = replayer(EngineVersions.of(List.of(new PolicyEvaluatorV1(), declinesEverything)));
        for (UUID request : decided) {
            DecisionReplayer.Replay replay = read(uow -> withTwo.replay(uow, decisionOf(request))).orElseThrow();
            assertThat(replay.versions().engineVersion()).isEqualTo(1);
            assertThat(replay.verdict()).as(replay.divergences().toString()).isEqualTo(Verdict.IDENTICAL);
        }
    }

    // ------------------------------------------------------------------ the proof

    @Test
    @DisplayName("the proof reads one REPEATABLE READ snapshot and writes nothing - a decision committed mid-reading is not in"
            + " it, and a connection that could write is refused")
    void theProofReadsOneSnapshotAndWritesNothing() throws Exception {
        systemDecided(UUID.randomUUID(), eur(200_000));
        CreditReplayProof proof = new CreditReplayProof(replayer(EngineVersions.STANDARD));
        long replayed = count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.DecisionReplayed'");
        int[] seenFirst = new int[1];
        CreditReplayProof.Report report = read(uow -> {
            seenFirst[0] = Integer.parseInt(scalarOn(uow, "SELECT count(*)::text FROM credit.credit_decision"));
            systemDecided(UUID.randomUUID(), eur(200_000)); // committed by another transaction, mid-reading
            return proof.prove(uow);
        });
        assertThat(report.replays()).as("one snapshot: the later decision is not in it").hasSize(seenFirst[0]);
        assertThat(count("SELECT count(*) FROM credit.credit_decision")).isEqualTo(seenFirst[0] + 1L);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.DecisionReplayed'"))
                .as("the proof records nothing").isEqualTo(replayed);
        assertThatIllegalStateException().isThrownBy(() -> TRANSACTIONS.inTransaction(proof::prove));
    }

    @Test
    @DisplayName("two instances reading one committed state reach the same verdicts, an activation committing beside them")
    void twoInstancesReachTheSameVerdict() throws Exception {
        systemDecided(UUID.randomUUID(), eur(200_000));
        personDecided(approve(eur(300_000)));
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<CreditReplayProof.Report>> instances = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                instances.add(pool.submit(() -> {
                    start.await();
                    CreditReadingSnapshot snapshots = new CreditReadingSnapshot(DatabaseRoles::application);
                    return snapshots.read(new CreditReplayProof(replayer(EngineVersions.STANDARD))::prove);
                }));
            }
            Future<?> activation = pool.submit(() -> {
                start.await();
                return CreditWorld.inForce(CreditProduct.CREDIT_LINE, 1_100, false);
            });
            start.countDown();
            CreditReplayProof.Report first = instances.get(0).get(2, TimeUnit.MINUTES);
            CreditReplayProof.Report second = instances.get(1).get(2, TimeUnit.MINUTES);
            activation.get(2, TimeUnit.MINUTES);
            assertThat(first.count(Verdict.DIVERGED)).as(first.diverged().toString()).isZero();
            assertThat(second.count(Verdict.DIVERGED)).as(second.diverged().toString()).isZero();
            assertThat(verdicts(first)).as("no decision is made concurrently, so one committed state")
                    .isEqualTo(verdicts(second));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("finapp.credit.replay{verdict} counts this reading's verdicts - every decision IDENTICAL, DIVERGED 0")
    void theGaugeCountsTheVerdicts() {
        systemDecided(UUID.randomUUID(), eur(200_000));
        io.micrometer.core.instrument.simple.SimpleMeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        CreditReplayMetrics metrics = new CreditReplayMetrics(new CreditReplayProof(replayer(EngineVersions.STANDARD)),
                new CreditReadingSnapshot(DatabaseRoles::application), java.time.Clock.systemUTC(), registry);
        long decisions = count("SELECT count(*) FROM credit.credit_decision");
        assertThat(registry.get(CreditReplayMetrics.REPLAY).tag("verdict", "DIVERGED").gauge().value()).isZero();
        assertThat(registry.get(CreditReplayMetrics.REPLAY).tag("verdict", "IDENTICAL").gauge().value())
                .isEqualTo((double) decisions);
        assertThat(metrics.readNow().count(Verdict.IDENTICAL)).isEqualTo(decisions);
    }

    // ------------------------------------------------------------------ the door

    @Test
    @DisplayName("a replay without a reason is 422 credit.ReasonRequired, recorded nowhere; an unknown decision 404")
    void replayWithoutAReasonIs422() throws Exception {
        String officer = sessionWith(RoleName.CREDIT_POLICY_OFFICER);
        CreditDecisionId decision = decisionOf(systemDecided(UUID.randomUUID(), eur(200_000)));
        long audits = replays(decision);
        for (String body : List.of("{}", "{\"reason\":\"  \"}")) {
            HttpResponse<String> refused = client.post(replayPath(decision.value().toString()), body, officer, null);
            assertThat(refused.statusCode()).as(refused.body()).isEqualTo(422);
            assertThat(refused.body()).contains("credit.ReasonRequired");
        }
        assertThat(replays(decision)).isEqualTo(audits);
        assertThat(client.post(replayPath(UUID.randomUUID().toString()), "{\"reason\":\"why\"}", officer, null).statusCode())
                .isEqualTo(404);
        assertThat(client.post(replayPath("not-a-uuid"), "{\"reason\":\"why\"}", officer, null).statusCode()).isEqualTo(404);
    }

    @Test
    @DisplayName("every replay is audited with its reason and verdict before it is served - and the door is CREDIT_INVESTIGATE's"
            + " alone (an underwriter and a customer 403), naming differences by kind, never a value")
    void everyReplayIsAudited() throws Exception {
        String officer = sessionWith(RoleName.CREDIT_POLICY_OFFICER);
        String underwriter = sessionWith(RoleName.UNDERWRITER);
        String customer = client.customer(true).token();
        CreditDecisionId decision = decisionOf(systemDecided(UUID.randomUUID(), eur(200_000)));
        long before = replays(decision);
        HttpResponse<String> replayed = client.post(replayPath(decision.value().toString()),
                "{\"reason\":\"regulator query 42\"}", officer, null);
        assertThat(replayed.statusCode()).as(replayed.body()).isEqualTo(200);
        assertThat(CreditTestClient.field(replayed.body(), "verdict")).isEqualTo("IDENTICAL");
        assertThat(replayed.body()).contains("\"differences\":[]").doesNotContain("\"score\"").doesNotContain("2000.00");
        assertThat(replays(decision)).isEqualTo(before + 1);
        assertThat(scalar("SELECT reason || '|' || change_summary FROM platform.audit_record WHERE operation ="
                + " 'credit.DecisionReplayed' AND target_id = ? ORDER BY occurred_at DESC LIMIT 1", decision.value().toString()))
                .startsWith("regulator query 42|").contains("IDENTICAL");
        assertThat(client.post(replayPath(decision.value().toString()), "{\"reason\":\"x\"}", underwriter, null).statusCode())
                .as("an UNDERWRITER does not investigate").isEqualTo(403);
        assertThat(client.post(replayPath(decision.value().toString()), "{\"reason\":\"x\"}", customer, null).statusCode())
                .as("nor does a customer").isEqualTo(403);
        assertThat(client.post(replayPath(decision.value().toString()), "{\"reason\":\"x\"}", null, null).statusCode())
                .isEqualTo(401);
        assertThat(replays(decision)).as("no refused caller is recorded").isEqualTo(before + 1);
    }

    @Test
    @DisplayName("a decision re-pointed at ANOTHER request's intact snapshot DIVERGES by hash - the seal is the decision's"
            + " own request's, whatever that other snapshot re-derives (the Phase 10 to 11 transition, INV-CRD-06)")
    void aDecisionOnAnotherRequestsSnapshotDivergesByHash() throws SQLException {
        // Two twins: the same amount, the same default bureau answer - so the other request's snapshot re-derives exactly
        // this decision's outcome, amount and reasons, and only the request check can tell.
        UUID own = systemDecided(UUID.randomUUID(), eur(200_000));
        UUID twin = systemDecided(UUID.randomUUID(), eur(200_000));
        assertThat(scalar("SELECT outcome || '/' || coalesce(approved_minor::text, '-') FROM credit.credit_decision"
                + " WHERE decision_request_id = ?", own)).isEqualTo(scalar("SELECT outcome || '/'"
                + " || coalesce(approved_minor::text, '-') FROM credit.credit_decision WHERE decision_request_id = ?", twin));
        CreditDecisionId decision = decisionOf(own);
        String twinSnapshot = "(SELECT snapshot_id FROM credit.credit_decision WHERE decision_request_id = '" + twin + "')";
        DecisionReplayer.Replay repointed = tamperedReplay(replayer(EngineVersions.STANDARD), decision,
                "ALTER TABLE credit.credit_decision DISABLE TRIGGER USER",
                // credit V017's composite key would refuse the re-pointing; the owner drops it inside the rolled-back plant
                "ALTER TABLE credit.credit_decision DROP CONSTRAINT credit_decision_snapshot_of_its_request_fk",
                "UPDATE credit.credit_decision SET snapshot_id = " + twinSnapshot + ", snapshot_sha256 = (SELECT"
                        + " content_sha256 FROM credit.decision_snapshot WHERE id = " + twinSnapshot + ")"
                        + " WHERE decision_request_id = '" + own + "'");
        assertThat(repointed.verdict()).isEqualTo(Verdict.DIVERGED);
        assertThat(repointed.divergences()).containsExactly(Divergence.HASH);
        assertThat(read(uow -> replayer(EngineVersions.STANDARD).replay(uow, decision)).orElseThrow().verdict())
                .as("the untampered decision replays identical").isEqualTo(Verdict.IDENTICAL);
    }

    // ------------------------------------------------------------------ plumbing

    /** A replayer over this suite's database, holding {@code engines} - each call an instance of its own. */
    private static DecisionReplayer replayer(EngineVersions engines) {
        return new DecisionReplayer(new JdbcCreditDecisions(), new JdbcDecisionSnapshotStore(), CreditWorld.POLICIES,
                CreditWorld.SCORECARDS, new JdbcCreditAssessmentStore(), new JdbcPolicyEvaluationStore(),
                new JdbcUnderwritingCaseStore(), engines);
    }

    private static <R> R read(Function<Connection, R> work) {
        return new CreditReadingSnapshot(DatabaseRoles::application).read(work);
    }

    /** {@code statements} run as the owner, then the replay - in one transaction, rolled back: no other case sees it. */
    private static DecisionReplayer.Replay tamperedReplay(
            DecisionReplayer replayer, CreditDecisionId decision, String... statements) throws SQLException {
        try (Connection owner = DatabaseRoles.migrator()) {
            owner.setAutoCommit(false);
            try {
                for (String sql : statements) {
                    try (Statement statement = owner.createStatement()) {
                        statement.execute(sql);
                    }
                }
                return replayer.replay(owner, decision).orElseThrow();
            } finally {
                owner.rollback();
            }
        }
    }

    /** A line request decided by the platform. */
    private static UUID systemDecided(UUID party, Money amount) {
        UUID request = evaluated(party, CreditProduct.CREDIT_LINE, amount, WEEK);
        assertThat(decide(deciding(), request)).isEqualTo(Decider.Decided.DECIDED);
        return request;
    }

    /** A line request the platform declines: the party's bureau balance past the exposure limit. */
    private static UUID declinedLine() {
        UUID party = UUID.randomUUID();
        BALANCES.put(party, 3_000_000L);
        return systemDecided(party, eur(200_000));
    }

    /** A loan referred by the review policy, assigned and decided by a person with {@code judgement}. */
    private static UUID personDecided(UnderwritingCases.Judgement judgement) {
        UUID request = evaluated(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN, eur(500_000), WEEK);
        assertThat(decide(deciding(), request)).isEqualTo(Decider.Decided.REFERRED);
        UnderwritingCaseId id = UnderwritingCaseId.of(UUID.fromString(
                scalar("SELECT id::text FROM credit.underwriting_case WHERE decision_request_id = ?", request)));
        Actor underwriter = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
        UnderwritingCases reviewing = reviewing();
        TRANSACTIONS.inTransaction(uow -> reviewing.assign(uow, id, underwriter, correlation()));
        TRANSACTIONS.inTransaction(uow -> reviewing.decide(uow, id, judgement, underwriter, correlation()));
        assertThat(scalar("SELECT status FROM credit.underwriting_case WHERE id = ?", id.value())).isEqualTo("DECIDED");
        return request;
    }

    /** A large loan's referral approved above the four-eyes threshold by one person and seconded by another. */
    private static UUID fourEyesDecided() {
        Money big = eur(2_100_000);
        UUID request = evaluated(UUID.randomUUID(), CreditProduct.PERSONAL_LOAN, big, WEEK);
        assertThat(decide(deciding(), request)).isEqualTo(Decider.Decided.REFERRED);
        UnderwritingCaseId id = UnderwritingCaseId.of(UUID.fromString(
                scalar("SELECT id::text FROM credit.underwriting_case WHERE decision_request_id = ?", request)));
        Actor first = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
        Actor second = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);
        UnderwritingCases reviewing = reviewing();
        TRANSACTIONS.inTransaction(uow -> reviewing.assign(uow, id, first, correlation()));
        TRANSACTIONS.inTransaction(uow -> reviewing.decide(uow, id, approve(big), first, correlation()));
        assertThat(scalar("SELECT status FROM credit.underwriting_case WHERE id = ?", id.value())).isEqualTo("AWAITING_SECOND");
        TRANSACTIONS.inTransaction(uow -> reviewing.approveSecond(uow, id, Optional.empty(), second, correlation()));
        assertThat(scalar("SELECT status FROM credit.underwriting_case WHERE id = ?", id.value())).isEqualTo("DECIDED");
        return request;
    }

    private static UnderwritingCases reviewing() {
        return new UnderwritingCases(new JdbcUnderwritingCaseStore(), CreditWorld.REQUESTS,
                new JdbcCreditProfiles(CreditWorld.IDS), deciding(), new JdbcDecisionSnapshotStore(),
                new JdbcPolicyEvaluationStore(), CreditWorld.POLICIES, CreditWorld.ACTING_PARTIES,
                new JdbcAuditWriter(), CreditWorld.IDS,
                CreditWorld.CLOCK);
    }

    private static UnderwritingCases.Judgement approve(Money amount) {
        return new UnderwritingCases.Judgement(DecisionOutcome.APPROVED, Optional.of(amount), REVIEW_REASONS,
                "reviewed against the referral");
    }

    private static UnderwritingCases.Judgement decline() {
        return new UnderwritingCases.Judgement(DecisionOutcome.DECLINED, Optional.empty(), REVIEW_REASONS, "not convinced");
    }

    private static CreditDecisionId decisionOf(UUID request) {
        return CreditDecisionId.of(UUID.fromString(
                scalar("SELECT id::text FROM credit.credit_decision WHERE decision_request_id = ?", request)));
    }

    private static List<String> outcomes(List<UUID> requests) {
        List<String> outcomes = new ArrayList<>();
        for (UUID request : requests) {
            outcomes.add(scalar("SELECT outcome FROM credit.credit_decision WHERE decision_request_id = ?", request));
        }
        return outcomes;
    }

    private static List<String> verdicts(CreditReplayProof.Report report) {
        return report.replays().stream().map(replay -> replay.decision().value() + ":" + replay.verdict()).toList();
    }

    private static long replays(CreditDecisionId decision) {
        return count("SELECT count(*) FROM platform.audit_record WHERE operation = 'credit.DecisionReplayed'"
                + " AND target_id = ?", decision.value().toString());
    }

    private static String scalarOn(Connection connection, String sql) {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getString(1);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String replayPath(String id) {
        return "/v1/operator/credit/decisions/" + id + "/replay";
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(CreditWorld.IDS);
    }

    /** PERSONAL_LOAN v1 without its score rules, plus a cap and a rule that refers every loan to a person. */
    private static void referringLoan() {
        loanWithReviewRules("the replay suite's referring loan");
    }

    /** {@code product}'s seed with one more rule, first: every request DECLINED - a version no pinned decision rests on. */
    private static void decliningEverything(CreditProduct product) {
        TRANSACTIONS.inTransaction(uow -> {
            CreditPolicy seed = CreditWorld.POLICIES.policy(uow,
                    product == CreditProduct.PERSONAL_LOAN ? CreditWorld.LOAN_SEED : CreditWorld.LINE_SEED).orElseThrow().policy();
            List<CreditPolicy.PolicyRule> rules = new ArrayList<>();
            rules.add(new CreditPolicy.PolicyRule("DECLINE_ALL",
                    new CreditPolicy.Subject.Attribute(CreditAttributeCode.BUREAU_EXTERNAL_SCORE), PolicyOperator.GE,
                    new CreditPolicy.Operand.IntegerOperand(0), PolicyEffect.DECLINE, Optional.empty(),
                    ReasonCode.INSUFFICIENT_CREDIT_HISTORY));
            for (CreditPolicy.PolicyRule rule : seed.rules()) {
                if (!(rule.subject() instanceof CreditPolicy.Subject.Figure figure && figure.figure() == PolicyFigure.SCORE)
                        && !rule.ruleCode().equals("DECLINE_ALL")) {
                    rules.add(rule);
                }
            }
            CreditPolicy policy = new CreditPolicy(seed.product(), seed.assessmentRateBps(), seed.minimumDisposable(),
                    seed.minimumPaymentRatioBps(), seed.maximumExposure(), seed.maximumDataAge(), UnavailableFallback.REFER,
                    seed.autoApprovalCeiling(), rules);
            CreditPolicyAdministration.Proposed proposed = CreditWorld.POLICY_ADMINISTRATION.propose(uow, policy,
                    "the replay suite's declining version", new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE),
                    correlation());
            CreditWorld.POLICY_ADMINISTRATION.approve(uow, proposed.id(),
                    new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE), "activated", correlation());
            return null;
        });
    }

    private static void loanWithReviewRules(String reason) {
        TRANSACTIONS.inTransaction(uow -> {
            CreditPolicy v1 = CreditWorld.POLICIES.policy(uow, CreditWorld.LOAN_SEED).orElseThrow().policy();
            List<CreditPolicy.PolicyRule> rules = new ArrayList<>();
            for (CreditPolicy.PolicyRule rule : v1.rules()) {
                if (!(rule.subject() instanceof CreditPolicy.Subject.Figure figure && figure.figure() == PolicyFigure.SCORE)) {
                    rules.add(rule);
                }
            }
            rules.add(new CreditPolicy.PolicyRule("REVIEW_CAP",
                    new CreditPolicy.Subject.Attribute(CreditAttributeCode.BUREAU_TOTAL_BALANCE), PolicyOperator.LT,
                    new CreditPolicy.Operand.MoneyOperand(eur(10_000)), PolicyEffect.CAP_AMOUNT, Optional.of(eur(800_000)),
                    ReasonCode.INSUFFICIENT_CREDIT_HISTORY));
            rules.add(new CreditPolicy.PolicyRule("REVIEW_REFERRAL",
                    new CreditPolicy.Subject.Attribute(CreditAttributeCode.BUREAU_EXTERNAL_SCORE), PolicyOperator.GE,
                    new CreditPolicy.Operand.IntegerOperand(0), PolicyEffect.REFER, Optional.empty(),
                    ReasonCode.RISK_REFERRAL));
            CreditPolicy policy = new CreditPolicy(v1.product(), v1.assessmentRateBps(), v1.minimumDisposable(),
                    v1.minimumPaymentRatioBps(), v1.maximumExposure(), v1.maximumDataAge(), UnavailableFallback.REFER,
                    v1.autoApprovalCeiling(), rules);
            CreditPolicyAdministration.Proposed proposed = CreditWorld.POLICY_ADMINISTRATION.propose(uow, policy, reason,
                    new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE), correlation());
            CreditWorld.POLICY_ADMINISTRATION.approve(uow, proposed.id(),
                    new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE), "activated", correlation());
            return null;
        });
    }

    /** A registered identity holding {@code role}, and its session. */
    private String sessionWith(RoleName role) throws Exception {
        String login = "rp." + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        assertThat(client.post("/v1/registrations", "{\"loginIdentifier\":\"" + login + "\",\"displayName\":\"Ada Lovelace\","
                + "\"password\":\"" + CreditTestClient.PASSWORD + "\"}", null, CreditTestClient.key()).statusCode())
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
        try (CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(correlation()));
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            authorization.assign(app, IdentityId.of(identity), role, IdentityId.of(identity), "test fixture");
            app.commit();
        }
        HttpResponse<String> session = client.post("/v1/authentications",
                "{\"loginIdentifier\":\"" + login + "\",\"password\":\"" + CreditTestClient.PASSWORD + "\"}", null,
                CreditTestClient.key());
        return CreditTestClient.field(session.body(), "sessionToken");
    }
}
