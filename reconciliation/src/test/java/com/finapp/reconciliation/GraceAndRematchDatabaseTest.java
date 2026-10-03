package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Grace, ageing, rematch and late evidence against the real schema (`P8-TSK-013`): the
 * grace leg re-deciding on the LOCKED row â€” a candidate committed meanwhile is allocated,
 * never parked beside it (ADR-0073 Â§7) â€” and typing the true remainders through the
 * lookup; the rematch leg unparking a whole parked value on later evidence, `EVIDENCED`
 * with the unpark the park's exact inverse; ageing marking an expectation overdue ONCE
 * under ten sweepers, on the DATABASE clock; late settlement resolving the overdue break
 * `EVIDENCED` with the timing on the decision and NO second timing break; escalation once
 * per band; a lost run blocked once.
 *
 * <p>The Matching clock is deliberately pinned MONTHS behind the database clock: every
 * window this module judges must be judged in SQL on the database clock (`INV-SET-02`),
 * so a leg that consulted the JVM clock would find nothing here and the suite fails loud
 * â€” the probe catcher is structural, not a special case. The suite owns private sources
 * (the `MatchingDatabaseTest` discipline) so its commits never race another suite's.
 */
@Tag("database")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("grace, ageing, rematch and late evidence (P8-TSK-013)")
class GraceAndRematchDatabaseTest {

    /** Months behind the database clock, on purpose â€” see the class note. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor PLATFORM = new Actor("system", ActorType.SYSTEM);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final UUID SOURCE =
            UUID.fromString("01a0e2bc-8200-7014-8000-000000000014");
    private static final UUID RULE_SET =
            UUID.fromString("01a0e2bd-8300-7014-8000-000000000014");
    private static final UUID BLOCK_SOURCE =
            UUID.fromString("01a0e2bc-8200-7014-8000-0000000000ff");
    private static final UUID BLOCK_RULE_SET =
            UUID.fromString("01a0e2bd-8300-7014-8000-0000000000ff");
    private static final LocalDate SETTLED_ON = LocalDate.parse("2026-09-25");
    /** Never inside any window this suite runs through. */
    private static final LocalDate FAR_FUTURE = SETTLED_ON.plusYears(1);
    /** Sixty days before the settlement date: overdue at any real database clock. */
    private static final LocalDate FAR_PAST = SETTLED_ON.minusDays(60);
    private static final AtomicLong SEQUENCES = new AtomicLong(System.nanoTime() % 90_000);

    /**
     * Knowledge the platform gains while an item WAITS: a run-time {@code TERMINAL}
     * answer parks in the chunk itself (`P8-TSK-011`), so the grace leg's terminal path
     * exists exactly for an answer that CHANGED during the wait â€” the flag flips
     * between the run sweep and the grace sweep.
     */
    private static volatile boolean terminalVisible = false;

    /** Types by the key's own prefix â€” the app-composed lookup's shape, made pure. */
    private static final InternalReferenceLookup LOOKUP =
            (unitOfWork, subject) -> {
                for (String value : subject.references().values()) {
                    if (value.startsWith("INF-")) {
                        return new InternalReferenceLookup.InternalReference(
                                InternalClassification.IN_FLIGHT,
                                Optional.of("op-inflight"),
                                Optional.of("PENDING"));
                    }
                    if (value.startsWith("TRM-") && terminalVisible) {
                        return new InternalReferenceLookup.InternalReference(
                                InternalClassification.TERMINAL,
                                Optional.of("op-terminal"),
                                Optional.of("VOIDED"));
                    }
                }
                return InternalReferenceLookup.InternalReference.unknown();
            };

    private static Connection application;
    private static JdbcMatchingStore store;
    private static JdbcReconciliationRuns runs;
    private static JdbcExternalItems items;
    private static JdbcExpectationRegister expectations;
    private static JdbcBreakRegister breakRegister;
    private static Matching matching;

