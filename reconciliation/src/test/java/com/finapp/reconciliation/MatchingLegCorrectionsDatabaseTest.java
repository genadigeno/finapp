package com.finapp.reconciliation;

import static com.finapp.reconciliation.GateFixtures.EUR;
import static com.finapp.reconciliation.GateFixtures.FAR_FUTURE;
import static com.finapp.reconciliation.GateFixtures.IDS;
import static com.finapp.reconciliation.GateFixtures.PLATFORM;
import static com.finapp.reconciliation.GateFixtures.SETTLED_ON;
import static com.finapp.reconciliation.GateFixtures.count;
import static com.finapp.reconciliation.GateFixtures.execute;
import static com.finapp.reconciliation.GateFixtures.itemOf;
import static com.finapp.reconciliation.GateFixtures.string;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The Phase 8 -> 9 transition's corrections to the matcher's legs and time's observers, each
 * reproducing the completion gate's scenario against the real schema and ledger: a definitive
 * fingerprint duplicate never allocating by a late leg (REC-1); a line no rule can allocate owned
 * at once (REC-2); every settling path closing every remainder break (REC-5); a contained fee line
 * re-checked by a reprocess run (REC-6); a correction's top-up seen by the rest of its chunk
 * (ATOM-01); a rolled-back item undoing its Java effects with its rows in every leg (ATOM-02); a
 * late reach judged on rows, never on two clocks (rematch-keyed-valuedate-clocks), consumed once
 * (rematch-key-clause-reselection), in claimant order (claimant-order-by-leg); the expectation
 * events carrying no operation reference and the ageing leg passing a failing row (ARCH-P8-01);
 * the escalation selecting only due rows (MI-1); a correction offsetting the park its own chunk
 * queued, as it would across two runs (ATOM-04); a conditional exit that finds its row moved
 * rolling the item back (IDEM-3).
 *
 * <p>Every scenario seeds its own private source (the {@code GateFixtures} discipline), so no
 * sweep's per-source work reaches another scenario's rows. The matcher's clock is the fixtures'
 * - months behind the database's - which is exactly what made the old two-clock rematch reading
 * pass for the wrong reason; the scenarios below that depend on a reach are built so that reading
 * would MISS it.
 */
@Tag("database")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the matcher's legs and time's observers, corrected (the Phase 8 -> 9 transition)")
class MatchingLegCorrectionsDatabaseTest {

    /** Sixty days before the settlement date: overdue at any real database clock. */
    private static final LocalDate FAR_PAST = SETTLED_ON.minusDays(60);

    private static final Actor CONTROLLER = new Actor("op-transition-controller", ActorType.EMPLOYEE);

    // ----------------------------------------------------------------- REC-1

