package com.finapp.reconciliation;

import static com.finapp.reconciliation.GateFixtures.CLOCK;
import static com.finapp.reconciliation.GateFixtures.EUR;
import static com.finapp.reconciliation.GateFixtures.FAR_FUTURE;
import static com.finapp.reconciliation.GateFixtures.IDS;
import static com.finapp.reconciliation.GateFixtures.PLATFORM;
import static com.finapp.reconciliation.GateFixtures.PROPOSER;
import static com.finapp.reconciliation.GateFixtures.capture;
import static com.finapp.reconciliation.GateFixtures.count;
import static com.finapp.reconciliation.GateFixtures.itemOf;
import static com.finapp.reconciliation.GateFixtures.string;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalEntry;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Atomicity by failure injection (`P8-DOC-001`'s gate claim, the {@code BatchAcceptanceDatabaseTest}
 * seam pattern carried into reconciliation): each of four multi-write transactions is crashed
 * BETWEEN two of its writes - the fault fires only after a witness, on the SAME connection,
 * has seen the earlier write inside the transaction - and nothing of the transaction survives;
 * the next fault-free tick then converges on the whole effect exactly once. Plus the
 * {@code recon-suspense} re-drive: a park re-driven on a later business date converges on the
 * item's conditional edge and posts nothing.
 *
 * <p>Each test owns a private source, so the sweeps' commits never reach another suite's rows.
 */
@Tag("database")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("atomicity by failure injection, and the recon-suspense re-drive (P8-DOC-001)")
class AtomicityByFailureInjectionDatabaseTest {

    private static final UUID REMATCH_SOURCE = IDS.next();
    private static final UUID REMATCH_RULE_SET = IDS.next();
    private static final UUID GRACE_SOURCE = IDS.next();
    private static final UUID GRACE_RULE_SET = IDS.next();
    private static final UUID AGEING_SOURCE = IDS.next();
    private static final UUID AGEING_RULE_SET = IDS.next();
    private static final UUID PROPOSAL_SOURCE = IDS.next();
    private static final UUID PROPOSAL_RULE_SET = IDS.next();
    private static final UUID REDRIVE_SOURCE = IDS.next();
    private static final UUID REDRIVE_RULE_SET = IDS.next();
    private static final UUID CONTAIN_SOURCE = IDS.next();
    private static final UUID CONTAIN_RULE_SET = IDS.next();

    @BeforeAll
    static void seed() {
        GateFixtures.seedRuleSet(REMATCH_SOURCE, REMATCH_RULE_SET);
        GateFixtures.seedRuleSet(GRACE_SOURCE, GRACE_RULE_SET);
        GateFixtures.seedRuleSet(AGEING_SOURCE, AGEING_RULE_SET);
        GateFixtures.seedRuleSet(PROPOSAL_SOURCE, PROPOSAL_RULE_SET);
        GateFixtures.seedRuleSet(REDRIVE_SOURCE, REDRIVE_RULE_SET);
        GateFixtures.seedRuleSet(CONTAIN_SOURCE, CONTAIN_RULE_SET);
    }

    // ----------------------------------------------------------------- the rematch leg

    @Test
    @Order(1)
    @DisplayName("the rematch leg crashed AFTER the unpark and BEFORE the EVIDENCED resolution"
            + " leaves nothing: the item PARKED, the suspense item OPEN, no allocation, no"
            + " REMATCH decision, no unpark entry - and the next tick unparks once")
    void theRematchLegIsAtomic() {
        String key = "INF-ATOM-" + UUID.randomUUID();
        UUID run = GateFixtures.seedRun(REMATCH_SOURCE, REMATCH_RULE_SET, capture(1, 25_00, key));
        GateFixtures.matching().sweep();
        GateFixtures.expireGrace(run);
        GateFixtures.matching().sweep();
        UUID item = itemOf(run, 1);
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?", item))
                .as("the fixture: grace parked the in-flight remainder").isEqualTo("PARKED");
        UUID suspenseItem = (UUID) GateFixtures.one("SELECT id FROM reconciliation.suspense_item"
                + " WHERE external_item_id = ?", item);
        UUID breakId = (UUID) GateFixtures.one("SELECT break_id FROM"
                + " reconciliation.suspense_item WHERE id = ?", suspenseItem);
        long decisionsBefore = count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " external_item_id = ?", item);
        GateFixtures.Seeded expectation = GateFixtures.openExpectation(
                REMATCH_SOURCE, REMATCH_RULE_SET, key, 25_00, ExpectationDirection.INBOUND,
                FAR_FUTURE);

