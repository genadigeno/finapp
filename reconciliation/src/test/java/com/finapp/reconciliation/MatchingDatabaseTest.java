package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * The run leg against the real schema, the real ledger and real commits (`P8-TSK-011`,
 * ADR-0068): a run walked in chunks and completed with its counts, decisions and candidate
 * snapshots explainable from stored rows alone, mismatches parked through `P8-TSK-010`'s
 * suspense with their breaks, ten sweepers converging on one effect WITH the advisory
 * try-lock and WITHOUT it (the lock only orders; the uniques, conditional edges and
 * deferred Σ triggers arbitrate), a poisoned item contained under its savepoint, and a run
 * blocked after consecutive chunk failures, holding its source visibly.
 *
 * <p>The suite owns a private source and rule set (seeded here, committed) so the sweeps'
 * commits never race another suite's fixtures. {@code AMBIGUOUS} is proven hermetically in
 * {@code MatchEngineTest}: `V002`'s {@code expectation_key_once} means one key reaches at
 * most one expectation per source, so no produced path reaches the edge yet.
 *
 * <p>Ordered: the last test deliberately leaves a {@code BLOCKED} run holding the private
 * source — exactly the visible hold it proves.
 */
@Tag("database")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the matcher's run leg (P8-TSK-011)")
class MatchingDatabaseTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-29T16:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor PLATFORM = new Actor("system", ActorType.SYSTEM);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final CurrencyCode GBP = CurrencyCode.of("GBP");
    private static final CurrencyCode CHF = CurrencyCode.of("CHF");
    private static final UUID SOURCE =
            UUID.fromString("01a0e2bc-8200-7011-8000-000000000011");
    private static final UUID RULE_SET =
            UUID.fromString("01a0e2bd-8300-7011-8000-000000000011");
    private static final LocalDate SETTLED_ON = LocalDate.parse("2026-09-25");
    private static final AtomicLong SEQUENCES = new AtomicLong(System.nanoTime() % 100_000);

    /** Lookup by planted prefix: the composed lookup is the app's; the seam is the port. */
    private static final InternalReferenceLookup LOOKUP =
            (unitOfWork, subject) -> {
                String reference =
                        subject.references().values().stream().findFirst().orElse("");
                if (reference.startsWith("POISON")) {
                    throw new IllegalStateException("planted poison");
                }
                if (reference.startsWith("TERM")) {
                    return new InternalReferenceLookup.InternalReference(
                            InternalClassification.TERMINAL,
                            Optional.of("op-term"),
                            Optional.of("VOIDED"));
                }
                return InternalReferenceLookup.InternalReference.unknown();
            };

    private static Connection application;
    private static JdbcMatchingStore store;
    private static JdbcReconciliationRuns runs;
    private static JdbcExternalItems items;
    private static JdbcExpectationRegister expectations;
    private static Matching matching;
    private static Matching matchingSmallChunks;

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
        store = new JdbcMatchingStore();
        runs = new JdbcReconciliationRuns();
        items = new JdbcExternalItems();
        expectations = new JdbcExpectationRegister(IDS);
        matching = matching(new Matching.Config(200, 2), false);
        matchingSmallChunks = matching(new Matching.Config(3, 2), false);
        seedPrivateRuleSet();
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.rollback();
            application.close();
        }
    }

    private static Matching matching(Matching.Config config, boolean bypassTheLock) {
        JdbcBreakRegister register =
                new JdbcBreakRegister(new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS);
        Suspense suspense =
                new Suspense(postingService(), new JdbcLedgerAccountStore(), IDS);
        if (bypassTheLock) {
            return new Matching(
                    store, new MatchingRules(), register, suspense,
                    ResolutionFixtures.resolutions(IDS, CLOCK),
                    LOOKUP,
                    new JdbcLedgerAccountStore(), new JdbcOutboxWriter(),
                    new JdbcAuditWriter(), IDS, CLOCK, config, runner()) {
                @Override
                boolean claimSource(Connection unitOfWork, UUID sourceId) {
                    return true; // The probe: the lock ORDERS, the arbiters decide.
                }
            };
        }
        return new Matching(
                store, new MatchingRules(), register, suspense,
                ResolutionFixtures.resolutions(IDS, CLOCK),
                LOOKUP,
                new JdbcLedgerAccountStore(), new JdbcOutboxWriter(),
                new JdbcAuditWriter(), IDS, CLOCK, config, runner());
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

    /** One committed transaction per call — what the app's runner bean does. */
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

    /** The private source's rule set v1 shape: capture, refund, and the fee CHECK. */
    private static void seedPrivateRuleSet() {
        runner().inTransaction(
                unitOfWork -> {
                    execute(unitOfWork,
                            "INSERT INTO reconciliation.rule_set (id, source_id, version,"
                                    + " status, funding_lag_days, gain_min_age_days,"
                                    + " effective_from, proposed_by, decided_by, reason,"
                                    + " created_at, correlation_id) VALUES (?, ?, 1,"
                                    + " 'ACTIVE', 2, 90, ?, 'test', 'test',"
                                    + " 'MatchingDatabaseTest private rule set', now(),"
                                    + " 'p8-tsk-011-test') ON CONFLICT (id) DO NOTHING",
                            RULE_SET, SOURCE, java.sql.Date.valueOf(SETTLED_ON));
                    execute(unitOfWork,
                            "INSERT INTO reconciliation.rule (rule_set_id, priority,"
                                    + " line_type, key_kind, expectation_kind, cardinality,"
                                    + " operation_anchored, grace_hours) VALUES"
                                    + " (?, 1, 'CAPTURE', 'PSP_CAPTURE_REF', 'CARD_CAPTURE',"
                                    + " 'ONE_TO_ONE', false, 48),"
                                    + " (?, 2, 'REFUND', 'PSP_REFUND_REF', 'CARD_REFUND',"
                                    + " 'ONE_TO_ONE', false, 48),"
                                    + " (?, 3, 'PROCESSING_FEE', 'ORIGINAL_REF', NULL,"
                                    + " 'CHECK', false, 48),"
                                    + " (?, 4, 'CAPTURE', 'ACQUIRER_REF', 'CARD_CAPTURE',"
                                    + " 'ONE_TO_ONE', false, 48)"
                                    + " ON CONFLICT DO NOTHING",
                            RULE_SET, RULE_SET, RULE_SET, RULE_SET);
                    execute(unitOfWork,
                            "INSERT INTO reconciliation.tolerance (rule_set_id, comparison,"
                                    + " currency, absolute_minor, days) VALUES"
                                    + " (?, 'SETTLEMENT_DATE_DAYS', NULL, NULL, 2)"
                                    + " ON CONFLICT DO NOTHING",
                            RULE_SET);
                    execute(unitOfWork,
                            "INSERT INTO reconciliation.severity_threshold (rule_set_id,"
                                    + " currency, high_value_minor) VALUES (?, 'EUR',"
                                    + " 100000), (?, 'GBP', 100000)"
                                    + " ON CONFLICT DO NOTHING",
                            RULE_SET, RULE_SET);
                    return null;
                });
    }

    // ----------------------------------------------------------------- the happy run

    @Test
    @Order(1)
    @DisplayName("a run is walked in chunks, allocations settle their expectations, the"
            + " waiting are graced, and completion carries its counts - explainable from"
            + " stored rows alone")
    void theRunAllocatesExplainsAndCompletes() throws SQLException {
        UUID settled = openExpectation("CAP-A", 100_00, EUR, ExpectationDirection.INBOUND);
        UUID settledToo =
                openExpectation("CAP-D", 100_00, EUR, ExpectationDirection.INBOUND);
        UUID runId =
                seedRun(
                        line(1, ExternalLineType.CAPTURE, 100_00, EUR,
                                ItemKeyKind.PSP_CAPTURE_REF, "CAP-A", SETTLED_ON),
                        line(2, ExternalLineType.CAPTURE, 50_00, EUR,
                                ItemKeyKind.PSP_CAPTURE_REF, "CAP-NOBODY", SETTLED_ON),
                        line(3, ExternalLineType.PROCESSING_FEE, 1_50, EUR,
                                ItemKeyKind.ORIGINAL_REF, "ORIG-X", SETTLED_ON),
                        line(4, ExternalLineType.CAPTURE, 100_00, EUR,
                                ItemKeyKind.PSP_CAPTURE_REF, "CAP-D", SETTLED_ON),
                        line(5, ExternalLineType.CAPTURE, 25_00, EUR,
                                ItemKeyKind.PSP_CAPTURE_REF, "CAP-NOBODY2", SETTLED_ON));

        Matching.SweepResult swept = matchingSmallChunks.sweep();

        assertThat(swept.decided()).isGreaterThanOrEqualTo(5);
        assertThat(status(runId)).isEqualTo("COMPLETED");
        assertThat(scalar("SELECT cursor FROM reconciliation.reconciliation_batch"
                + " WHERE id = ?", runId)).isEqualTo(5L);
        // Two chunks of three: the cursor advanced per chunk, IN_PROGRESS on the record.
        assertThat(count("SELECT count(*) FROM"
                + " reconciliation.reconciliation_batch_event WHERE run_id = ? AND"
                + " to_status = 'IN_PROGRESS'", runId)).isEqualTo(1);

        // The decisions, per outcome.
        Map<DecisionOutcome, Long> counts = store.outcomeCounts(application, runId);
        assertThat(counts).containsEntry(DecisionOutcome.MATCHED, 2L);
        assertThat(counts).containsEntry(DecisionOutcome.UNMATCHED, 2L);
        // Since P8-TSK-012 the fee line is JUDGED, not left waiting.
        assertThat(counts).containsEntry(DecisionOutcome.CHECKED, 1L);

        // Both expectations settled through the live path, once each, with the event.
        for (UUID expectation : List.of(settled, settledToo)) {
            assertThat(string("SELECT status FROM reconciliation.expectation WHERE"
                    + " id = ?", expectation)).isEqualTo("SETTLED");
            assertThat(count("SELECT count(*) FROM reconciliation.expectation_event"
                    + " WHERE expectation_id = ? AND event_type = 'ALLOCATED'",
                    expectation)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                    + " expectation_id = ?", expectation)).isEqualTo(1);
        }
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.SettlementExpectationSettled' AND aggregate_id IN"
                + " (?, ?)", settled, settledToo)).isEqualTo(2);

        // Completion: the acting-only audit record and the event, once.
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.RunCompleted' AND target_id = ?", runId.toString()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.ReconciliationRunCompleted' AND aggregate_id = ?",
                runId)).isEqualTo(1);

        // The waiting: a graced clock where a landed rule reached nothing; the fee line
        // is CHECKED - judged at once, no clock, never parked (P8-TSK-012).
        assertThat(count("SELECT count(*) FROM reconciliation.external_item WHERE"
                + " run_id = ? AND status = 'UNMATCHED' AND grace_until IS NOT NULL",
                runId)).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM reconciliation.external_item WHERE"
                + " run_id = ? AND line_type = 'PROCESSING_FEE' AND status = 'CHECKED'"
                + " AND grace_until IS NULL", runId)).isEqualTo(1);

        // The explanation, from stored rows alone: the snapshot names the rule, the key,
        // the applied tolerance and what it saw.
        UUID decisionId = (UUID) queryOne("SELECT d.id FROM reconciliation.match_decision"
                + " d JOIN reconciliation.external_item i ON i.id = d.external_item_id"
                + " WHERE d.run_id = ? AND i.line_no = 1", runId);
        MatchingStore.DecisionRow decision =
                store.decision(application, decisionId).orElseThrow();
        assertThat(decision.outcome()).isEqualTo("MATCHED");
        assertThat(decision.origin()).isEqualTo("RUN");
        assertThat(decision.ruleSetId()).isEqualTo(RULE_SET);
        assertThat(decision.rulePriority()).contains(1);
        assertThat(decision.strategy()).contains("ONE_TO_ONE");
        assertThat(decision.matchedKeyKind()).contains("PSP_CAPTURE_REF");
        assertThat(decision.claimantRank()).contains(1);
        assertThat(decision.claimantCount()).contains(1);
        assertThat(decision.timingToleranceDays()).contains(2);
        List<MatchingStore.CandidateRow> candidates =
                store.candidatesOf(application, decisionId);
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).expectationId()).isEqualTo(settled);
        assertThat(candidates.get(0).remainderBeforeMinor()).isEqualTo(100_00L);
        List<MatchingStore.AllocationRow> allocations =
                store.allocationsOfDecision(application, decisionId);
        assertThat(allocations).hasSize(1);
        assertThat(allocations.get(0).amount().minorUnits()).isEqualTo(100_00L);
        assertThat(store.allocation(application, allocations.get(0).id())).isPresent();
        assertThat(store.run(application, runId).orElseThrow().status())
                .isEqualTo(RunStatus.COMPLETED);
    }

    // ----------------------------------------------------------------- mismatches

    @Test
    @Order(2)
    @DisplayName("mismatches park with their breaks: excess over the remainder, an"
            + " exhausted expectation in the SAME chunk (INV-REC-07), a repeated"
            + " fingerprint, a contradicted direction, a terminal refund, an under-paid"
            + " expectation, and a late match's timing observation")
    void mismatchesParkWithTheirBreaks() throws SQLException {
        UUID over = openExpectation("CAP-OVER", 60_00, EUR, ExpectationDirection.INBOUND);
        UUID twice = openExpectation("CAP-TWICE", 100_00, EUR, ExpectationDirection.INBOUND);
        UUID foreign = openExpectation("CAP-GBP", 100_00, GBP, ExpectationDirection.INBOUND);
        UUID under = openExpectation("CAP-UNDER", 100_00, EUR, ExpectationDirection.INBOUND);
        UUID late = openExpectation("CAP-LATE", 100_00, EUR, ExpectationDirection.INBOUND);

        byte[] repeated = fingerprint();
        UUID runId =
                seedRun(
                        new Line(1, ExternalLineType.CAPTURE,
                                ExpectationDirection.INBOUND, 100_00, EUR,
                                ItemKeyKind.PSP_CAPTURE_REF, "CAP-OVER", fingerprint(),
                                SETTLED_ON),
                        new Line(2, ExternalLineType.CAPTURE,
                                ExpectationDirection.INBOUND, 100_00, EUR,
                                ItemKeyKind.PSP_CAPTURE_REF, "CAP-TWICE", repeated,
                                SETTLED_ON),
                        new Line(3, ExternalLineType.CAPTURE,
                                ExpectationDirection.INBOUND, 100_00, EUR,
                                ItemKeyKind.PSP_CAPTURE_REF, "CAP-TWICE", fingerprint(),
                                SETTLED_ON),
                        new Line(4, ExternalLineType.CAPTURE,
                                ExpectationDirection.INBOUND, 100_00, EUR,
                                ItemKeyKind.PSP_CAPTURE_REF, "CAP-GBP", fingerprint(),
                                SETTLED_ON),
                        new Line(5, ExternalLineType.CAPTURE,
                                ExpectationDirection.INBOUND, 100_00, EUR,
                                ItemKeyKind.PSP_CAPTURE_REF, "CAP-REPEAT", repeated,
                                SETTLED_ON),
                        new Line(6, ExternalLineType.CAPTURE,
                                ExpectationDirection.OUTBOUND, 100_00, EUR,
                                ItemKeyKind.PSP_CAPTURE_REF, "CAP-OVER", fingerprint(),
                                SETTLED_ON),
                        new Line(7, ExternalLineType.REFUND,
                                ExpectationDirection.OUTBOUND, 30_00, EUR,
                                ItemKeyKind.PSP_REFUND_REF, "TERM-R1", fingerprint(),
                                SETTLED_ON),
                        new Line(8, ExternalLineType.CAPTURE,
                                ExpectationDirection.INBOUND, 40_00, EUR,
                                ItemKeyKind.PSP_CAPTURE_REF, "CAP-UNDER", fingerprint(),
                                SETTLED_ON),
                        new Line(9, ExternalLineType.CAPTURE,
                                ExpectationDirection.INBOUND, 100_00, EUR,
                                ItemKeyKind.PSP_CAPTURE_REF, "CAP-LATE", fingerprint(),
                                SETTLED_ON.plusDays(10)));

        matching.sweep();

        assertThat(status(runId)).isEqualTo("COMPLETED");

        // Line 1: 60.00 allocated, the 40.00 excess parked under AMOUNT_MISMATCH.
        assertThat(scalar("SELECT allocated_minor FROM reconciliation.expectation WHERE"
                + " id = ?", over)).isEqualTo(60_00L);
        assertThat(itemStatus(runId, 1)).isEqualTo("PARKED");
        assertThat(suspenseRemainder(runId, 1)).isEqualTo(40_00L);
        assertThat(breakRow(runId, 1, "AMOUNT_MISMATCH", "AMOUNT_DIFFERS")).isEqualTo(1);

        // Lines 2 and 3, ONE chunk: the second claimant sees the DIMINISHED remainder -
        // one allocation, never two (INV-REC-07 inside the chunk).
        assertThat(scalar("SELECT allocated_minor FROM reconciliation.expectation WHERE"
                + " id = ?", twice)).isEqualTo(100_00L);
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                + " expectation_id = ?", twice)).isEqualTo(1);
        assertThat(itemStatus(runId, 2)).isEqualTo("MATCHED");
        assertThat(itemStatus(runId, 3)).isEqualTo("PARKED");
        assertThat(breakRow(runId, 3, "DUPLICATE_EXTERNAL", "EXPECTATION_EXHAUSTED"))
                .isEqualTo(1);

        // Line 4: the key reached another currency's expectation.
        assertThat(itemStatus(runId, 4)).isEqualTo("PARKED");
        assertThat(breakRow(runId, 4, "CURRENCY_MISMATCH", "CURRENCY_DIFFERS")).isEqualTo(1);
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                foreign)).isEqualTo("OPEN");

        // Line 5: line 2's exact bytes, later in claimant order - definitive whatever its
        // keys still reach.
        assertThat(itemStatus(runId, 5)).isEqualTo("PARKED");
        assertThat(breakRow(runId, 5, "DUPLICATE_EXTERNAL", "REPEATED_FINGERPRINT"))
                .isEqualTo(1);

        // Line 6: an OUTBOUND line against an INBOUND record.
        assertThat(itemStatus(runId, 6)).isEqualTo("PARKED");
        assertThat(breakRow(runId, 6, "REVERSAL_MISMATCH", "DIRECTION_CONTRADICTED"))
                .isEqualTo(1);

        // Line 7: no candidate, and the lookup answers TERMINAL - a refund line against a
        // refund not completed, the frozen answer on the break.
        assertThat(itemStatus(runId, 7)).isEqualTo("PARKED");
        assertThat(breakRow(runId, 7, "REFUND_MISMATCH", "REFUND_CONTRADICTED")).isEqualTo(1);
        assertThat(string("SELECT b.internal_classification FROM reconciliation.break b"
                + " JOIN reconciliation.external_item i ON b.external_item_id = i.id"
                + " WHERE i.run_id = ? AND i.line_no = 7", runId)).isEqualTo("TERMINAL");

        // Line 8: the item allocates whole; the expectation's shortfall is a break on the
        // EXPECTATION, and nothing parks - the value is an open remainder, not suspense.
        assertThat(itemStatus(runId, 8)).isEqualTo("MATCHED");
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                under)).isEqualTo("PARTIALLY_SETTLED");
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE"
                + " expectation_id = ? AND type = 'AMOUNT_MISMATCH'", under)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item s JOIN"
                + " reconciliation.external_item i ON s.external_item_id = i.id WHERE"
                + " i.run_id = ? AND i.line_no = 8", runId)).isEqualTo(0);

        // Line 9: ten days past expected_by, tolerance two - matched AND observed, the
        // zero-value TIMING_DIFFERENCE naming its decision (INV-SET-03).
        assertThat(itemStatus(runId, 9)).isEqualTo("MATCHED");
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                late)).isEqualTo("SETTLED");
        assertThat(count("SELECT count(*) FROM reconciliation.break b JOIN"
                + " reconciliation.match_decision d ON b.decision_id = d.id WHERE"
                + " d.run_id = ? AND b.type = 'TIMING_DIFFERENCE' AND b.cause ="
                + " 'LATE_MATCH' AND b.value_at_issue_minor = 0", runId)).isEqualTo(1);
        UUID lateDecision = (UUID) queryOne("SELECT d.id FROM"
                + " reconciliation.match_decision d JOIN reconciliation.external_item i"
                + " ON i.id = d.external_item_id WHERE d.run_id = ? AND i.line_no = 9",
                runId);
        assertThat(store.decision(application, lateDecision).orElseThrow()
                .dateDeviationDays()).contains(7);
    }

    // ----------------------------------------------------------------- the races

    @Test
    @Order(3)
    @DisplayName("ten sweepers, one effect: every item one decision, every expectation one"
            + " allocation, the run completed once")
    void tenSweepersOneEffect() throws Exception {
        UUID runId = race(matching);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.RunCompleted' AND target_id = ?", runId.toString()))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.ReconciliationRunCompleted' AND aggregate_id = ?",
                runId)).isEqualTo(1);
        // With the lock, losers SKIP: one decision per item, exactly.
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " run_id = ?", runId)).isEqualTo(10);
    }

    @Test
    @Order(4)
    @DisplayName("the lock only ORDERS: bypassed, ten sweepers still produce one"
            + " allocation per pair, one settle per expectation and one completion - the"
            + " uniques, the conditional edges and the deferred sums arbitrate")
    void theLockOnlyOrders() throws Exception {
        UUID runId = race(matching(new Matching.Config(200, 2), true));
        // A bypassed loser may record its losing evaluation - money may not move twice.
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.RunCompleted' AND target_id = ?", runId.toString()))
                .isEqualTo(1);
    }

    /** Ten concurrent sweeps over a fresh ten-item run; the money-side asserts shared. */
    private UUID race(Matching racerShape) throws Exception {
        List<UUID> raced = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        String tag = Integer.toHexString(new SecureRandom().nextInt());
        for (int i = 0; i < 10; i++) {
            String key = "CAP-RACE-" + tag + "-" + i;
            raced.add(openExpectation(key, 100_00, EUR, ExpectationDirection.INBOUND));
            lines.add(line(i + 1, ExternalLineType.CAPTURE, 100_00, EUR,
                    ItemKeyKind.PSP_CAPTURE_REF, key, SETTLED_ON));
        }
        UUID runId = seedRun(lines.toArray(Line[]::new));

        ExecutorService racers = Executors.newFixedThreadPool(10);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> outcomes = new ArrayList<>();
            for (int racer = 0; racer < 10; racer++) {
                outcomes.add(racers.submit(() -> {
                    start.await();
                    racerShape.sweep();
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

        assertThat(status(runId)).isEqualTo("COMPLETED");
        for (UUID expectation : raced) {
            assertThat(scalar("SELECT allocated_minor FROM reconciliation.expectation"
                    + " WHERE id = ?", expectation))
                    .as("one allocation's worth, never two")
                    .isEqualTo(100_00L);
            assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                    + " expectation_id = ?", expectation)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM reconciliation.expectation_event"
                    + " WHERE expectation_id = ? AND event_type = 'ALLOCATED'",
                    expectation)).isEqualTo(1);
        }
        assertThat(count("SELECT count(*) FROM reconciliation.external_item WHERE"
                + " run_id = ? AND status = 'MATCHED'", runId)).isEqualTo(10);
        return runId;
    }

    // ----------------------------------------------------------------- the record

    @Test
    @Order(5)
    @DisplayName("the record binds every writer - a raw completion over PENDING and a raw"
            + " over-allocation refused at commit, decisions and allocations immutable -"
            + " the ARN reaches through its alias, a later allocation moves the touched"
            + " break's residual, and a stored decision re-decides from its snapshot")
    void theRecordBindsAndExplains() throws Exception {
        // THE ALIAS (ADR-0068's local two-hop): an item quoting only the ARN reaches the
        // CARD_ATTEMPT anchor's expectation.
        String attempt = "att-" + UUID.randomUUID().toString().substring(0, 12);
        String arn = "2401230000" + (System.nanoTime() % 1_000_000_0L);
        UUID anchored =
                openExpectation("arn-" + attempt, 50_00, EUR,
                        ExpectationDirection.INBOUND, KeyKind.CARD_ATTEMPT, attempt);
        runner().inTransaction(unitOfWork -> {
            execute(unitOfWork,
                    "INSERT INTO reconciliation.reference_alias (source_id, key_kind,"
                            + " key_value, anchor_kind, anchor_value, registered_at,"
                            + " correlation_id) VALUES (?, 'ACQUIRER_REF', ?,"
                            + " 'CARD_ATTEMPT', ?, now(), 'p8t11-alias')",
                    SOURCE, arn, attempt);
            return null;
        });
        // THE RESIDUAL: an under-payment leaves a break judging a 60.00 remainder...
        UUID judged = openExpectation("CAP-RES", 100_00, EUR, ExpectationDirection.INBOUND);
        UUID underRun =
                seedRun(
                        new Line(1, ExternalLineType.CAPTURE, ExpectationDirection.INBOUND,
                                50_00, EUR, ItemKeyKind.ACQUIRER_REF, arn, fingerprint(),
                                SETTLED_ON),
                        line(2, ExternalLineType.CAPTURE, 40_00, EUR,
                                ItemKeyKind.PSP_CAPTURE_REF, "CAP-RES", SETTLED_ON));
        matching.sweep();
        assertThat(status(underRun)).isEqualTo("COMPLETED");
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                anchored)).as("the ARN reached through the alias").isEqualTo("SETTLED");
        UUID aliasDecision = (UUID) queryOne("SELECT d.id FROM"
                + " reconciliation.match_decision d JOIN reconciliation.external_item i"
                + " ON i.id = d.external_item_id WHERE d.run_id = ? AND i.line_no = 1",
                underRun);
        assertThat(store.decision(application, aliasDecision).orElseThrow()
                .matchedKeyKind()).contains("ACQUIRER_REF");
        UUID staleBreak = (UUID) queryOne("SELECT id FROM reconciliation.break WHERE"
                + " expectation_id = ? AND type = 'AMOUNT_MISMATCH'", judged);
        assertThat(scalar("SELECT residual_version FROM reconciliation.break WHERE"
                + " id = ?", staleBreak))
                .as("born after its own allocation's bump: version 0, honestly")
                .isEqualTo(0L);
        // ...and the LATER allocation that moves the judged remainder moves the counter.
        UUID settlingRun =
                seedRun(line(1, ExternalLineType.CAPTURE, 60_00, EUR,
                        ItemKeyKind.PSP_CAPTURE_REF, "CAP-RES", SETTLED_ON));
        matching.sweep();
        assertThat(status(settlingRun)).isEqualTo("COMPLETED");
        assertThat(scalar("SELECT residual_version FROM reconciliation.break WHERE"
                + " id = ?", staleBreak))
                .as("a resolution judged on the old residual must see it moved (ADR-0071)")
                .isEqualTo(1L);

        // THE RE-DECIDE (INV-REC-04 as amended): the stored snapshot, the pinned rule and
        // the item's stored facts reproduce the under-payment decision exactly.
        UUID storedDecisionId = (UUID) queryOne("SELECT d.id FROM"
                + " reconciliation.match_decision d JOIN reconciliation.external_item i"
                + " ON i.id = d.external_item_id WHERE d.run_id = ? AND i.line_no = 2",
                underRun);
        MatchingStore.DecisionRow stored =
                store.decision(application, storedDecisionId).orElseThrow();
        List<MatchingStore.CandidateRow> snapshot =
                store.candidatesOf(application, storedDecisionId);
        assertThat(snapshot).as("every landed decision carries its snapshot").isNotEmpty();
        MatchingStore.CandidateRow seen = snapshot.get(0);
        Object[] itemFacts = row("SELECT line_type, direction, amount_minor, currency,"
                + " scale, business_date, settlement_date FROM"
                + " reconciliation.external_item WHERE id = ?", stored.externalItemId());
        LocalDate settlement = ((java.sql.Date) itemFacts[6]).toLocalDate();
        MatchEngine.ItemFacts replayedItem =
                new MatchEngine.ItemFacts(
                        stored.externalItemId(),
                        ExternalLineType.valueOf((String) itemFacts[0]),
                        ExpectationDirection.valueOf((String) itemFacts[1]),
                        Money.ofPersisted(
                                ((Number) itemFacts[2]).longValue(),
                                CurrencyCode.of(((String) itemFacts[3]).trim()),
                                ((Number) itemFacts[4]).intValue()),
                        ((java.sql.Date) itemFacts[5]).toLocalDate(),
                        Optional.of(settlement),
                        false);
        // expected_by is not part of the snapshot; the stored deviation implies it.
        LocalDate expectedBy =
                settlement.minusDays(stored.dateDeviationDays().orElseThrow());
        MatchEngine.Verdict replayed =
                MatchEngine.decide(
                        replayedItem,
                        Optional.of(new MatchEngine.FiredRule(
                                stored.rulePriority().orElseThrow(),
                                KeyKind.valueOf(stored.matchedKeyKind().orElseThrow()),
                                Optional.of(ExpectationKind.CARD_CAPTURE),
                                Cardinality.valueOf(stored.strategy().orElseThrow()),
                                48,
                                List.of(new MatchEngine.HitFacts(
                                        seen.expectationId(),
                                        ExpectationKind.CARD_CAPTURE,
                                        ExpectationDirection.valueOf(seen.direction()),
                                        seen.amount(),
                                        seen.remainderBeforeMinor(),
                                        seen.openedAt(),
                                        expectedBy,
                                        KeyKind.valueOf(seen.keyKind()),
                                        "op-replay")))),
                        true,
                        stored.timingToleranceDays().orElseThrow());
        assertThat(replayed.kind()).isEqualTo(MatchEngine.VerdictKind.ALLOCATE);
        assertThat(replayed.allocation().orElseThrow().minorUnits())
                .as("the stored inputs reproduce the stored allocation")
                .isEqualTo(store.allocationsOfDecision(application, storedDecisionId)
                        .get(0).amount().minorUnits());
        assertThat(replayed.candidate().orElseThrow().expectationId())
                .isEqualTo(seen.expectationId());

        // THE RAW COMPLETION: a run never completes over a PENDING item, for any writer.
        UUID rawRun = seedRun(line(1, ExternalLineType.CAPTURE, 10_00, EUR,
                ItemKeyKind.PSP_CAPTURE_REF, "CAP-RAW-" + UUID.randomUUID(), SETTLED_ON));
        try (Connection raw = DatabaseRoles.application()) {
            raw.setAutoCommit(false);
            execute(raw, "UPDATE reconciliation.reconciliation_batch SET status ="
                    + " 'COMPLETED', status_changed_at = now() WHERE id = ?", rawRun);
            assertThatThrownBy(raw::commit)
                    .as("the deferred trigger judges the raw edge at commit")
                    .hasMessageContaining("never completes with an item PENDING");
        }
        matching.sweep(); // Disposed properly, so the source is not held for later tests.
        assertThat(status(rawRun)).isEqualTo("COMPLETED");

        // THE RAW OVER-ALLOCATION: allocated_minor without its allocation rows, refused
        // at commit by the deferred sum for every writer the grants admit. A FRESH
        // expectation: on a settled one V002's conservation CHECK refuses first, so only
        // here is the sum trigger the arbiter under probe.
        UUID sigma = openExpectation(
                "CAP-SIGMA-" + UUID.randomUUID().toString().substring(0, 8), 100_00, EUR,
                ExpectationDirection.INBOUND);
        try (Connection raw = DatabaseRoles.application()) {
            raw.setAutoCommit(false);
            execute(raw, "UPDATE reconciliation.expectation SET allocated_minor ="
                    + " allocated_minor + 5 WHERE id = ?", sigma);
            assertThatThrownBy(raw::commit)
                    .as("recording without allocating is unrepresentable (INV-REC-07)")
                    .hasMessageContaining("INV-REC-07");
        }

        // THE PAIR UNIQUE, probed directly (the P8-TSK-008 live-unique precedent): two
        // raw positive allocations for one (item, expectation), sized so the sum
        // discipline and the conservation CHECK would both tolerate them - only the
        // unique can refuse the second, for ANY writer.
        try (Connection raw = DatabaseRoles.application()) {
            raw.setAutoCommit(false);
            String insert = "INSERT INTO reconciliation.allocation (id, decision_id,"
                    + " external_item_id, expectation_id, amount_minor, currency, scale,"
                    + " reverses_allocation_id, created_at, correlation_id) VALUES (?,"
                    + " ?, ?, ?, 1, 'EUR', 2, NULL, now(), 'p8t11-unique-probe')";
            execute(raw, insert, IDS.next(), storedDecisionId,
                    stored.externalItemId(), sigma);
            assertThatThrownBy(() -> execute(raw, insert, IDS.next(), storedDecisionId,
                    stored.externalItemId(), sigma))
                    .as("at most one positive allocation per pair, for any writer"
                            + " (INV-REC-07)")
                    .hasStackTraceContaining("allocation_pair_once");
            raw.rollback();
        }

        // IMMUTABILITY, both writers: the application role holds no UPDATE or DELETE;
        // the migrator, whom no grant binds, is refused by the trigger.
        for (String sql :
                List.of(
                        "UPDATE reconciliation.match_decision SET outcome = 'ERRORED'"
                                + " WHERE id = '" + storedDecisionId + "'",
                        "DELETE FROM reconciliation.allocation WHERE decision_id = '"
                                + storedDecisionId + "'",
                        "UPDATE reconciliation.match_candidate SET remainder_before_minor"
                                + " = 0 WHERE decision_id = '" + storedDecisionId + "'")) {
            try (Connection raw = DatabaseRoles.application()) {
                raw.setAutoCommit(false);
                assertThatThrownBy(() -> execute(raw, sql))
                        .as("the application role holds no such grant")
                        .hasStackTraceContaining("permission denied");
            }
            try (Connection migrator = DatabaseRoles.migrator()) {
                migrator.setAutoCommit(false);
                assertThatThrownBy(() -> execute(migrator, sql))
                        .as("the trigger refuses the migrator too, in the invariant's"
                                + " own words")
                        .hasStackTraceContaining("INV-REC");
                migrator.rollback();
            }
        }
    }

    // ----------------------------------------------------------------- containment

    @Test
    @Order(6)
    @DisplayName("a poisoned item is contained under its savepoint: ERRORED on the record,"
            + " parked whole under PROCESSING_ERROR, and the rows behind it decided")
    void aPoisonedItemIsContained() throws SQLException {
        UUID before = openExpectation("CAP-P1", 100_00, EUR, ExpectationDirection.INBOUND);
        UUID after = openExpectation("CAP-P2", 100_00, EUR, ExpectationDirection.INBOUND);
        UUID runId =
                seedRun(
                        line(1, ExternalLineType.CAPTURE, 100_00, EUR,
                                ItemKeyKind.PSP_CAPTURE_REF, "CAP-P1", SETTLED_ON),
                        line(2, ExternalLineType.REFUND, 30_00, EUR,
                                ItemKeyKind.PSP_REFUND_REF, "POISON-1", SETTLED_ON),
                        line(3, ExternalLineType.CAPTURE, 100_00, EUR,
                                ItemKeyKind.PSP_CAPTURE_REF, "CAP-P2", SETTLED_ON));

        Matching.SweepResult swept = matching.sweep();

        assertThat(swept.blockedRuns()).isZero();
        assertThat(status(runId)).isEqualTo("COMPLETED");
        for (UUID expectation : List.of(before, after)) {
            assertThat(string("SELECT status FROM reconciliation.expectation WHERE"
                    + " id = ?", expectation))
                    .as("the rows around the poison are other people's money")
                    .isEqualTo("SETTLED");
        }
        assertThat(itemStatus(runId, 2)).isEqualTo("PARKED");
        assertThat(suspenseRemainder(runId, 2)).isEqualTo(30_00L);
        assertThat(breakRow(runId, 2, "PROCESSING_ERROR", "ITEM_ERRORED")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " run_id = ? AND outcome = 'ERRORED'", runId)).isEqualTo(1);
    }

    @Test
    @Order(7)
    @DisplayName("consecutive chunk failures block the run with its CRITICAL break, and"
            + " the blocked run holds its source visibly - a later run stays untouched")
    void consecutiveFailuresBlockTheRunVisibly() throws Exception {
        // CHF has no seeded clearing account: the park fails past containment, so the
        // chunk itself fails - twice, then BLOCKED (blockAfterFailures = 2).
        UUID poisonedRun =
                seedRun(
                        line(1, ExternalLineType.REFUND, 30_00, CHF,
                                ItemKeyKind.PSP_REFUND_REF, "TERM-CHF", SETTLED_ON));
        assertThat(matching.sweep().blockedRuns()).isZero();
        assertThat(status(poisonedRun)).isNotEqualTo("BLOCKED");
        assertThat(matching.sweep().blockedRuns()).isEqualTo(1);

        assertThat(status(poisonedRun)).isEqualTo("BLOCKED");
        assertThat(scalar("SELECT failures FROM reconciliation.reconciliation_batch"
                + " WHERE id = ?", poisonedRun)).isEqualTo(2L);
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE run_id = ?"
                + " AND type = 'PROCESSING_ERROR' AND cause = 'RUN_BLOCKED' AND"
                + " value_at_issue_minor = 0", poisonedRun)).isEqualTo(1);

        // The hold: a later, perfectly matchable run of this source is NOT walked.
        openExpectation("CAP-HELD", 100_00, EUR, ExpectationDirection.INBOUND);
        UUID heldRun =
                seedRun(line(1, ExternalLineType.CAPTURE, 100_00, EUR,
                        ItemKeyKind.PSP_CAPTURE_REF, "CAP-HELD", SETTLED_ON));
        matching.sweep();
        assertThat(status(heldRun)).isEqualTo("OPEN");
        assertThat(itemStatus(heldRun, 1)).isEqualTo("PENDING");

        // The gauges' reads see the hold, per source (P8-TSK-011's three run gauges).
        JdbcRunReadings readings = new JdbcRunReadings();
        assertThat(readings.blockedCountBySource(application).get(SOURCE)).isEqualTo(1L);
        assertThat(readings.pendingCountBySource(application).get(SOURCE))
                .isGreaterThanOrEqualTo(1L);
        assertThat(readings.oldestPendingBySource(application)).containsKey(SOURCE);
    }

    // ----------------------------------------------------------------- seeding

    private record Line(
            int lineNo,
            ExternalLineType type,
            ExpectationDirection direction,
            long minor,
            CurrencyCode currency,
            ItemKeyKind keyKind,
            String keyValue,
            byte[] print,
            LocalDate settlementDate) {}

    private static Line line(
            int lineNo,
            ExternalLineType type,
            long minor,
            CurrencyCode currency,
            ItemKeyKind keyKind,
            String keyValue,
            LocalDate settlementDate) {
        return new Line(lineNo, type, ExpectationDirection.INBOUND, minor, currency,
                keyKind, keyValue, fingerprint(), settlementDate);
    }

    private static byte[] fingerprint() {
        byte[] print = new byte[32];
        new SecureRandom().nextBytes(print);
        return print;
    }

    /** A committed BATCH run with its PENDING items — what acceptance births. */
    private static UUID seedRun(Line... lines) {
        UUID runId = IDS.next();
        long sequence = SEQUENCES.incrementAndGet();
        return runner().inTransaction(unitOfWork -> {
            runs.birth(
                    unitOfWork,
                    new ReconciliationRuns.NewRun(
                            runId,
                            SOURCE,
                            Optional.of(IDS.next()),
                            RunKind.BATCH,
                            RULE_SET,
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
                newItems.add(
                        new ExternalItems.NewItem(
                                IDS.next(),
                                runId,
                                SOURCE,
                                IDS.next(),
                                line.lineNo(),
                                line.type(),
                                line.direction(),
                                Money.ofPersisted(line.minor(), line.currency(), 2),
                                AccountPurpose.SETTLEMENT_CLEARING,
                                SETTLED_ON,
                                Optional.of(line.settlementDate()),
                                Optional.of(line.settlementDate()),
                                line.print(),
                                Map.of(line.keyKind(), line.keyValue()),
                                Instant.now(CLOCK),
                                CorrelationId.generate(IDS)));
            }
            items.birthAll(unitOfWork, PLATFORM, newItems);
            return runId;
        });
    }

    private static UUID openExpectation(
            String keyValue, long minor, CurrencyCode currency,
            ExpectationDirection direction) {
        return openExpectation(
                keyValue, minor, currency, direction, KeyKind.PSP_CAPTURE_REF, keyValue);
    }

    private static UUID openExpectation(
            String name, long minor, CurrencyCode currency, ExpectationDirection direction,
            KeyKind keyKind, String keyValue) {
        String operationRef = "op-" + name + "-" + IDS.next().toString().substring(24);
        return runner().inTransaction(unitOfWork -> {
            UUID position;
            try {
                position =
                        new JdbcLedgerAccountStore()
                                .findOperational(
                                        unitOfWork, AccountPurpose.SETTLEMENT_CLEARING,
                                        currency)
                                .orElseThrow()
                                .id()
                                .value();
                expectations.open(
                        unitOfWork,
                        new NewExpectation(
                                ExpectationKind.CARD_CAPTURE,
                                operationRef,
                                "payment-capture:" + operationRef,
                                SOURCE,
                                AccountPurpose.SETTLEMENT_CLEARING,
                                position,
                                direction,
                                Money.ofPersisted(minor, currency, 2),
                                Optional.of(IDS.next()),
                                SETTLED_ON,
                                Optional.empty(),
                                SETTLED_ON.plusDays(3),
                                RULE_SET,
                                List.of(new NewExpectation.ExpectationKey(
                                        keyKind, keyValue)),
                                PLATFORM,
                                Instant.now(CLOCK),
                                CorrelationId.generate(IDS)));
                try (PreparedStatement read =
                        unitOfWork.prepareStatement(
                                "SELECT id FROM reconciliation.expectation WHERE"
                                        + " operation_ref = ?")) {
                    read.setString(1, operationRef);
                    try (ResultSet row = read.executeQuery()) {
                        row.next();
                        return row.getObject("id", UUID.class);
                    }
                }
            } catch (SQLException failure) {
                throw new ReconciliationStorageException("could not seed", failure);
            }
        });
    }

    // ----------------------------------------------------------------- plumbing

    private static void execute(Connection unitOfWork, String sql, Object... args) {
        try (PreparedStatement statement = unitOfWork.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("seed failed: " + sql, failure);
        }
    }

    private String status(UUID runId) throws SQLException {
        return string("SELECT status FROM reconciliation.reconciliation_batch WHERE"
                + " id = ?", runId);
    }

    private String itemStatus(UUID runId, int lineNo) throws SQLException {
        return string("SELECT status FROM reconciliation.external_item WHERE run_id = ?"
                + " AND line_no = " + lineNo, runId);
    }

    private long suspenseRemainder(UUID runId, int lineNo) throws SQLException {
        return count("SELECT s.amount_minor - s.released_minor FROM"
                + " reconciliation.suspense_item s JOIN reconciliation.external_item i"
                + " ON s.external_item_id = i.id WHERE i.run_id = ? AND i.line_no = "
                + lineNo, runId);
    }

    private long breakRow(UUID runId, int lineNo, String type, String cause)
            throws SQLException {
        return count("SELECT count(*) FROM reconciliation.break b JOIN"
                + " reconciliation.external_item i ON b.external_item_id = i.id WHERE"
                + " i.run_id = ? AND i.line_no = " + lineNo + " AND b.type = '" + type
                + "' AND b.cause = '" + cause + "'", runId);
    }

    private long count(String sql, Object... args) throws SQLException {
        Object value = queryOne(sql, args);
        return ((Number) value).longValue();
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

    private long scalar(String sql, Object... args) throws SQLException {
        return count(sql, args);
    }

    private String string(String sql, Object... args) throws SQLException {
        return (String) queryOne(sql, args);
    }

    private Object queryOne(String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = application.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                return row.getObject(1);
            }
        }
    }
}