    @Test
    @Order(1)
    @DisplayName("REC-1, the reprocess leg: a REPEATED_FINGERPRINT park is never on a REPROCESS"
            + " run's worklist - with its expectation's remainder able to absorb it, it stays"
            + " PARKED, its duplicate break OPEN, and the expectation holds its one allocation")
    void aFingerprintDuplicateNeverAllocatesByReprocess() {
        Source s = source();
        String key = "REC1-RP-" + UUID.randomUUID();
        GateFixtures.Seeded expectation =
                GateFixtures.openExpectation(s.id(), s.ruleSet(), key, 200_00,
                        ExpectationDirection.INBOUND, FAR_FUTURE);
        byte[] same = GateFixtures.fingerprint();
        GateFixtures.seedRun(s.id(), s.ruleSet(), line(1, ExternalLineType.CAPTURE, 100_00,
                ItemKeyKind.PSP_CAPTURE_REF, key, same));
        GateFixtures.matching().sweep();
        UUID second = GateFixtures.seedRun(s.id(), s.ruleSet(),
                line(1, ExternalLineType.CAPTURE, 100_00, ItemKeyKind.PSP_CAPTURE_REF, key, same),
                capture(2, 7_00, "REC1-RP-WAITS-" + UUID.randomUUID()));
        GateFixtures.matching().sweep();
        UUID repeat = itemOf(second, 1);
        assertThat(breakFacts(repeat))
                .as("precondition: the identical line is the definitive duplicate")
                .containsExactly("DUPLICATE_EXTERNAL/REPEATED_FINGERPRINT/OPEN");

        RunAdministration.Reprocessing opened = reprocess(s);
        assertThat(opened.itemCount())
                .as("the worklist holds the waiting line alone - never the duplicate's park")
                .isEqualTo(1);
        sweepUntilCompleted(GateFixtures.matching(), opened.runId());

        assertThat(status(repeat)).as("the duplicate's park leaves only by a person").isEqualTo(
                "PARKED");
        assertThat(breakFacts(repeat)).containsExactly(
                "DUPLICATE_EXTERNAL/REPEATED_FINGERPRINT/OPEN");
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " external_item_id = ? AND origin IN ('REPROCESS', 'REMATCH')", repeat))
                .as("no late leg re-decided the duplicate").isZero();
        assertThat(string("SELECT status || '/' || allocated_minor FROM"
                + " reconciliation.expectation WHERE id = ?", expectation.id()))
                .as("the counterparty's repeat discharged nothing").isEqualTo("PARTIALLY_SETTLED/10000");
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE expectation_id = ?",
                expectation.id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item WHERE"
                + " external_item_id = ? AND status = 'OPEN'", repeat))
                .as("its value still parked").isEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("REC-1, the rematch leg: an expectation opening after a line and its identical"
            + " repeat were decided is claimed by the genuine line alone - the repeat (a LOWER"
            + " line_no in a later report) stays PARKED, off the worklist, its break OPEN")
    void aFingerprintDuplicateNeverAllocatesByRematch() {
        Source s = source();
        String key = "REC1-RM-" + UUID.randomUUID();
        byte[] same = GateFixtures.fingerprint();
        UUID genuineRun = GateFixtures.seedRun(s.id(), s.ruleSet(),
                line(5, ExternalLineType.CAPTURE, 100_00, ItemKeyKind.PSP_CAPTURE_REF, key, same));
        GateFixtures.matching().sweep();
        UUID repeatRun = GateFixtures.seedRun(s.id(), s.ruleSet(),
                line(1, ExternalLineType.CAPTURE, 100_00, ItemKeyKind.PSP_CAPTURE_REF, key, same));
        GateFixtures.matching().sweep();
        UUID genuine = itemOf(genuineRun, 5);
        UUID repeat = itemOf(repeatRun, 1);
        assertThat(status(genuine)).isEqualTo("UNMATCHED");
        assertThat(breakFacts(repeat)).containsExactly(
                "DUPLICATE_EXTERNAL/REPEATED_FINGERPRINT/OPEN");

        GateFixtures.Seeded late = GateFixtures.openExpectation(s.id(), s.ruleSet(), key, 200_00,
                ExpectationDirection.INBOUND, FAR_FUTURE);
        assertThat(rematchWorklist(s.id()))
                .as("the genuine line is on the worklist, the definitive duplicate never")
                .contains(genuine).doesNotContain(repeat);
        GateFixtures.matching().sweep();

        assertThat(status(genuine)).isEqualTo("MATCHED");
        assertThat(status(repeat)).isEqualTo("PARKED");
        assertThat(breakFacts(repeat)).containsExactly(
                "DUPLICATE_EXTERNAL/REPEATED_FINGERPRINT/OPEN");
        assertThat(column("SELECT external_item_id::text FROM reconciliation.allocation WHERE"
                + " expectation_id = ?", late.id()))
                .as("ONE allocation on the late expectation, the genuine line's")
                .containsExactly(genuine.toString());
    }

    // ----------------------------------------------------------------- REC-2

    @Test
    @Order(3)
    @DisplayName("REC-2: a line no rule of its pinned version can allocate (OTHER_IN) is OWNED AT"
            + " ONCE - parked with its UNKNOWN_EXTERNAL break and its RECON_PARK suspense item at"
            + " run time, no grace window hand-written, never left waiting with no clock")
    void aRuleLessLineIsOwnedAtOnce() {
        Source s = source();
        String reference = "MISC-" + UUID.randomUUID();
        UUID run = GateFixtures.seedRun(s.id(), s.ruleSet(),
                line(1, ExternalLineType.OTHER_IN, 5_00, ItemKeyKind.OUR_REF, reference));
        GateFixtures.matching().sweep();

        UUID item = itemOf(run, 1);
        assertThat(status(item)).as("owned at run time").isEqualTo("PARKED");
        assertThat(breakFacts(item)).containsExactly("UNKNOWN_EXTERNAL/GRACE_EXPIRED/OPEN");
        assertThat(string("SELECT origin || '/' || side || '/' || amount_minor || '/' || status"
                + " FROM reconciliation.suspense_item WHERE external_item_id = ?", item))
                .as("its value owned in suspense, never only a waiting row")
                .isEqualTo("RECON_PARK/CREDIT/500/OPEN");
        assertThat(string("SELECT verdict || '/' || outcome FROM reconciliation.match_decision"
                + " WHERE external_item_id = ?", item))
                .as("the decision states why: no rule of its version serves the line")
                .isEqualTo("NO_RULE/PARKED");
        assertThat(GateFixtures.one("SELECT grace_until FROM reconciliation.external_item WHERE"
                + " id = ?", item)).as("it never waited").isNull();
    }

    // ----------------------------------------------------------------- REC-5

    @Test
    @Order(4)
    @DisplayName("REC-5, the parked rematch: a parked line that settles an expectation by a late"
            + " rematch closes BOTH its remainder breaks EVIDENCED - the overdue MISSING_EXTERNAL"
            + " and the earlier tranche's AMOUNT_MISMATCH - nothing left over a SETTLED"
            + " expectation")
    void aParkedRematchClosesEveryRemainderBreak() {
        Source s = source();
        String key = "REC5-PR-" + UUID.randomUUID();
        UUID parkedRun = GateFixtures.seedRun(s.id(), s.ruleSet(), capture(1, 100_00, key));
        GateFixtures.matching().sweep();
        GateFixtures.expireGrace(parkedRun);
        GateFixtures.matching().sweep();
        UUID parked = itemOf(parkedRun, 1);
        assertThat(status(parked)).as("precondition: parked at grace").isEqualTo("PARKED");

        GateFixtures.Seeded expectation = GateFixtures.openExpectation(s.id(), s.ruleSet(), key,
                200_00, ExpectationDirection.INBOUND, FAR_PAST);
        GateFixtures.reconciliationSweep(GateFixtures.register()).sweep();
        UUID overdue = breakOn(expectation.id(), "MISSING_EXTERNAL");
        // The first tranche, at run time: half the remainder, its shortfall raised.
        GateFixtures.seedRun(s.id(), s.ruleSet(), capture(1, 100_00, key));
        GateFixtures.matching().sweep();

        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                expectation.id())).isEqualTo("SETTLED");
        assertThat(status(parked)).as("the parked line settled it late").isEqualTo("MATCHED");
        assertThat(column("SELECT type || '/' || status FROM reconciliation.break WHERE"
                + " expectation_id = ? ORDER BY type", expectation.id()))
                .as("the overdue break AND the shortfall closed by the settling rematch")
                .containsExactly("AMOUNT_MISMATCH/RESOLVED", "MISSING_EXTERNAL/RESOLVED");
        assertThat(string("SELECT r.kind || '/' || d.origin FROM reconciliation.resolution r JOIN"
                + " reconciliation.match_decision d ON d.id = r.decision_id JOIN"
                + " reconciliation.break b ON b.id = r.break_id WHERE b.expectation_id = ? AND"
                + " b.type = 'AMOUNT_MISMATCH'", expectation.id()))
                .as("the shortfall explained by the rematch's own decision")
                .isEqualTo("EVIDENCED/REMATCH");
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", overdue))
                .isEqualTo("RESOLVED");
    }

    @Test
    @Order(5)
    @DisplayName("REC-5, the correction's top-up: a top-up that settles an overdue expectation"
            + " carrying a shortfall closes BOTH breaks EVIDENCED, the overdue MISSING_EXTERNAL"
            + " included")
    void aTopUpClosesEveryRemainderBreak() {
        Source s = source();
        String key = "REC5-TU-" + UUID.randomUUID();
        GateFixtures.Seeded expectation = GateFixtures.openExpectation(s.id(), s.ruleSet(), key,
                100_00, ExpectationDirection.INBOUND, FAR_PAST);
        GateFixtures.reconciliationSweep(GateFixtures.register()).sweep();
        GateFixtures.seedRun(s.id(), s.ruleSet(), capture(1, 70_00, key));
        GateFixtures.matching().sweep();
        assertThat(column("SELECT type || '/' || status FROM reconciliation.break WHERE"
                + " expectation_id = ? ORDER BY type", expectation.id()))
                .as("precondition: the overdue break and the 30.00 shortfall both stand")
                .containsExactly("AMOUNT_MISMATCH/OPEN", "MISSING_EXTERNAL/OPEN");

        UUID correction = GateFixtures.seedRun(s.id(), s.ruleSet(),
                line(1, ExternalLineType.COUNTERPARTY_ADJUSTMENT, 30_00, ItemKeyKind.ORIGINAL_REF,
                        key));
        GateFixtures.matching().sweep();

        assertThat(status(itemOf(correction, 1))).isEqualTo("MATCHED");
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                expectation.id())).isEqualTo("SETTLED");
        assertThat(column("SELECT type || '/' || status FROM reconciliation.break WHERE"
                + " expectation_id = ? ORDER BY type", expectation.id()))
                .as("the settling top-up closes the overdue break too")
                .containsExactly("AMOUNT_MISMATCH/RESOLVED", "MISSING_EXTERNAL/RESOLVED");
    }

    // ----------------------------------------------------------------- ATOM-01

    @Test
    @Order(6)
    @DisplayName("ATOM-01: a correction's top-up in a chunk diminishes the remainder the chunk's"
            + " next line is judged against - the 70.00 sale settles the 100.00 capture and its"
            + " overdue break, no AMOUNT_MISMATCH on the SETTLED expectation, the snapshot saying"
            + " 70.00; a 100.00 sale matches 70.00 and parks 30.00, never ERRORED")
    void aTopUpInTheChunkIsSeenByTheNextLine() {
        Source s = source();
        String key = "ATOM1-" + UUID.randomUUID();
        GateFixtures.Seeded expectation = GateFixtures.openExpectation(s.id(), s.ruleSet(), key,
                100_00, ExpectationDirection.INBOUND, FAR_PAST);
        GateFixtures.reconciliationSweep(GateFixtures.register()).sweep();
        UUID overdue = breakOn(expectation.id(), "MISSING_EXTERNAL");
        UUID run = GateFixtures.seedRun(s.id(), s.ruleSet(),
                line(1, ExternalLineType.COUNTERPARTY_ADJUSTMENT, 30_00, ItemKeyKind.ORIGINAL_REF,
                        key),
                capture(2, 70_00, key));
        GateFixtures.matching().sweep();

        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                expectation.id())).isEqualTo("SETTLED");
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", overdue))
                .as("the sale settled the remainder the top-up left: the overdue break closes")
                .isEqualTo("RESOLVED");
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE expectation_id = ? AND"
                + " type = 'AMOUNT_MISMATCH'", expectation.id()))
                .as("no shortfall raised over a SETTLED expectation").isZero();
        assertThat(count("SELECT c.remainder_before_minor FROM reconciliation.match_candidate c"
                + " JOIN reconciliation.match_decision d ON d.id = c.decision_id WHERE"
                + " d.external_item_id = ?", itemOf(run, 2)))
                .as("the sale's snapshot records the remainder it truly saw").isEqualTo(70_00L);

        // The variant: the sale over the topped-up remainder.
        String overKey = "ATOM1-OVER-" + UUID.randomUUID();
        GateFixtures.Seeded overExpectation = GateFixtures.openExpectation(s.id(), s.ruleSet(),
                overKey, 100_00, ExpectationDirection.INBOUND, FAR_FUTURE);
        UUID overRun = GateFixtures.seedRun(s.id(), s.ruleSet(),
                line(1, ExternalLineType.COUNTERPARTY_ADJUSTMENT, 30_00, ItemKeyKind.ORIGINAL_REF,
                        overKey),
                capture(2, 100_00, overKey));
        GateFixtures.matching().sweep();
        UUID sale = itemOf(overRun, 2);
        assertThat(string("SELECT status || '/' || allocated_minor || '/' || parked_minor FROM"
                + " reconciliation.external_item WHERE id = ?", sale))
                .as("70.00 matched, 30.00 excess parked").isEqualTo("PARKED/7000/3000");
        assertThat(column("SELECT outcome FROM reconciliation.match_decision WHERE"
                + " external_item_id = ?", sale))
                .as("judged, never contained as ERRORED").containsExactly("PARKED");
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                overExpectation.id())).isEqualTo("SETTLED");
    }

    // ----------------------------------------------------------------- ATOM-02

    @Test
    @Order(7)
    @DisplayName("ATOM-02, the run leg: a fault after an allocation rolls the item back to its"
            + " savepoint and the chunk re-reads its remainders - the next line is judged against"
            + " the 100.00 the database holds, never the 40.00 of a rolled-back allocation, so the"
            + " overdue break stays OPEN over the 60.00 still owed, and the claimant rank counts"
            + " only what committed; the rematch then heals the contained line")
    void aRolledBackAllocationIsUndoneInTheChunk() {
        Source s = source();
        String key = "ATOM2-RUN-" + UUID.randomUUID();
        GateFixtures.Seeded expectation = GateFixtures.openExpectation(s.id(), s.ruleSet(), key,
                100_00, ExpectationDirection.INBOUND, FAR_PAST);
        GateFixtures.reconciliationSweep(GateFixtures.register()).sweep();
        UUID overdue = breakOn(expectation.id(), "MISSING_EXTERNAL");
        AtomicBoolean armed = new AtomicBoolean(true);
        GateFixtures.MatchingParts parts = new GateFixtures.MatchingParts();
        // The planted fault: the FIRST shortfall raise on the expectation - after the
        // allocation, its event and the chunk's remainder update, before the item finishes.
        parts.breaks = GateFixtures.intercept(BreakRegister.class, GateFixtures.register(),
                "raise", (args, proceed) -> {
                    BreakRegister.NewBreak raised = (BreakRegister.NewBreak) args[1];
                    if (raised.type() == BreakType.AMOUNT_MISMATCH
                            && raised.subject().expectationId()
                                    .equals(Optional.of(expectation.id()))
                            && armed.getAndSet(false)) {
                        throw new GateFixtures.InjectedFault("after the allocation");
                    }
                    return proceed.call();
                });
        // The rematch leg held off (its reach read refused): the run leg's state is observed
        // alone, before the late leg heals the contained line below.
        parts.store = GateFixtures.intercept(MatchingStore.class, new JdbcMatchingStore(),
                "rematchReach", (args, proceed) -> {
                    throw new IllegalStateException("the rematch leg held off");
                });
        UUID run = GateFixtures.seedRun(s.id(), s.ruleSet(), capture(1, 60_00, key),
                capture(2, 40_00, key));
        parts.build().sweep();

        UUID first = itemOf(run, 1);
        UUID second = itemOf(run, 2);
        assertThat(armed.get()).as("the fault fired").isFalse();
        assertThat(column("SELECT outcome FROM reconciliation.match_decision WHERE"
                + " external_item_id = ?", first))
                .as("the first line contained").containsExactly("ERRORED");
        assertThat(string("SELECT status || '/' || allocated_minor FROM"
                + " reconciliation.expectation WHERE id = ?", expectation.id()))
                .as("only the second line's 40.00 committed").isEqualTo("PARTIALLY_SETTLED/4000");
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", overdue))
                .as("60.00 still owed: the overdue break is never closed by evidence that does"
                        + " not explain it")
                .isEqualTo("OPEN");
        assertThat(count("SELECT c.remainder_before_minor FROM reconciliation.match_candidate c"
                + " JOIN reconciliation.match_decision d ON d.id = c.decision_id WHERE"
                + " d.external_item_id = ?", second))
                .as("judged against the database's remainder").isEqualTo(100_00L);
        assertThat(count("SELECT claimant_rank FROM reconciliation.match_decision WHERE"
                + " external_item_id = ?", second))
                .as("the rolled-back claimant is not counted").isEqualTo(1);

        // The contained line's park is a late reach the rematch leg owes: its value goes home.
        GateFixtures.matching().sweep();
        assertThat(status(first)).isEqualTo("MATCHED");
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                expectation.id())).isEqualTo("SETTLED");
        assertThat(string("SELECT d.origin || '/' || (d.external_item_id = ?) FROM"
                + " reconciliation.resolution r JOIN reconciliation.match_decision d ON d.id ="
                + " r.decision_id WHERE r.break_id = ?", first, overdue))
                .as("the overdue break explained by the line that truly settled it")
                .isEqualTo("REMATCH/true");
    }

    @Test
    @Order(8)
    @DisplayName("ATOM-02, the rematch leg: a fault after a parked rematch queued its unpark rolls"
            + " the residual back and drops the queued unpark with it - the batch's other residual"
            + " commits, and the faulted one waits for the next tick, which unparks it")
    void aRolledBackRematchDropsItsQueuedUnpark() {
        Source s = source();
        String keyOne = "INF-ATOM2-RM1-" + UUID.randomUUID();
        String keyTwo = "INF-ATOM2-RM2-" + UUID.randomUUID();
        UUID run = GateFixtures.seedRun(s.id(), s.ruleSet(), capture(1, 30_00, keyOne),
                capture(2, 40_00, keyTwo));
        GateFixtures.matching().sweep();
        GateFixtures.expireGrace(run);
        GateFixtures.matching().sweep();
        UUID one = itemOf(run, 1);
        UUID two = itemOf(run, 2);
        assertThat(status(one)).isEqualTo("PARKED");
        assertThat(status(two)).isEqualTo("PARKED");

        GateFixtures.Seeded overdueOne = GateFixtures.openExpectation(s.id(), s.ruleSet(), keyOne,
                30_00, ExpectationDirection.INBOUND, FAR_PAST);
        GateFixtures.Seeded later = GateFixtures.openExpectation(s.id(), s.ruleSet(), keyTwo,
                40_00, ExpectationDirection.INBOUND, FAR_FUTURE);
        GateFixtures.reconciliationSweep(GateFixtures.register()).sweep();
        UUID overdue = breakOn(overdueOne.id(), "MISSING_EXTERNAL");

        AtomicBoolean armed = new AtomicBoolean(true);
        Resolutions real = ResolutionFixtures.resolutions(IDS, GateFixtures.CLOCK);
        GateFixtures.MatchingParts parts = new GateFixtures.MatchingParts();
        // The planted fault: the overdue break's evidence - after the unpark was queued.
        parts.resolutions = (unitOfWork, evidence) -> {
            if (armed.get() && evidence.breakId().equals(overdue)) {
                throw new GateFixtures.InjectedFault("after the unpark was queued");
            }
            return real.evidence(unitOfWork, evidence);
        };
        parts.build().sweep();

        assertThat(status(two)).as("the batch's other residual committed").isEqualTo("MATCHED");
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                later.id())).isEqualTo("SETTLED");
        assertThat(status(one)).as("the faulted residual keeps its record").isEqualTo("PARKED");
        assertThat(string("SELECT status FROM reconciliation.suspense_item WHERE"
                + " external_item_id = ?", one))
                .as("its rolled-back unpark never posted").isEqualTo("OPEN");
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " external_item_id = ? AND origin = 'REMATCH'", one))
                .as("nothing of the faulted attempt committed").isZero();
        assertThat(string("SELECT status || '/' || allocated_minor FROM"
                + " reconciliation.expectation WHERE id = ?", overdueOne.id()))
                .isEqualTo("OPEN/0");

        armed.set(false);
        GateFixtures.matching().sweep();
        assertThat(status(one)).as("the next tick re-decides it whole").isEqualTo("MATCHED");
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", overdue))
                .isEqualTo("RESOLVED");
    }

    @Test
    @Order(9)
    @DisplayName("ATOM-02, the reprocess leg: a fault after a re-decided park queued its unpark"
            + " records the item ERRORED for the run and drops the unpark - the run's other item"
            + " commits and the run COMPLETES, never failed nor blocked")
    void aRolledBackReprocessDropsItsQueuedUnpark() {
        Source s = source();
        GateFixtures.Seeded chosenOne = GateFixtures.openExpectation(s.id(), s.ruleSet(),
                "ATOM2-RP1-" + UUID.randomUUID(), 30_00, ExpectationDirection.INBOUND, FAR_PAST);
        GateFixtures.Seeded otherOne = GateFixtures.openExpectation(s.id(), s.ruleSet(),
                "ATOM2-RP1-OT-" + UUID.randomUUID(), 30_00, ExpectationDirection.INBOUND,
                FAR_FUTURE);
        GateFixtures.Seeded chosenTwo = GateFixtures.openExpectation(s.id(), s.ruleSet(),
                "ATOM2-RP2-" + UUID.randomUUID(), 40_00, ExpectationDirection.INBOUND, FAR_FUTURE);
        GateFixtures.Seeded otherTwo = GateFixtures.openExpectation(s.id(), s.ruleSet(),
                "ATOM2-RP2-OT-" + UUID.randomUUID(), 40_00, ExpectationDirection.INBOUND,
                FAR_FUTURE);
        GateFixtures.Parked first =
                GateFixtures.plantAmbiguous(s.id(), s.ruleSet(), chosenOne, otherOne, 30_00);
        GateFixtures.Parked second =
                GateFixtures.plantAmbiguous(s.id(), s.ruleSet(), chosenTwo, otherTwo, 40_00);
        GateFixtures.reconciliationSweep(GateFixtures.register()).sweep();
        UUID overdue = breakOn(chosenOne.id(), "MISSING_EXTERNAL");

        RunAdministration.Reprocessing opened = reprocess(s);
        Resolutions real = ResolutionFixtures.resolutions(IDS, GateFixtures.CLOCK);
        GateFixtures.MatchingParts parts = new GateFixtures.MatchingParts();
        parts.resolutions = (unitOfWork, evidence) -> {
            if (evidence.breakId().equals(overdue)) {
                throw new GateFixtures.InjectedFault("after the unpark was queued");
            }
            return real.evidence(unitOfWork, evidence);
        };
        parts.build().sweep();

        assertThat(string("SELECT status || '/' || failures FROM"
                + " reconciliation.reconciliation_batch WHERE id = ?", opened.runId()))
                .as("the chunk committed: the run completed, no failure counted")
                .isEqualTo("COMPLETED/0");
        assertThat(status(second.itemId())).isEqualTo("MATCHED");
        assertThat(status(first.itemId())).isEqualTo("PARKED");
        assertThat(column("SELECT outcome FROM reconciliation.match_decision WHERE"
                + " external_item_id = ? AND run_id = ?", first.itemId(), opened.runId()))
                .as("the faulted item's decision of the run is its ERRORED record")
                .containsExactly("ERRORED");
        assertThat(string("SELECT status FROM reconciliation.suspense_item WHERE id = ?",
                first.suspenseItemId()))
                .as("its rolled-back unpark never posted").isEqualTo("OPEN");
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?",
                first.breakId())).isEqualTo("OPEN");
    }

    // ----------------------------------------------------------------- REC-6

    @Test
    @Order(10)
    @DisplayName("REC-6: a fee line whose check was contained (ERRORED, its PROCESSING_ERROR"
            + " break CRITICAL and unparked, the line UNMATCHED with no clock) is re-checked by a"
            + " REPROCESS run once the defect is gone - CHECKED, and its break closed EVIDENCED"
            + " naming that check")
    void aContainedFeeLineIsReCheckedByReprocess() {
        Source s = source();
        String captureRef = "REC6-CAP-" + UUID.randomUUID();
        GateFixtures.openExpectation(s.id(), s.ruleSet(), captureRef, 100_00,
                ExpectationDirection.INBOUND, FAR_FUTURE);
        AtomicBoolean poisoned = new AtomicBoolean(true);
        GateFixtures.MatchingParts poisonedParts = new GateFixtures.MatchingParts();
        poisonedParts.store = GateFixtures.intercept(MatchingStore.class, new JdbcMatchingStore(),
                "expectationsByKey", (args, proceed) -> {
                    if (poisoned.get() && captureRef.equals(args[3])) {
                        throw new IllegalStateException("the fee check's planted defect");
                    }
                    return proceed.call();
                });
        UUID run = GateFixtures.seedRun(s.id(), s.ruleSet(),
                line(1, ExternalLineType.PROCESSING_FEE, 1_75, ItemKeyKind.ORIGINAL_REF,
                        captureRef));
        poisonedParts.build().sweep();
        UUID fee = itemOf(run, 1);
        assertThat(status(fee)).as("precondition: contained, waiting").isEqualTo("UNMATCHED");
        UUID contained = (UUID) GateFixtures.one("SELECT id FROM reconciliation.break WHERE"
                + " external_item_id = ? AND type = 'PROCESSING_ERROR' AND cause = 'ITEM_ERRORED'"
                + " AND status = 'OPEN'", fee);
        assertThat(contained).isNotNull();

        poisoned.set(false);
        RunAdministration.Reprocessing opened = reprocess(s);
        sweepUntilCompleted(GateFixtures.matching(), opened.runId());

        assertThat(status(fee)).as("re-checked against its pinned schedule").isEqualTo("CHECKED");
        UUID checked = (UUID) GateFixtures.one("SELECT id FROM reconciliation.match_decision"
                + " WHERE external_item_id = ? AND run_id = ? AND origin = 'REPROCESS' AND"
                + " outcome = 'CHECKED'", fee, opened.runId());
        assertThat(checked).as("one CHECKED decision of the reprocess run").isNotNull();
        assertThat(string("SELECT fee_expected_minor || '/' || judged_status FROM"
                + " reconciliation.match_decision WHERE id = ?", checked))
                .as("priced on the original's gross, the item judged as it waited")
                .isEqualTo("175/UNMATCHED");
        assertThat(string("SELECT b.status || '/' || r.kind || '/' || r.decision_id FROM"
                + " reconciliation.break b JOIN reconciliation.resolution r ON r.break_id = b.id"
                + " WHERE b.id = ?", contained))
                .as("the contained break's exit: EVIDENCED by the check")
                .isEqualTo("RESOLVED/EVIDENCED/" + checked);
        RunReplays.Replay replay = GateFixtures.replays().replay(opened.runId(),
                new Actor("op-transition-investigator", ActorType.EMPLOYEE),
                CorrelationId.generate(IDS));
        assertThat(replay.verdict()).as("the re-check replays from its stored basis")
                .isEqualTo("IDENTICAL");
    }

    // ----------------------------------------------------------------- the clocks

    @Test
    @Order(11)
    @DisplayName("rematch-keyed-valuedate-clocks: an expectation stamped BEFORE the grace leg"
            + " parked its line but committed AFTER - its transaction held open across the"
            + " parking - is reached on rows by the rematch leg: the line MATCHED, its break"
            + " EVIDENCED, the expectation SETTLED")
    void aReachStampedBeforeItsCommitIsStillRematched() throws SQLException {
        Source s = source();
        String key = "CLK-" + UUID.randomUUID();
        UUID run = GateFixtures.seedRun(s.id(), s.ruleSet(), capture(1, 25_00, key));
        GateFixtures.matching().sweep();
        UUID item = itemOf(run, 1);
        UUID expectation;
        try (Connection holder = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            // Stamped a month before the matcher's own clock - the opener's instant taken inside
            // its transaction, long before that transaction commits.
            expectation = open(holder, s, key, 25_00, FAR_FUTURE, UUID.randomUUID().toString(),
                    GateFixtures.CLOCK.instant().minus(Duration.ofDays(31)));
            GateFixtures.expireGrace(run);
            GateFixtures.matching().sweep();
            assertThat(status(item))
                    .as("precondition: the grace leg could not see the uncommitted expectation")
                    .isEqualTo("PARKED");
            holder.commit();
        }
        assertThat(GateFixtures.one("SELECT e.opened_at < (SELECT max(d.decided_at) FROM"
                        + " reconciliation.match_decision d WHERE d.external_item_id = ?) FROM"
                        + " reconciliation.expectation e WHERE e.id = ?", item, expectation))
                .as("precondition: the expectation reads opened BEFORE the line's decision")
                .isEqualTo(true);
        GateFixtures.matching().sweep();

        assertThat(status(item)).as("reached on rows, never on the two stamps").isEqualTo(
                "MATCHED");
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                expectation)).isEqualTo("SETTLED");
        assertThat(string("SELECT b.status || '/' || r.kind FROM reconciliation.break b JOIN"
                + " reconciliation.resolution r ON r.break_id = b.id WHERE"
                + " b.external_item_id = ?", item)).isEqualTo("RESOLVED/EVIDENCED");
    }

    // ----------------------------------------------------------------- reselection

    @Test
    @Order(12)
    @DisplayName("rematch-key-clause-reselection: residents the leg can never act on (each"
            + " parked for 100.00, its late expectation 99.00) fill whole batches ahead of a"
            + " genuine late-evidence park - each is examined ONCE, its reach consumed, and the"
            + " same sweep reaches and unparks the genuine one; the next sweep writes nothing")
    void residentsThatCannotActNeverStarveTheLeg() {
        Source s = source();
        List<String> residentKeys = new ArrayList<>();
        List<GateFixtures.Line> lines = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            String key = "RSD-" + i + "-" + UUID.randomUUID();
            residentKeys.add(key);
            lines.add(capture(i, 100_00, key));
        }
        String genuineKey = "RSD-G-" + UUID.randomUUID();
        lines.add(capture(5, 50_00, genuineKey));
        UUID run = GateFixtures.seedRun(s.id(), s.ruleSet(), lines.toArray(GateFixtures.Line[]::new));
        GateFixtures.matching().sweep();
        GateFixtures.expireGrace(run);
        GateFixtures.matching().sweep();
        for (String key : residentKeys) {
            GateFixtures.openExpectation(s.id(), s.ruleSet(), key, 99_00,
                    ExpectationDirection.INBOUND, FAR_FUTURE);
        }
        GateFixtures.openExpectation(s.id(), s.ruleSet(), genuineKey, 50_00,
                ExpectationDirection.INBOUND, FAR_FUTURE);

        // Three-line batches: the residents fill the first whole batch.
        GateFixtures.MatchingParts parts = new GateFixtures.MatchingParts();
        parts.config = new Matching.Config(3, 2);
        Matching.SweepResult swept = parts.build().sweep();

        UUID genuine = itemOf(run, 5);
        assertThat(status(genuine)).as("reached in the same sweep").isEqualTo("MATCHED");
        assertThat(string("SELECT status FROM reconciliation.break WHERE external_item_id = ?",
                genuine)).isEqualTo("RESOLVED");
        assertThat(swept.rematched()).as("the one allocation, the residents' examinations not")
                .isEqualTo(1);
        for (int i = 1; i <= 4; i++) {
            UUID resident = itemOf(run, i);
            assertThat(status(resident)).isEqualTo("PARKED");
            assertThat(column("SELECT outcome FROM reconciliation.match_decision WHERE"
                    + " external_item_id = ? AND origin = 'REMATCH'", resident))
                    .as("resident %d examined once, its reach consumed", i)
                    .containsExactly("PARKED");
        }
        assertThat(rematchWorklist(s.id())).as("nothing left that could ever act").isEmpty();
        long decisions = count("SELECT count(*) FROM reconciliation.match_decision d JOIN"
                + " reconciliation.external_item i ON i.id = d.external_item_id WHERE"
                + " i.run_id = ?", run);
        parts.build().sweep();
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision d JOIN"
                + " reconciliation.external_item i ON i.id = d.external_item_id WHERE"
                + " i.run_id = ?", run)).as("no churn on the next sweep").isEqualTo(decisions);
    }

    // ----------------------------------------------------------------- claimant order

    @Test
    @Order(13)
    @DisplayName("claimant-order-by-leg, the rematch leg: an expectation opening late is claimed"
            + " by the EARLIER report's line (lower source_sequence, higher line_no, higher row id)"
            + " - the later report's line waits")
    void theRematchLegServesClaimantOrder() {
        Source s = source();
        String key = "CLM-RM-" + UUID.randomUUID();
        Claimants claimants = claimants(s, key);
        GateFixtures.matching().sweep();
        assertThat(status(claimants.earlier())).isEqualTo("UNMATCHED");
        assertThat(status(claimants.later())).isEqualTo("UNMATCHED");

        GateFixtures.Seeded late = GateFixtures.openExpectation(s.id(), s.ruleSet(), key, 50_00,
                ExpectationDirection.INBOUND, FAR_FUTURE);
        GateFixtures.matching().sweep();

        assertThat(column("SELECT external_item_id::text FROM reconciliation.allocation WHERE"
                + " expectation_id = ?", late.id()))
                .as("the earlier report's line claims first").containsExactly(
                        claimants.earlier().toString());
        assertThat(status(claimants.later())).isEqualTo("UNMATCHED");
    }

    @Test
    @Order(14)
    @DisplayName("claimant-order-by-leg, the grace leg: within the expired set the earlier"
            + " report's line claims a candidate committed meanwhile, the later one finds it"
            + " exhausted - whatever order their windows expired in")
    void theGraceLegServesClaimantOrder() {
        Source s = source();
        String key = "CLM-GR-" + UUID.randomUUID();
        Claimants claimants = claimants(s, key);
        GateFixtures.matching().sweep();
        GateFixtures.Seeded late = GateFixtures.openExpectation(s.id(), s.ruleSet(), key, 50_00,
                ExpectationDirection.INBOUND, FAR_FUTURE);
        // The later report's window expires FIRST.
        GateFixtures.expireGrace(claimants.laterRun());
        GateFixtures.expireGrace(claimants.earlierRun());
        GateFixtures.matching().sweep();

        assertThat(column("SELECT external_item_id::text FROM reconciliation.allocation WHERE"
                + " expectation_id = ?", late.id()))
                .as("the earlier report's line claims first").containsExactly(
                        claimants.earlier().toString());
        assertThat(breakFacts(claimants.later()))
                .containsExactly("DUPLICATE_EXTERNAL/EXPECTATION_EXHAUSTED/OPEN");
    }

    // ----------------------------------------------------------------- ARCH-P8-01

    @Test
    @Order(15)
    @DisplayName("ARCH-P8-01: an expectation whose operation reference is the real opener's"
            + " 'rail:schemeReference' shape settles and ages - its SettlementExpectationSettled"
            + " and SettlementExpectationOverdue events at version 2, carrying no operation"
            + " reference, and no ERRORED decision")
    void anUnmatchedConfirmationShapedReferenceSettlesAndAges() throws SQLException {
        Source s = source();
        String settledKey = "SCH-SET-" + UUID.randomUUID();
        UUID settled;
        UUID overdue;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            settled = open(app, s, settledKey, 12_00, FAR_FUTURE,
                    "instant:" + settledKey, Instant.now());
            overdue = open(app, s, "SCH-OVD-" + UUID.randomUUID(), 13_00, FAR_PAST,
                    "instant:SCH-OVD-" + UUID.randomUUID(), Instant.now());
            app.commit();
        }
        UUID run = GateFixtures.seedRun(s.id(), s.ruleSet(), capture(1, 12_00, settledKey));
        GateFixtures.matching().sweep();
        GateFixtures.reconciliationSweep(GateFixtures.register()).sweep();

        assertThat(column("SELECT outcome FROM reconciliation.match_decision WHERE"
                + " external_item_id = ?", itemOf(run, 1)))
                .as("matched, never contained").containsExactly("MATCHED");
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?", settled))
                .isEqualTo("SETTLED");
        assertThat(column("SELECT event_version || '/' || (convert_from(payload, 'UTF8') LIKE"
                + " '%operationRef%') FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.SettlementExpectationSettled' AND aggregate_id = ?", settled))
                .as("one settled event, version 2, no operation reference")
                .containsExactly("2/false");
        assertThat(GateFixtures.one("SELECT overdue_since FROM reconciliation.expectation WHERE"
                + " id = ?", overdue)).as("aged").isNotNull();
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE expectation_id = ? AND"
                + " type = 'MISSING_EXTERNAL'", overdue)).isEqualTo(1);
        assertThat(column("SELECT event_version || '/' || (convert_from(payload, 'UTF8') LIKE"
                + " '%operationRef%') FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.SettlementExpectationOverdue' AND aggregate_id = ?", overdue))
                .as("one overdue event, version 2, no operation reference")
                .containsExactly("2/false");
    }

    @Test
    @Order(16)
    @DisplayName("ARCH-P8-01, containment: a page of ageing rows failing on every attempt never"
            + " holds the leg - the cursor carries the sweep past them, the expectation behind"
            + " them is aged, the failed rows raise nothing and are tried again next sweep")
    void failingAgeingRowsNeverHoldTheLeg() throws SQLException {
        Source s = source();
        Set<UUID> failing = new java.util.HashSet<>();
        UUID behind;
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            // The oldest promises anywhere: the head of every ageing page.
            for (int day = 1; day <= 3; day++) {
                failing.add(open(app, s, "AGE-FAIL-" + day + "-" + UUID.randomUUID(), 10_00,
                        LocalDate.of(2000, 1, day), UUID.randomUUID().toString(), Instant.now()));
            }
            behind = open(app, s, "AGE-BEHIND-" + UUID.randomUUID(), 10_00,
                    LocalDate.of(2000, 1, 4), UUID.randomUUID().toString(), Instant.now());
            app.commit();
        }
        AtomicBoolean[] attempts = {new AtomicBoolean(false)};
        BreakRegister failingRegister = GateFixtures.intercept(BreakRegister.class,
                GateFixtures.register(), "raise", (args, proceed) -> {
                    BreakRegister.NewBreak raised = (BreakRegister.NewBreak) args[1];
                    if (raised.subject().expectationId().filter(failing::contains).isPresent()) {
                        attempts[0].set(true);
                        throw new GateFixtures.InjectedFault("an ageing row that always fails");
                    }
                    return proceed.call();
                });
        // Pages of two: the failing rows fill the first page whole.
        ageingSweep(failingRegister, 2).sweep();

        assertThat(attempts[0].get()).as("the failing rows were tried").isTrue();
        assertThat(GateFixtures.one("SELECT overdue_since FROM reconciliation.expectation WHERE"
                + " id = ?", behind)).as("the row behind them aged").isNotNull();
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE expectation_id = ?"
                + " AND type = 'MISSING_EXTERNAL'", behind)).isEqualTo(1);
        for (UUID row : failing) {
            assertThat(GateFixtures.one("SELECT overdue_since FROM reconciliation.expectation"
                    + " WHERE id = ?", row)).as("a failed row rolled back whole").isNull();
            assertThat(count("SELECT count(*) FROM reconciliation.break WHERE"
                    + " expectation_id = ?", row)).isZero();
        }
        // The fault cleared, the next sweep tries them again.
        ageingSweep(GateFixtures.register(), 2).sweep();
        for (UUID row : failing) {
            assertThat(GateFixtures.one("SELECT overdue_since FROM reconciliation.expectation"
                    + " WHERE id = ?", row)).isNotNull();
        }
    }

    // ----------------------------------------------------------------- MI-1

    @Test
    @Order(17)
    @DisplayName("MI-1: a page of older breaks NOT yet due never starves a newer break whose band"
            + " is due - the escalation selects only due rows, and the due one steps once")
    void olderBreaksNotYetDueNeverStarveADueOne() {
        Source s = source();
        // Every older break stepped for every band it crossed: not due.
        List<UUID> notDue = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            GateFixtures.Seeded subject = GateFixtures.openExpectation(s.id(), s.ruleSet(),
                    "ESC-ND-" + i + "-" + UUID.randomUUID(), 1_00, ExpectationDirection.INBOUND,
                    FAR_FUTURE);
            notDue.add(raiseOverdue(subject.id(), s, Instant.now().minus(Duration.ofDays(6))));
        }
        GateFixtures.reconciliationSweep(GateFixtures.register()).sweep();
        for (UUID older : notDue) {
            assertThat(count("SELECT count(*) FROM reconciliation.break_event WHERE break_id = ?"
                    + " AND event_type = 'SEVERITY_ESCALATED'", older))
                    .as("precondition: each older break owes nothing").isEqualTo(1);
            assertThat(string("SELECT severity FROM reconciliation.break WHERE id = ?", older))
                    .as("precondition: still below CRITICAL, so on the old page").isNotEqualTo(
                            "CRITICAL");
        }
        GateFixtures.Seeded newer = GateFixtures.openExpectation(s.id(), s.ruleSet(),
                "ESC-DUE-" + UUID.randomUUID(), 1_00, ExpectationDirection.INBOUND, FAR_FUTURE);
        UUID due = raiseOverdue(newer.id(), s, Instant.now().minus(Duration.ofDays(4)));
        String born = string("SELECT severity FROM reconciliation.break WHERE id = ?", due);

        // Pages of two: the older, not-due breaks would fill the page whole.
        ageingSweep(GateFixtures.register(), 2).sweep();

        assertThat(count("SELECT count(*) FROM reconciliation.break_event WHERE break_id = ? AND"
                + " event_type = 'SEVERITY_ESCALATED'", due))
                .as("the due break stepped once, past the page of older ones").isEqualTo(1);
        assertThat(string("SELECT severity FROM reconciliation.break WHERE id = ?", due))
                .isEqualTo(Severity.values()[Severity.valueOf(born).ordinal() + 1].name());
    }

    // ----------------------------------------------------------------- ATOM-04

    @Test
    @Order(18)
    @DisplayName("ATOM-04: a correction in the chunk that queued its original's excess park"
            + " OFFSETS it exactly as across two runs - the park posted, then released"
            + " CORRECTION_OFFSET, the excess break RESOLVED EVIDENCED naming the correction,"
            + " the original RESOLVED; a second correction naming the same park waits; the"
            + " run replays IDENTICAL")
    void aCorrectionOffsetsTheParkItsOwnChunkQueued() {
        Source s = source();
        String key = "ATOM4-" + UUID.randomUUID();
        GateFixtures.Seeded capture = GateFixtures.openExpectation(s.id(), s.ruleSet(), key,
                100_00, ExpectationDirection.INBOUND, FAR_FUTURE);
        // Line 1 pays 120.00 against the 100.00 capture: 20.00 excess, its park QUEUED for the
        // chunk's posting phase. Line 3 claws the 20.00 back by its original's reference, in the
        // SAME chunk; line 4 claws it back a second time.
        UUID run = GateFixtures.seedRun(s.id(), s.ruleSet(),
                capture(1, 120_00, key),
                capture(2, 5_00, "ATOM4-OTHER-" + UUID.randomUUID()),
                clawBack(3, 20_00, key),
                clawBack(4, 20_00, key));
        GateFixtures.matching().sweep();

        UUID original = itemOf(run, 1);
        UUID correction = itemOf(run, 3);
        UUID again = itemOf(run, 4);
        assertThat(string("SELECT status FROM reconciliation.reconciliation_batch WHERE id = ?",
                run)).isEqualTo("COMPLETED");
        assertThat(string("SELECT verdict || '/' || outcome FROM reconciliation.match_decision"
                + " WHERE external_item_id = ?", correction))
                .as("the correction offsets the queued park, never UNREACHED")
                .isEqualTo("OFFSET/OFFSET");
        assertThat(status(correction)).isEqualTo("OFFSET");
        assertThat(status(original)).as("its excess explained").isEqualTo("RESOLVED");
        UUID excessBreak = (UUID) GateFixtures.one("SELECT id FROM reconciliation.break WHERE"
                + " external_item_id = ? AND type = 'AMOUNT_MISMATCH'", original);
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", excessBreak))
                .isEqualTo("RESOLVED");
        assertThat(string("SELECT r.kind || '/' || (r.decision_id = d.id) FROM"
                + " reconciliation.resolution r, reconciliation.match_decision d WHERE"
                + " r.break_id = ? AND d.external_item_id = ?", excessBreak, correction))
                .as("resolved EVIDENCED by the correction's own decision, no person's offset")
                .isEqualTo("EVIDENCED/true");
        assertThat(string("SELECT s.status || '/' || s.amount_minor || '/' || r.cause || '/'"
                + " || r.amount_minor FROM reconciliation.suspense_item s JOIN"
                + " reconciliation.suspense_release r ON r.item_id = s.id WHERE"
                + " s.external_item_id = ?", original))
                .as("the excess parked, then released whole by the correction")
                .isEqualTo("RELEASED/2000/CORRECTION_OFFSET/2000");
        assertThat(column("SELECT p.kind FROM reconciliation.park p WHERE p.source_id = ?"
                + " ORDER BY p.kind", s.id()))
                .as("one park entry and its exact inverse, as across two runs")
                .containsExactly("PARK", "UNPARK");
        assertThat(string("SELECT o.original_item_id::text || '/' || o.remainder_minor FROM"
                + " reconciliation.match_parked_original o JOIN reconciliation.match_decision d"
                + " ON d.id = o.decision_id JOIN reconciliation.suspense_item s ON s.id ="
                + " o.suspense_item_id WHERE d.external_item_id = ?", correction))
                .as("the snapshot names the park the correction judged")
                .isEqualTo(original + "/2000");
        assertThat(string("SELECT verdict || '/' || outcome FROM reconciliation.match_decision"
                + " WHERE external_item_id = ?", again))
                .as("the park is claimed once: the second claw-back reaches nothing and waits")
                .isEqualTo("CORRECTION_UNREACHED/UNMATCHED");
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item WHERE"
                + " external_item_id = ?", correction))
                .as("the correction parks nothing of its own").isZero();
        assertThat(GateFixtures.replays()
                .replay(run, CONTROLLER, CorrelationId.generate(IDS)).verdict())
                .as("every decision, the offset's included, replays from its own snapshot")
                .isEqualTo("IDENTICAL");
        assertThat(string("SELECT status || '/' || allocated_minor FROM"
                + " reconciliation.expectation WHERE id = ?", capture.id()))
                .isEqualTo("SETTLED/10000");
    }

    // ----------------------------------------------------------------- IDEM-3

    @Test
    @Order(19)
    @DisplayName("IDEM-3: a conditional exit that finds its row moved throws - the allocation"
            + " rolls back with its item, which is contained ERRORED and parked whole; never an"
            + " allocation committed beside an item that never left PENDING")
    void aLostConditionalExitRollsTheItemBack() {
        Source s = source();
        String key = "IDEM3-" + UUID.randomUUID();
        GateFixtures.Seeded capture = GateFixtures.openExpectation(s.id(), s.ruleSet(), key,
                40_00, ExpectationDirection.INBOUND, FAR_FUTURE);
        GateFixtures.MatchingParts parts = new GateFixtures.MatchingParts();
        // The planted loss: the exit's conditional UPDATE finds its row elsewhere (returns false
        // without writing) - what a writer that skipped the lock would meet.
        parts.store = GateFixtures.intercept(MatchingStore.class, new JdbcMatchingStore(),
                "markItemMatchedFrom", (args, proceed) -> false);
        UUID run = GateFixtures.seedRun(s.id(), s.ruleSet(), capture(1, 40_00, key),
                capture(2, 6_00, "IDEM3-OTHER-" + UUID.randomUUID()));
        parts.build().sweep();

        UUID lost = itemOf(run, 1);
        assertThat(string("SELECT status || '/' || failures FROM"
                + " reconciliation.reconciliation_batch WHERE id = ?", run))
                .as("the chunk committed: the loss was the item's, contained")
                .isEqualTo("COMPLETED/0");
        assertThat(column("SELECT outcome FROM reconciliation.match_decision WHERE"
                + " external_item_id = ?", lost))
                .as("the item's attempt rolled back and was contained").containsExactly("ERRORED");
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                + " external_item_id = ?", lost))
                .as("no allocation beside an item that never left PENDING").isZero();
        assertThat(string("SELECT status || '/' || allocated_minor FROM"
                + " reconciliation.expectation WHERE id = ?", capture.id()))
                .isEqualTo("OPEN/0");
        assertThat(string("SELECT status || '/' || parked_minor FROM"
                + " reconciliation.external_item WHERE id = ?", lost))
                .as("its value owned by its PROCESSING_ERROR break").isEqualTo("PARKED/4000");
        assertThat(breakFacts(lost)).containsExactly("PROCESSING_ERROR/ITEM_ERRORED/OPEN");
    }

    // ------------------------------------------------- the fee check's currency pre-filter

    @Test
    @Order(20)
    @DisplayName("the FeeCheck currency pre-filter (the Phase 8 -> 9 transition): an EUR fee"
            + " line whose ORIGINAL_REF names a GBP capture is CHECKED against no gross -"
            + " expected zero, the whole fee at issue as FEE_MISMATCH - never handed to"
            + " FeeCheck's cross-currency arithmetic, never contained ITEM_ERRORED, and the"
            + " replay is IDENTICAL")
    void aFeeLineWhoseOriginalIsInAnotherCurrencyIsCheckedNotErrored() {
        Source s = source();
        String captureRef = "XCCY-CAP-" + UUID.randomUUID();
        GateFixtures.openExpectation(s.id(), s.ruleSet(), captureRef, 100_00,
                com.finapp.sharedkernel.money.CurrencyCode.of("GBP"),
                ExpectationDirection.INBOUND, FAR_FUTURE);
        UUID run = GateFixtures.seedRun(s.id(), s.ruleSet(),
                line(1, ExternalLineType.PROCESSING_FEE, 1_75, ItemKeyKind.ORIGINAL_REF,
                        captureRef));
        GateFixtures.matching().sweep();

        UUID fee = itemOf(run, 1);
        assertThat(string("SELECT status || '/' || failures FROM"
                + " reconciliation.reconciliation_batch WHERE id = ?", run))
                .as("the chunk committed: the line was judged, never contained")
                .isEqualTo("COMPLETED/0");
        assertThat(status(fee)).as("judged in the fee vocabulary").isEqualTo("CHECKED");
        assertThat(string("SELECT outcome || '/' || fee_expected_minor FROM"
                + " reconciliation.match_decision WHERE external_item_id = ?", fee))
                .as("a gross the fee's own currency cannot price prices nothing (F1)")
                .isEqualTo("CHECKED/0");
        assertThat(breakFacts(fee))
                .as("the whole reported fee at issue, in the type ACKNOWLEDGE closes - never"
                        + " CURRENCY_MISMATCH, whose kinds all dispose of parked value a fee"
                        + " line does not hold")
                .containsExactly("FEE_MISMATCH/FEE_BEYOND_TOLERANCE/OPEN");
        RunReplays.Replay replay = GateFixtures.replays().replay(run,
                new Actor("op-transition-investigator", ActorType.EMPLOYEE),
                CorrelationId.generate(IDS));
        assertThat(replay.verdict()).as("the stored basis replays without the gross")
                .isEqualTo("IDENTICAL");
    }

    // ----------------------------------------------------------------- seeding

    private record Source(UUID id, UUID ruleSet) {}

    /**
     * A private source whose rule set v1 carries the capture and refund one-to-one rules, the
     * processing fee's CHECK and the counterparty adjustment's CORRECTION - with the date window,
     * the fee bounds and the PSP's published fee terms.
     */
    private static Source source() {
        UUID source = IDS.next();
        UUID ruleSet = IDS.next();
        GateFixtures.inCommittedTransaction(app -> {
            execute(app,
                    "INSERT INTO reconciliation.rule_set (id, source_id, version, status,"
                            + " funding_lag_days, gain_min_age_days, effective_from,"
                            + " proposed_by, decided_by, reason, created_at, correlation_id)"
                            + " VALUES (?, ?, 1, 'PROPOSED', 2, 90, ?, 'test', NULL,"
                            + " 'the Phase 8 -> 9 transition late-leg suite private rule set',"
                            + " now(), 'p8-p9-transition-test')",
                    ruleSet, source, java.sql.Date.valueOf(SETTLED_ON));
            execute(app,
                    "INSERT INTO reconciliation.rule (rule_set_id, priority, line_type,"
                            + " key_kind, expectation_kind, cardinality, operation_anchored,"
                            + " grace_hours) VALUES"
                            + " (?, 1, 'CAPTURE', 'PSP_CAPTURE_REF', 'CARD_CAPTURE',"
                            + " 'ONE_TO_ONE', false, 48),"
                            + " (?, 2, 'REFUND', 'PSP_REFUND_REF', 'CARD_REFUND',"
                            + " 'ONE_TO_ONE', false, 48),"
                            + " (?, 8, 'PROCESSING_FEE', 'ORIGINAL_REF', NULL, 'CHECK', false,"
                            + " 48),"
                            + " (?, 9, 'COUNTERPARTY_ADJUSTMENT', 'ORIGINAL_REF', NULL,"
                            + " 'CORRECTION', false, 48)",
                    ruleSet, ruleSet, ruleSet, ruleSet);
            execute(app,
                    "INSERT INTO reconciliation.tolerance (rule_set_id, comparison, currency,"
                            + " absolute_minor, days) VALUES"
                            + " (?, 'SETTLEMENT_DATE_DAYS', NULL, NULL, 2),"
                            + " (?, 'PROCESSING_FEE_PER_LINE', 'EUR', 2, NULL),"
                            + " (?, 'PROCESSING_FEE_PER_BATCH', 'EUR', 50, NULL)",
                    ruleSet, ruleSet, ruleSet);
            execute(app,
                    "INSERT INTO reconciliation.provider_fee_schedule (rule_set_id, line_type,"
                            + " currency, rate, fixed_minor, scale, rounding_policy) VALUES"
                            + " (?, 'PROCESSING_FEE', 'EUR', 0.015000, 25, 2, 'HALF_UP')",
                    ruleSet);
            execute(app,
                    "INSERT INTO reconciliation.severity_threshold (rule_set_id, currency,"
                            + " high_value_minor) VALUES (?, 'EUR', 100000)",
                    ruleSet);
            execute(app,
                    "UPDATE reconciliation.rule_set SET status = 'ACTIVE',"
                            + " decided_by = 'test-activator', decided_at = now()"
                            + " WHERE id = ? AND status = 'PROPOSED'",
                    ruleSet);
            return null;
        });
        return new Source(source, ruleSet);
    }

    private static GateFixtures.Line capture(int lineNo, long minor, String key) {
        return line(lineNo, ExternalLineType.CAPTURE, minor, ItemKeyKind.PSP_CAPTURE_REF, key);
    }

    /** A counterparty's claw-back: an OUTBOUND adjustment naming its original's capture. */
    private static GateFixtures.Line clawBack(int lineNo, long minor, String originalKey) {
        return new GateFixtures.Line(lineNo, ExternalLineType.COUNTERPARTY_ADJUSTMENT,
                ExpectationDirection.OUTBOUND, minor, EUR, ItemKeyKind.ORIGINAL_REF, originalKey,
                GateFixtures.fingerprint(), SETTLED_ON);
    }

    private static GateFixtures.Line line(
            int lineNo, ExternalLineType type, long minor, ItemKeyKind keyKind, String key) {
        return line(lineNo, type, minor, keyKind, key, GateFixtures.fingerprint());
    }

    private static GateFixtures.Line line(
            int lineNo, ExternalLineType type, long minor, ItemKeyKind keyKind, String key,
            byte[] fingerprint) {
        ExpectationDirection direction =
                type == ExternalLineType.REFUND || type == ExternalLineType.OTHER_OUT
                        ? ExpectationDirection.OUTBOUND
                        : ExpectationDirection.INBOUND;
        return new GateFixtures.Line(lineNo, type, direction, minor, EUR, keyKind, key,
                fingerprint, SETTLED_ON);
    }

    /** Two waiting claimants of one key: the earlier report's at line 40, the later's at line 3. */
    private record Claimants(UUID earlierRun, UUID earlier, UUID laterRun, UUID later) {}

    private static Claimants claimants(Source s, String key) {
        // The later report's line gets the LOWER row id and the lower line_no: only the
        // source_sequence can order them right.
        List<UUID> ids = new ArrayList<>(List.of(IDS.next(), IDS.next()));
        ids.sort(null);
        long earlierSequence = GateFixtures.nextSequence();
        long laterSequence = GateFixtures.nextSequence();
        UUID earlierRun = GateFixtures.seedRun(s.id(), s.ruleSet(), earlierSequence,
                List.of(ids.get(1)), List.of(0), capture(40, 50_00, key));
        UUID laterRun = GateFixtures.seedRun(s.id(), s.ruleSet(), laterSequence,
                List.of(ids.get(0)), List.of(0), capture(3, 50_00, key));
        return new Claimants(earlierRun, ids.get(1), laterRun, ids.get(0));
    }

    /**
     * A capture expectation under {@code s}, keyed by {@code key}, opened on {@code unitOfWork}
     * (committed by the caller) with the operation reference and the opener's instant given.
     */
    private static UUID open(
            Connection unitOfWork, Source s, String key, long minor, LocalDate expectedBy,
            String operationRef, Instant openedAt) throws SQLException {
        UUID position =
                new JdbcLedgerAccountStore()
                        .findOperational(unitOfWork, AccountPurpose.SETTLEMENT_CLEARING, EUR)
                        .orElseThrow()
                        .id()
                        .value();
        new JdbcExpectationRegister(IDS).open(
                unitOfWork,
                new NewExpectation(
                        ExpectationKind.CARD_CAPTURE, operationRef, "p8p9:" + operationRef,
                        s.id(), AccountPurpose.SETTLEMENT_CLEARING, position,
                        ExpectationDirection.INBOUND, Money.ofPersisted(minor, EUR, 2),
                        Optional.of(IDS.next()), SETTLED_ON, Optional.empty(), expectedBy,
                        s.ruleSet(),
                        List.of(new NewExpectation.ExpectationKey(KeyKind.PSP_CAPTURE_REF, key)),
                        PLATFORM, openedAt, CorrelationId.generate(IDS)));
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT id FROM reconciliation.expectation WHERE kind = 'CARD_CAPTURE'"
                                + " AND operation_ref = ?")) {
            read.setString(1, operationRef);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getObject("id", UUID.class);
            }
        }
    }

    /** Ageing's MISSING_EXTERNAL on a capture expectation, raised at {@code raisedAt}. */
    private static UUID raiseOverdue(UUID expectationId, Source s, Instant raisedAt) {
        UUID breakId = IDS.next();
        GateFixtures.inCommittedTransaction(app -> {
            GateFixtures.register().raise(app, new BreakRegister.NewBreak(
                    breakId, BreakType.MISSING_EXTERNAL, BreakCause.EXPECTATION_OVERDUE,
                    BreakRegister.Subject.expectation(expectationId), s.id(), s.ruleSet(),
                    Money.ofPersisted(1_00, EUR, 2),
                    Optional.of(ExpectationDirection.INBOUND),
                    Optional.of(ExpectationKind.CARD_CAPTURE), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), PLATFORM, raisedAt,
                    CorrelationId.generate(IDS)));
            return null;
        });
        return breakId;
    }

    /** Time's observers on the real clock, paging by {@code batch}, over {@code breaks}. */
    private static ReconciliationSweep ageingSweep(BreakRegister breaks, int batch) {
        return new ReconciliationSweep(
                new JdbcMatchingStore(), breaks,
                new KeyCollisionBreaks(GateFixtures.register(), new JdbcRuleSets(), IDS),
                new JdbcOutboxWriter(), IDS, Clock.systemUTC(),
                new ReconciliationSweep.Config(batch, 3), GateFixtures.runner());
    }

    private static RunAdministration.Reprocessing reprocess(Source s) {
        return GateFixtures.as(CONTROLLER, uow -> new RunAdministration(new JdbcMatchingStore(),
                        new JdbcReconciliationRuns(), new JdbcAuditWriter(), IDS)
                .requestReprocessing(uow, s.id(), CONTROLLER, "the transition's re-decision",
                        Instant.now(GateFixtures.CLOCK), CorrelationId.generate(IDS)));
    }

    private static void sweepUntilCompleted(Matching matching, UUID runId) {
        for (int tick = 0; tick < 5 && !"COMPLETED".equals(string("SELECT status FROM"
                + " reconciliation.reconciliation_batch WHERE id = ?", runId)); tick++) {
            matching.sweep();
        }
        assertThat(string("SELECT status FROM reconciliation.reconciliation_batch WHERE id = ?",
                runId)).isEqualTo("COMPLETED");
    }

    /** The rematch leg's worklist for one source, read as the leg reads it, rolled back. */
    private static List<UUID> rematchWorklist(UUID source) {
        return GateFixtures.inCommittedTransaction(app -> {
            List<UUID> worklist = new ArrayList<>();
            new JdbcMatchingStore()
                    .lockRematchCandidates(app, source, List.of(), 200)
                    .forEach(residual -> worklist.add(residual.item().id()));
            app.rollback();
            return worklist;
        });
    }

    private static String status(UUID itemId) {
        return string("SELECT status FROM reconciliation.external_item WHERE id = ?", itemId);
    }

    private static List<String> breakFacts(UUID itemId) {
        return column("SELECT type || '/' || cause || '/' || status FROM reconciliation.break"
                + " WHERE external_item_id = ? ORDER BY raised_at", itemId);
    }

    private static UUID breakOn(UUID expectationId, String type) {
        UUID found = (UUID) GateFixtures.one("SELECT id FROM reconciliation.break WHERE"
                + " expectation_id = ? AND type = ? AND status <> 'RESOLVED'", expectationId, type);
        assertThat(found).as("an open %s on the expectation", type).isNotNull();
        return found;
    }

    private static List<String> column(String sql, Object... args) {
        return GateFixtures.column(sql, args);
    }
}
