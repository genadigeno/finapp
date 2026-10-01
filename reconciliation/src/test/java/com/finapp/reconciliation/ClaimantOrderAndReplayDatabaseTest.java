package com.finapp.reconciliation;

import static com.finapp.reconciliation.GateFixtures.APPROVER;
import static com.finapp.reconciliation.GateFixtures.EUR;
import static com.finapp.reconciliation.GateFixtures.FAR_FUTURE;
import static com.finapp.reconciliation.GateFixtures.IDS;
import static com.finapp.reconciliation.GateFixtures.PROPOSER;
import static com.finapp.reconciliation.GateFixtures.SETTLED_ON;
import static com.finapp.reconciliation.GateFixtures.capture;
import static com.finapp.reconciliation.GateFixtures.count;
import static com.finapp.reconciliation.GateFixtures.itemOf;
import static com.finapp.reconciliation.GateFixtures.string;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The matcher's determinism claims against the real schema (`P8-DOC-001`): a line repeated
 * ACROSS files is the fingerprint duplicate it is; competing lines for one expectation, born in
 * shuffled orders with adversarial row ids, produce IDENTICAL outcomes because claimant order is
 * {@code (source_sequence, line_no)} and nothing else; and - at the end of a battery that
 * resolves breaks across several runs by evidence, by rematch and by four-eyes transfers - every
 * run replays {@code IDENTICAL} from its stored decisions alone.
 *
 * <p>Ordered: the last test replays every run the earlier tests produced.
 */
@Tag("database")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("claimant order, the cross-file duplicate and the replay battery (P8-DOC-001)")
class ClaimantOrderAndReplayDatabaseTest {

    private static final UUID SOURCE = IDS.next();
    private static final UUID RULE_SET = IDS.next();
    private static final UUID ORDER_SOURCE_A = IDS.next();
    private static final UUID ORDER_RULE_SET_A = IDS.next();
    private static final UUID ORDER_SOURCE_B = IDS.next();
    private static final UUID ORDER_RULE_SET_B = IDS.next();

    /** Every run this suite produced - the replay battery's worklist. */
    private static final List<UUID> RUNS = Collections.synchronizedList(new ArrayList<>());
    private static UUID duplicateBreak;
    private static UUID excessBreak;

    @BeforeAll
    static void seed() {
        GateFixtures.seedRuleSet(SOURCE, RULE_SET);
        GateFixtures.seedRuleSet(ORDER_SOURCE_A, ORDER_RULE_SET_A);
        GateFixtures.seedRuleSet(ORDER_SOURCE_B, ORDER_RULE_SET_B);
    }

    // ----------------------------------------------------------------- across files

