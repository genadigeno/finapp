package com.finapp.reconciliation;

import static com.finapp.reconciliation.GateFixtures.APPROVER;
import static com.finapp.reconciliation.GateFixtures.EUR;
import static com.finapp.reconciliation.GateFixtures.FAR_FUTURE;
import static com.finapp.reconciliation.GateFixtures.GBP;
import static com.finapp.reconciliation.GateFixtures.IDS;
import static com.finapp.reconciliation.GateFixtures.PROPOSER;
import static com.finapp.reconciliation.GateFixtures.SETTLED_ON;
import static com.finapp.reconciliation.GateFixtures.capture;
import static com.finapp.reconciliation.GateFixtures.count;
import static com.finapp.reconciliation.GateFixtures.fingerprint;
import static com.finapp.reconciliation.GateFixtures.itemOf;
import static com.finapp.reconciliation.GateFixtures.string;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The races the Phase 8 exit review claims (`P8-DOC-001`, PHASE_8_PLAN section 7 C11 and C12),
 * each driven by latches that know the other transaction is BLOCKED ({@code pg_stat_activity}),
 * in both orders, with the production try-lock and with it bypassed (the lock only ORDERS):
 * a manual match approved while the matcher allocates the same pair; a matcher chunk against
 * the ageing sweeper on one expectation; ten bypassed sweepers parking lines that MISMATCH; and
 * ten approvals of ten distinct breaks over the same projection rows, which must all commit.
 *
 * <p>Every scenario seeds its own private source, so no sweep reaches another scenario's rows.
 */
@Tag("database")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the gate's races: C11, C12, bypassed parking and the pre-lock (P8-DOC-001)")
class MatcherRacesDatabaseTest {

    private static final long SECONDS = 60;

    // ----------------------------------------------------------------- C11

    private record Ambiguity(
            UUID source, GateFixtures.Seeded chosen, GateFixtures.Parked parked,
            UUID resolutionId) {}

    /** A planted AMBIGUOUS_MATCH with a pending MANUAL_MATCH proposal naming {@code chosen}. */
    private static Ambiguity proposedManualMatch() {
        UUID source = IDS.next();
        UUID ruleSet = IDS.next();
        GateFixtures.seedRuleSet(source, ruleSet);
        GateFixtures.Seeded chosen = GateFixtures.openExpectation(source, ruleSet,
                "MM-CH-" + UUID.randomUUID(), 30_00, ExpectationDirection.INBOUND, FAR_FUTURE);
        GateFixtures.Seeded other = GateFixtures.openExpectation(source, ruleSet,
                "MM-OT-" + UUID.randomUUID(), 30_00, ExpectationDirection.INBOUND, FAR_FUTURE);
        GateFixtures.Parked parked =
                GateFixtures.plantAmbiguous(source, ruleSet, chosen, other, 30_00);
        ResolutionMachine.Proposed proposed = GateFixtures.propose(GateFixtures.machine(),
                PROPOSER, parked.breakId(), ResolutionKind.MANUAL_MATCH,
                ResolutionReasonCode.AMBIGUITY_RESOLVED_BY_EVIDENCE, Optional.empty(),
                Optional.of(chosen.id()));
        assertThat(proposed.status()).isEqualTo(ResolutionStatus.PROPOSED);
        return new Ambiguity(source, chosen, parked, proposed.resolutionId());
    }

    /** An approving machine that stops, holding every lock, just before its APPROVED edge. */
    private static ResolutionMachine approvalHeldAtDecide(
            CountDownLatch holds, CountDownLatch release) {
        return GateFixtures.machine(
                GateFixtures.intercept(ResolutionStore.class, new JdbcResolutionStore(), "decide",
                        (args, proceed) -> {
                            if (args[2] == ResolutionStatus.APPROVED) {
                                holds.countDown();
                                awaitOrFail(release, "the approval's release");
                            }
                            return proceed.call();
                        }),
                new JdbcMatchingStore());
    }