        AtomicBoolean fired = new AtomicBoolean();
        Resolutions real = ResolutionFixtures.resolutions(IDS, CLOCK);
        GateFixtures.MatchingParts parts = new GateFixtures.MatchingParts();
        parts.resolutions = (unitOfWork, evidence) -> {
            if (evidence.parkId().isPresent()) {
                // The witness: the unpark is written INSIDE this transaction...
                assertThat(read(unitOfWork, "SELECT status FROM reconciliation.suspense_item"
                        + " WHERE id = ?", suspenseItem))
                        .as("the unpark released the item before the fault")
                        .isEqualTo("RELEASED");
                assertThat(read(unitOfWork, "SELECT status FROM reconciliation.external_item"
                        + " WHERE id = ?", item)).isEqualTo("MATCHED");
                fired.set(true);
                // ...and the EVIDENCED closure never runs.
                throw new GateFixtures.InjectedFault("rematch: after the unpark");
            }
            return real.evidence(unitOfWork, evidence);
        };
        parts.build().sweep();

        assertThat(fired).as("the fault fired between the unpark and the evidence").isTrue();
        assertThat(string("SELECT status || '/' || parked_minor || '/' || allocated_minor FROM"
                + " reconciliation.external_item WHERE id = ?", item))
                .as("the item's PARKED -> MATCHED edge rolled back with the crash")
                .isEqualTo("PARKED/2500/0");
        assertThat(string("SELECT status || '/' || released_minor FROM"
                + " reconciliation.suspense_item WHERE id = ?", suspenseItem))
                .as("the release rolled back: the value is still parked whole")
                .isEqualTo("OPEN/0");
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_release WHERE item_id = ?",
                suspenseItem)).as("no release row survived").isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.park WHERE source_id = ? AND"
                + " kind = 'UNPARK'", REMATCH_SOURCE))
                .as("no unpark park row - and so no unpark entry - survived").isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                + " external_item_id = ?", item)).as("no allocation survived").isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " external_item_id = ?", item))
                .as("no REMATCH decision survived").isEqualTo(decisionsBefore);
        assertThat(string("SELECT status || '/' || allocated_minor FROM"
                + " reconciliation.expectation WHERE id = ?", expectation.id()))
                .as("the expectation never saw the allocation").isEqualTo("OPEN/0");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.SettlementExpectationSettled' AND aggregate_id = ?",
                expectation.id())).as("no settled event survived").isZero();
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", breakId))
                .isEqualTo("OPEN");
        assertThat(count("SELECT count(*) FROM reconciliation.resolution WHERE break_id = ?",
                breakId)).as("no resolution survived").isZero();

        GateFixtures.matching().sweep();
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?", item))
                .as("the next tick converges: the rematch unparks the item").isEqualTo("MATCHED");
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " external_item_id = ? AND origin = 'REMATCH'", item))
                .as("one REMATCH decision, once").isEqualTo(1);
        assertThat(string("SELECT kind || '/' || status FROM reconciliation.resolution WHERE"
                + " break_id = ?", breakId)).isEqualTo("EVIDENCED/APPROVED");
        assertThat(count("SELECT count(*) FROM reconciliation.park WHERE source_id = ? AND"
                + " kind = 'UNPARK'", REMATCH_SOURCE)).as("one unpark, once").isEqualTo(1);
    }

    // ----------------------------------------------------------------- the grace leg

    @Test
    @Order(2)
    @DisplayName("the grace leg crashed AFTER the park's posting - its break and decision written"
            + " before it - rolls the post phase back to its own savepoint, the posting, the park"
            + " row and the suspense item together, and re-posts the park ONCE in the same tick"
            + " (the Phase 8 -> 9 transition's per-item containment, REC-3): one park, one entry,"
            + " one suspense item, one grace decision, one break - and the next tick adds nothing")
    void theGraceLegIsAtomic() {
        String key = "GRK-ATOM-" + UUID.randomUUID();
        UUID run = GateFixtures.seedRun(GRACE_SOURCE, GRACE_RULE_SET, capture(1, 30_00, key));
        GateFixtures.matching().sweep();
        UUID item = itemOf(run, 1);
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?", item))
                .isEqualTo("UNMATCHED");
        GateFixtures.expireGrace(run);

        // The break register runs first in the transaction; it hands the witness the
        // transaction's own connection, on which the posting's fault then looks.
        AtomicReference<Connection> transaction = new AtomicReference<>();
        AtomicBoolean fired = new AtomicBoolean();
        GateFixtures.MatchingParts parts = new GateFixtures.MatchingParts();
        BreakRegister real = GateFixtures.register();
        parts.breaks = (unitOfWork, newBreak) -> {
            transaction.set(unitOfWork);
            return real.raise(unitOfWork, newBreak);
        };
        parts.suspense = GateFixtures.suspense((outcome, elapsed) -> {
            if (outcome == PostingObserver.Outcome.POSTED && !fired.get()) {
                Connection unitOfWork = transaction.get();
                assertThat(read(unitOfWork, "SELECT count(*) FROM reconciliation.break WHERE"
                        + " external_item_id = ?", item))
                        .as("the grace break is written inside the transaction")
                        .isEqualTo(1L);
                assertThat(read(unitOfWork, "SELECT status FROM reconciliation.external_item"
                        + " WHERE id = ?", item))
                        .as("the item's park edge is written inside the transaction")
                        .isEqualTo("PARKED");
                fired.set(true);
                throw new GateFixtures.InjectedFault("grace: after the park's posting");
            }
        });
        parts.build().sweep();

        assertThat(fired).as("the fault fired after the park's posting").isTrue();
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?", item))
                .as("re-posted alone in the same tick: parked").isEqualTo("PARKED");
        assertThat(count("SELECT count(*) FROM reconciliation.park WHERE source_id = ?",
                GRACE_SOURCE))
                .as("the crashed posting's park row rolled back with it: one park, one entry")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item s JOIN"
                + " reconciliation.park p ON p.id = s.park_id WHERE s.external_item_id = ? AND"
                + " s.status = 'OPEN' AND s.entry_id = p.journal_entry_id", item))
                .as("one suspense item, carrying the surviving park's entry whole").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry e WHERE"
                + " e.idempotency_scope LIKE '%recon-suspense:%' AND NOT EXISTS (SELECT 1 FROM"
                + " reconciliation.park p WHERE p.journal_entry_id = e.id)"))
                .as("the crashed posting left no entry behind: every park entry has its park")
                .isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " external_item_id = ?", item))
                .as("the run's waiting decision and ONE grace decision, written before the"
                        + " post phase's savepoint")
                .isEqualTo(2);
        assertThat(string("SELECT b.type || '/' || b.cause FROM reconciliation.break b WHERE"
                + " b.external_item_id = ?", item)).isEqualTo("UNKNOWN_EXTERNAL/GRACE_EXPIRED");

        GateFixtures.matching().sweep();
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " external_item_id = ?", item))
                .as("the next tick adds nothing: the parked item is in no time leg's worklist")
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM reconciliation.park WHERE source_id = ?",
                GRACE_SOURCE)).isEqualTo(1);
    }

    @Test
    @Order(6)
    @DisplayName("the Phase 8 -> 9 transition (REC-3): a park that fails EVERY time is contained to"
            + " its item - the source's next expired item is graced and parked in the same tick,"
            + " the poisoned item left UNMATCHED, owned by its grace break, its clock stopped,"
            + " with no suspense item and no park - and the next tick takes nothing again")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aParkThatAlwaysFailsIsContainedToItsItem() {
        long poisonedMinor = 41_17;
        long healthyMinor = 52_29;
        UUID run = GateFixtures.seedRun(CONTAIN_SOURCE, CONTAIN_RULE_SET,
                capture(1, poisonedMinor, "CNT-P-" + UUID.randomUUID()),
                capture(2, healthyMinor, "CNT-H-" + UUID.randomUUID()));
        GateFixtures.matching().sweep();
        UUID poisoned = itemOf(run, 1);
        UUID healthy = itemOf(run, 2);
        assertThat(GateFixtures.column("SELECT status FROM reconciliation.external_item WHERE"
                + " run_id = ?", run)).containsOnly("UNMATCHED");
        GateFixtures.expireGrace(run);

        // Every entry carrying the poisoned item's value - alone, or summed with the healthy
        // one's in the leg's aggregated park - fails to post.
        JournalEntryStore journal = GateFixtures.intercept(
                JournalEntryStore.class, new JdbcJournalEntryStore(IDS), "append",
                (args, proceed) -> {
                    JournalEntry entry = (JournalEntry) args[1];
                    if (entry.lines().stream().anyMatch(line ->
                            line.amount().minorUnits() == poisonedMinor
                                    || line.amount().minorUnits()
                                            == poisonedMinor + healthyMinor)) {
                        throw new GateFixtures.InjectedFault("a park that cannot post");
                    }
                    return proceed.call();
                });
        GateFixtures.MatchingParts parts = new GateFixtures.MatchingParts();
        parts.suspense =
                new Suspense(
                        new PostingService(
                                new IdempotentExecutor(
                                        new JdbcIdempotencyRecordStore(), CLOCK,
                                        Duration.ofDays(1), Duration.ofMinutes(5)),
                                (JournalEntryStore<Connection>) journal,
                                new JdbcAuditWriter(),
                                new JdbcOutboxWriter(),
                                new JdbcBalanceProjection(),
                                IDS,
                                CLOCK,
                                PostingObserver.NONE),
                        new JdbcLedgerAccountStore(),
                        IDS);
        Matching poisonedLeg = parts.build();
        poisonedLeg.sweep();

        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?", healthy))
                .as("REC-3: the source's next expired item is graced in the same tick")
                .isEqualTo("PARKED");
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item WHERE"
                + " external_item_id = ? AND status = 'OPEN'", healthy)).isEqualTo(1);
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?",
                poisoned))
                .as("the poisoned item waits, its park undone").isEqualTo("UNMATCHED");
        assertThat(count("SELECT count(*) FROM reconciliation.external_item WHERE id = ? AND"
                + " grace_until IS NULL", poisoned))
                .as("its grace clock stopped: no time leg takes it again").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item WHERE"
                + " external_item_id = ?", poisoned)).as("no suspense item of its own").isZero();
        assertThat(string("SELECT b.type || '/' || (b.status <> 'RESOLVED')::text FROM"
                + " reconciliation.break b WHERE b.external_item_id = ?", poisoned))
                .as("owned by the open break its grace decision raised")
                .isEqualTo("UNKNOWN_EXTERNAL/true");
        long decisions = count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " external_item_id = ?", poisoned);

        poisonedLeg.sweep();
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " external_item_id = ?", poisoned))
                .as("the next tick takes the contained item nowhere")
                .isEqualTo(decisions);
    }

    // ----------------------------------------------------------------- the ageing sweep

    @Test
    @Order(3)
    @DisplayName("the ageing sweep crashed AFTER overdue_since and BEFORE the MISSING_EXTERNAL"
            + " break leaves nothing: overdue_since NULL, no break, no event - and the next"
            + " sweep ages it once")
    void theAgeingSweepIsAtomic() {
        LocalDate farPast = GateFixtures.databaseToday().minusDays(60);
        GateFixtures.Seeded overdue = GateFixtures.openExpectation(
                AGEING_SOURCE, AGEING_RULE_SET, "AGE-ATOM-" + UUID.randomUUID(), 70_00,
                ExpectationDirection.INBOUND, farPast);

        AtomicBoolean fired = new AtomicBoolean();
        BreakRegister real = GateFixtures.register();
        BreakRegister faulty = (unitOfWork, newBreak) -> {
            if (newBreak.type() == BreakType.MISSING_EXTERNAL
                    && newBreak.subject().expectationId().equals(Optional.of(overdue.id()))) {
                assertThat(read(unitOfWork, "SELECT count(*) FROM reconciliation.expectation"
                        + " WHERE id = ? AND overdue_since IS NOT NULL", overdue.id()))
                        .as("overdue_since is written inside the transaction")
                        .isEqualTo(1L);
                fired.set(true);
                throw new GateFixtures.InjectedFault("ageing: after overdue_since");
            }
            return real.raise(unitOfWork, newBreak);
        };
        GateFixtures.reconciliationSweep(faulty).sweep();

        assertThat(fired).as("the fault fired between the mark and the break").isTrue();
        assertThat(count("SELECT count(*) FROM reconciliation.expectation WHERE id = ? AND"
                + " overdue_since IS NULL", overdue.id()))
                .as("the one-way fact rolled back with its break").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE expectation_id = ?",
                overdue.id())).as("no break survived").isZero();
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.SettlementExpectationOverdue' AND aggregate_id = ?",
                overdue.id())).as("no event survived").isZero();

        GateFixtures.reconciliationSweep(GateFixtures.register()).sweep();
        assertThat(count("SELECT count(*) FROM reconciliation.expectation WHERE id = ? AND"
                + " overdue_since IS NOT NULL", overdue.id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE expectation_id = ?"
                + " AND type = 'MISSING_EXTERNAL'", overdue.id()))
                .as("the next sweep ages it once").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.SettlementExpectationOverdue' AND aggregate_id = ?",
                overdue.id())).isEqualTo(1);
    }

    // ----------------------------------------------------------------- the proposal

    @Test
    @Order(4)
    @DisplayName("a resolution proposal crashed AFTER its ledger proposal and its resolution row"
            + " leaves nothing: no resolution, no ledger proposal or lines, the break OPEN, no"
            + " audit - and the next proposal is accepted")
    void theProposalIsAtomic() {
        GateFixtures.Seeded expectation = GateFixtures.openExpectation(
                PROPOSAL_SOURCE, PROPOSAL_RULE_SET, "PRO-ATOM-" + UUID.randomUUID(), 40_00,
                ExpectationDirection.INBOUND, FAR_FUTURE);
        UUID breakId = GateFixtures.raise(BreakType.MISSING_EXTERNAL,
                BreakCause.EXPECTATION_OVERDUE, BreakRegister.Subject.expectation(expectation.id()),
                40_00, PROPOSAL_SOURCE, PROPOSAL_RULE_SET);

        AtomicReference<ResolutionStore.NewResolution> written = new AtomicReference<>();
        ResolutionStore faulty = GateFixtures.intercept(
                ResolutionStore.class, new JdbcResolutionStore(), "insert", (args, proceed) -> {
                    proceed.call();
                    Connection unitOfWork = (Connection) args[0];
                    ResolutionStore.NewResolution resolution =
                            (ResolutionStore.NewResolution) args[1];
                    written.set(resolution);
                    assertThat(read(unitOfWork, "SELECT count(*) FROM reconciliation.resolution"
                            + " WHERE id = ?", resolution.id()))
                            .as("the resolution row is written inside the transaction")
                            .isEqualTo(1L);
                    assertThat(read(unitOfWork, "SELECT count(*) FROM"
                            + " ledger.adjustment_proposal WHERE id = ?",
                            resolution.adjustmentProposalId().orElseThrow()))
                            .as("the ledger proposal is written inside the transaction")
                            .isEqualTo(1L);
                    throw new GateFixtures.InjectedFault("proposal: after the resolution row");
                });
        ResolutionMachine crashing = GateFixtures.machine(faulty, new JdbcMatchingStore());

        assertThatThrownBy(() -> GateFixtures.propose(crashing, PROPOSER, breakId,
                        ResolutionKind.WRITE_OFF, ResolutionReasonCode.LOSS_ACCEPTED,
                        Optional.empty(), Optional.empty()))
                .as("the injected crash surfaces to the caller")
                .isInstanceOf(GateFixtures.InjectedFault.class);

        ResolutionStore.NewResolution lost = written.get();
        assertThat(lost).as("the fault fired after the resolution row").isNotNull();
        assertThat(count("SELECT count(*) FROM reconciliation.resolution WHERE break_id = ?",
                breakId)).as("no resolution row survived").isZero();
        UUID proposal = lost.adjustmentProposalId().orElseThrow();
        assertThat(count("SELECT count(*) FROM ledger.adjustment_proposal WHERE id = ?",
                proposal)).as("the ledger proposal - the FIRST write - rolled back").isZero();
        assertThat(count("SELECT count(*) FROM ledger.adjustment_proposal_line WHERE"
                + " proposal_id = ?", proposal)).as("no proposal line survived").isZero();
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", breakId))
                .as("the break never moved to RESOLUTION_PROPOSED").isEqualTo("OPEN");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE target_id = ?",
                lost.id().toString())).as("no audit record survived").isZero();

        ResolutionMachine.Proposed again = GateFixtures.propose(GateFixtures.machine(), PROPOSER,
                breakId, ResolutionKind.WRITE_OFF, ResolutionReasonCode.LOSS_ACCEPTED,
                Optional.empty(), Optional.empty());
        assertThat(again.status())
                .as("nothing of the crashed proposal holds the one-live-proposal seat")
                .isEqualTo(ResolutionStatus.PROPOSED);
    }

    // ----------------------------------------------------------------- the re-drive

    @Test
    @Order(5)
    @DisplayName("recon-suspense re-driven on a moved clock: a park re-driven for the same item"
            + " on a later business date, derived from the stored park row, converges on the"
            + " item's conditional edge - no second park, suspense item or entry")
    void aParkReDrivenOnAMovedClockConverges() {
        UUID run = GateFixtures.seedRun(REDRIVE_SOURCE, REDRIVE_RULE_SET,
                GateFixtures.refund(1, 12_00, "TERM-REDRIVE-" + UUID.randomUUID()));
        GateFixtures.matching().sweep();
        UUID item = itemOf(run, 1);
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?", item))
                .as("the fixture: a terminal refund parks in the chunk").isEqualTo("PARKED");
        UUID suspenseItem = (UUID) GateFixtures.one("SELECT id FROM reconciliation.suspense_item"
                + " WHERE external_item_id = ?", item);
        UUID breakId = (UUID) GateFixtures.one("SELECT break_id FROM"
                + " reconciliation.suspense_item WHERE id = ?", suspenseItem);
        UUID parkId = (UUID) GateFixtures.one("SELECT park_id FROM reconciliation.suspense_item"
                + " WHERE id = ?", suspenseItem);
        Object storedDecidedOn = GateFixtures.one("SELECT decided_on FROM reconciliation.park"
                + " WHERE id = ?", parkId);
        LocalDate decidedOn = storedDecidedOn instanceof java.sql.Date date
                ? date.toLocalDate() : (LocalDate) storedDecidedOn;
        long parks = count("SELECT count(*) FROM reconciliation.park WHERE source_id = ?",
                REDRIVE_SOURCE);
        long entries = count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?",
                parkId.toString());
        assertThat(entries).as("the original park posted one entry").isEqualTo(1);

        // The moved clock: three days after the stored decision, on the stored row's own date.
        LocalDate later = decidedOn.plusDays(3);
        Suspense.ParkResult redriven = GateFixtures.as(PLATFORM, uow -> GateFixtures.suspense()
                .park(uow, new Suspense.ParkCommand(
                        REDRIVE_SOURCE, later,
                        List.of(new Suspense.ParkedItem(item, breakId,
                                Money.ofPersisted(12_00, EUR, 2),
                                GateFixtures.operational(AccountPurpose.SETTLEMENT_CLEARING))),
                        PLATFORM, Instant.now(CLOCK).plus(Duration.ofDays(3)),
                        CorrelationId.generate(IDS))));

        assertThat(redriven.parked())
                .as("a re-driven park on a later date parks nothing a second time").isEmpty();
        assertThat(redriven.converged())
                .as("it converges on the item's conditional edge").containsExactly(item);
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item WHERE"
                + " external_item_id = ?", item)).as("one suspense item, still").isEqualTo(1);
        assertThat(string("SELECT park_id || '/' || status || '/' || amount_minor FROM"
                + " reconciliation.suspense_item WHERE id = ?", suspenseItem))
                .as("the original suspense item untouched")
                .isEqualTo(parkId + "/OPEN/1200");
        assertThat(count("SELECT count(*) FROM reconciliation.park WHERE source_id = ?",
                REDRIVE_SOURCE)).as("no second park row").isEqualTo(parks);
        assertThat(count("SELECT count(*) FROM reconciliation.park WHERE source_id = ? AND"
                + " decided_on = ?", REDRIVE_SOURCE, later))
                .as("nothing was decided on the moved date").isZero();
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE reference = ?",
                parkId.toString())).as("the original entry alone").isEqualTo(1);
    }

    // ----------------------------------------------------------------- plumbing

    /** A read inside the transaction under test - the witness's view of its own writes. */
    private static Object read(Connection unitOfWork, String sql, Object... args) {
        try (PreparedStatement statement = unitOfWork.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                Object value = row.getObject(1);
                return value instanceof Number number ? number.longValue() : value;
            }
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("witness read failed: " + sql, failure);
        }
    }
}
