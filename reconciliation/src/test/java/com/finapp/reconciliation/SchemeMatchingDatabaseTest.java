package com.finapp.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
 * The instant scheme's cycle report matched against the real schema, the real ledger and real
 * commits (`P8-TSK-017`, ADR-0065 §2, ADR-0067 §5, ADR-0068 §§1-3): a {@code CREDIT_IN} reaches
 * its pay-in or a Phase 7 parking by {@code SCHEME_REF} first and {@code END_TO_END_REF} second;
 * a {@code DEBIT_OUT} its withdrawal by reference or its return by {@code OUR_REF}; the report's
 * cycle — its run's frozen birth fact — against the cycle the completion announced is a
 * zero-value {@code TIMING_DIFFERENCE(CYCLE_MISMATCH)} (suppressed where an overdue break
 * stands, which the money closes {@code EVIDENCED} instead); a return, which announced no cycle,
 * LEARNS it — once, equal to its report's, for every writer (`V009`); a direction contradicted is
 * {@code REVERSAL_MISMATCH} and the pay-in stays untouched ({@code INV-REV-03}); the scheme's fee
 * is judged against its pinned flat terms by the fee's own original key; a platform refund the
 * lookup answers {@code FAILED} is a refund's mismatch whatever line named it; a key collision
 * is recorded and raised, never failed; and ten sweepers — or ten with the try-lock BYPASSED —
 * move each expectation's money once.
 *
 * <p>The suite owns a PRIVATE scheme-shaped source and rule set (the {@code MatchingDatabaseTest}
 * discipline): `V002`'s scheme rows copied onto a pair only this suite writes, so no other
 * suite's runs, keys or collisions stand in its scope and each case owns its references, cycles
 * and dates. The Matching clock is pinned months behind the database clock (the
 * {@code GraceAndRematchDatabaseTest} discipline) and expectations open on the REAL clock, so
 * "opened after the item's latest decision" is true exactly for an expectation opened after a
 * sweep. Ordered only so the lock-bypassed race, which leaves its losers' records, runs last.
 */
@Tag("database")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the scheme cycle report's matching: references, the cycle and the learned cycle"
        + " (P8-TSK-017)")
class SchemeMatchingDatabaseTest {

    /** Months behind the database clock, on purpose - see the class note. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor PLATFORM = new Actor("system", ActorType.SYSTEM);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    /** A private scheme-shaped source and rule set: `V002`'s scheme rows, this suite's alone. */
    private static final UUID SOURCE = UUID.fromString("01a0e2bc-8200-7017-8000-000000000017");
    private static final UUID RULE_SET =
            UUID.fromString("01a0e2bd-8300-7017-8000-000000000017");
    /** A fresh span of future dates per suite run: nothing here ever ages. */
    private static final LocalDate BASE =
            LocalDate.parse("2026-11-01").plusDays(100L * new SecureRandom().nextInt(30));
    private static final AtomicLong SEQUENCES = new AtomicLong(System.nanoTime() % 70_000);

    /** A return the platform concluded FAILED: the lookup's refund answer (case l). */
    private static final String FAILED_RETURN = "RTN-FAILED-";
    /** A withdrawal the platform concluded FAILED: the lookup's withdrawal answer (case l). */
    private static final String FAILED_WITHDRAWAL = "WDL-FAILED-";
    /** The key-scope source each typed reference was looked up under. */
    private static final Map<String, Optional<UUID>> LOOKED_UP_UNDER = new ConcurrentHashMap<>();

    /** Types by the reference's planted prefix - the app-composed lookup's shape, made pure. */
    private static final InternalReferenceLookup LOOKUP =
            (unitOfWork, subject) -> {
                for (String value : subject.references().values()) {
                    if (value.startsWith(FAILED_RETURN)) {
                        LOOKED_UP_UNDER.put(value, subject.scopeSourceId());
                        return new InternalReferenceLookup.InternalReference(
                                InternalClassification.TERMINAL,
                                Optional.of("rtn-1"),
                                Optional.of("FAILED"),
                                Optional.of(InternalSubject.REFUND));
                    }
                    if (value.startsWith(FAILED_WITHDRAWAL)) {
                        LOOKED_UP_UNDER.put(value, subject.scopeSourceId());
                        return new InternalReferenceLookup.InternalReference(
                                InternalClassification.TERMINAL,
                                Optional.of("wdl-1"),
                                Optional.of("FAILED"),
                                Optional.of(InternalSubject.WITHDRAWAL));
                    }
                }
                return InternalReferenceLookup.InternalReference.unknown();
            };