    @Test
    @Order(1)
    @DisplayName("a line repeated ACROSS files: the second run's line repeats run 1's fingerprint"
            + " and is ONE DUPLICATE_EXTERNAL (REPEATED_FINGERPRINT), parked whole - the"
            + " expectation allocated once")
    void aLineRepeatedAcrossFilesIsOneDuplicate() {
        String key = "XF-" + UUID.randomUUID();
        GateFixtures.Seeded expectation = GateFixtures.openExpectation(SOURCE, RULE_SET, key,
                45_00, ExpectationDirection.INBOUND, FAR_FUTURE);
        byte[] sameBytes = GateFixtures.fingerprint();
        GateFixtures.Line original = new GateFixtures.Line(1, ExternalLineType.CAPTURE,
                ExpectationDirection.INBOUND, 45_00, EUR, ItemKeyKind.PSP_CAPTURE_REF, key,
                sameBytes, SETTLED_ON);
        UUID first = GateFixtures.seedRun(SOURCE, RULE_SET, original);
        GateFixtures.matching().sweep();
        UUID second = GateFixtures.seedRun(SOURCE, RULE_SET, original);
        GateFixtures.matching().sweep();
        RUNS.add(first);
        RUNS.add(second);

        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?",
                itemOf(first, 1))).isEqualTo("MATCHED");
        UUID repeated = itemOf(second, 1);
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?",
                repeated)).as("the cross-file repeat parks").isEqualTo("PARKED");
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE external_item_id = ?",
                repeated)).as("ONE break on the repeat").isEqualTo(1);
        assertThat(string("SELECT type || '/' || cause FROM reconciliation.break WHERE"
                + " external_item_id = ?", repeated))
                .as("judged by the fingerprint seen in an EARLIER file, not by exhaustion")
                .isEqualTo("DUPLICATE_EXTERNAL/REPEATED_FINGERPRINT");
        assertThat(string("SELECT amount_minor || '/' || status FROM"
                + " reconciliation.suspense_item WHERE external_item_id = ?", repeated))
                .as("parked whole, once").isEqualTo("4500/OPEN");
        assertThat(string("SELECT fingerprint_seen_earlier::text FROM"
                + " reconciliation.match_decision WHERE external_item_id = ?", repeated))
                .as("the decision stores the cross-file sighting it judged on")
                .isEqualTo("true");
        assertThat(string("SELECT allocated_minor || '/' || (SELECT count(*) FROM"
                + " reconciliation.allocation a WHERE a.expectation_id = e.id) FROM"
                + " reconciliation.expectation e WHERE e.id = ?", expectation.id()))
                .as("the money allocated once, by the original").isEqualTo("4500/1");
        duplicateBreak = (UUID) GateFixtures.one("SELECT id FROM reconciliation.break WHERE"
                + " external_item_id = ?", repeated);
    }

    // ----------------------------------------------------------------- claimant order

    private record Outcome(String role, long lineNo, String status, long allocated, long parked,
            String breakFacts) {}

    /**
     * Two runs competing for one 100.00 expectation: run A (the LOWER sequence) line 2 claims
     * 60.00; run B line 1 claims 70.00 and line 2 30.00. {@code adversarial} births B before A,
     * gives B's lines DESCENDING row ids against their line numbers, and inserts every item in
     * reverse.
     */
    private List<Outcome> compete(UUID source, UUID ruleSet, boolean adversarial) {
        String key = "ORD-" + UUID.randomUUID();
        GateFixtures.openExpectation(source, ruleSet, key, 100_00,
                ExpectationDirection.INBOUND, FAR_FUTURE);
        GateFixtures.Line[] runA = {
            capture(1, 5_00, "ORD-NOISE-" + UUID.randomUUID()), capture(2, 60_00, key)
        };
        GateFixtures.Line[] runB = {capture(1, 70_00, key), capture(2, 30_00, key)};
        long sequenceA = GateFixtures.nextSequence();
        long sequenceB = GateFixtures.nextSequence();
        List<UUID> idsA = sortedIds(2);
        List<UUID> idsB = sortedIds(2);
        List<Integer> forward = List.of(0, 1);
        List<Integer> reverse = List.of(1, 0);
        UUID a;
        UUID b;
        if (adversarial) {
            Collections.reverse(idsB);
            b = GateFixtures.seedRun(source, ruleSet, sequenceB, idsB, reverse, runB);
            a = GateFixtures.seedRun(source, ruleSet, sequenceA, idsA, reverse, runA);
        } else {
            a = GateFixtures.seedRun(source, ruleSet, sequenceA, idsA, forward, runA);
            b = GateFixtures.seedRun(source, ruleSet, sequenceB, idsB, forward, runB);
        }
        for (int tick = 0; tick < 4; tick++) {
            GateFixtures.matching().sweep();
        }
        RUNS.add(a);
        RUNS.add(b);
        List<Outcome> outcomes = new ArrayList<>();
        outcomes.add(outcome("A", a, 2));
        outcomes.add(outcome("B", b, 1));
        outcomes.add(outcome("B", b, 2));
        return outcomes;
    }

    private static List<UUID> sortedIds(int how) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < how; i++) {
            ids.add(IDS.next());
        }
        ids.sort(null);
        return ids;
    }

    private static Outcome outcome(String role, UUID run, int lineNo) {
        UUID item = itemOf(run, lineNo);
        return new Outcome(
                role,
                lineNo,
                string("SELECT status FROM reconciliation.external_item WHERE id = ?", item),
                count("SELECT allocated_minor FROM reconciliation.external_item WHERE id = ?",
                        item),
                count("SELECT parked_minor FROM reconciliation.external_item WHERE id = ?", item),
                String.join(",", GateFixtures.column("SELECT type || '/' || cause FROM"
                        + " reconciliation.break WHERE external_item_id = ? ORDER BY type",
                        item)));
    }

    @Test
    @Order(2)
    @DisplayName("deterministic claimant order: competing lines for one expectation, born in"
            + " shuffled orders with adversarial row ids, produce identical allocations - claimant"
            + " order is (source_sequence, line_no)")
    void claimantOrderIsDeterministic() {
        List<Outcome> plain = compete(ORDER_SOURCE_A, ORDER_RULE_SET_A, false);
        List<Outcome> shuffled = compete(ORDER_SOURCE_B, ORDER_RULE_SET_B, true);

        List<Outcome> expected = List.of(
                new Outcome("A", 2, "MATCHED", 60_00, 0, ""),
                new Outcome("B", 1, "PARKED", 40_00, 30_00, "AMOUNT_MISMATCH/AMOUNT_DIFFERS"),
                new Outcome("B", 2, "PARKED", 0, 30_00,
                        "DUPLICATE_EXTERNAL/EXPECTATION_EXHAUSTED"));
        assertThat(plain)
                .as("claimant order: the lower sequence first, then line_no within a run")
                .containsExactlyElementsOf(expected);
        assertThat(shuffled)
                .as("birth order, insertion order and row ids change nothing")
                .containsExactlyElementsOf(plain);
        excessBreak = (UUID) GateFixtures.one("SELECT b.id FROM reconciliation.break b JOIN"
                + " reconciliation.external_item i ON i.id = b.external_item_id JOIN"
                + " reconciliation.reconciliation_batch r ON r.id = i.run_id WHERE"
                + " r.source_id = ? AND b.type = 'AMOUNT_MISMATCH'", ORDER_SOURCE_A);
    }

    // ----------------------------------------------------------------- the battery

    @Test
    @Order(3)
    @DisplayName("the battery resolves breaks across runs: a rematch unparks a grace park"
            + " EVIDENCED, and two four-eyes transfers dispose of the cross-file duplicate and the"
            + " claimant race's excess")
    void theBatteryResolvesAcrossRuns() {
        String key = "INF-BAT-" + UUID.randomUUID();
        UUID graced = GateFixtures.seedRun(SOURCE, RULE_SET, capture(1, 21_00, key));
        GateFixtures.matching().sweep();
        GateFixtures.expireGrace(graced);
        GateFixtures.matching().sweep();
        RUNS.add(graced);
        UUID item = itemOf(graced, 1);
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?", item))
                .isEqualTo("PARKED");
        GateFixtures.openExpectation(SOURCE, RULE_SET, key, 21_00,
                ExpectationDirection.INBOUND, FAR_FUTURE);
        GateFixtures.matching().sweep();
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?", item))
                .as("the rematch unparked the grace park").isEqualTo("MATCHED");
        assertThat(string("SELECT r.kind FROM reconciliation.resolution r JOIN"
                + " reconciliation.suspense_item s ON s.break_id = r.break_id WHERE"
                + " s.external_item_id = ?", item)).isEqualTo("EVIDENCED");

        UUID wallet = GateFixtures.openWallet();
        ResolutionMachine machine = GateFixtures.machine();
        for (UUID breakId : List.of(duplicateBreak, excessBreak)) {
            ResolutionMachine.Proposed proposed = GateFixtures.propose(machine, PROPOSER, breakId,
                    ResolutionKind.TRANSFER_TO_ACCOUNT, ResolutionReasonCode.FUNDS_ATTRIBUTED,
                    Optional.of(wallet), Optional.empty());
            assertThat(GateFixtures.approve(machine, APPROVER, proposed.resolutionId()).status())
                    .isEqualTo(ResolutionStatus.APPROVED);
            assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", breakId))
                    .isEqualTo("RESOLVED");
        }
    }

    @Test
    @Order(4)
    @DisplayName("replay over the battery: every run the suite produced replays IDENTICAL from"
            + " its stored decisions alone - no divergence, every decision on its items read")
    void everyRunReplaysIdentical() {
        RunReplays replays = GateFixtures.replays();
        Actor investigator = new Actor("op-gate-investigator", ActorType.EMPLOYEE);
        assertThat(RUNS).as("the battery produced its runs").hasSizeGreaterThanOrEqualTo(7);
        for (UUID run : List.copyOf(RUNS)) {
            RunReplays.Replay replay =
                    replays.replay(run, investigator, CorrelationId.generate(IDS));
            long decisions = count("SELECT count(*) FROM reconciliation.match_decision d JOIN"
                    + " reconciliation.external_item i ON i.id = d.external_item_id WHERE"
                    + " i.run_id = ?", run);
            assertThat(replay.verdict())
                    .as("run %s replays IDENTICAL (pending rematch: %d)", run,
                            replay.pendingRematch())
                    .isEqualTo("IDENTICAL");
            assertThat(replay.divergences()).as("run %s: no divergence", run).isZero();
            assertThat(replay.replayed()).as("run %s: decisions were re-run", run).isPositive();
            assertThat((long) replay.replayed() + replay.notReplayed())
                    .as("run %s: every decision on its items was read", run)
                    .isEqualTo(decisions);
            assertThat(replay.firstDivergentDecision())
                    .as("run %s: no divergent decision named", run).isEmpty();
            assertThat(count("SELECT count(*) FROM reconciliation.break b JOIN"
                    + " reconciliation.match_decision d ON d.id = b.decision_id JOIN"
                    + " reconciliation.external_item i ON i.id = d.external_item_id WHERE"
                    + " i.run_id = ? AND b.cause = 'REPLAY_DIVERGED'", run))
                    .as("run %s: no REPLAY_DIVERGED break raised", run).isZero();
        }
    }
}