    // Order 1's MISSING_INTERNAL park: the capture completes late, Order 3 unparks it.
    private static UUID inFlightItem;
    private static String inFlightKey;
    private static UUID overdueExpectation; // Order 4's aged subject, settled at Order 5.
    private static UUID overdueBreak;

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
        store = new JdbcMatchingStore();
        runs = new JdbcReconciliationRuns();
        items = new JdbcExternalItems();
        expectations = new JdbcExpectationRegister(IDS);
        breakRegister =
                new JdbcBreakRegister(new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS);
        matching = matching();
        seedPrivateRuleSets();
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.rollback();
            application.close();
        }
    }

    private static Matching matching() {
        return new Matching(
                store,
                new MatchingRules(),
                new JdbcBreakRegister(new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS),
                new Suspense(postingService(), new JdbcLedgerAccountStore(), IDS),
                ResolutionFixtures.resolutions(IDS, CLOCK),
                LOOKUP,
                new JdbcLedgerAccountStore(),
                new JdbcOutboxWriter(),
                new JdbcAuditWriter(),
                IDS,
                CLOCK,
                new Matching.Config(200, 2),
                runner());
    }

    private static ReconciliationSweep reconciliationSweep() {
        // The observers stamp their records on the real clock: an escalation's due
        // bands are the DATABASE's day count, never this JVM's.
        return new ReconciliationSweep(
                store,
                new JdbcBreakRegister(new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS),
                new KeyCollisionBreaks(
                        new JdbcBreakRegister(
                                new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS),
                        new JdbcRuleSets(),
                        IDS),
                new JdbcOutboxWriter(),
                IDS,
                Clock.systemUTC(),
                new ReconciliationSweep.Config(500, 3),
                runner());
    }

    private static PostingService postingService() {
        return new PostingService(
                new IdempotentExecutor(
                        new JdbcIdempotencyRecordStore(),
                        CLOCK,
                        Duration.ofDays(1),
                        Duration.ofMinutes(5)),
                new JdbcJournalEntryStore(IDS),
                new JdbcAuditWriter(),
                new JdbcOutboxWriter(),
                new JdbcBalanceProjection(),
                IDS,
                CLOCK,
                PostingObserver.NONE);
    }

    private static TransactionRunner runner() {
        return new TransactionRunner() {
            @Override
            public <R> R inTransaction(java.util.function.Function<Connection, R> work) {
                try (Connection unitOfWork = DatabaseRoles.application()) {
                    unitOfWork.setAutoCommit(false);
                    try {
                        R result = work.apply(unitOfWork);
                        unitOfWork.commit();
                        return result;
                    } catch (RuntimeException failure) {
                        unitOfWork.rollback();
                        throw failure;
                    }
                } catch (SQLException failure) {
                    throw new ReconciliationStorageException(
                            "the test transaction failed", failure);
                }
            }
        };
    }

    private static void seedPrivateRuleSets() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            for (UUID[] pair :
                    new UUID[][] {{RULE_SET, SOURCE}, {BLOCK_RULE_SET, BLOCK_SOURCE}}) {
                execute(app,
                        "INSERT INTO reconciliation.rule_set (id, source_id, version,"
                                + " status, funding_lag_days, gain_min_age_days,"
                                + " effective_from, proposed_by, decided_by, reason,"
                                + " created_at, correlation_id) VALUES (?, ?, 1,"
                                + " 'PROPOSED', 2, 90, ?, 'test', NULL,"
                                + " 'GraceAndRematchDatabaseTest private rule set',"
                                + " now(), 'p8-tsk-013-test') ON CONFLICT (id) DO NOTHING",
                        pair[0], pair[1], java.sql.Date.valueOf(SETTLED_ON));
                execute(app,
                        "INSERT INTO reconciliation.rule (rule_set_id, priority,"
                                + " line_type, key_kind, expectation_kind, cardinality,"
                                + " operation_anchored, grace_hours) VALUES"
                                + " (?, 1, 'CAPTURE', 'PSP_CAPTURE_REF', 'CARD_CAPTURE',"
                                + " 'ONE_TO_ONE', false, 48) ON CONFLICT DO NOTHING",
                        pair[0]);
                execute(app,
                        "INSERT INTO reconciliation.tolerance (rule_set_id, comparison,"
                                + " currency, absolute_minor, days) VALUES"
                                + " (?, 'SETTLEMENT_DATE_DAYS', NULL, NULL, 2)"
                                + " ON CONFLICT DO NOTHING",
                        pair[0]);
                execute(app,
                        "INSERT INTO reconciliation.severity_threshold (rule_set_id,"
                                + " currency, high_value_minor) VALUES (?, 'EUR', 100000)"
                                + " ON CONFLICT DO NOTHING",
                        pair[0]);
                execute(app,
                        "UPDATE reconciliation.rule_set SET status = 'ACTIVE', decided_by = 'test-activator',"
                                + " decided_at = now() WHERE id = ? AND status = 'PROPOSED'",
                        pair[0]);
            }
            app.commit();
        }
    }

    // ----------------------------------------------------------------- the grace leg

    @Test
    @Order(1)
    @DisplayName("scenario 4: grace expiry types the true remainders through the lookup -"
            + " unknown, in-flight and terminal each parked with its own break, the"
            + " lookup's answer frozen, and the predicate self-drains")
    void graceTypesTheExpiredRemainders() throws SQLException {
        String unknownKey = "GRK-UNK-" + UUID.randomUUID();
        inFlightKey = "INF-" + UUID.randomUUID();
        String terminalKey = "TRM-" + UUID.randomUUID();
        UUID runId =
                seedRun(
                        SOURCE, RULE_SET,
                        line(1, ExternalLineType.CAPTURE, ExpectationDirection.INBOUND,
                                30_00, ItemKeyKind.PSP_CAPTURE_REF, unknownKey),
                        line(2, ExternalLineType.CAPTURE, ExpectationDirection.INBOUND,
                                20_00, ItemKeyKind.PSP_CAPTURE_REF, inFlightKey),
                        line(3, ExternalLineType.CAPTURE, ExpectationDirection.INBOUND,
                                25_00, ItemKeyKind.PSP_CAPTURE_REF, terminalKey));
        Matching.SweepResult first = matching.sweep();
        assertThat(first.graced())
                .as("a fresh 48h window is judged on the DATABASE clock: nothing expired,"
                        + " although every window is already 'expired' on this suite's"
                        + " deliberately-past JVM clock")
                .isZero();
        for (int lineNo = 1; lineNo <= 3; lineNo++) {
            assertThat(itemStatus(runId, lineNo)).isEqualTo("UNMATCHED");
        }

        // While the items waited, the platform learned the terminal reference VOIDED.
        terminalVisible = true;
        expireGrace(runId);
        Matching.SweepResult swept = matching.sweep();
        assertThat(swept.graced()).isEqualTo(3);

        inFlightItem = itemId(runId, 2);
        assertThat(itemStatus(runId, 1)).isEqualTo("PARKED");
        assertThat(itemStatus(runId, 2)).isEqualTo("PARKED");
        assertThat(itemStatus(runId, 3)).isEqualTo("PARKED");
        assertThat(breakRow(runId, 1))
                .containsExactly("UNKNOWN_EXTERNAL", "GRACE_EXPIRED", "UNKNOWN");
        Object[] inFlight = row("SELECT b.type, b.cause, b.internal_classification,"
                + " b.internal_operation_ref, b.internal_state FROM reconciliation.break"
                + " b JOIN reconciliation.external_item i ON b.external_item_id = i.id"
                + " WHERE i.run_id = ? AND i.line_no = 2", runId);
        assertThat(inFlight)
                .containsExactly(
                        "MISSING_INTERNAL", "GRACE_EXPIRED", "IN_FLIGHT", "op-inflight",
                        "PENDING");
        assertThat(breakRow(runId, 3))
                .containsExactly(
                        "REVERSAL_MISMATCH", "TERMINAL_STATE_CONTRADICTED", "TERMINAL");
        for (int lineNo = 1; lineNo <= 3; lineNo++) {
            assertThat(count("SELECT count(*) FROM reconciliation.suspense_item s JOIN"
                    + " reconciliation.external_item i ON s.external_item_id = i.id"
                    + " WHERE i.run_id = ? AND i.line_no = " + lineNo
                    + " AND s.status = 'OPEN'", runId)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM reconciliation.match_decision d WHERE"
                    + " d.external_item_id = (SELECT id FROM"
                    + " reconciliation.external_item WHERE run_id = ? AND line_no = "
                    + lineNo + ")", runId))
                    .as("one waiting decision at the run, one grace decision")
                    .isEqualTo(2);
        }

        assertThat(matching.sweep().graced())
                .as("a parked item leaves the predicate: the leg self-drains")
                .isZero();
    }

    @Test
    @Order(2)
    @DisplayName("scenario 4: a candidate committed while the item waited is found on the"
            + " grace re-decision and ALLOCATED - never parked beside the money")
    void graceAllocatesACandidateCommittedMeanwhile() throws SQLException {
        String key = "GRC-LATE-" + UUID.randomUUID();
        UUID runId =
                seedRun(SOURCE, RULE_SET,
                        line(1, ExternalLineType.CAPTURE, ExpectationDirection.INBOUND,
                                40_00, ItemKeyKind.PSP_CAPTURE_REF, key));
        matching.sweep();
        assertThat(itemStatus(runId, 1)).isEqualTo("UNMATCHED");

        UUID expectation = openExpectation(key, 40_00, FAR_FUTURE);
        expireGrace(runId);
        matching.sweep();

        assertThat(itemStatus(runId, 1)).isEqualTo("MATCHED");
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                expectation)).isEqualTo("SETTLED");
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item s JOIN"
                + " reconciliation.external_item i ON s.external_item_id = i.id WHERE"
                + " i.run_id = ?", runId))
                .as("the money reached its expectation: nothing parked")
                .isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.break b JOIN"
                + " reconciliation.external_item i ON b.external_item_id = i.id WHERE"
                + " i.run_id = ?", runId)).isZero();
        assertThat(string("SELECT d.outcome FROM reconciliation.match_decision d JOIN"
                + " reconciliation.external_item i ON i.id = d.external_item_id WHERE"
                + " i.run_id = ? ORDER BY d.decided_at DESC, d.id DESC LIMIT 1", runId))
                .isEqualTo("MATCHED");

        long decisions = decisionCount(runId, 1);
        matching.sweep();
        assertThat(decisionCount(runId, 1))
                .as("a disposed item is re-judged by nobody (INV-HIST-04)")
                .isEqualTo(decisions);
    }

    @Test
    @Order(3)
    @DisplayName("scenarios 4 and 8: the capture completes late and its expectation"
            + " opens - rematch unparks the MISSING_INTERNAL park whole, the unpark the"
            + " park's exact inverse under cause UNPARK, the break RESOLVED with its"
            + " EVIDENCED resolution carrying the unpark's entry")
    void rematchUnparksTheParkedItemWholeAndEvidenced() throws SQLException {
        UUID suspenseItem = (UUID) one("SELECT id FROM reconciliation.suspense_item"
                + " WHERE external_item_id = ?", inFlightItem);
        UUID parkEntry = (UUID) one("SELECT p.journal_entry_id FROM reconciliation.park p"
                + " JOIN reconciliation.suspense_item s ON s.park_id = p.id WHERE"
                + " s.id = ?", suspenseItem);
        UUID breakId = (UUID) one("SELECT break_id FROM reconciliation.suspense_item"
                + " WHERE id = ?", suspenseItem);

        UUID expectation = openExpectation(inFlightKey, 20_00, FAR_FUTURE);
        Matching.SweepResult swept = matching.sweep();

        assertThat(swept.rematched()).isEqualTo(1);
        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?",
                inFlightItem)).isEqualTo("MATCHED");
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                expectation)).isEqualTo("SETTLED");
        assertThat(string("SELECT status FROM reconciliation.suspense_item WHERE id = ?",
                suspenseItem)).isEqualTo("RELEASED");
        Object[] release = row("SELECT cause, amount_minor, cause_ref FROM"
                + " reconciliation.suspense_release WHERE item_id = ?", suspenseItem);
        assertThat(release[0]).isEqualTo("UNPARK");
        assertThat(release[1]).isEqualTo(20_00L);
        assertThat((String) release[2]).startsWith("decision=");

        Object[] resolution = row("SELECT kind, status, reason_code, proposed_by_type,"
                + " journal_entry_id, proposed_amount_minor FROM"
                + " reconciliation.resolution WHERE break_id = ?", breakId);
        assertThat(resolution[0]).isEqualTo("EVIDENCED");
        assertThat(resolution[1]).isEqualTo("APPROVED");
        assertThat(resolution[2]).isEqualTo("EVIDENCE_RECEIVED");
        assertThat(resolution[3]).isEqualTo("SYSTEM");
        assertThat(resolution[5]).isEqualTo(20_00L);
        UUID unparkEntry = (UUID) resolution[4];
        assertThat(unparkEntry).as("the unpark's entry rides the resolution").isNotNull();
        // The park entry aggregated the batch's three parks (75.00, one entry per
        // position and value date); the unpark inverts the ITEM's park exactly - the
        // same two accounts, directions swapped, the item's own 20.00.
        assertThat(entryLines(unparkEntry))
                .containsExactlyInAnyOrderElementsOf(
                        entryLines(parkEntry).stream()
                                .map(lineText -> lineText.startsWith("DEBIT:")
                                        ? "CREDIT:" + lineText.substring(6)
                                        : "DEBIT:" + lineText.substring(7))
                                .map(lineText ->
                                        lineText.substring(0, lineText.lastIndexOf(':'))
                                                + ":2000")
                                .toList());
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?",
                breakId)).isEqualTo("RESOLVED");
        assertThat(string("SELECT d.origin FROM reconciliation.match_decision d WHERE"
                + " d.external_item_id = ? ORDER BY d.decided_at DESC, d.id DESC"
                + " LIMIT 1", inFlightItem)).isEqualTo("REMATCH");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.BreakResolved' AND aggregate_id = ?", breakId))
                .isEqualTo(1);

        long decisions = count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " external_item_id = ?", inFlightItem);
        Matching.SweepResult again = matching.sweep();
        assertThat(again.rematched())
                .as("a MATCHED item leaves the worklist: committed decisions are"
                        + " never re-judged")
                .isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " external_item_id = ?", inFlightItem)).isEqualTo(decisions);
    }

    // ----------------------------------------------------------------- ageing

    @Test
    @Order(4)
    @DisplayName("scenario 5: ten ageing sweepers mark ONCE - one overdue_since, one"
            + " MISSING_EXTERNAL break, one event; the window holds the inside row and"
            + " a settled row never ages; the readings count per source")
    void tenAgeingSweepersMarkOnceAndTheWindowHolds() throws Exception {
        overdueExpectation = openExpectation("AGE-OVD-" + UUID.randomUUID(), 70_00,
                FAR_PAST);
        UUID inside = openExpectation("AGE-IN-" + UUID.randomUUID(), 10_00, FAR_FUTURE);
        String settledKey = "AGE-SET-" + UUID.randomUUID();
        UUID settled = openExpectation(settledKey, 15_00, FAR_PAST);
        seedRun(SOURCE, RULE_SET,
                line(1, ExternalLineType.CAPTURE, ExpectationDirection.INBOUND, 15_00,
                        ItemKeyKind.PSP_CAPTURE_REF, settledKey));
        // One waiting item (balanced by its own expectation) for the unmatched reading.
        String waitKey = "AGE-WAIT-" + UUID.randomUUID();
        openExpectation("AGE-WAIT-BAL-" + UUID.randomUUID(), 5_00, FAR_FUTURE);
        seedRun(SOURCE, RULE_SET,
                line(1, ExternalLineType.CAPTURE, ExpectationDirection.INBOUND, 5_00,
                        ItemKeyKind.PSP_CAPTURE_REF, waitKey));
        matching.sweep();
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                settled)).isEqualTo("SETTLED");

        ExecutorService racers = Executors.newFixedThreadPool(10);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> outcomes = new ArrayList<>();
            for (int racer = 0; racer < 10; racer++) {
                outcomes.add(racers.submit(() -> {
                    start.await();
                    reconciliationSweep().sweep();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> outcome : outcomes) {
                outcome.get();
            }
        } finally {
            racers.shutdownNow();
        }

        assertThat(one("SELECT overdue_since FROM reconciliation.expectation WHERE"
                + " id = ?", overdueExpectation)).isNotNull();
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE"
                + " expectation_id = ? AND type = 'MISSING_EXTERNAL' AND cause ="
                + " 'EXPECTATION_OVERDUE'", overdueExpectation))
                .as("ten racers, one break: the NULL -> value conditional and the"
                        + " one-open unique arbitrate")
                .isEqualTo(1);
        overdueBreak = (UUID) one("SELECT id FROM reconciliation.break WHERE"
                + " expectation_id = ? AND type = 'MISSING_EXTERNAL'",
                overdueExpectation);
        assertThat(scalar("SELECT value_at_issue_minor FROM reconciliation.break WHERE"
                + " id = ?", overdueBreak))
                .as("the break carries the remainder at the raise")
                .isEqualTo(70_00L);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.SettlementExpectationOverdue' AND aggregate_id = ?",
                overdueExpectation)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.SettlementExpectationOverdue' AND aggregate_id = ?"
                + " AND causation_id = correlation_id AND causation_id <> aggregate_id::text",
                overdueExpectation))
                .as("the sweep's flow is the cause, never the expectation (ARCH-P8-04)")
                .isEqualTo(1);
        assertThat(one("SELECT overdue_since FROM reconciliation.expectation WHERE"
                + " id = ?", inside))
                .as("inside its window: untouched")
                .isNull();
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE"
                + " expectation_id = ?", inside)).isZero();
        assertThat(one("SELECT overdue_since FROM reconciliation.expectation WHERE"
                + " id = ?", settled))
                .as("a settled expectation never ages, whatever its dates")
                .isNull();

        // The readings behind the per-source gauges (`ReconciliationMetrics`).
        JdbcRunReadings readings = new JdbcRunReadings();
        assertThat(readings.overdueCountBySource(application).get(SOURCE)).isEqualTo(1L);
        assertThat(readings.oldestOverdueBySource(application)).containsKey(SOURCE);
        assertThat(readings.unmatchedCountBySource(application).get(SOURCE))
                .isEqualTo(count("SELECT count(*) FROM reconciliation.external_item"
                        + " WHERE source_id = ? AND status = 'UNMATCHED'", SOURCE));
    }

    @Test
    @Order(5)
    @DisplayName("scenario 8, L1: the money arrives late and whole - the overdue break"
            + " resolves EVIDENCED with no posting, the timing frozen on the decision,"
            + " and NO TIMING_DIFFERENCE break beside it; a partial arrival resolves"
            + " nothing and still raises no second timing record")
    void lateSettlementResolvesTheOverdueBreakEvidenced() throws SQLException {
        String key = keyOf(overdueExpectation);
        UUID runId =
                seedRun(SOURCE, RULE_SET,
                        line(1, ExternalLineType.CAPTURE, ExpectationDirection.INBOUND,
                                70_00, ItemKeyKind.PSP_CAPTURE_REF, key));
        matching.sweep();

        assertThat(itemStatus(runId, 1)).isEqualTo("MATCHED");
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                overdueExpectation)).isEqualTo("SETTLED");
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?",
                overdueBreak)).isEqualTo("RESOLVED");
        Object[] resolution = row("SELECT kind, status, reason_code, journal_entry_id,"
                + " proposed_amount_minor FROM reconciliation.resolution WHERE"
                + " break_id = ?", overdueBreak);
        assertThat(resolution[0]).isEqualTo("EVIDENCED");
        assertThat(resolution[1]).isEqualTo("APPROVED");
        assertThat(resolution[2]).isEqualTo("EVIDENCE_RECEIVED");
        assertThat(resolution[3]).as("a late settlement posts nothing of its own")
                .isNull();
        assertThat(resolution[4]).isEqualTo(70_00L);
        Object[] timing = row("SELECT d.date_deviation_days, d.timing_tolerance_days"
                + " FROM reconciliation.match_decision d JOIN"
                + " reconciliation.external_item i ON i.id = d.external_item_id WHERE"
                + " i.run_id = ?", runId);
        assertThat(timing[0]).as("the lateness is the decision's frozen fact")
                .isEqualTo(60);
        assertThat(timing[1]).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM reconciliation.break b JOIN"
                + " reconciliation.match_decision d ON b.decision_id = d.id JOIN"
                + " reconciliation.external_item i ON i.id = d.external_item_id WHERE"
                + " i.run_id = ? AND b.type = 'TIMING_DIFFERENCE'", runId))
                .as("the overdue break IS the timing record: one fact, one break")
                .isZero();

        // The partial arrival: the overdue break stands, no timing raise beside it.
        String partialKey = "AGE-PART-" + UUID.randomUUID();
        UUID partial = openExpectation(partialKey, 50_00, FAR_PAST);
        reconciliationSweep().sweep();
        UUID partialBreak = (UUID) one("SELECT id FROM reconciliation.break WHERE"
                + " expectation_id = ? AND type = 'MISSING_EXTERNAL'", partial);
        assertThat(partialBreak).isNotNull();
        UUID partialRun =
                seedRun(SOURCE, RULE_SET,
                        line(1, ExternalLineType.CAPTURE, ExpectationDirection.INBOUND,
                                20_00, ItemKeyKind.PSP_CAPTURE_REF, partialKey));
        matching.sweep();
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                partial)).isEqualTo("PARTIALLY_SETTLED");
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?",
                partialBreak))
                .as("a 30.00 remainder still stands: EVIDENCED only at zero")
                .isEqualTo("OPEN");
        assertThat(count("SELECT count(*) FROM reconciliation.resolution WHERE"
                + " break_id = ?", partialBreak)).isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.break b JOIN"
                + " reconciliation.match_decision d ON b.decision_id = d.id JOIN"
                + " reconciliation.external_item i ON i.id = d.external_item_id WHERE"
                + " i.run_id = ? AND b.type = 'TIMING_DIFFERENCE'", partialRun))
                .as("the standing overdue break suppresses the raise either way")
                .isZero();
    }

    // ----------------------------------------------------------------- escalation

    @Test
    @Order(6)
    @DisplayName("scenario: escalation once per band under ten sweepers - the"
            + " expected-value step converges the racers, CRITICAL is the cap, and a"
            + " second sweep owes nothing")
    void escalationOncePerBandUnderTen() throws Exception {
        UUID subject = openExpectation("ESC-" + UUID.randomUUID(), 40_00, FAR_FUTURE);
        UUID breakId = IDS.next();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            breakRegister.raise(
                    app,
                    new BreakRegister.NewBreak(
                            breakId,
                            BreakType.MISSING_EXTERNAL,
                            BreakCause.EXPECTATION_OVERDUE,
                            BreakRegister.Subject.expectation(subject),
                            SOURCE,
                            RULE_SET,
                            Money.ofPersisted(40_00, EUR, 2),
                            Optional.of(ExpectationDirection.INBOUND),
                            Optional.of(ExpectationKind.CARD_CAPTURE),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            PLATFORM,
                            Instant.now().minus(Duration.ofDays(10)),
                            CorrelationId.generate(IDS)));
            app.commit();
        }
        Severity born =
                Severity.valueOf(string(
                        "SELECT severity FROM reconciliation.break WHERE id = ?",
                        breakId));
        // Ten days behind two bands (2 and 7); the third (30) is not yet crossed.
        int expectedSteps =
                Math.min(2, Severity.CRITICAL.ordinal() - born.ordinal());

        ExecutorService racers = Executors.newFixedThreadPool(10);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> outcomes = new ArrayList<>();
            for (int racer = 0; racer < 10; racer++) {
                outcomes.add(racers.submit(() -> {
                    start.await();
                    reconciliationSweep().sweep();
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> outcome : outcomes) {
                outcome.get();
            }
        } finally {
            racers.shutdownNow();
        }

        assertThat(count("SELECT count(*) FROM reconciliation.break_event WHERE"
                + " break_id = ? AND event_type = 'SEVERITY_ESCALATED'", breakId))
                .as("once per band, whoever sweeps: the expected-value step converges")
                .isEqualTo(expectedSteps);
        assertThat(string("SELECT severity FROM reconciliation.break WHERE id = ?",
                breakId))
                .isEqualTo(Severity.values()[born.ordinal() + expectedSteps].name());

        reconciliationSweep().sweep();
        assertThat(count("SELECT count(*) FROM reconciliation.break_event WHERE"
                + " break_id = ? AND event_type = 'SEVERITY_ESCALATED'", breakId))
                .as("the bands already stepped owe nothing more")
                .isEqualTo(expectedSteps);
    }

    // ----------------------------------------------------------------- lost blocks

    @Test
    @Order(7)
    @DisplayName("scenario: a run whose recorded failures reached the bound but whose"
            + " blocking transaction died is BLOCKED once under ten sweepers, with its"
            + " one RUN_BLOCKED break")
    void aLostRunIsBlockedOnce() throws Exception {
        UUID runId = seedRun(BLOCK_SOURCE, BLOCK_RULE_SET,
                line(1, ExternalLineType.CAPTURE, ExpectationDirection.INBOUND, 9_99,
                        ItemKeyKind.PSP_CAPTURE_REF, "BLK-" + UUID.randomUUID()));
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            execute(app, "UPDATE reconciliation.reconciliation_batch SET failures = 3"
                    + " WHERE id = ?", runId);
            app.commit();
        }

        ExecutorService racers = Executors.newFixedThreadPool(10);
        List<Future<ReconciliationSweep.SweepResult>> outcomes = new ArrayList<>();
        try {
            CountDownLatch start = new CountDownLatch(1);
            for (int racer = 0; racer < 10; racer++) {
                outcomes.add(racers.submit(() -> {
                    start.await();
                    return reconciliationSweep().sweep();
                }));
            }
            start.countDown();
            for (Future<ReconciliationSweep.SweepResult> outcome : outcomes) {
                outcome.get();
            }
        } finally {
            racers.shutdownNow();
        }

        assertThat(string("SELECT status FROM reconciliation.reconciliation_batch WHERE"
                + " id = ?", runId)).isEqualTo("BLOCKED");
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE run_id = ?"
                + " AND type = 'PROCESSING_ERROR' AND cause = 'RUN_BLOCKED'", runId))
                .isEqualTo(1);
        int blocked = 0;
        for (Future<ReconciliationSweep.SweepResult> outcome : outcomes) {
            blocked += outcome.get().blocked();
        }
        assertThat(blocked)
                .as("the conditional edge admits one winner; the losers converge")
                .isEqualTo(1);
    }

    // ----------------------------------------------------------------- the locked row

    @Test
    @Order(8)
    @DisplayName("ADR-0073 Â§7: the grace judgement is made on the LOCKED row - a leg held"
            + " off the item sees the expectation committed meanwhile and allocates,"
            + " never parking money whose owner had already appeared")
    void theGraceLegJudgesOnTheLockedRow() throws Exception {
        String key = "GRL-LOCK-" + UUID.randomUUID();
        UUID runId =
                seedRun(SOURCE, RULE_SET,
                        line(1, ExternalLineType.CAPTURE, ExpectationDirection.INBOUND,
                                33_00, ItemKeyKind.PSP_CAPTURE_REF, key));
        matching.sweep();
        expireGrace(runId);
        UUID itemId = itemId(runId, 1);

        ExecutorService sweeper = Executors.newSingleThreadExecutor();
        try (Connection holder = DatabaseRoles.application();
                Connection watcher = DatabaseRoles.application()) {
            holder.setAutoCommit(false);
            execute(holder, "SELECT 1 FROM reconciliation.external_item WHERE id = ?"
                    + " FOR SHARE", itemId);

            Future<Matching.SweepResult> sweep =
                    sweeper.submit(() -> matching().sweep());
            // Wait until the leg is held at the row lock (or, under a mutant that
            // dropped the lock, until it ran to its wrong end - both proceed below).
            watcher.setAutoCommit(true);
            for (int poll = 0; poll < 300 && !sweep.isDone(); poll++) {
                long waiting;
                try (PreparedStatement read =
                        watcher.prepareStatement(
                                "SELECT count(*) FROM pg_stat_activity WHERE"
                                        + " wait_event_type = 'Lock'")) {
                    try (ResultSet rows = read.executeQuery()) {
                        rows.next();
                        waiting = rows.getLong(1);
                    }
                }
                if (waiting > 0) {
                    break;
                }
                Thread.sleep(100);
            }
            openExpectation(key, 33_00, FAR_FUTURE);
            holder.rollback(); // Release the share lock: the leg proceeds.
            sweep.get();
        } finally {
            sweeper.shutdownNow();
        }

        assertThat(string("SELECT status FROM reconciliation.external_item WHERE id = ?",
                itemId))
                .as("judged on the locked row: allocated, never parked")
                .isEqualTo("MATCHED");
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item WHERE"
                + " external_item_id = ?", itemId)).isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE"
                + " external_item_id = ?", itemId)).isZero();
    }

    // ----------------------------------------------------------------- the arbiter

    @Test
    @Order(9)
    @DisplayName("the one-open seat is the database's: a second raw OPEN MISSING_EXTERNAL"
            + " on one expectation is refused by break_one_open_per_expectation, for ANY"
            + " writer the ageing conditional misses")
    void theOneOpenSeatRefusesAnyWriter() throws SQLException {
        UUID subject = openExpectation("RAW-SEAT-" + UUID.randomUUID(), 1_00, FAR_FUTURE);
        String insert = "INSERT INTO reconciliation.break (id, type, cause, status,"
                + " severity, source_id, rule_set_id, expectation_id,"
                + " value_at_issue_minor, currency, scale, raised_at,"
                + " status_changed_at, correlation_id) VALUES (?, 'MISSING_EXTERNAL',"
                + " 'EXPECTATION_OVERDUE', 'OPEN', 'MEDIUM', ?, ?, ?, 100, 'EUR', 2,"
                + " now(), now(), 'p8-tsk-013-raw-seat')";
        try (Connection first = DatabaseRoles.application()) {
            first.setAutoCommit(false);
            execute(first, insert, IDS.next(), SOURCE, RULE_SET, subject);
            first.commit();
        }
        try (Connection second = DatabaseRoles.application()) {
            second.setAutoCommit(false);
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> execute(second, insert, IDS.next(), SOURCE, RULE_SET,
                                    subject))
                    .as("the partial unique arbitrates for any writer, raw SQL included")
                    .hasStackTraceContaining("break_one_open_per_expectation");
            second.rollback();
        }
    }

    // ----------------------------------------------------------------- seeding

    private record Line(
            int lineNo,
            ExternalLineType type,
            ExpectationDirection direction,
            long minor,
            ItemKeyKind keyKind,
            String keyValue) {}

    private static Line line(
            int lineNo,
            ExternalLineType type,
            ExpectationDirection direction,
            long minor,
            ItemKeyKind keyKind,
            String keyValue) {
        return new Line(lineNo, type, direction, minor, keyKind, keyValue);
    }

    private static UUID seedRun(UUID sourceId, UUID ruleSetId, Line... lines)
            throws SQLException {
        UUID runId = IDS.next();
        long sequence = SEQUENCES.incrementAndGet();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            runs.birth(
                    app,
                    new ReconciliationRuns.NewRun(
                            runId,
                            sourceId,
                            Optional.of(IDS.next()),
                            RunKind.BATCH,
                            ruleSetId,
                            SETTLED_ON,
                            Optional.of(sequence),
                            lines.length,
                            Optional.empty(),
                            Optional.empty(),
                            PLATFORM,
                            Instant.now(CLOCK),
                            CorrelationId.generate(IDS)));
            List<ExternalItems.NewItem> newItems = new ArrayList<>();
            for (Line line : lines) {
                byte[] fingerprint = new byte[32];
                new SecureRandom().nextBytes(fingerprint);
                newItems.add(
                        new ExternalItems.NewItem(
                                IDS.next(),
                                runId,
                                sourceId,
                                IDS.next(),
                                line.lineNo(),
                                line.type(),
                                line.direction(),
                                Money.ofPersisted(line.minor(), EUR, 2),
                                AccountPurpose.SETTLEMENT_CLEARING,
                                SETTLED_ON,
                                Optional.of(SETTLED_ON),
                                Optional.of(SETTLED_ON),
                                fingerprint,
                                Map.of(line.keyKind(), line.keyValue()),
                                Instant.now(CLOCK),
                                CorrelationId.generate(IDS)));
            }
            items.birthAll(app, PLATFORM, newItems);
            app.commit();
        }
        return runId;
    }

    private static UUID openExpectation(String keyValue, long minor, LocalDate expectedBy)
            throws SQLException {
        String operationRef =
                "op-" + keyValue.substring(0, Math.min(24, keyValue.length())) + "-"
                        + IDS.next().toString().substring(24);
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            UUID position =
                    new JdbcLedgerAccountStore()
                            .findOperational(app, AccountPurpose.SETTLEMENT_CLEARING, EUR)
                            .orElseThrow()
                            .id()
                            .value();
            expectations.open(
                    app,
                    new NewExpectation(
                            ExpectationKind.CARD_CAPTURE,
                            operationRef,
                            "p8t13:" + operationRef,
                            SOURCE,
                            AccountPurpose.SETTLEMENT_CLEARING,
                            position,
                            ExpectationDirection.INBOUND,
                            Money.ofPersisted(minor, EUR, 2),
                            Optional.of(IDS.next()),
                            SETTLED_ON,
                            Optional.empty(),
                            expectedBy,
                            RULE_SET,
                            List.of(new NewExpectation.ExpectationKey(
                                    KeyKind.PSP_CAPTURE_REF, keyValue)),
                            PLATFORM,
                            // The REAL clock: rematch reads opened-after-decided, and
                            // the decisions are deliberately stamped months back.
                            Instant.now(),
                            CorrelationId.generate(IDS)));
            app.commit();
            try (PreparedStatement read =
                    app.prepareStatement(
                            "SELECT id FROM reconciliation.expectation WHERE"
                                    + " operation_ref = ?")) {
                read.setString(1, operationRef);
                try (ResultSet row = read.executeQuery()) {
                    row.next();
                    return row.getObject("id", UUID.class);
                }
            }
        }
    }

    /** The stored window moved, never the clock: expiry stays a database-clock fact. */
    private static void expireGrace(UUID runId) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            execute(app, "UPDATE reconciliation.external_item SET grace_until = now()"
                    + " - interval '1 hour' WHERE run_id = ?", runId);
            app.commit();
        }
    }

    // ----------------------------------------------------------------- plumbing

    private static void execute(Connection unitOfWork, String sql, Object... args) {
        try (PreparedStatement statement = unitOfWork.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.execute();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("statement failed: " + sql, failure);
        }
    }

    private String itemStatus(UUID runId, int lineNo) throws SQLException {
        return string("SELECT status FROM reconciliation.external_item WHERE run_id = ?"
                + " AND line_no = " + lineNo, runId);
    }

    private UUID itemId(UUID runId, int lineNo) throws SQLException {
        return (UUID) one("SELECT id FROM reconciliation.external_item WHERE run_id = ?"
                + " AND line_no = " + lineNo, runId);
    }

    private Object[] breakRow(UUID runId, int lineNo) throws SQLException {
        return row("SELECT b.type, b.cause, b.internal_classification FROM"
                + " reconciliation.break b JOIN reconciliation.external_item i ON"
                + " b.external_item_id = i.id WHERE i.run_id = ? AND i.line_no = "
                + lineNo, runId);
    }

    private long decisionCount(UUID runId, int lineNo) throws SQLException {
        return count("SELECT count(*) FROM reconciliation.match_decision d WHERE"
                + " d.external_item_id = (SELECT id FROM reconciliation.external_item"
                + " WHERE run_id = ? AND line_no = " + lineNo + ")", runId);
    }

    private String keyOf(UUID expectationId) throws SQLException {
        return string("SELECT key_value FROM reconciliation.expectation_key WHERE"
                + " expectation_id = ?", expectationId);
    }

    private List<String> entryLines(UUID entryId) throws SQLException {
        List<String> lines = new ArrayList<>();
        try (PreparedStatement read =
                application.prepareStatement(
                        "SELECT direction, ledger_account_id, amount_minor FROM"
                                + " ledger.journal_line WHERE entry_id = ?"
                                + " ORDER BY ledger_account_id, direction")) {
            read.setObject(1, entryId);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    lines.add(rows.getString("direction") + ":"
                            + rows.getObject("ledger_account_id") + ":"
                            + rows.getLong("amount_minor"));
                }
            }
        }
        return lines;
    }

    private long count(String sql, Object... args) throws SQLException {
        return ((Number) one(sql, args)).longValue();
    }

    private long scalar(String sql, Object... args) throws SQLException {
        return count(sql, args);
    }

    private String string(String sql, Object... args) throws SQLException {
        return (String) one(sql, args);
    }

    private Object[] row(String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = application.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                Object[] values = new Object[result.getMetaData().getColumnCount()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = result.getObject(i + 1);
                }
                return values;
            }
        }
    }

    private static Object one(String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = application.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? row.getObject(1) : null;
            }
        }
    }
}