    private static Connection application;
    private static JdbcReconciliationRuns runs;
    private static JdbcExternalItems items;
    private static JdbcExpectationRegister expectations;
    private static JdbcBreakRegister breakRegister;
    private static Matching matching;

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
        runs = new JdbcReconciliationRuns();
        items = new JdbcExternalItems();
        expectations = new JdbcExpectationRegister(IDS);
        breakRegister =
                new JdbcBreakRegister(new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS);
        matching = matching(false);
        seedPrivateSchemeSource();
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.rollback();
            application.close();
        }
    }

    private static Matching matching(boolean bypassTheLock) {
        JdbcBreakRegister register =
                new JdbcBreakRegister(new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS);
        Suspense suspense = new Suspense(postingService(), new JdbcLedgerAccountStore(), IDS);
        if (bypassTheLock) {
            return new Matching(
                    new JdbcMatchingStore(), new MatchingRules(), register, suspense,
                    ResolutionFixtures.resolutions(IDS, CLOCK),
                    LOOKUP,
                    new JdbcLedgerAccountStore(), new JdbcOutboxWriter(),
                    new JdbcAuditWriter(), IDS, CLOCK, new Matching.Config(200, 2), runner()) {
                @Override
                boolean claimSource(Connection unitOfWork, UUID sourceId) {
                    return true; // The probe: the lock ORDERS, the arbiters decide.
                }
            };
        }
        return new Matching(
                new JdbcMatchingStore(), new MatchingRules(), register, suspense,
                ResolutionFixtures.resolutions(IDS, CLOCK),
                LOOKUP,
                new JdbcLedgerAccountStore(), new JdbcOutboxWriter(),
                new JdbcAuditWriter(), IDS, CLOCK, new Matching.Config(200, 2), runner());
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

    /** One committed transaction per call - what the app's runner bean does. */
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

    /**
     * `V002`'s scheme rule set copied onto the private pair: the five ONE_TO_ONE rules (no
     * expectation kind - the key's own expectation decides), the fee CHECK, the pinned flat fee
     * terms with NO fee tolerance (it reads zero), the date window, the lags and the thresholds.
     */
    private static void seedPrivateSchemeSource() {
        runner().inTransaction(
                unitOfWork -> {
                    execute(unitOfWork,
                            "INSERT INTO reconciliation.rule_set (id, source_id, version,"
                                    + " status, funding_lag_days, gain_min_age_days,"
                                    + " effective_from, proposed_by, decided_by, reason,"
                                    + " created_at, correlation_id) VALUES (?, ?, 1,"
                                    + " 'ACTIVE', 2, 90, ?, 'test', 'test',"
                                    + " 'SchemeMatchingDatabaseTest private scheme source',"
                                    + " now(), 'p8-tsk-017-test') ON CONFLICT (id) DO NOTHING",
                            RULE_SET, SOURCE, java.sql.Date.valueOf(LocalDate.parse("2026-09-29")));
                    execute(unitOfWork,
                            "INSERT INTO reconciliation.rule_set_lag (rule_set_id,"
                                    + " expectation_kind, lag_days) VALUES"
                                    + " (?, 'PUSH_PAY_IN', 1), (?, 'UNMATCHED_CONFIRMATION', 1),"
                                    + " (?, 'PUSH_WITHDRAWAL', 1), (?, 'PUSH_RETURN', 1)"
                                    + " ON CONFLICT DO NOTHING",
                            RULE_SET, RULE_SET, RULE_SET, RULE_SET);
                    execute(unitOfWork,
                            "INSERT INTO reconciliation.rule (rule_set_id, priority,"
                                    + " line_type, key_kind, expectation_kind, cardinality,"
                                    + " operation_anchored, grace_hours) VALUES"
                                    + " (?, 1, 'CREDIT_IN', 'SCHEME_REF', NULL, 'ONE_TO_ONE',"
                                    + " false, 48),"
                                    + " (?, 2, 'CREDIT_IN', 'END_TO_END_REF', NULL,"
                                    + " 'ONE_TO_ONE', false, 48),"
                                    + " (?, 3, 'DEBIT_OUT', 'SCHEME_REF', NULL, 'ONE_TO_ONE',"
                                    + " false, 48),"
                                    + " (?, 4, 'DEBIT_OUT', 'END_TO_END_REF', NULL,"
                                    + " 'ONE_TO_ONE', false, 48),"
                                    + " (?, 5, 'DEBIT_OUT', 'OUR_REF', NULL, 'ONE_TO_ONE',"
                                    + " false, 48),"
                                    + " (?, 6, 'SCHEME_FEE', NULL, NULL, 'CHECK', false, 48)"
                                    + " ON CONFLICT DO NOTHING",
                            RULE_SET, RULE_SET, RULE_SET, RULE_SET, RULE_SET, RULE_SET);
                    execute(unitOfWork,
                            "INSERT INTO reconciliation.tolerance (rule_set_id, comparison,"
                                    + " currency, absolute_minor, days) VALUES"
                                    + " (?, 'SETTLEMENT_DATE_DAYS', NULL, NULL, 2)"
                                    + " ON CONFLICT DO NOTHING",
                            RULE_SET);
                    execute(unitOfWork,
                            "INSERT INTO reconciliation.provider_fee_schedule (rule_set_id,"
                                    + " line_type, currency, rate, fixed_minor, scale,"
                                    + " rounding_policy) VALUES"
                                    + " (?, 'SCHEME_FEE', 'EUR', 0.000000, 10, 2, 'HALF_UP'),"
                                    + " (?, 'SCHEME_FEE', 'GBP', 0.000000, 10, 2, 'HALF_UP'),"
                                    + " (?, 'SCHEME_FEE', 'USD', 0.000000, 10, 2, 'HALF_UP')"
                                    + " ON CONFLICT DO NOTHING",
                            RULE_SET, RULE_SET, RULE_SET);
                    execute(unitOfWork,
                            "INSERT INTO reconciliation.severity_threshold (rule_set_id,"
                                    + " currency, high_value_minor) VALUES (?, 'EUR', 100000),"
                                    + " (?, 'GBP', 100000), (?, 'USD', 100000)"
                                    + " ON CONFLICT DO NOTHING",
                            RULE_SET, RULE_SET, RULE_SET);
                    return null;
                });
    }

    // ----------------------------------------------------------------- credits

    @Test
    @Order(1)
    @DisplayName("(a) a CREDIT_IN quoting its pay-in's SCHEME_REF in the cycle the pay-in"
            + " announced settles it: priority 1 by SCHEME_REF, no timing, nothing learned")
    void aCreditSettlesItsPayInBySchemeReference() throws SQLException {
        LocalDate day = day(0);
        String cycle = cycle("A");
        String scheme = reference("A-SCH");
        String endToEnd = reference("A-E2E");
        UUID payIn =
                open(ExpectationKind.PUSH_PAY_IN, 100_00, day, Optional.of(cycle),
                        schemeRef(scheme), endToEnd(endToEnd));
        UUID runId =
                seedRun(cycle,
                        credit(1, 100_00, day,
                                Map.of(ItemKeyKind.SCHEME_REF, scheme,
                                        ItemKeyKind.END_TO_END_REF, endToEnd)));

        matching.sweep();

        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(string("SELECT settlement_cycle FROM reconciliation.reconciliation_batch"
                + " WHERE id = ?", runId))
                .as("the report's cycle is the run's birth fact")
                .isEqualTo(cycle);
        assertThat(itemStatus(runId, 1)).isEqualTo("MATCHED");
        assertThat(expectationStatus(payIn)).isEqualTo("SETTLED");
        UUID decision = decisionOf(runId, 1);
        assertThat(row("SELECT strategy, matched_key_kind, rule_priority, outcome, origin,"
                + " date_deviation_days FROM reconciliation.match_decision WHERE id = ?",
                decision))
                .containsExactly("ONE_TO_ONE", "SCHEME_REF", 1, "MATCHED", "RUN", 0);
        assertThat(row("SELECT expectation_id, key_kind FROM reconciliation.match_candidate"
                + " WHERE decision_id = ?", decision))
                .containsExactly(payIn, "SCHEME_REF");
        assertThat(count("SELECT allocated_minor FROM reconciliation.expectation WHERE id = ?",
                payIn)).isEqualTo(100_00);
        assertThat(settledEvents(payIn)).isEqualTo(1);
        assertThat(timingBreaksOn(decision)).isZero();
        assertThat(learnedCycle(itemId(runId, 1)))
                .as("the pay-in announced its cycle: there is nothing to learn")
                .isNull();
    }

    @Test
    @Order(2)
    @DisplayName("(b) a CREDIT_IN whose SCHEME_REF reaches nothing falls through to its"
            + " END_TO_END_REF: priority 2")
    void aCreditFallsThroughToTheEndToEndReference() throws SQLException {
        LocalDate day = day(1);
        String cycle = cycle("B");
        String endToEnd = reference("B-E2E");
        UUID payIn =
                open(ExpectationKind.PUSH_PAY_IN, 55_00, day, Optional.of(cycle),
                        schemeRef(reference("B-SCH")), endToEnd(endToEnd));
        UUID runId =
                seedRun(cycle,
                        credit(1, 55_00, day,
                                Map.of(ItemKeyKind.SCHEME_REF, reference("B-UNKNOWN"),
                                        ItemKeyKind.END_TO_END_REF, endToEnd)));

        matching.sweep();

        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(itemStatus(runId, 1)).isEqualTo("MATCHED");
        assertThat(expectationStatus(payIn)).isEqualTo("SETTLED");
        UUID decision = decisionOf(runId, 1);
        assertThat(row("SELECT strategy, matched_key_kind, rule_priority, outcome FROM"
                + " reconciliation.match_decision WHERE id = ?", decision))
                .containsExactly("ONE_TO_ONE", "END_TO_END_REF", 2, "MATCHED");
        assertThat(row("SELECT expectation_id, key_kind FROM reconciliation.match_candidate"
                + " WHERE decision_id = ?", decision))
                .containsExactly(payIn, "END_TO_END_REF");
        assertThat(timingBreaksOn(decision)).isZero();
    }

    @Test
    @Order(3)
    @DisplayName("(c) a CREDIT_IN quoting a Phase 7 parking's SCHEME_REF settles its"
            + " UNMATCHED_CONFIRMATION expectation")
    void aCreditSettlesAParking() throws SQLException {
        LocalDate day = day(2);
        String cycle = cycle("C");
        String scheme = reference("C-SCH");
        UUID parking =
                open(ExpectationKind.UNMATCHED_CONFIRMATION, 42_00, day, Optional.of(cycle),
                        schemeRef(scheme));
        UUID runId = seedRun(cycle, credit(1, 42_00, day, Map.of(ItemKeyKind.SCHEME_REF, scheme)));

        matching.sweep();

        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(itemStatus(runId, 1)).isEqualTo("MATCHED");
        assertThat(expectationStatus(parking)).isEqualTo("SETTLED");
        UUID decision = decisionOf(runId, 1);
        assertThat(row("SELECT strategy, matched_key_kind, rule_priority, outcome FROM"
                + " reconciliation.match_decision WHERE id = ?", decision))
                .containsExactly("ONE_TO_ONE", "SCHEME_REF", 1, "MATCHED");
        assertThat(one("SELECT expectation_id FROM reconciliation.allocation WHERE"
                + " decision_id = ?", decision)).isEqualTo(parking);
        assertThat(settledEvents(parking)).isEqualTo(1);
        assertThat(timingBreaksOn(decision)).isZero();
        assertThat(learnedCycle(itemId(runId, 1))).isNull();
    }

    // ----------------------------------------------------------------- debits

    @Test
    @Order(4)
    @DisplayName("(d) a DEBIT_OUT settles its withdrawal by SCHEME_REF (priority 3) and another"
            + " its return by OUR_REF alone (priority 5): the return, which announced no cycle,"
            + " LEARNS the run's; the withdrawal, which announced one, learns nothing")
    void debitsSettleWithdrawalsAndReturnsAndTheReturnLearnsItsCycle() throws SQLException {
        LocalDate day = day(3);
        String cycle = cycle("D");
        String withdrawalScheme = reference("D-WDL");
        String returnOurs = reference("D-RTN-OUR");
        UUID withdrawal =
                open(ExpectationKind.PUSH_WITHDRAWAL, 70_00, day, Optional.of(cycle),
                        schemeRef(withdrawalScheme), endToEnd(reference("D-WDL-E2E")));
        UUID pushReturn =
                open(ExpectationKind.PUSH_RETURN, 30_00, day, Optional.empty(),
                        schemeRef(reference("D-RTN")), ourRef(returnOurs));
        UUID runId =
                seedRun(cycle,
                        debit(1, 70_00, day, Map.of(ItemKeyKind.SCHEME_REF, withdrawalScheme)),
                        // The scheme's reference reaches nothing and there is no end-to-end
                        // reference: only rule 5 fires.
                        debit(2, 30_00, day,
                                Map.of(ItemKeyKind.SCHEME_REF, reference("D-UNKNOWN"),
                                        ItemKeyKind.OUR_REF, returnOurs)));

        matching.sweep();

        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(itemStatus(runId, 1)).isEqualTo("MATCHED");
        assertThat(itemStatus(runId, 2)).isEqualTo("MATCHED");
        assertThat(expectationStatus(withdrawal)).isEqualTo("SETTLED");
        assertThat(expectationStatus(pushReturn)).isEqualTo("SETTLED");
        UUID withdrawalDecision = decisionOf(runId, 1);
        UUID returnDecision = decisionOf(runId, 2);
        assertThat(row("SELECT strategy, matched_key_kind, rule_priority, outcome FROM"
                + " reconciliation.match_decision WHERE id = ?", withdrawalDecision))
                .containsExactly("ONE_TO_ONE", "SCHEME_REF", 3, "MATCHED");
        assertThat(row("SELECT strategy, matched_key_kind, rule_priority, outcome FROM"
                + " reconciliation.match_decision WHERE id = ?", returnDecision))
                .containsExactly("ONE_TO_ONE", "OUR_REF", 5, "MATCHED");

        assertThat(learnedCycle(itemId(runId, 2)))
                .as("the return learned its cycle from the report that settled it")
                .isEqualTo(cycle);
        assertThat(learnedCycle(itemId(runId, 1)))
                .as("the withdrawal announced its cycle: nothing to learn")
                .isNull();
        assertThat(timingBreaksOn(withdrawalDecision)).isZero();
        assertThat(timingBreaksOn(returnDecision))
                .as("a cycle-less return is never a cycle shift")
                .isZero();
    }

    // ----------------------------------------------------------------- the cycle

    @Test
    @Order(5)
    @DisplayName("(e) a line settled in cycle C2 against a pay-in that announced C1 is MATCHED"
            + " and SETTLED with ONE zero-value TIMING_DIFFERENCE(CYCLE_MISMATCH) on its decision;"
            + " where an overdue MISSING_EXTERNAL stands, the money closes it EVIDENCED and no"
            + " timing break is raised")
    void aCycleShiftIsAZeroValueTimingBreakOrClosesTheOverdueBreak() throws SQLException {
        LocalDate day = day(4);
        String announced = cycle("E1");
        String reported = cycle("E2");
        String shiftedScheme = reference("E-SHIFT");
        String overdueScheme = reference("E-OVERDUE");
        UUID shifted =
                open(ExpectationKind.PUSH_PAY_IN, 40_00, day, Optional.of(announced),
                        schemeRef(shiftedScheme));
        UUID overdue =
                open(ExpectationKind.PUSH_PAY_IN, 60_00, day, Optional.of(announced),
                        schemeRef(overdueScheme));
        UUID overdueBreak = raiseOverdue(overdue, 60_00);
        UUID runId =
                seedRun(reported,
                        credit(1, 40_00, day, Map.of(ItemKeyKind.SCHEME_REF, shiftedScheme)),
                        credit(2, 60_00, day, Map.of(ItemKeyKind.SCHEME_REF, overdueScheme)));

        matching.sweep();

        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        // The shift alone: the dates agree, the cycles do not.
        assertThat(itemStatus(runId, 1)).isEqualTo("MATCHED");
        assertThat(expectationStatus(shifted)).isEqualTo("SETTLED");
        UUID shiftedDecision = decisionOf(runId, 1);
        assertThat(row("SELECT outcome, date_deviation_days, timing_tolerance_days FROM"
                + " reconciliation.match_decision WHERE id = ?", shiftedDecision))
                .containsExactly("MATCHED", 0, 2);
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE decision_id = ?",
                shiftedDecision)).as("one break per decision").isEqualTo(1);
        assertThat(row("SELECT type, cause, value_at_issue_minor, source_id, rule_set_id,"
                + " status, external_item_id, expectation_id FROM reconciliation.break WHERE"
                + " decision_id = ?", shiftedDecision))
                .as("the money matched; only the cycle differs - never a refusal")
                .containsExactly(
                        "TIMING_DIFFERENCE", "CYCLE_MISMATCH", 0L, SOURCE, RULE_SET, "OPEN",
                        null, null);
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item WHERE"
                + " external_item_id = ?", itemId(runId, 1))).isZero();

        // The overdue variant: L1 across a shift - one fact, one break.
        assertThat(itemStatus(runId, 2)).isEqualTo("MATCHED");
        assertThat(expectationStatus(overdue)).isEqualTo("SETTLED");
        UUID overdueDecision = decisionOf(runId, 2);
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", overdueBreak))
                .isEqualTo("RESOLVED");
        assertThat(row("SELECT kind, decision_id, journal_entry_id FROM"
                + " reconciliation.resolution WHERE break_id = ?", overdueBreak))
                .containsExactly("EVIDENCED", overdueDecision, null);
        assertThat(timingBreaksOn(overdueDecision))
                .as("the overdue break IS the timing record: no second break states it")
                .isZero();
    }

    @Test
    @Order(6)
    @DisplayName("(f) a DEBIT_OUT reaching a pay-in by END_TO_END_REF contradicts its"
            + " direction: PARKED under REVERSAL_MISMATCH(DIRECTION_CONTRADICTED), the pay-in"
            + " untouched (INV-REV-03)")
    void aContradictedDirectionParksAndLeavesThePayInUntouched() throws SQLException {
        LocalDate day = day(5);
        String cycle = cycle("F");
        String endToEnd = reference("F-E2E");
        UUID payIn =
                open(ExpectationKind.PUSH_PAY_IN, 25_00, day, Optional.of(cycle),
                        schemeRef(reference("F-SCH")), endToEnd(endToEnd));
        UUID runId =
                seedRun(cycle, debit(1, 25_00, day, Map.of(ItemKeyKind.END_TO_END_REF, endToEnd)));

        matching.sweep();

        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(itemStatus(runId, 1)).isEqualTo("PARKED");
        UUID decision = decisionOf(runId, 1);
        assertThat(row("SELECT strategy, matched_key_kind, rule_priority, outcome FROM"
                + " reconciliation.match_decision WHERE id = ?", decision))
                .containsExactly("ONE_TO_ONE", "END_TO_END_REF", 4, "PARKED");
        assertThat(row("SELECT b.type, b.cause, b.value_at_issue_minor FROM"
                + " reconciliation.break b JOIN reconciliation.external_item i ON"
                + " b.external_item_id = i.id WHERE i.run_id = ?", runId))
                .containsExactly("REVERSAL_MISMATCH", "DIRECTION_CONTRADICTED", 25_00L);
        assertThat(row("SELECT s.origin, s.amount_minor - s.released_minor FROM"
                + " reconciliation.suspense_item s JOIN reconciliation.external_item i ON"
                + " s.external_item_id = i.id WHERE i.run_id = ?", runId))
                .containsExactly("RECON_PARK", 25_00L);
        assertThat(row("SELECT status, allocated_minor FROM reconciliation.expectation WHERE"
                + " id = ?", payIn))
                .as("a debit never discharges a credit's expectation")
                .containsExactly("OPEN", 0L);
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                + " expectation_id = ?", payIn)).isZero();
    }

    // ----------------------------------------------------------------- the scheme's fee

    @Test
    @Order(7)
    @DisplayName("(g) the scheme's fee is judged against its pinned flat terms through its"
            + " ORIGINAL_REF's SCHEME_REF: 0.10 CHECKED with no break, 0.11 CHECKED with"
            + " FEE_MISMATCH 0.01 - with no tolerance row, inside collapses onto exactly-at")
    void schemeFeesAreJudgedByTheirOwnOriginalKey() throws SQLException {
        LocalDate day = day(6);
        String cycle = cycle("G");
        String scheme = reference("G-SCH");
        UUID payIn =
                open(ExpectationKind.PUSH_PAY_IN, 200_00, day, Optional.of(cycle),
                        schemeRef(scheme));
        UUID runId =
                seedRun(cycle,
                        credit(1, 200_00, day, Map.of(ItemKeyKind.SCHEME_REF, scheme)),
                        fee(2, 10, day, scheme),
                        fee(3, 11, day, scheme));

        matching.sweep();

        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(itemStatus(runId, 1)).isEqualTo("MATCHED");
        assertThat(expectationStatus(payIn)).isEqualTo("SETTLED");
        assertThat(itemStatus(runId, 2)).isEqualTo("CHECKED");
        assertThat(itemStatus(runId, 3)).isEqualTo("CHECKED");
        assertThat(row("SELECT strategy, rule_priority, outcome, fee_expected_minor,"
                + " fee_reported_minor, fee_tolerance_minor FROM reconciliation.match_decision"
                + " WHERE id = ?", decisionOf(runId, 2)))
                .as("the original reached by SCHEME_REF - an unreachable one would price zero")
                .containsExactly("CHECK", 6, "CHECKED", 10L, 10L, 0L);
        assertThat(row("SELECT fee_expected_minor, fee_reported_minor, fee_tolerance_minor"
                + " FROM reconciliation.match_decision WHERE id = ?", decisionOf(runId, 3)))
                .containsExactly(10L, 11L, 0L);
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE external_item_id = ?",
                itemId(runId, 2)))
                .as("exactly the terms: nothing")
                .isZero();
        assertThat(row("SELECT type, cause, value_at_issue_minor FROM reconciliation.break"
                + " WHERE external_item_id = ?", itemId(runId, 3)))
                .containsExactly("FEE_MISMATCH", "FEE_BEYOND_TOLERANCE", 1L);
        assertThat(count("SELECT count(*) FROM reconciliation.break b JOIN"
                + " reconciliation.external_item i ON b.external_item_id = i.id WHERE"
                + " i.run_id = ? AND b.type = 'FEE_MISMATCH'", runId))
                .as("the per-batch fold is the processing fee's alone")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item s JOIN"
                + " reconciliation.external_item i ON s.external_item_id = i.id WHERE"
                + " i.run_id = ?", runId))
                .as("an expensed fee is judged, never parked")
                .isZero();
    }

    // ----------------------------------------------------------------- the learned cycle

    @Test
    @Order(8)
    @DisplayName("(h) the learned cycle binds every writer: a second write refused, a value"
            + " other than the run's cycle refused, a cycle-less run teaches nothing, an item"
            + " born knowing a cycle refused - and the run's cycle never moves")
    void theLearnedCycleBindsEveryWriter() throws SQLException {
        LocalDate day = day(7);
        String cycle = cycle("H");
        String withdrawalScheme = reference("H-WDL");
        String returnOurs = reference("H-RTN-OUR");
        open(ExpectationKind.PUSH_WITHDRAWAL, 15_00, day, Optional.of(cycle),
                schemeRef(withdrawalScheme));
        open(ExpectationKind.PUSH_RETURN, 12_00, day, Optional.empty(),
                schemeRef(reference("H-RTN")), ourRef(returnOurs));
        UUID runId =
                seedRun(cycle,
                        debit(1, 15_00, day, Map.of(ItemKeyKind.SCHEME_REF, withdrawalScheme)),
                        debit(2, 12_00, day, Map.of(ItemKeyKind.OUR_REF, returnOurs)));
        matching.sweep();
        UUID withdrawalItem = itemId(runId, 1);
        UUID returnItem = itemId(runId, 2);
        assertThat(learnedCycle(returnItem)).isEqualTo(cycle);
        assertThat(learnedCycle(withdrawalItem)).isNull();

        // ONCE: never to another value, never back to nothing - for the application role the
        // column grant admits, and for the migrator no grant binds.
        for (String again : List.of("learned_cycle = 'ANOTHER-CYCLE'", "learned_cycle = NULL")) {
            assertRefused(DatabaseRoles::application,
                    "UPDATE reconciliation.external_item SET " + again + " WHERE id = ?",
                    "P0001", "recorded once", returnItem);
        }
        assertRefused(DatabaseRoles::migrator,
                "UPDATE reconciliation.external_item SET learned_cycle = 'ANOTHER-CYCLE'"
                        + " WHERE id = ?",
                "P0001", "recorded once", returnItem);

        // ITS OWN REPORT'S CYCLE: any other value refused...
        assertRefused(DatabaseRoles::application,
                "UPDATE reconciliation.external_item SET learned_cycle = ? WHERE id = ?",
                "P0001", "own report", "NOT-" + cycle, withdrawalItem);
        // ...while the exact value is admitted: a rule, not a blanket freeze.
        try (Connection raw = DatabaseRoles.application()) {
            raw.setAutoCommit(false);
            assertThat(update(raw,
                    "UPDATE reconciliation.external_item SET learned_cycle = ? WHERE id = ?",
                    cycle, withdrawalItem)).isEqualTo(1);
            raw.rollback();
        }

        // A run that settles no cycle teaches none.
        try (Connection raw = DatabaseRoles.application()) {
            raw.setAutoCommit(false);
            UUID bareRun = IDS.next();
            UUID bareItem = IDS.next();
            runs.birth(raw, newRun(bareRun, Optional.empty(), day, 1));
            items.birthAll(raw, PLATFORM, List.of(newItem(bareItem, bareRun,
                    credit(1, 10_00, day, Map.of(ItemKeyKind.SCHEME_REF, reference("H-BARE"))))));
            Throwable taught =
                    catchThrowable(() -> execute(raw,
                            "UPDATE reconciliation.external_item SET learned_cycle ="
                                    + " 'ANY-CYCLE' WHERE id = ?",
                            bareItem));
            assertThat(taught).isNotNull().hasStackTraceContaining("own report");
            assertThat(sqlState(taught)).isEqualTo("P0001");
            raw.rollback();
        }

        // NEVER AT BIRTH: the same raw insert is admitted without a cycle and refused with one.
        String columns =
                "INSERT INTO reconciliation.external_item (id, run_id, source_id,"
                        + " settlement_line_id, line_no, line_type, direction, amount_minor,"
                        + " currency, scale, position_purpose, business_date,"
                        + " canonical_fingerprint, created_at, status_changed_at,"
                        + " correlation_id";
        String values =
                " VALUES (?, ?, ?, ?, ?, 'CREDIT_IN', 'INBOUND', 100, 'EUR', 2,"
                        + " 'INSTANT_CLEARING', ?, ?, now(), now(), 'p8-tsk-017-probe'";
        try (Connection raw = DatabaseRoles.application()) {
            raw.setAutoCommit(false);
            assertThat(update(raw, columns + ")" + values + ")",
                    IDS.next(), runId, SOURCE, IDS.next(), 90, java.sql.Date.valueOf(day),
                    fingerprint())).isEqualTo(1);
            Throwable born =
                    catchThrowable(() -> execute(raw,
                            columns + ", learned_cycle)" + values + ", ?)",
                            IDS.next(), runId, SOURCE, IDS.next(), 91,
                            java.sql.Date.valueOf(day), fingerprint(), cycle));
            assertThat(born).isNotNull().hasStackTraceContaining("never at birth");
            assertThat(sqlState(born)).isEqualTo("P0001");
            raw.rollback();
        }

        // THE RUN'S CYCLE: born, never granted, frozen for every writer.
        assertRefused(DatabaseRoles::application,
                "UPDATE reconciliation.reconciliation_batch SET settlement_cycle = ?"
                        + " WHERE id = ?",
                "42501", "permission denied", "NOT-" + cycle, runId);
        assertRefused(DatabaseRoles::migrator,
                "UPDATE reconciliation.reconciliation_batch SET settlement_cycle = ?"
                        + " WHERE id = ?",
                "P0001", "birth statement is frozen", "NOT-" + cycle, runId);
        assertThat(string("SELECT settlement_cycle FROM reconciliation.reconciliation_batch"
                + " WHERE id = ?", runId)).isEqualTo(cycle);
        assertThat(learnedCycle(returnItem)).isEqualTo(cycle);
        assertThat(learnedCycle(withdrawalItem)).isNull();
    }

    // ----------------------------------------------------------------- late evidence

    @Test
    @Order(9)
    @DisplayName("(i) a CREDIT_IN that arrives before its pay-in waits UNMATCHED; the pay-in"
            + " opens while TEN sweepers race - exactly one allocation, one decision of origin"
            + " REMATCH, the pay-in SETTLED")
    void tenRematchSweepersSettleALatePayInOnce() throws Exception {
        LocalDate day = day(8);
        String cycle = cycle("I");
        String scheme = reference("I-SCH");
        String endToEnd = reference("I-E2E");
        UUID runId =
                seedRun(cycle,
                        credit(1, 80_00, day,
                                Map.of(ItemKeyKind.SCHEME_REF, scheme,
                                        ItemKeyKind.END_TO_END_REF, endToEnd)));
        matching.sweep();
        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(itemStatus(runId, 1)).isEqualTo("UNMATCHED");
        UUID item = itemId(runId, 1);
        assertThat(one("SELECT grace_until FROM reconciliation.external_item WHERE id = ?",
                item)).as("waiting under its rule's grace").isNotNull();

        UUID payIn;
        ExecutorService racers = Executors.newFixedThreadPool(10);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> outcomes = new ArrayList<>();
            for (int racer = 0; racer < 10; racer++) {
                outcomes.add(racers.submit(() -> {
                    start.await();
                    for (int pass = 0; pass < 3; pass++) {
                        matching.sweep();
                    }
                    return null;
                }));
            }
            start.countDown();
            // The pay-in resolver's EXECUTED transaction commits while the sweepers race.
            payIn =
                    open(ExpectationKind.PUSH_PAY_IN, 80_00, day, Optional.of(cycle),
                            schemeRef(scheme), endToEnd(endToEnd));
            for (Future<?> outcome : outcomes) {
                outcome.get();
            }
        } finally {
            racers.shutdownNow();
        }
        // Whatever the interleaving, the committed pay-in is visible to this last leg.
        matching.sweep();

        assertThat(itemStatus(runId, 1)).isEqualTo("MATCHED");
        assertThat(expectationStatus(payIn)).isEqualTo("SETTLED");
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " external_item_id = ? AND origin = 'REMATCH'", item))
                .as("one rematch decision, whatever the ten sweepers did")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " external_item_id = ?", item))
                .as("the run's wait and the one rematch")
                .isEqualTo(2);
        UUID rematch =
                (UUID) one("SELECT id FROM reconciliation.match_decision WHERE"
                        + " external_item_id = ? AND origin = 'REMATCH'", item);
        assertThat(row("SELECT strategy, matched_key_kind, rule_priority, outcome FROM"
                + " reconciliation.match_decision WHERE id = ?", rematch))
                .containsExactly("ONE_TO_ONE", "SCHEME_REF", 1, "MATCHED");
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                + " external_item_id = ?", item)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                + " expectation_id = ?", payIn)).isEqualTo(1);
        assertThat(count("SELECT allocated_minor FROM reconciliation.expectation WHERE id = ?",
                payIn)).isEqualTo(80_00);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_event WHERE"
                + " expectation_id = ? AND event_type = 'ALLOCATED'", payIn)).isEqualTo(1);
        assertThat(settledEvents(payIn)).isEqualTo(1);
        assertThat(timingBreaksOn(rematch)).isZero();
    }

    // ----------------------------------------------------------------- the race

    @Test
    @Order(10)
    @DisplayName("(j) ten sweepers over one scheme run of five lines (pay-in, withdrawal,"
            + " return, parking, fee): one decision per line, one allocation and one settle per"
            + " expectation, the run completed once, the return's cycle learned once")
    void tenSweepersOneEffect() throws Exception {
        SchemeRun scheme = fiveLineSchemeRun("J", day(9));

        race(matching);

        assertThat(runStatus(scheme.runId())).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " run_id = ?", scheme.runId()))
                .as("with the try-lock the losers skip: one decision per line, exactly")
                .isEqualTo(5);
        for (int lineNo = 1; lineNo <= 5; lineNo++) {
            assertThat(decisionCount(scheme.runId(), lineNo)).isEqualTo(1);
        }
        for (UUID expectation : scheme.expectations()) {
            assertSettledOnce(expectation);
            assertThat(count("SELECT count(*) FROM reconciliation.expectation_event WHERE"
                    + " expectation_id = ? AND event_type = 'ALLOCATED'", expectation))
                    .isEqualTo(1);
            assertThat(settledEvents(expectation)).isEqualTo(1);
        }
        assertLinesDisposed(scheme);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.RunCompleted' AND target_id = ?",
                scheme.runId().toString())).isEqualTo(1);
    }

    // ----------------------------------------------------------------- the collision

    @Test
    @Order(11)
    @DisplayName("(k) a pay-in and a parking opened under one SCHEME_REF: the second opening's"
            + " key converges and the collision is recorded, the key-collision leg raises ONE"
            + " DUPLICATE_INTERNAL on the collider - and a line quoting the reference still"
            + " settles the key's one owner; nothing fails")
    void aSchemeReferenceCollisionIsRecordedAndRaisedNeverFailed() throws SQLException {
        LocalDate day = day(10);
        String cycle = cycle("K");
        String shared = reference("K-SHARED");
        Opened payIn =
                openWithResult(ExpectationKind.PUSH_PAY_IN, 90_00, day, Optional.of(cycle),
                        schemeRef(shared), endToEnd(reference("K-E2E")));
        Opened parking =
                openWithResult(ExpectationKind.UNMATCHED_CONFIRMATION, 90_00, day,
                        Optional.of(cycle), schemeRef(shared));

        assertThat(payIn.result()).isEqualTo(ExpectationRegister.OpenResult.OPENED);
        assertThat(parking.result())
                .as("the colliding expectation still opens: the completion never fails")
                .isEqualTo(ExpectationRegister.OpenResult.OPENED);
        assertThat(one("SELECT expectation_id FROM reconciliation.expectation_key WHERE"
                + " source_id = ? AND key_kind = 'SCHEME_REF' AND key_value = ?",
                SOURCE, shared))
                .as("the first writer holds the key")
                .isEqualTo(payIn.id());
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_key WHERE"
                + " expectation_id = ?", parking.id())).isZero();
        assertThat(row("SELECT event_type, detail FROM reconciliation.expectation_event WHERE"
                + " expectation_id = ? AND event_type = 'KEY_COLLISION'", parking.id()))
                .containsExactly("KEY_COLLISION", "SCHEME_REF:" + shared);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_event WHERE"
                + " expectation_id = ? AND event_type = 'KEY_COLLISION'", payIn.id())).isZero();

        KeyCollisionBreaks leg = new KeyCollisionBreaks(breakRegister, new JdbcRuleSets(), IDS);
        int created =
                runner().inTransaction(
                        unitOfWork ->
                                leg.raiseFromRecordedCollisions(
                                        unitOfWork, 1_000, PLATFORM, Instant.now(),
                                        CorrelationId.generate(IDS)));
        // The container may hold other suites' unraised collisions, each raised once here.
        assertThat(created).isGreaterThanOrEqualTo(1);
        assertThat(row("SELECT type, cause, source_id, rule_set_id, value_at_issue_minor,"
                + " status FROM reconciliation.break WHERE expectation_id = ? AND type ="
                + " 'DUPLICATE_INTERNAL'", parking.id()))
                .containsExactly(
                        "DUPLICATE_INTERNAL", "KEY_COLLISION", SOURCE, RULE_SET, 90_00L,
                        "OPEN");
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE expectation_id = ?"
                + " AND type = 'DUPLICATE_INTERNAL'", payIn.id()))
                .as("the standing owner's key holds; the question is the collider's")
                .isZero();
        runner().inTransaction(
                unitOfWork ->
                        leg.raiseFromRecordedCollisions(
                                unitOfWork, 1_000, PLATFORM, Instant.now(),
                                CorrelationId.generate(IDS)));
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE expectation_id = ?"
                + " AND type = 'DUPLICATE_INTERNAL'", parking.id()))
                .as("the leg is idempotent")
                .isEqualTo(1);

        UUID runId = seedRun(cycle, credit(1, 90_00, day, Map.of(ItemKeyKind.SCHEME_REF, shared)));
        matching.sweep();
        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(itemStatus(runId, 1)).isEqualTo("MATCHED");
        assertThat(expectationStatus(payIn.id())).isEqualTo("SETTLED");
        assertThat(row("SELECT status, allocated_minor FROM reconciliation.expectation WHERE"
                + " id = ?", parking.id())).containsExactly("OPEN", 0L);
        assertThat(row("SELECT matched_key_kind, rule_priority, outcome FROM"
                + " reconciliation.match_decision WHERE id = ?", decisionOf(runId, 1)))
                .containsExactly("SCHEME_REF", 1, "MATCHED");
    }

    // ----------------------------------------------------------------- the refund answer

    @Test
    @Order(12)
    @DisplayName("(l) a DEBIT_OUT naming a return the lookup answers TERMINAL (a refund) and"
            + " reaching no expectation parks at once as REFUND_MISMATCH(REFUND_CONTRADICTED),"
            + " never a reversal's - looked up under the item's key scope; a failed withdrawal"
            + " stays REVERSAL_MISMATCH")
    void aFailedReturnIsARefundMismatch() throws SQLException {
        LocalDate day = day(11);
        String cycle = cycle("L");
        String failedReturn = FAILED_RETURN + UUID.randomUUID().toString().substring(0, 13);
        String failedWithdrawal =
                FAILED_WITHDRAWAL + UUID.randomUUID().toString().substring(0, 13);
        UUID runId =
                seedRun(cycle,
                        debit(1, 25_00, day, Map.of(ItemKeyKind.OUR_REF, failedReturn)),
                        debit(2, 35_00, day, Map.of(ItemKeyKind.OUR_REF, failedWithdrawal)));

        matching.sweep();

        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(itemStatus(runId, 1)).isEqualTo("PARKED");
        assertThat(one("SELECT grace_until FROM reconciliation.external_item WHERE id = ?",
                itemId(runId, 1))).as("parked at once: no grace clock").isNull();
        assertThat(row("SELECT outcome, rule_priority, strategy FROM"
                + " reconciliation.match_decision WHERE id = ?", decisionOf(runId, 1)))
                .containsExactly("PARKED", null, null);
        assertThat(row("SELECT type, cause, internal_classification, internal_operation_ref,"
                + " internal_state, value_at_issue_minor FROM reconciliation.break WHERE"
                + " external_item_id = ?", itemId(runId, 1)))
                .containsExactly(
                        "REFUND_MISMATCH", "REFUND_CONTRADICTED", "TERMINAL", "rtn-1",
                        "FAILED", 25_00L);
        assertThat(row("SELECT origin, amount_minor - released_minor FROM"
                + " reconciliation.suspense_item WHERE external_item_id = ?", itemId(runId, 1)))
                .containsExactly("RECON_PARK", 25_00L);
        assertThat(LOOKED_UP_UNDER.get(failedReturn))
                .as("the lookup subject carries the item's key-scope source")
                .isEqualTo(Optional.of(SOURCE));

        assertThat(itemStatus(runId, 2)).isEqualTo("PARKED");
        assertThat(row("SELECT type, cause, internal_operation_ref FROM reconciliation.break"
                + " WHERE external_item_id = ?", itemId(runId, 2)))
                .as("what the lookup found decides the type, not the line")
                .containsExactly("REVERSAL_MISMATCH", "TERMINAL_STATE_CONTRADICTED", "wdl-1");
    }

    // ----------------------------------------------------------------- a parked line re-matched

    @Test
    @Order(13)
    @DisplayName("(m) a PARKED line re-matched observes the cycle as any allocation does: a"
            + " DEBIT_OUT parked on a failed withdrawal's answer, then reached by its return's"
            + " OUR_REF, is MATCHED and LEARNS the run's cycle; a parked CREDIT_IN re-matched to a"
            + " pay-in that announced another cycle carries ONE zero-value"
            + " TIMING_DIFFERENCE(CYCLE_MISMATCH) - both parks released whole")
    void aParkedLineRematchedObservesTheCycle() throws SQLException {
        LocalDate day = day(13);
        String cycle = cycle("M");
        String returnOurs = reference("M-RTN-OUR");
        String payInEndToEnd = reference("M-PI-E2E");
        UUID runId =
                seedRun(cycle,
                        debit(1, 45_00, day,
                                Map.of(ItemKeyKind.SCHEME_REF,
                                        FAILED_WITHDRAWAL
                                                + UUID.randomUUID().toString().substring(0, 13),
                                        ItemKeyKind.OUR_REF, returnOurs)),
                        credit(2, 55_00, day,
                                Map.of(ItemKeyKind.SCHEME_REF,
                                        FAILED_WITHDRAWAL
                                                + UUID.randomUUID().toString().substring(0, 13),
                                        ItemKeyKind.END_TO_END_REF, payInEndToEnd)));
        matching.sweep();
        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(itemStatus(runId, 1)).as("a terminal answer parks at once").isEqualTo("PARKED");
        assertThat(itemStatus(runId, 2)).isEqualTo("PARKED");

        UUID pushReturn =
                open(ExpectationKind.PUSH_RETURN, 45_00, day, Optional.empty(),
                        schemeRef(reference("M-RTN")), ourRef(returnOurs));
        UUID payIn =
                open(ExpectationKind.PUSH_PAY_IN, 55_00, day, Optional.of(cycle("M-ANNOUNCED")),
                        schemeRef(reference("M-PI")), endToEnd(payInEndToEnd));
        matching.sweep();

        UUID debit = itemId(runId, 1);
        assertThat(itemStatus(runId, 1)).isEqualTo("MATCHED");
        assertThat(expectationStatus(pushReturn)).isEqualTo("SETTLED");
        assertThat(learnedCycle(debit))
                .as("the return announced no cycle: the parked line re-matched to it learns its"
                        + " run's")
                .isEqualTo(cycle);
        UUID returnRematch = rematchOf(debit);
        assertThat(row("SELECT matched_key_kind, outcome FROM reconciliation.match_decision"
                + " WHERE id = ?", returnRematch))
                .containsExactly("OUR_REF", "MATCHED");
        assertThat(timingBreaksOn(returnRematch))
                .as("a cycle-less return is never a cycle shift")
                .isZero();

        UUID credit = itemId(runId, 2);
        assertThat(itemStatus(runId, 2)).isEqualTo("MATCHED");
        assertThat(expectationStatus(payIn)).isEqualTo("SETTLED");
        assertThat(learnedCycle(credit)).as("the pay-in announced a cycle").isNull();
        UUID payInRematch = rematchOf(credit);
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE decision_id = ?",
                payInRematch)).as("one break per decision").isEqualTo(1);
        assertThat(row("SELECT type, cause, value_at_issue_minor, status FROM"
                + " reconciliation.break WHERE decision_id = ?", payInRematch))
                .containsExactly("TIMING_DIFFERENCE", "CYCLE_MISMATCH", 0L, "OPEN");

        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item s JOIN"
                + " reconciliation.external_item i ON s.external_item_id = i.id WHERE"
                + " i.run_id = ? AND s.released_minor = s.amount_minor", runId))
                .as("each park's exact inverse: both released whole")
                .isEqualTo(2);
    }

    /** The one REMATCH decision on an item - decided_at ties under the pinned clock. */
    private static UUID rematchOf(UUID itemId) throws SQLException {
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " external_item_id = ? AND origin = 'REMATCH'", itemId)).isEqualTo(1);
        return (UUID) one("SELECT id FROM reconciliation.match_decision WHERE"
                + " external_item_id = ? AND origin = 'REMATCH'", itemId);
    }

    // ----------------------------------------------------------------- the lock only orders

    @Test
    @Order(14)
    @DisplayName("(j) the lock only ORDERS: bypassed, ten sweepers over a fresh five-line run"
            + " still allocate each (item, expectation) at most once, never above an"
            + " expectation's amount, park nothing, and complete the run once")
    void theLockOnlyOrders() throws Exception {
        SchemeRun scheme = fiveLineSchemeRun("JB", day(12));

        race(matching(true));

        // A bypassed loser may record its losing evaluation - money may not move twice.
        assertThat(runStatus(scheme.runId())).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM (SELECT a.external_item_id, a.expectation_id"
                + " FROM reconciliation.allocation a JOIN reconciliation.external_item i ON"
                + " i.id = a.external_item_id WHERE i.run_id = ? AND"
                + " a.reverses_allocation_id IS NULL GROUP BY a.external_item_id,"
                + " a.expectation_id HAVING count(*) > 1) doubled", scheme.runId()))
                .as("at most one positive allocation per (item, expectation)")
                .isZero();
        for (UUID expectation : scheme.expectations()) {
            assertSettledOnce(expectation);
        }
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item s JOIN"
                + " reconciliation.external_item i ON s.external_item_id = i.id WHERE"
                + " i.run_id = ?", scheme.runId()))
                .as("no loser parked a line another sweeper matched")
                .isZero();
        assertLinesDisposed(scheme);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.RunCompleted' AND target_id = ?",
                scheme.runId().toString())).isEqualTo(1);
    }

    // ----------------------------------------------------------------- the five-line run

    /** One scheme run's facts: its id, its cycle, and its four expectations in line order. */
    private record SchemeRun(UUID runId, String cycle, List<UUID> expectations) {}

    /**
     * A committed scheme run of five lines and its four expectations: a pay-in by SCHEME_REF, a
     * withdrawal by SCHEME_REF, a cycle-less return by OUR_REF, a parking by SCHEME_REF, and the
     * pay-in's fee at its exact terms.
     */
    private static SchemeRun fiveLineSchemeRun(String tag, LocalDate day) {
        String cycle = cycle(tag);
        String payInScheme = reference(tag + "-PAY");
        String withdrawalScheme = reference(tag + "-WDL");
        String returnOurs = reference(tag + "-RTN-OUR");
        String parkingScheme = reference(tag + "-PARK");
        List<UUID> opened =
                List.of(
                        open(ExpectationKind.PUSH_PAY_IN, 10_00, day, Optional.of(cycle),
                                schemeRef(payInScheme), endToEnd(reference(tag + "-PAY-E2E"))),
                        open(ExpectationKind.PUSH_WITHDRAWAL, 20_00, day, Optional.of(cycle),
                                schemeRef(withdrawalScheme),
                                endToEnd(reference(tag + "-WDL-E2E"))),
                        open(ExpectationKind.PUSH_RETURN, 30_00, day, Optional.empty(),
                                schemeRef(reference(tag + "-RTN")), ourRef(returnOurs)),
                        open(ExpectationKind.UNMATCHED_CONFIRMATION, 40_00, day,
                                Optional.of(cycle), schemeRef(parkingScheme)));
        UUID runId =
                seedRun(cycle,
                        credit(1, 10_00, day, Map.of(ItemKeyKind.SCHEME_REF, payInScheme)),
                        debit(2, 20_00, day, Map.of(ItemKeyKind.SCHEME_REF, withdrawalScheme)),
                        debit(3, 30_00, day, Map.of(ItemKeyKind.OUR_REF, returnOurs)),
                        credit(4, 40_00, day, Map.of(ItemKeyKind.SCHEME_REF, parkingScheme)),
                        fee(5, 10, day, payInScheme));
        return new SchemeRun(runId, cycle, opened);
    }

    /** Ten concurrent sweeps, started together. */
    private static void race(Matching racerShape) throws Exception {
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
    }

    /** One allocation's worth, never two, never above the amount: SETTLED exactly. */
    private static void assertSettledOnce(UUID expectation) throws SQLException {
        assertThat(expectationStatus(expectation)).isEqualTo("SETTLED");
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                + " expectation_id = ? AND reverses_allocation_id IS NULL", expectation))
                .isEqualTo(1);
        Object[] value = row("SELECT allocated_minor, amount_minor FROM"
                + " reconciliation.expectation WHERE id = ?", expectation);
        assertThat(value[0]).as("allocated exactly the amount").isEqualTo(value[1]);
    }

    /** Four lines MATCHED, the fee CHECKED, and the return's cycle learned - once. */
    private static void assertLinesDisposed(SchemeRun scheme) throws SQLException {
        for (int lineNo = 1; lineNo <= 4; lineNo++) {
            assertThat(itemStatus(scheme.runId(), lineNo)).isEqualTo("MATCHED");
        }
        assertThat(itemStatus(scheme.runId(), 5)).isEqualTo("CHECKED");
        assertThat(learnedCycle(itemId(scheme.runId(), 3))).isEqualTo(scheme.cycle());
        for (int lineNo : new int[] {1, 2, 4}) {
            assertThat(learnedCycle(itemId(scheme.runId(), lineNo))).isNull();
        }
    }

    // ----------------------------------------------------------------- seeding

    private record Line(
            int lineNo,
            ExternalLineType type,
            ExpectationDirection direction,
            long minor,
            LocalDate settlementDate,
            Map<ItemKeyKind, String> keys) {}

    private static Line credit(
            int lineNo, long minor, LocalDate day, Map<ItemKeyKind, String> keys) {
        return new Line(lineNo, ExternalLineType.CREDIT_IN, ExpectationDirection.INBOUND,
                minor, day, keys);
    }

    private static Line debit(
            int lineNo, long minor, LocalDate day, Map<ItemKeyKind, String> keys) {
        return new Line(lineNo, ExternalLineType.DEBIT_OUT, ExpectationDirection.OUTBOUND,
                minor, day, keys);
    }

    /** The scheme's fee naming its execution by the scheme's reference. */
    private static Line fee(int lineNo, long minor, LocalDate day, String originalScheme) {
        return new Line(lineNo, ExternalLineType.SCHEME_FEE, ExpectationDirection.OUTBOUND,
                minor, day, Map.of(ItemKeyKind.ORIGINAL_REF, originalScheme));
    }

    private static LocalDate day(int caseNo) {
        return BASE.plusDays(10L * caseNo);
    }

    private static String reference(String tag) {
        return "SIMSCH-" + tag + "-" + UUID.randomUUID().toString().substring(0, 13);
    }

    private static String cycle(String tag) {
        return "SIMSCHEME-CYC-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static byte[] fingerprint() {
        byte[] print = new byte[32];
        new SecureRandom().nextBytes(print);
        return print;
    }

    private static ReconciliationRuns.NewRun newRun(
            UUID runId, Optional<String> cycle, LocalDate businessDate, int itemCount) {
        return new ReconciliationRuns.NewRun(
                runId,
                SOURCE,
                Optional.of(IDS.next()),
                RunKind.BATCH,
                RULE_SET,
                businessDate,
                Optional.of(SEQUENCES.incrementAndGet()),
                itemCount,
                Optional.empty(),
                Optional.empty(),
                PLATFORM,
                Instant.now(CLOCK),
                CorrelationId.generate(IDS),
                cycle);
    }

    /**
     * The working copy a scheme line's acceptance births: the instant clearing position, the
     * counterparty's settlement date as business, settlement and value date, PENDING.
     */
    private static ExternalItems.NewItem newItem(UUID itemId, UUID runId, Line line) {
        return new ExternalItems.NewItem(
                itemId,
                runId,
                SOURCE,
                IDS.next(),
                line.lineNo(),
                line.type(),
                line.direction(),
                Money.ofPersisted(line.minor(), EUR, 2),
                AccountPurpose.INSTANT_CLEARING,
                line.settlementDate(),
                Optional.of(line.settlementDate()),
                Optional.of(line.settlementDate()),
                fingerprint(),
                line.keys(),
                Instant.now(CLOCK),
                CorrelationId.generate(IDS));
    }

    /** A committed scheme cycle report's run - its cycle the run's birth fact - with its items. */
    private static UUID seedRun(String cycle, Line... lines) {
        UUID runId = IDS.next();
        return runner().inTransaction(unitOfWork -> {
            runs.birth(unitOfWork,
                    newRun(runId, Optional.of(cycle), lines[0].settlementDate(), lines.length));
            List<ExternalItems.NewItem> newItems = new ArrayList<>();
            for (Line line : lines) {
                newItems.add(newItem(IDS.next(), runId, line));
            }
            items.birthAll(unitOfWork, PLATFORM, newItems);
            return runId;
        });
    }

    private record Opened(UUID id, ExpectationRegister.OpenResult result) {}

    private static UUID open(
            ExpectationKind kind,
            long minor,
            LocalDate expectedBy,
            Optional<String> announcedCycle,
            NewExpectation.ExpectationKey... keys) {
        return openWithResult(kind, minor, expectedBy, announcedCycle, keys).id();
    }

    /**
     * A push stage's expectation under the private source, as its completing transaction opens
     * it - in the instant clearing position, the cycle it announced (none for a return) - opened
     * on the REAL clock (see the class note).
     */
    private static Opened openWithResult(
            ExpectationKind kind,
            long minor,
            LocalDate expectedBy,
            Optional<String> announcedCycle,
            NewExpectation.ExpectationKey... keys) {
        String operationRef = kind.name().toLowerCase(Locale.ROOT) + "-" + UUID.randomUUID();
        ExpectationDirection direction =
                kind == ExpectationKind.PUSH_PAY_IN
                                || kind == ExpectationKind.UNMATCHED_CONFIRMATION
                        ? ExpectationDirection.INBOUND
                        : ExpectationDirection.OUTBOUND;
        return runner().inTransaction(unitOfWork -> {
            UUID position =
                    new JdbcLedgerAccountStore()
                            .findOperational(unitOfWork, AccountPurpose.INSTANT_CLEARING, EUR)
                            .orElseThrow()
                            .id()
                            .value();
            ExpectationRegister.OpenResult result =
                    expectations.open(
                            unitOfWork,
                            new NewExpectation(
                                    kind,
                                    operationRef,
                                    "push:" + operationRef,
                                    SOURCE,
                                    AccountPurpose.INSTANT_CLEARING,
                                    position,
                                    direction,
                                    Money.ofPersisted(minor, EUR, 2),
                                    Optional.of(IDS.next()),
                                    expectedBy.minusDays(1),
                                    announcedCycle,
                                    expectedBy,
                                    RULE_SET,
                                    List.of(keys),
                                    PLATFORM,
                                    Instant.now(),
                                    CorrelationId.generate(IDS)));
            try (PreparedStatement read =
                    unitOfWork.prepareStatement(
                            "SELECT id FROM reconciliation.expectation WHERE kind = ? AND"
                                    + " operation_ref = ?")) {
                read.setString(1, kind.name());
                read.setString(2, operationRef);
                try (ResultSet row = read.executeQuery()) {
                    row.next();
                    return new Opened(row.getObject("id", UUID.class), result);
                }
            } catch (SQLException failure) {
                throw new ReconciliationStorageException("could not seed", failure);
            }
        });
    }

    private static NewExpectation.ExpectationKey schemeRef(String value) {
        return new NewExpectation.ExpectationKey(KeyKind.SCHEME_REF, value);
    }

    private static NewExpectation.ExpectationKey endToEnd(String value) {
        return new NewExpectation.ExpectationKey(KeyKind.END_TO_END_REF, value);
    }

    private static NewExpectation.ExpectationKey ourRef(String value) {
        return new NewExpectation.ExpectationKey(KeyKind.OUR_REF, value);
    }

    /** Ageing's overdue break on a pay-in, stamped with the private source as ageing does. */
    private static UUID raiseOverdue(UUID expectation, long minor) {
        return runner().inTransaction(
                unitOfWork ->
                        breakRegister
                                .raise(
                                        unitOfWork,
                                        new BreakRegister.NewBreak(
                                                IDS.next(),
                                                BreakType.MISSING_EXTERNAL,
                                                BreakCause.EXPECTATION_OVERDUE,
                                                BreakRegister.Subject.expectation(expectation),
                                                SOURCE,
                                                RULE_SET,
                                                Money.ofPersisted(minor, EUR, 2),
                                                Optional.of(ExpectationDirection.INBOUND),
                                                Optional.of(ExpectationKind.PUSH_PAY_IN),
                                                Optional.empty(),
                                                Optional.empty(),
                                                Optional.empty(),
                                                Optional.empty(),
                                                PLATFORM,
                                                Instant.now(),
                                                CorrelationId.generate(IDS)))
                                .breakId());
    }

    // ----------------------------------------------------------------- raw writers

    /** A database role a raw writer connects as. */
    @FunctionalInterface
    private interface Role {
        Connection connect() throws SQLException;
    }

    /** The statement is refused for {@code role}, by the named SQLState, in its own words. */
    private static void assertRefused(
            Role role, String sql, String expectedState, String fragment, Object... args)
            throws SQLException {
        try (Connection writer = role.connect()) {
            writer.setAutoCommit(false);
            Throwable refusal = catchThrowable(() -> execute(writer, sql, args));
            assertThat(refusal)
                    .as("refused: %s", sql)
                    .isNotNull()
                    .hasStackTraceContaining(fragment);
            assertThat(sqlState(refusal)).as("the refusal's SQLState").isEqualTo(expectedState);
            writer.rollback();
        }
    }

    private static String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
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

    private static int update(Connection unitOfWork, String sql, Object... args) {
        try (PreparedStatement statement = unitOfWork.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("statement failed: " + sql, failure);
        }
    }

    private static String runStatus(UUID runId) throws SQLException {
        return string("SELECT status FROM reconciliation.reconciliation_batch WHERE id = ?",
                runId);
    }

    private static String itemStatus(UUID runId, int lineNo) throws SQLException {
        return string("SELECT status FROM reconciliation.external_item WHERE run_id = ?"
                + " AND line_no = " + lineNo, runId);
    }

    private static UUID itemId(UUID runId, int lineNo) throws SQLException {
        return (UUID) one("SELECT id FROM reconciliation.external_item WHERE run_id = ?"
                + " AND line_no = " + lineNo, runId);
    }

    private static String learnedCycle(UUID itemId) throws SQLException {
        return string("SELECT learned_cycle FROM reconciliation.external_item WHERE id = ?",
                itemId);
    }

    private static String expectationStatus(UUID expectationId) throws SQLException {
        return string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                expectationId);
    }

    private static UUID decisionOf(UUID runId, int lineNo) throws SQLException {
        return (UUID) one("SELECT d.id FROM reconciliation.match_decision d JOIN"
                + " reconciliation.external_item i ON i.id = d.external_item_id WHERE"
                + " i.run_id = ? AND i.line_no = " + lineNo
                + " ORDER BY d.decided_at DESC, d.id DESC LIMIT 1", runId);
    }

    private static long decisionCount(UUID runId, int lineNo) throws SQLException {
        return count("SELECT count(*) FROM reconciliation.match_decision d JOIN"
                + " reconciliation.external_item i ON i.id = d.external_item_id WHERE"
                + " i.run_id = ? AND i.line_no = " + lineNo, runId);
    }

    private static long timingBreaksOn(UUID decisionId) throws SQLException {
        return count("SELECT count(*) FROM reconciliation.break WHERE decision_id = ? AND"
                + " type = 'TIMING_DIFFERENCE'", decisionId);
    }

    private static long settledEvents(UUID expectationId) throws SQLException {
        return count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.SettlementExpectationSettled' AND aggregate_id = ?",
                expectationId);
    }

    private static long count(String sql, Object... args) throws SQLException {
        return ((Number) one(sql, args)).longValue();
    }

    private static String string(String sql, Object... args) throws SQLException {
        return (String) one(sql, args);
    }

    private static Object[] row(String sql, Object... args) throws SQLException {
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