    /** A matcher that stops, holding every lock, after its unpark and before its evidence. */
    private static Matching matcherHeldAtEvidence(
            CountDownLatch holds, CountDownLatch release, boolean bypassTheTryLock) {
        Resolutions real = ResolutionFixtures.resolutions(IDS, GateFixtures.CLOCK);
        GateFixtures.MatchingParts parts = new GateFixtures.MatchingParts();
        parts.bypassTheTryLock = bypassTheTryLock;
        parts.resolutions = (unitOfWork, evidence) -> {
            if (evidence.parkId().isPresent()) {
                holds.countDown();
                awaitOrFail(release, "the matcher's release");
            }
            return real.evidence(unitOfWork, evidence);
        };
        return parts.build();
    }

    /** One allocation of the pair, by {@code origin}, and every closing effect exactly once. */
    private static void assertOneAllocation(Ambiguity ambiguity, String origin) {
        UUID item = ambiguity.parked().itemId();
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE expectation_id = ?"
                + " AND reverses_allocation_id IS NULL", ambiguity.chosen().id()))
                .as("C11: ONE allocation of the chosen expectation, whoever won")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                + " external_item_id = ?", item)).as("C11: one allocation of the item").isEqualTo(1);
        assertThat(string("SELECT d.origin FROM reconciliation.allocation a JOIN"
                + " reconciliation.match_decision d ON d.id = a.decision_id WHERE"
                + " a.external_item_id = ?", item))
                .as("C11: the winner's decision").isEqualTo(origin);
        assertThat(string("SELECT status || '/' || allocated_minor FROM"
                + " reconciliation.expectation WHERE id = ?", ambiguity.chosen().id()))
                .isEqualTo("SETTLED/3000");
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?", item))
                .isEqualTo("MATCHED");
        assertThat(string("SELECT status || '/' || released_minor FROM"
                + " reconciliation.suspense_item WHERE id = ?",
                ambiguity.parked().suspenseItemId()))
                .as("C11: the parked value released once").isEqualTo("RELEASED/3000");
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_release WHERE item_id = ?",
                ambiguity.parked().suspenseItemId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.park WHERE source_id = ? AND"
                + " kind = 'UNPARK'", ambiguity.source()))
                .as("C11: one unpark entry, never two").isEqualTo(1);
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?",
                ambiguity.parked().breakId())).isEqualTo("RESOLVED");
    }

    @Test
    @Order(1)
    @DisplayName("C11, production lock, the approval first: the matcher's try-lock is refused"
            + " while the approval holds the source, and afterwards finds the item MATCHED - one"
            + " MANUAL allocation")
    void c11ApprovalFirstUnderTheLock() throws Exception {
        Ambiguity ambiguity = proposedManualMatch();
        CountDownLatch holds = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            ResolutionMachine latched = approvalHeldAtDecide(holds, release);
            Future<ResolutionMachine.Decided> approval = pool.submit(() ->
                    GateFixtures.approve(latched, APPROVER, ambiguity.resolutionId()));
            awaitOrFail(holds, "the approval holding its locks");
            GateFixtures.matching().sweep();
            assertThat(approval.isDone())
                    .as("C11: the approval was still holding while the matcher swept").isFalse();
            release.countDown();
            ResolutionMachine.Decided decided = approval.get(SECONDS, TimeUnit.SECONDS);
            GateFixtures.matching().sweep();

            assertThat(decided.status()).isEqualTo(ResolutionStatus.APPROVED);
            assertThat(rematchDecisions(ambiguity))
                    .as("C11: the try-lock refused the matcher while the approval held the"
                            + " source, and afterwards the MATCHED item is off the worklist")
                    .isZero();
            assertOneAllocation(ambiguity, "MANUAL");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @Order(2)
    @DisplayName("C11, production lock, the matcher first: the approval waits on the source's"
            + " advisory, then finds its proposal WITHDRAWN by the evidence - ResolutionNotPending,"
            + " one REMATCH allocation")
    void c11MatcherFirstUnderTheLock() throws Exception {
        c11MatcherFirst(false);
    }

    @Test
    @Order(3)
    @DisplayName("C11, try-lock bypassed, the approval first: the matcher blocks on the item row"
            + " the approval moved, and its locking re-read excludes the MATCHED item - one"
            + " MANUAL allocation")
    void c11ApprovalFirstBypassed() throws Exception {
        Ambiguity ambiguity = proposedManualMatch();
        CountDownLatch holds = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            ResolutionMachine latched = approvalHeldAtDecide(holds, release);
            Future<ResolutionMachine.Decided> approval = pool.submit(() ->
                    GateFixtures.approve(latched, APPROVER, ambiguity.resolutionId()));
            awaitOrFail(holds, "the approval holding its locks");
            GateFixtures.MatchingParts parts = new GateFixtures.MatchingParts();
            parts.bypassTheTryLock = true;
            Matching bypassed = parts.build();
            Future<Matching.SweepResult> matcher = pool.submit(bypassed::sweep);
            assertThat(GateFixtures.awaitLockWaiter(matcher, ""))
                    .as("C11: the bypassed matcher is held on a row the approval locked")
                    .isTrue();
            release.countDown();
            ResolutionMachine.Decided decided = approval.get(SECONDS, TimeUnit.SECONDS);
            Matching.SweepResult swept = matcher.get(SECONDS, TimeUnit.SECONDS);

            assertThat(decided.status()).isEqualTo(ResolutionStatus.APPROVED);
            assertThat(swept).isNotNull();
            assertThat(rematchDecisions(ambiguity))
                    .as("C11: the matcher's locking re-read saw the item MATCHED and acted on"
                            + " nothing")
                    .isZero();
            assertOneAllocation(ambiguity, "MANUAL");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @Order(4)
    @DisplayName("C11, try-lock bypassed, the matcher first: the approval blocks behind the"
            + " matcher's locks, then finds its proposal WITHDRAWN - ResolutionNotPending, one"
            + " REMATCH allocation")
    void c11MatcherFirstBypassed() throws Exception {
        c11MatcherFirst(true);
    }

    private void c11MatcherFirst(boolean bypassTheTryLock) throws Exception {
        Ambiguity ambiguity = proposedManualMatch();
        CountDownLatch holds = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            Matching latched = matcherHeldAtEvidence(holds, release, bypassTheTryLock);
            Future<Matching.SweepResult> matcher = pool.submit(latched::sweep);
            awaitOrFail(holds, "the matcher holding its locks");
            Future<ResolutionMachine.Decided> approval = pool.submit(() ->
                    GateFixtures.approve(GateFixtures.machine(), APPROVER,
                            ambiguity.resolutionId()));
            assertThat(GateFixtures.awaitLockWaiter(approval, ""))
                    .as("C11: the approval is held behind the matcher's locks")
                    .isTrue();
            release.countDown();
            Matching.SweepResult swept = matcher.get(SECONDS, TimeUnit.SECONDS);
            Throwable loser = failureOf(approval);

            assertThat(swept).isNotNull();
            assertThat(rematchDecisions(ambiguity))
                    .as("C11: the matcher allocated the pair, once").isEqualTo(1);
            assertThat(loser)
                    .as("C11: the losing approval is told its proposal is no longer pending -"
                            + " the evidence withdrew it under the break's lock")
                    .isInstanceOf(ResolutionMachine.ResolutionNotPending.class);
            assertThat(string("SELECT status FROM reconciliation.resolution WHERE id = ?",
                    ambiguity.resolutionId())).isEqualTo("WITHDRAWN");
            assertThat(string("SELECT kind FROM reconciliation.resolution WHERE break_id = ? AND"
                    + " status = 'APPROVED'", ambiguity.parked().breakId()))
                    .isEqualTo("EVIDENCED");
            assertOneAllocation(ambiguity, "REMATCH");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @Order(5)
    @DisplayName("C11, the competing claim: another line allocates the chosen expectation first -"
            + " the approval re-derives the candidate under its lock and is refused"
            + " ResolutionStale, nothing written")
    void c11ACompetingClaimMakesTheApprovalStale() throws Exception {
        Ambiguity ambiguity = proposedManualMatch();
        UUID ruleSet = (UUID) GateFixtures.one("SELECT rule_set_id FROM"
                + " reconciliation.reconciliation_batch WHERE id = ?", ambiguity.parked().runId());
        UUID competing = GateFixtures.seedRun(ambiguity.source(), ruleSet,
                capture(1, 30_00, ambiguity.chosen().key()));
        GateFixtures.matching().sweep();
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?",
                itemOf(competing, 1))).isEqualTo("MATCHED");

        Throwable refused = null;
        try {
            GateFixtures.approve(GateFixtures.machine(), APPROVER, ambiguity.resolutionId());
        } catch (RuntimeException thrown) {
            refused = thrown;
        }
        assertThat(refused)
                .as("C11: a moved candidate is a moved subject - ResolutionStale")
                .isInstanceOf(ResolutionMachine.ResolutionStale.class);
        assertThat(string("SELECT status FROM reconciliation.resolution WHERE id = ?",
                ambiguity.resolutionId())).as("the stale approval wrote nothing").isEqualTo("PROPOSED");
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE expectation_id = ?"
                + " AND reverses_allocation_id IS NULL", ambiguity.chosen().id()))
                .as("C11: the competing line's allocation alone").isEqualTo(1);
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?",
                ambiguity.parked().itemId())).isEqualTo("PARKED");
        assertThat(string("SELECT status FROM reconciliation.suspense_item WHERE id = ?",
                ambiguity.parked().suspenseItemId())).isEqualTo("OPEN");
    }

    // ----------------------------------------------------------------- C12

    private record Overdue(UUID source, GateFixtures.Seeded expectation, UUID run) {}

    /** An expectation already past its window and a run line that settles it exactly. */
    private static Overdue overdueWithItsLine() {
        UUID source = IDS.next();
        UUID ruleSet = IDS.next();
        GateFixtures.seedRuleSet(source, ruleSet);
        LocalDate expectedBy = GateFixtures.databaseToday().minusDays(60);
        String key = "C12-" + UUID.randomUUID();
        GateFixtures.Seeded expectation = GateFixtures.openExpectation(source, ruleSet, key,
                50_00, ExpectationDirection.INBOUND, expectedBy);
        // Settled on the expected date itself: no TIMING_DIFFERENCE muddies the census.
        UUID run = GateFixtures.seedRun(source, ruleSet,
                new GateFixtures.Line(1, ExternalLineType.CAPTURE, ExpectationDirection.INBOUND,
                        50_00, EUR, ItemKeyKind.PSP_CAPTURE_REF, key, fingerprint(), expectedBy));
        return new Overdue(source, expectation, run);
    }

    private static void assertNeverBoth(Overdue overdue) {
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE expectation_id = ?",
                overdue.expectation().id())).as("C12: one allocation").isEqualTo(1);
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                overdue.expectation().id())).isEqualTo("SETTLED");
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE expectation_id = ? AND"
                + " type = 'MISSING_EXTERNAL' AND status <> 'RESOLVED'",
                overdue.expectation().id()))
                .as("C12: never a stale overdue break standing beside the allocation")
                .isZero();
    }

    @Test
    @Order(6)
    @DisplayName("C12, the ageing sweeper first: it marks the expectation and raises its"
            + " MISSING_EXTERNAL while the chunk waits on the row - the chunk then settles it and"
            + " closes the break EVIDENCED")
    void c12AgeingFirst() throws Exception {
        Overdue overdue = overdueWithItsLine();
        CountDownLatch holds = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        BreakRegister real = GateFixtures.register();
        BreakRegister latched = (unitOfWork, newBreak) -> {
            if (newBreak.type() == BreakType.MISSING_EXTERNAL
                    && newBreak.subject().expectationId()
                            .equals(Optional.of(overdue.expectation().id()))) {
                holds.countDown();
                awaitOrFail(release, "the ageing sweeper's release");
            }
            return real.raise(unitOfWork, newBreak);
        };
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            Future<ReconciliationSweep.SweepResult> ageing =
                    pool.submit(() -> GateFixtures.reconciliationSweep(latched).sweep());
            awaitOrFail(holds, "the ageing sweeper holding the expectation");
            Future<Matching.SweepResult> matcher = pool.submit(GateFixtures.matching()::sweep);
            assertThat(GateFixtures.awaitLockWaiter(matcher, ""))
                    .as("C12: the chunk is held on the expectation row the sweeper marked")
                    .isTrue();
            release.countDown();
            ageing.get(SECONDS, TimeUnit.SECONDS);
            matcher.get(SECONDS, TimeUnit.SECONDS);

            assertNeverBoth(overdue);
            assertThat(count("SELECT count(*) FROM reconciliation.expectation WHERE id = ? AND"
                    + " overdue_since IS NOT NULL", overdue.expectation().id()))
                    .as("C12: the sweeper's one-way fact stands").isEqualTo(1);
            assertThat(string("SELECT r.kind || '/' || r.status FROM reconciliation.resolution r"
                    + " JOIN reconciliation.break b ON b.id = r.break_id WHERE"
                    + " b.expectation_id = ? AND b.type = 'MISSING_EXTERNAL'",
                    overdue.expectation().id()))
                    .as("C12: the late money explains the overdue break EVIDENCED")
                    .isEqualTo("EVIDENCED/APPROVED");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @Order(7)
    @DisplayName("C12, the chunk first: it allocates while the sweeper waits on the row - the"
            + " sweeper's conditional mark then finds the expectation SETTLED and writes nothing:"
            + " no overdue_since, no break")
    void c12ChunkFirst() throws Exception {
        Overdue overdue = overdueWithItsLine();
        CountDownLatch holds = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        GateFixtures.MatchingParts parts = new GateFixtures.MatchingParts();
        parts.store = GateFixtures.intercept(MatchingStore.class, new JdbcMatchingStore(),
                "insertAllocation", (args, proceed) -> {
                    holds.countDown();
                    awaitOrFail(release, "the chunk's release");
                    return proceed.call();
                });
        Matching latched = parts.build();
        ExecutorService pool = Executors.newCachedThreadPool();
        try {
            Future<Matching.SweepResult> matcher = pool.submit(latched::sweep);
            awaitOrFail(holds, "the chunk holding the expectation");
            Future<ReconciliationSweep.SweepResult> ageing = pool.submit(() ->
                    GateFixtures.reconciliationSweep(GateFixtures.register()).sweep());
            assertThat(GateFixtures.awaitLockWaiter(ageing, ""))
                    .as("C12: the sweeper is held on the expectation row the chunk locked")
                    .isTrue();
            release.countDown();
            matcher.get(SECONDS, TimeUnit.SECONDS);
            ageing.get(SECONDS, TimeUnit.SECONDS);

            assertNeverBoth(overdue);
            assertThat(count("SELECT count(*) FROM reconciliation.expectation WHERE id = ? AND"
                    + " overdue_since IS NULL", overdue.expectation().id()))
                    .as("C12: a settled expectation is never marked overdue").isEqualTo(1);
            assertThat(count("SELECT count(*) FROM reconciliation.break WHERE expectation_id = ?",
                    overdue.expectation().id()))
                    .as("C12: no break at all - the money arrived first").isZero();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    // ----------------------------------------------------------------- bypassed parking

    @Test
    @Order(8)
    @DisplayName("parking with the try-lock bypassed: ten sweepers over lines that MISMATCH park"
            + " each at most once - one suspense item, one park row and one entry per park, the"
            + " allocations once - the money arbitrated by the schema, three rounds")
    void tenBypassedSweepersParkMismatchesOnce() throws Exception {
        UUID source = IDS.next();
        UUID ruleSet = IDS.next();
        GateFixtures.seedRuleSet(source, ruleSet);
        for (int round = 0; round < 3; round++) {
            String tag = "R" + round + "-" + UUID.randomUUID().toString().substring(0, 8);
            GateFixtures.Seeded over = GateFixtures.openExpectation(source, ruleSet,
                    "OVER-" + tag, 60_00, ExpectationDirection.INBOUND, FAR_FUTURE);
            GateFixtures.Seeded twice = GateFixtures.openExpectation(source, ruleSet,
                    "TWICE-" + tag, 100_00, ExpectationDirection.INBOUND, FAR_FUTURE);
            GateFixtures.openExpectation(source, ruleSet, "GBP-" + tag, 100_00, GBP,
                    ExpectationDirection.INBOUND, FAR_FUTURE);
            GateFixtures.Seeded contradicted = GateFixtures.openExpectation(source, ruleSet,
                    "DIR-" + tag, 100_00, ExpectationDirection.INBOUND, FAR_FUTURE);
            GateFixtures.Seeded under = GateFixtures.openExpectation(source, ruleSet,
                    "UNDER-" + tag, 50_00, ExpectationDirection.INBOUND, FAR_FUTURE);
            UUID run = GateFixtures.seedRun(source, ruleSet,
                    capture(1, 100_00, "OVER-" + tag),
                    capture(2, 100_00, "TWICE-" + tag),
                    capture(3, 100_00, "TWICE-" + tag),
                    capture(4, 100_00, "GBP-" + tag),
                    GateFixtures.refund(5, 30_00, "TERM-" + tag),
                    new GateFixtures.Line(6, ExternalLineType.CAPTURE,
                            ExpectationDirection.OUTBOUND, 100_00, EUR,
                            ItemKeyKind.PSP_CAPTURE_REF, "DIR-" + tag, fingerprint(), SETTLED_ON),
                    capture(7, 20_00, "UNDER-" + tag));

            GateFixtures.MatchingParts parts = new GateFixtures.MatchingParts();
            parts.bypassTheTryLock = true;
            parts.config = new Matching.Config(200, 100);
            Matching bypassed = parts.build();
            ExecutorService racers = Executors.newFixedThreadPool(10);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<?>> outcomes = new ArrayList<>();
                for (int racer = 0; racer < 10; racer++) {
                    outcomes.add(racers.submit(() -> {
                        start.await();
                        bypassed.sweep();
                        return null;
                    }));
                }
                start.countDown();
                for (Future<?> outcome : outcomes) {
                    outcome.get(SECONDS, TimeUnit.SECONDS);
                }
            } finally {
                racers.shutdownNow();
            }

            // What each line parks, by claimant order: line 1's excess, line 3's exhausted
            // whole, line 4's currency, line 5's terminal refund, line 6's direction.
            Map<Integer, Long> parkedMinor =
                    Map.of(1, 40_00L, 2, 0L, 3, 100_00L, 4, 100_00L, 5, 30_00L, 6, 100_00L,
                            7, 0L);
            for (Map.Entry<Integer, Long> expected : parkedMinor.entrySet()) {
                UUID item = itemOf(run, expected.getKey());
                long suspenseItems = count("SELECT count(*) FROM reconciliation.suspense_item"
                        + " WHERE external_item_id = ?", item);
                assertThat(suspenseItems)
                        .as("round %d line %d: at most one suspense item, exactly where value"
                                + " parks", round, expected.getKey())
                        .isEqualTo(expected.getValue() > 0 ? 1L : 0L);
                if (expected.getValue() > 0) {
                    assertThat(count("SELECT amount_minor FROM reconciliation.suspense_item"
                            + " WHERE external_item_id = ?", item))
                            .as("round %d line %d: the parked value, once", round,
                                    expected.getKey())
                            .isEqualTo(expected.getValue());
                }
                assertThat(count("SELECT parked_minor FROM reconciliation.external_item WHERE"
                        + " id = ?", item))
                        .as("round %d line %d: the item's parked figure agrees", round,
                                expected.getKey())
                        .isEqualTo(expected.getValue());
            }
            assertThat(count("SELECT count(*) FROM reconciliation.park p WHERE p.source_id = ?"
                    + " AND p.kind = 'PARK' AND NOT EXISTS (SELECT 1 FROM"
                    + " reconciliation.suspense_item s WHERE s.park_id = p.id)", source))
                    .as("round %d: no park row without its suspense item - no orphan entry",
                            round)
                    .isZero();
            assertThat(count("SELECT count(*) FROM reconciliation.park p WHERE p.source_id = ?"
                    + " AND (SELECT count(*) FROM ledger.journal_entry e WHERE e.reference ="
                    + " p.id::text) <> 1", source))
                    .as("round %d: one journal entry per park, never two", round)
                    .isZero();
            assertThat(string("SELECT allocated_minor || '/' || (SELECT count(*) FROM"
                    + " reconciliation.allocation a WHERE a.expectation_id = e.id) FROM"
                    + " reconciliation.expectation e WHERE e.id = ?", over.id()))
                    .as("round %d: line 1's 60.00 allocated once", round).isEqualTo("6000/1");
            assertThat(string("SELECT allocated_minor || '/' || (SELECT count(*) FROM"
                    + " reconciliation.allocation a WHERE a.expectation_id = e.id) FROM"
                    + " reconciliation.expectation e WHERE e.id = ?", twice.id()))
                    .as("round %d: one of the twin lines allocated, once", round)
                    .isEqualTo("10000/1");
            assertThat(string("SELECT allocated_minor FROM reconciliation.expectation WHERE"
                    + " id = ?", under.id())).isEqualTo("2000");
            assertThat(string("SELECT allocated_minor FROM reconciliation.expectation WHERE"
                    + " id = ?", contradicted.id()))
                    .as("round %d: a contradicted direction allocates nothing", round)
                    .isEqualTo("0");
        }
    }

    // ----------------------------------------------------------------- the pre-lock

    @Test
    @Order(9)
    @DisplayName("the pre-lock, no deadlock: ten concurrent approvals of ten DISTINCT breaks on"
            + " ten sources - transfers, write-offs and manual-match unparks of both sides - over"
            + " the same suspense, losses, clearing and wallet rows all commit, no 40P01")
    void tenApprovalsOverSharedProjectionRowsAllCommit() throws Exception {
        UUID wallet = GateFixtures.openWallet();
        List<UUID> proposals = new ArrayList<>();
        List<UUID> breaks = new ArrayList<>();
        ResolutionMachine machine = GateFixtures.machine();
        for (int index = 0; index < 10; index++) {
            UUID source = IDS.next();
            UUID ruleSet = IDS.next();
            GateFixtures.seedRuleSet(source, ruleSet);
            String tag = "PRE-" + index + "-" + UUID.randomUUID().toString().substring(0, 8);
            UUID breakId;
            ResolutionKind kind;
            ResolutionReasonCode code;
            Optional<UUID> target = Optional.empty();
            Optional<UUID> chosen = Optional.empty();
            switch (index % 5) {
                case 0 -> { // DR suspense / CR wallet
                    breakId = GateFixtures.parked(source, ruleSet, capture(1, 11_00 + index, tag),
                            BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT, SETTLED_ON)
                            .breakId();
                    kind = ResolutionKind.TRANSFER_TO_ACCOUNT;
                    code = ResolutionReasonCode.FUNDS_ATTRIBUTED;
                    target = Optional.of(wallet);
                }
                case 1 -> { // DR losses / CR suspense
                    breakId = GateFixtures.parked(source, ruleSet,
                            GateFixtures.refund(1, 12_00 + index, tag),
                            BreakType.UNKNOWN_EXTERNAL, BreakCause.PARKED_ON_RECEIPT, SETTLED_ON)
                            .breakId();
                    kind = ResolutionKind.WRITE_OFF;
                    code = ResolutionReasonCode.LOSS_ACCEPTED;
                }
                case 2 -> { // DR losses / CR clearing
                    GateFixtures.Seeded owed = GateFixtures.openExpectation(source, ruleSet, tag,
                            13_00 + index, ExpectationDirection.INBOUND, FAR_FUTURE);
                    breakId = GateFixtures.raise(BreakType.MISSING_EXTERNAL,
                            BreakCause.EXPECTATION_OVERDUE,
                            BreakRegister.Subject.expectation(owed.id()), 13_00 + index, source,
                            ruleSet);
                    kind = ResolutionKind.WRITE_OFF;
                    code = ResolutionReasonCode.LOSS_ACCEPTED;
                }
                default -> { // the unparks: DR suspense / CR clearing, and its mirror
                    ExpectationDirection direction = index % 5 == 3
                            ? ExpectationDirection.INBOUND : ExpectationDirection.OUTBOUND;
                    GateFixtures.Seeded pick = GateFixtures.openExpectation(source, ruleSet,
                            tag + "-A", 14_00, direction, FAR_FUTURE);
                    GateFixtures.Seeded rival = GateFixtures.openExpectation(source, ruleSet,
                            tag + "-B", 14_00, direction, FAR_FUTURE);
                    breakId = GateFixtures.plantAmbiguous(source, ruleSet, pick, rival, 14_00,
                            direction).breakId();
                    kind = ResolutionKind.MANUAL_MATCH;
                    code = ResolutionReasonCode.AMBIGUITY_RESOLVED_BY_EVIDENCE;
                    chosen = Optional.of(pick.id());
                }
            }
            breaks.add(breakId);
            proposals.add(GateFixtures.propose(machine, PROPOSER, breakId, kind, code, target,
                    chosen).resolutionId());
        }

        ExecutorService approvers = Executors.newFixedThreadPool(10);
        List<Future<ResolutionMachine.Decided>> outcomes = new ArrayList<>();
        try {
            CountDownLatch start = new CountDownLatch(1);
            for (UUID proposal : proposals) {
                outcomes.add(approvers.submit(() -> {
                    start.await();
                    return GateFixtures.approve(machine, APPROVER, proposal);
                }));
            }
            start.countDown();
            List<Throwable> failures = new ArrayList<>();
            for (Future<ResolutionMachine.Decided> outcome : outcomes) {
                Throwable failure = failureOf(outcome);
                if (failure != null) {
                    failures.add(failure);
                }
            }
            assertThat(failures.stream().filter(MatcherRacesDatabaseTest::isDeadlock).toList())
                    .as("the pre-lock: no approval died of a deadlock (SQLSTATE 40P01)")
                    .isEmpty();
            assertThat(failures)
                    .as("the pre-lock: every one of the ten approvals committed")
                    .isEmpty();
        } finally {
            approvers.shutdownNow();
        }
        for (UUID breakId : breaks) {
            assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", breakId))
                    .as("every break RESOLVED by its own approval").isEqualTo("RESOLVED");
        }
        for (UUID proposal : proposals) {
            assertThat(string("SELECT status FROM reconciliation.resolution WHERE id = ?",
                    proposal)).isEqualTo("APPROVED");
            assertThat(GateFixtures.one("SELECT journal_entry_id FROM reconciliation.resolution"
                    + " WHERE id = ?", proposal))
                    .as("every approval posted its one entry").isNotNull();
        }
    }

    // ----------------------------------------------------------------- plumbing

    private static long rematchDecisions(Ambiguity ambiguity) {
        return count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " external_item_id = ? AND origin = 'REMATCH'", ambiguity.parked().itemId());
    }

    private static void awaitOrFail(CountDownLatch latch, String what) {
        try {
            if (!latch.await(SECONDS, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for " + what);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for " + what, interrupted);
        }
    }

    /** The racer's failure, or null when it returned. */
    private static Throwable failureOf(Future<?> racer) throws Exception {
        try {
            racer.get(SECONDS, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException failed) {
            return failed.getCause();
        }
    }

    private static boolean isDeadlock(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "40P01".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
