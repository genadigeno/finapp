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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 * The payout provider's report matched against the real schema, the real ledger and real
 * commits (`P8-TSK-018`, ADR-0065 §2, ADR-0068 §§1-8, ADR-0069 §2): a {@code PAYOUT_EXECUTED}
 * line reaches its {@code MERCHANT_PAYOUT} by {@code PAYOUT_PROVIDER_REF} first and
 * {@code OUR_REF} second; the provider's fee is judged against its pinned flat terms through its
 * original's provider reference; and a {@code PAYOUT_RETURNED} line - whose references ARE its
 * payout's own keys - rides the OPERATION-ANCHORED rule: the key reaches the payout only as its
 * operation's anchor, and the candidate is that operation's {@code PAYOUT_RETURN}, never the
 * OUTBOUND payout (the transition's A4). Finding no return, it waits under the return rule's 72
 * hours with no break; at grace a payout the platform knows parks
 * {@code REVERSAL_MISMATCH(RETURN_NOT_APPLICABLE)}, an unknown one
 * {@code UNKNOWN_EXTERNAL(GRACE_EXPIRED)}, and a FAILED payout's return parks at once. Ten
 * sweepers - or ten with the try-lock BYPASSED - move each expectation's money once; and `V010`'s
 * in-place completion of the payout rule set v1 refuses once that rule set has deciding history.
 *
 * <p>The suite owns a PRIVATE payout-shaped source and rule set (the {@code MatchingDatabaseTest}
 * discipline): `V002`'s payout rows and `V010`'s fee terms copied onto a pair only this suite
 * writes, every position {@code PAYOUT_CLEARING}. The Matching clock is pinned months behind the
 * database clock (the {@code GraceAndRematchDatabaseTest} discipline) and expectations open on
 * the REAL clock, so "opened after the item's latest decision" holds for an expectation opened
 * after a sweep - and, note, for every anchor too: a waiting return's own payout keys keep it on
 * the rematch worklist every tick, where the anchored resolution reaches nothing and the leg
 * writes nothing. The rematch leg's anchored clause (a {@code PAYOUT_RETURN} opened AFTER the
 * return's decision) is `P8-TSK-019`'s and is deliberately not exercised here. Ordered only so
 * the lock-bypassed race, which leaves its losers' records, runs last.
 */
@Tag("database")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the payout report's matching: the payout's keys, the provider's fee and the"
        + " operation-anchored return (P8-TSK-018)")
class PayoutMatchingDatabaseTest {

    /** Months behind the database clock, on purpose - see the class note. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor PLATFORM = new Actor("system", ActorType.SYSTEM);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    /** A private payout-shaped source and rule set: `V002`'s payout rows, this suite's alone. */
    private static final UUID SOURCE = UUID.fromString("01a0e2bc-8200-7018-8000-000000000018");
    private static final UUID RULE_SET =
            UUID.fromString("01a0e2bd-8300-7018-8000-000000000018");
    /** The REAL payout source and rule set v1 - written only inside case (l)'s rollback. */
    private static final UUID PAYOUT_SOURCE =
            UUID.fromString("01a0e2bc-8200-7003-8000-000000000003");
    private static final UUID PAYOUT_RULE_SET =
            UUID.fromString("01a0e2bd-8300-7003-8000-000000000003");
    private static final String V010 =
            "db/migration/reconciliation/V010__payout_items_and_the_payout_fee_terms.sql";
    /** A fresh span of future dates per suite run: nothing here ever ages. */
    private static final LocalDate BASE =
            LocalDate.parse("2026-11-01").plusDays(100L * new SecureRandom().nextInt(30));
    private static final AtomicLong SEQUENCES = new AtomicLong(System.nanoTime() % 70_000);

    /** A payout the platform COMPLETED: the lookup's known answer (cases d, e). */
    private static final String COMPLETED_PAYOUT = "po_done_";
    /** A payout the platform concluded FAILED: the lookup's terminal answer (case g). */
    private static final String FAILED_PAYOUT = "po_failed_";
    /** Each planted provider reference's payout id - what the lookup names as its operation. */
    private static final Map<String, String> PAYOUT_OF = new ConcurrentHashMap<>();
    /** The key-scope source each looked-up reference was classified under. */
    private static final Map<String, Optional<UUID>> LOOKED_UP_UNDER = new ConcurrentHashMap<>();

    /** Types by the reference's planted prefix - the app-composed lookup's shape, made pure. */
    private static final InternalReferenceLookup LOOKUP =
            (unitOfWork, subject) -> {
                for (String value : subject.references().values()) {
                    LOOKED_UP_UNDER.put(value, subject.scopeSourceId());
                }
                for (String value : subject.references().values()) {
                    if (value.startsWith(COMPLETED_PAYOUT)) {
                        return new InternalReferenceLookup.InternalReference(
                                InternalClassification.COMPLETED,
                                Optional.ofNullable(PAYOUT_OF.get(value)),
                                Optional.of("COMPLETED"),
                                Optional.of(InternalSubject.PAYOUT));
                    }
                    if (value.startsWith(FAILED_PAYOUT)) {
                        return new InternalReferenceLookup.InternalReference(
                                InternalClassification.TERMINAL,
                                Optional.ofNullable(PAYOUT_OF.get(value)),
                                Optional.of("FAILED"),
                                Optional.of(InternalSubject.PAYOUT));
                    }
                }
                return InternalReferenceLookup.InternalReference.unknown();
            };

    private static Connection application;
    private static JdbcReconciliationRuns runs;
    private static JdbcExternalItems items;
    private static JdbcExpectationRegister expectations;
    private static Matching matching;

    @BeforeAll
    static void connect() throws SQLException {
        application = DatabaseRoles.application();
        application.setAutoCommit(false);
        DatabaseRoles.assertCannotBypassPrivileges(application);
        runs = new JdbcReconciliationRuns();
        items = new JdbcExternalItems();
        expectations = new JdbcExpectationRegister(IDS);
        matching = matching(false);
        seedPrivatePayoutSource();
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
     * `V002`'s payout rule set copied onto the private pair - the executed line's two ONE_TO_ONE
     * rules onto {@code MERCHANT_PAYOUT} (grace 48), the returned line's two OPERATION-ANCHORED
     * rules onto {@code PAYOUT_RETURN} (grace 72), the lags, the date window and the thresholds -
     * plus `V010`'s completion: the fee's CHECK rule at priority 5 and the flat 0.25 schedule,
     * with NO fee tolerance row (it reads zero).
     */
    private static void seedPrivatePayoutSource() {
        runner().inTransaction(
                unitOfWork -> {
                    execute(unitOfWork,
                            "INSERT INTO reconciliation.rule_set (id, source_id, version,"
                                    + " status, funding_lag_days, gain_min_age_days,"
                                    + " effective_from, proposed_by, decided_by, reason,"
                                    + " created_at, correlation_id) VALUES (?, ?, 1,"
                                    + " 'ACTIVE', 2, 90, ?, 'test', 'test',"
                                    + " 'PayoutMatchingDatabaseTest private payout source',"
                                    + " now(), 'p8-tsk-018-test') ON CONFLICT (id) DO NOTHING",
                            RULE_SET, SOURCE,
                            java.sql.Date.valueOf(LocalDate.parse("2026-09-29")));
                    execute(unitOfWork,
                            "INSERT INTO reconciliation.rule_set_lag (rule_set_id,"
                                    + " expectation_kind, lag_days) VALUES"
                                    + " (?, 'MERCHANT_PAYOUT', 2), (?, 'PAYOUT_RETURN', 2)"
                                    + " ON CONFLICT DO NOTHING",
                            RULE_SET, RULE_SET);
                    execute(unitOfWork,
                            "INSERT INTO reconciliation.rule (rule_set_id, priority,"
                                    + " line_type, key_kind, expectation_kind, cardinality,"
                                    + " operation_anchored, grace_hours) VALUES"
                                    + " (?, 1, 'PAYOUT_EXECUTED', 'PAYOUT_PROVIDER_REF',"
                                    + " 'MERCHANT_PAYOUT', 'ONE_TO_ONE', false, 48),"
                                    + " (?, 2, 'PAYOUT_EXECUTED', 'OUR_REF',"
                                    + " 'MERCHANT_PAYOUT', 'ONE_TO_ONE', false, 48),"
                                    + " (?, 3, 'PAYOUT_RETURNED', 'PAYOUT_PROVIDER_REF',"
                                    + " 'PAYOUT_RETURN', 'ONE_TO_ONE', true, 72),"
                                    + " (?, 4, 'PAYOUT_RETURNED', 'OUR_REF',"
                                    + " 'PAYOUT_RETURN', 'ONE_TO_ONE', true, 72),"
                                    + " (?, 5, 'PAYOUT_FEE', NULL, NULL, 'CHECK', false, 48)"
                                    + " ON CONFLICT DO NOTHING",
                            RULE_SET, RULE_SET, RULE_SET, RULE_SET, RULE_SET);
                    // The date window's currency is NULL, which the tolerance unique never
                    // matches: guarded by existence so a reused container holds one row.
                    execute(unitOfWork,
                            "INSERT INTO reconciliation.tolerance (rule_set_id, comparison,"
                                    + " currency, absolute_minor, days)"
                                    + " SELECT ?, 'SETTLEMENT_DATE_DAYS', NULL::char(3),"
                                    + " NULL::bigint, 2 WHERE NOT EXISTS (SELECT 1 FROM"
                                    + " reconciliation.tolerance WHERE rule_set_id = ? AND"
                                    + " comparison = 'SETTLEMENT_DATE_DAYS')",
                            RULE_SET, RULE_SET);
                    execute(unitOfWork,
                            "INSERT INTO reconciliation.provider_fee_schedule (rule_set_id,"
                                    + " line_type, currency, rate, fixed_minor, scale,"
                                    + " rounding_policy) VALUES"
                                    + " (?, 'PAYOUT_FEE', 'EUR', 0.000000, 25, 2, 'HALF_UP'),"
                                    + " (?, 'PAYOUT_FEE', 'GBP', 0.000000, 25, 2, 'HALF_UP'),"
                                    + " (?, 'PAYOUT_FEE', 'USD', 0.000000, 25, 2, 'HALF_UP')"
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

    // ----------------------------------------------------------------- the executed payout

    @Test
    @Order(1)
    @DisplayName("(a) a PAYOUT_EXECUTED line quoting its payout's PAYOUT_PROVIDER_REF settles its"
            + " MERCHANT_PAYOUT: priority 1 by the provider's reference, no timing, no cycle")
    void anExecutedLineSettlesItsPayoutByTheProviderReference() throws SQLException {
        LocalDate day = day(0);
        Payout payout = newPayout("po_a_");
        UUID expectation = openPayout(payout, 100_00, day);
        UUID runId = seedRun(executed(1, 100_00, day, refs(payout)));

        matching.sweep();

        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(string("SELECT settlement_cycle FROM reconciliation.reconciliation_batch"
                + " WHERE id = ?", runId))
                .as("a payout report settles no scheme cycle")
                .isNull();
        assertThat(itemStatus(runId, 1)).isEqualTo("MATCHED");
        assertThat(expectationStatus(expectation)).isEqualTo("SETTLED");
        UUID decision = decisionOf(runId, 1);
        assertThat(row("SELECT strategy, matched_key_kind, rule_priority, outcome, origin,"
                + " date_deviation_days FROM reconciliation.match_decision WHERE id = ?",
                decision))
                .containsExactly("ONE_TO_ONE", "PAYOUT_PROVIDER_REF", 1, "MATCHED", "RUN", 0);
        assertThat(count("SELECT count(*) FROM reconciliation.match_candidate WHERE"
                + " decision_id = ?", decision)).isEqualTo(1);
        assertThat(row("SELECT expectation_id, key_kind, direction FROM"
                + " reconciliation.match_candidate WHERE decision_id = ?", decision))
                .containsExactly(expectation, "PAYOUT_PROVIDER_REF", "OUTBOUND");
        assertThat(row("SELECT expectation_id, amount_minor FROM reconciliation.allocation"
                + " WHERE decision_id = ?", decision))
                .containsExactly(expectation, 100_00L);
        assertThat(count("SELECT allocated_minor FROM reconciliation.expectation WHERE id = ?",
                expectation)).isEqualTo(100_00);
        assertThat(count("SELECT allocated_minor FROM reconciliation.external_item WHERE"
                + " id = ?", itemId(runId, 1))).isEqualTo(100_00);
        assertThat(settledEvents(expectation)).isEqualTo(1);
        assertThat(timingBreaksOn(decision)).isZero();
        assertThat(learnedCycle(itemId(runId, 1))).isNull();
        assertThat(LOOKED_UP_UNDER)
                .as("a line its key reached is never typed by the lookup")
                .doesNotContainKey(payout.providerRef());
    }

    @Test
    @Order(2)
    @DisplayName("(b) a PAYOUT_EXECUTED line whose provider reference reaches nothing falls"
            + " through to its OUR_REF: priority 2")
    void anExecutedLineFallsThroughToOurReference() throws SQLException {
        LocalDate day = day(1);
        Payout payout = newPayout("po_b_");
        UUID expectation = openPayout(payout, 45_00, day);
        UUID runId =
                seedRun(executed(1, 45_00, day,
                        Map.of(ItemKeyKind.PAYOUT_PROVIDER_REF, providerRef("po_b_other_"),
                                ItemKeyKind.OUR_REF, payout.ourRef())));

        matching.sweep();

        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(itemStatus(runId, 1)).isEqualTo("MATCHED");
        assertThat(expectationStatus(expectation)).isEqualTo("SETTLED");
        UUID decision = decisionOf(runId, 1);
        assertThat(row("SELECT strategy, matched_key_kind, rule_priority, outcome FROM"
                + " reconciliation.match_decision WHERE id = ?", decision))
                .containsExactly("ONE_TO_ONE", "OUR_REF", 2, "MATCHED");
        assertThat(row("SELECT expectation_id, key_kind FROM reconciliation.match_candidate"
                + " WHERE decision_id = ?", decision))
                .containsExactly(expectation, "OUR_REF");
        assertThat(settledEvents(expectation)).isEqualTo(1);
        assertThat(timingBreaksOn(decision)).isZero();
    }

    // ----------------------------------------------------------------- the provider's fee

    @Test
    @Order(3)
    @DisplayName("(c) the provider's fee is judged against its pinned flat terms through its"
            + " ORIGINAL_REF's PAYOUT_PROVIDER_REF: 0.25 CHECKED with no break, 0.26 CHECKED"
            + " with FEE_MISMATCH 0.01 - one per breaching line, no batch fold, never parked")
    void payoutFeesAreJudgedByTheirOriginalsProviderReference() throws SQLException {
        LocalDate day = day(2);
        Payout payout = newPayout("po_c_");
        UUID expectation = openPayout(payout, 300_00, day);
        UUID runId =
                seedRun(
                        executed(1, 300_00, day, refs(payout)),
                        fee(2, 25, day, payout.providerRef()),
                        fee(3, 26, day, payout.providerRef()));

        matching.sweep();

        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(itemStatus(runId, 1)).isEqualTo("MATCHED");
        assertThat(expectationStatus(expectation)).isEqualTo("SETTLED");
        assertThat(itemStatus(runId, 2)).isEqualTo("CHECKED");
        assertThat(itemStatus(runId, 3)).isEqualTo("CHECKED");
        assertThat(row("SELECT strategy, rule_priority, outcome, matched_key_kind,"
                + " fee_expected_minor, fee_reported_minor, fee_tolerance_minor FROM"
                + " reconciliation.match_decision WHERE id = ?", decisionOf(runId, 2)))
                .as("the original reached by the provider's reference - an unreachable one"
                        + " would price zero")
                .containsExactly("CHECK", 5, "CHECKED", null, 25L, 25L, 0L);
        assertThat(row("SELECT fee_expected_minor, fee_reported_minor, fee_tolerance_minor"
                + " FROM reconciliation.match_decision WHERE id = ?", decisionOf(runId, 3)))
                .containsExactly(25L, 26L, 0L);
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
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                + " external_item_id IN (?, ?)", itemId(runId, 2), itemId(runId, 3)))
                .as("a fee never allocates")
                .isZero();
        assertThat(count("SELECT allocated_minor FROM reconciliation.expectation WHERE id = ?",
                expectation)).isEqualTo(300_00);
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item s JOIN"
                + " reconciliation.external_item i ON s.external_item_id = i.id WHERE"
                + " i.run_id = ?", runId))
                .as("an expensed fee is judged, never parked")
                .isZero();
    }

    // ----------------------------------------------------------------- the anchored return

    @Test
    @Order(4)
    @DisplayName("(d) a PAYOUT_RETURNED line quoting its payout's own keys, with no"
            + " PAYOUT_RETURN, never meets the OUTBOUND MERCHANT_PAYOUT - SETTLED or OPEN: it"
            + " waits UNMATCHED under the return rule's 72 hours with no break and no candidate"
            + " row, the payout untouched")
    void aReturnWithNoPayoutReturnWaitsAndNeverMeetsItsPayout() throws SQLException {
        LocalDate day = day(3);
        // The realistic variant: the payout was executed (its report settled it), later returned.
        Payout executedThenReturned = newPayout(COMPLETED_PAYOUT);
        // The contrast: a payout whose execution no report has stated yet - its expectation OPEN.
        Payout stillOpen = newPayout(COMPLETED_PAYOUT);
        UUID settledPayout = openPayout(executedThenReturned, 120_00, day);
        UUID unexecutedPayout = openPayout(stillOpen, 80_00, day);
        UUID executedRun = seedRun(executed(1, 120_00, day, refs(executedThenReturned)));
        matching.sweep();
        assertThat(runStatus(executedRun)).isEqualTo("COMPLETED");
        assertThat(expectationStatus(settledPayout)).isEqualTo("SETTLED");
        Object[] settledBefore = payoutFacts(settledPayout);
        Object[] openBefore = payoutFacts(unexecutedPayout);

        UUID returnRun =
                seedRun(
                        returned(1, 120_00, day, refs(executedThenReturned)),
                        returned(2, 80_00, day, refs(stillOpen)));
        matching.sweep();

        assertThat(runStatus(returnRun)).isEqualTo("COMPLETED");
        for (int lineNo = 1; lineNo <= 2; lineNo++) {
            UUID item = itemId(returnRun, lineNo);
            assertThat(itemStatus(returnRun, lineNo))
                    .as("line %d: an anchored rule that reaches no return has reached nothing",
                            lineNo)
                    .isEqualTo("UNMATCHED");
            assertThat(bool("SELECT grace_until > clock_timestamp() + interval '71 hours' AND"
                    + " grace_until <= clock_timestamp() + interval '72 hours' FROM"
                    + " reconciliation.external_item WHERE id = ?", item))
                    .as("the return rule's 72 hours, never the payout's 48")
                    .isTrue();
            assertThat(count("SELECT count(*) FROM reconciliation.break WHERE"
                    + " external_item_id = ?", item))
                    .as("never a direction mismatch against its own payout")
                    .isZero();
            assertThat(count("SELECT count(*) FROM reconciliation.suspense_item WHERE"
                    + " external_item_id = ?", item)).isZero();
            assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                    + " external_item_id = ?", item)).isEqualTo(1);
            UUID wait = decisionOf(returnRun, lineNo);
            assertThat(row("SELECT outcome, origin, rule_priority, strategy, matched_key_kind"
                    + " FROM reconciliation.match_decision WHERE id = ?", wait))
                    .containsExactly("UNMATCHED", "RUN", null, null, null);
            assertThat(count("SELECT count(*) FROM reconciliation.match_candidate WHERE"
                    + " decision_id = ?", wait)).isZero();
        }
        for (UUID payout : List.of(settledPayout, unexecutedPayout)) {
            assertThat(candidatesNaming(payout, returnRun))
                    .as("the anchor is never a candidate")
                    .isZero();
        }
        assertThat(payoutFacts(settledPayout)).containsExactly(settledBefore);
        assertThat(payoutFacts(unexecutedPayout)).containsExactly(openBefore);
        assertThat(row("SELECT status, allocated_minor FROM reconciliation.expectation WHERE"
                + " id = ?", unexecutedPayout)).containsExactly("OPEN", 0L);
        assertThat(allocationsOn(settledPayout)).as("its execution's, alone").isEqualTo(1);
        assertThat(allocationsOn(unexecutedPayout)).isZero();
        assertThat(LOOKED_UP_UNDER.get(executedThenReturned.providerRef()))
                .as("typed through the lookup under the item's key-scope source")
                .isEqualTo(Optional.of(SOURCE));
        assertThat(LOOKED_UP_UNDER.get(stillOpen.providerRef())).isEqualTo(Optional.of(SOURCE));
    }

    @Test
    @Order(5)
    @DisplayName("(e) grace expired on a return the platform's payout is known COMPLETED for:"
            + " PARKED as REVERSAL_MISMATCH(RETURN_NOT_APPLICABLE) for its whole amount, the"
            + " lookup's answer frozen, the park DR PAYOUT_CLEARING / CR suspense, the payout"
            + " untouched")
    void anUnappliedReturnParksAtGraceAsReturnNotApplicable() throws SQLException {
        LocalDate day = day(4);
        Payout payout = newPayout(COMPLETED_PAYOUT);
        UUID payoutExpectation = openPayout(payout, 140_00, day);
        seedRun(executed(1, 140_00, day, refs(payout)));
        matching.sweep();
        assertThat(expectationStatus(payoutExpectation)).isEqualTo("SETTLED");
        Object[] before = payoutFacts(payoutExpectation);
        UUID returnRun = seedRun(returned(1, 140_00, day, refs(payout)));
        matching.sweep();
        UUID item = itemId(returnRun, 1);
        assertThat(itemStatus(returnRun, 1)).as("inside grace it waits").isEqualTo("UNMATCHED");
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE external_item_id = ?",
                item)).isZero();

        LOOKED_UP_UNDER.remove(payout.providerRef());
        expireGrace(returnRun);
        matching.sweep();

        assertThat(itemStatus(returnRun, 1)).isEqualTo("PARKED");
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE external_item_id = ?",
                item)).isEqualTo(1);
        assertThat(row("SELECT type, cause, value_at_issue_minor, internal_classification,"
                + " internal_operation_ref, internal_state, status, source_id, rule_set_id"
                + " FROM reconciliation.break WHERE external_item_id = ?", item))
                .as("the matcher cannot apply a return: the value parks OWNED for a person")
                .containsExactly(
                        "REVERSAL_MISMATCH", "RETURN_NOT_APPLICABLE", 140_00L, "COMPLETED",
                        payout.id(), "COMPLETED", "OPEN", SOURCE, RULE_SET);
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " external_item_id = ?", item)).as("the wait and the park").isEqualTo(2);
        assertThat(row("SELECT origin, rule_priority, strategy, matched_key_kind FROM"
                + " reconciliation.match_decision WHERE id = ?", decisionWith(item, "PARKED")))
                .containsExactly("RUN", null, null, null);
        assertThat(row("SELECT status, parked_minor, allocated_minor FROM"
                + " reconciliation.external_item WHERE id = ?", item))
                .containsExactly("PARKED", 140_00L, 0L);
        UUID clearing = operational(AccountPurpose.PAYOUT_CLEARING);
        UUID suspense = operational(AccountPurpose.SUSPENSE_UNMATCHED);
        assertThat(row("SELECT origin, side, amount_minor - released_minor,"
                + " position_account_id FROM reconciliation.suspense_item WHERE"
                + " external_item_id = ?", item))
                .containsExactly("RECON_PARK", "CREDIT", 140_00L, clearing);
        UUID entry = (UUID) one("SELECT entry_id FROM reconciliation.suspense_item WHERE"
                + " external_item_id = ?", item);
        assertThat(entryLines(entry))
                .as("an INBOUND remainder parked: DR the credit-normal position, CR suspense")
                .containsExactlyInAnyOrder(
                        "DEBIT:" + clearing + ":14000", "CREDIT:" + suspense + ":14000");
        assertThat(payoutFacts(payoutExpectation)).containsExactly(before);
        assertThat(allocationsOn(payoutExpectation)).isEqualTo(1);
        assertThat(candidatesNaming(payoutExpectation, returnRun)).isZero();
        assertThat(LOOKED_UP_UNDER.get(payout.providerRef()))
                .as("the grace leg typed it under the item's key-scope source")
                .isEqualTo(Optional.of(SOURCE));
    }

    @Test
    @Order(6)
    @DisplayName("(f) a return quoting references that name no payout waits, then at grace parks"
            + " as UNKNOWN_EXTERNAL(GRACE_EXPIRED)")
    void anUnknownReturnParksAtGraceAsUnknownExternal() throws SQLException {
        LocalDate day = day(5);
        Payout nobody = newPayout("po_nobody_"); // No expectation opens: the platform never paid.
        UUID runId = seedRun(returned(1, 65_00, day, refs(nobody)));
        matching.sweep();
        UUID item = itemId(runId, 1);
        assertThat(itemStatus(runId, 1)).isEqualTo("UNMATCHED");
        assertThat(one("SELECT grace_until FROM reconciliation.external_item WHERE id = ?",
                item)).isNotNull();
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE external_item_id = ?",
                item)).isZero();

        expireGrace(runId);
        matching.sweep();

        assertThat(itemStatus(runId, 1)).isEqualTo("PARKED");
        assertThat(row("SELECT type, cause, value_at_issue_minor, internal_classification,"
                + " internal_operation_ref, internal_state FROM reconciliation.break WHERE"
                + " external_item_id = ?", item))
                .containsExactly(
                        "UNKNOWN_EXTERNAL", "GRACE_EXPIRED", 65_00L, "UNKNOWN", null, null);
        assertThat(row("SELECT origin, side, amount_minor - released_minor FROM"
                + " reconciliation.suspense_item WHERE external_item_id = ?", item))
                .containsExactly("RECON_PARK", "CREDIT", 65_00L);
        assertThat(LOOKED_UP_UNDER.get(nobody.providerRef())).isEqualTo(Optional.of(SOURCE));
    }

    @Test
    @Order(7)
    @DisplayName("(g) a return whose payout the lookup answers FAILED parks AT ONCE at run time"
            + " as REVERSAL_MISMATCH(TERMINAL_STATE_CONTRADICTED) - no grace clock, the"
            + " definitive path")
    void aFailedPayoutsReturnParksAtOnce() throws SQLException {
        LocalDate day = day(6);
        // A failed payout never completed, so it never opened a MERCHANT_PAYOUT to anchor on.
        Payout failed = newPayout(FAILED_PAYOUT);
        UUID runId = seedRun(returned(1, 55_00, day, refs(failed)));

        matching.sweep();

        UUID item = itemId(runId, 1);
        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(itemStatus(runId, 1)).isEqualTo("PARKED");
        assertThat(one("SELECT grace_until FROM reconciliation.external_item WHERE id = ?",
                item)).as("parked at once: no grace clock").isNull();
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " external_item_id = ?", item)).isEqualTo(1);
        assertThat(row("SELECT outcome, rule_priority, strategy FROM"
                + " reconciliation.match_decision WHERE id = ?", decisionOf(runId, 1)))
                .containsExactly("PARKED", null, null);
        assertThat(row("SELECT type, cause, internal_classification, internal_operation_ref,"
                + " internal_state, value_at_issue_minor FROM reconciliation.break WHERE"
                + " external_item_id = ?", item))
                .as("a payout is no refund: the terminal answer is a reversal's mismatch")
                .containsExactly(
                        "REVERSAL_MISMATCH", "TERMINAL_STATE_CONTRADICTED", "TERMINAL",
                        failed.id(), "FAILED", 55_00L);
        assertThat(row("SELECT origin, side, amount_minor - released_minor FROM"
                + " reconciliation.suspense_item WHERE external_item_id = ?", item))
                .containsExactly("RECON_PARK", "CREDIT", 55_00L);
        assertThat(LOOKED_UP_UNDER.get(failed.providerRef())).isEqualTo(Optional.of(SOURCE));
    }

    @Test
    @Order(8)
    @DisplayName("(h) the anchor reaches its operation's return: with a PAYOUT_RETURN opened for"
            + " the payout, the PAYOUT_RETURNED line settles IT - priority 3 by the provider's"
            + " reference, priority 4 by OUR_REF - and the MERCHANT_PAYOUT stays untouched")
    void theAnchorReachesItsOperationsReturn() throws SQLException {
        LocalDate day = day(7);
        Payout byProvider = newPayout("po_h1_");
        Payout byOurs = newPayout("po_h2_");
        UUID payout1 = openPayout(byProvider, 150_00, day);
        UUID payout2 = openPayout(byOurs, 70_00, day);
        UUID executedRun =
                seedRun(
                        executed(1, 150_00, day, refs(byProvider)),
                        executed(2, 70_00, day, refs(byOurs)));
        matching.sweep();
        assertThat(runStatus(executedRun)).isEqualTo("COMPLETED");
        assertThat(expectationStatus(payout1)).isEqualTo("SETTLED");
        assertThat(expectationStatus(payout2)).isEqualTo("SETTLED");
        UUID return1 = openReturn(byProvider, 150_00, day);
        UUID return2 = openReturn(byOurs, 70_00, day);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_key WHERE"
                + " expectation_id IN (?, ?)", return1, return2))
                .as("a payout return opens no key of its own")
                .isZero();
        Object[] payout1Before = payoutFacts(payout1);
        Object[] payout2Before = payoutFacts(payout2);

        UUID returnRun =
                seedRun(
                        returned(1, 150_00, day, refs(byProvider)),
                        returned(2, 70_00, day,
                                Map.of(ItemKeyKind.PAYOUT_PROVIDER_REF,
                                        providerRef("po_h2_other_"),
                                        ItemKeyKind.OUR_REF, byOurs.ourRef())));
        matching.sweep();

        assertThat(runStatus(returnRun)).isEqualTo("COMPLETED");
        assertThat(itemStatus(returnRun, 1)).isEqualTo("MATCHED");
        assertThat(itemStatus(returnRun, 2)).isEqualTo("MATCHED");
        assertThat(expectationStatus(return1)).isEqualTo("SETTLED");
        assertThat(expectationStatus(return2)).isEqualTo("SETTLED");
        UUID decision1 = decisionOf(returnRun, 1);
        UUID decision2 = decisionOf(returnRun, 2);
        assertThat(row("SELECT strategy, matched_key_kind, rule_priority, outcome, origin,"
                + " date_deviation_days FROM reconciliation.match_decision WHERE id = ?",
                decision1))
                .containsExactly("ONE_TO_ONE", "PAYOUT_PROVIDER_REF", 3, "MATCHED", "RUN", 0);
        assertThat(row("SELECT strategy, matched_key_kind, rule_priority, outcome FROM"
                + " reconciliation.match_decision WHERE id = ?", decision2))
                .containsExactly("ONE_TO_ONE", "OUR_REF", 4, "MATCHED");
        assertThat(count("SELECT count(*) FROM reconciliation.match_candidate WHERE"
                + " decision_id IN (?, ?)", decision1, decision2))
                .as("the anchored reach replaces the anchor: one candidate each")
                .isEqualTo(2);
        assertThat(row("SELECT expectation_id, key_kind, direction FROM"
                + " reconciliation.match_candidate WHERE decision_id = ?", decision1))
                .containsExactly(return1, "PAYOUT_PROVIDER_REF", "INBOUND");
        assertThat(row("SELECT expectation_id, key_kind, direction FROM"
                + " reconciliation.match_candidate WHERE decision_id = ?", decision2))
                .containsExactly(return2, "OUR_REF", "INBOUND");
        assertThat(row("SELECT expectation_id, amount_minor FROM reconciliation.allocation"
                + " WHERE decision_id = ?", decision1)).containsExactly(return1, 150_00L);
        assertThat(row("SELECT expectation_id, amount_minor FROM reconciliation.allocation"
                + " WHERE decision_id = ?", decision2)).containsExactly(return2, 70_00L);
        assertThat(settledEvents(return1)).isEqualTo(1);
        assertThat(settledEvents(return2)).isEqualTo(1);
        assertThat(timingBreaksOn(decision1)).isZero();
        assertThat(timingBreaksOn(decision2)).isZero();

        assertThat(payoutFacts(payout1)).containsExactly(payout1Before);
        assertThat(payoutFacts(payout2)).containsExactly(payout2Before);
        assertThat(allocationsOn(payout1)).as("its execution's, alone").isEqualTo(1);
        assertThat(allocationsOn(payout2)).isEqualTo(1);
        assertThat(candidatesNaming(payout1, returnRun)).isZero();
        assertThat(candidatesNaming(payout2, returnRun)).isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.break b JOIN"
                + " reconciliation.external_item i ON b.external_item_id = i.id WHERE"
                + " i.run_id = ?", returnRun)).isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item s JOIN"
                + " reconciliation.external_item i ON s.external_item_id = i.id WHERE"
                + " i.run_id = ?", returnRun)).isZero();
        assertThat(LOOKED_UP_UNDER)
                .as("a reached return is never typed")
                .doesNotContainKey(byProvider.providerRef());
    }

    // ----------------------------------------------------------------- late evidence

    @Test
    @Order(9)
    @DisplayName("(i) a payout reported executed before its expectation opens waits UNMATCHED;"
            + " the MERCHANT_PAYOUT opens while TEN sweepers race - exactly one allocation, one"
            + " decision of origin REMATCH, the payout SETTLED once")
    void tenRematchSweepersSettleALatePayoutOnce() throws Exception {
        LocalDate day = day(8);
        Payout late = newPayout("po_late_"); // The lookup answers UNKNOWN: not yet completed.
        UUID runId = seedRun(executed(1, 90_00, day, refs(late)));
        matching.sweep();
        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(itemStatus(runId, 1)).isEqualTo("UNMATCHED");
        UUID item = itemId(runId, 1);
        assertThat(one("SELECT grace_until FROM reconciliation.external_item WHERE id = ?",
                item)).as("waiting under its rule's grace").isNotNull();
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE external_item_id = ?",
                item)).isZero();

        UUID payout;
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
            // The payout applier's completing transaction commits while the sweepers race.
            payout = openPayout(late, 90_00, day);
            for (Future<?> outcome : outcomes) {
                outcome.get();
            }
        } finally {
            racers.shutdownNow();
        }
        // Whatever the interleaving, the committed payout is visible to this last leg.
        matching.sweep();

        assertThat(itemStatus(runId, 1)).isEqualTo("MATCHED");
        assertThat(expectationStatus(payout)).isEqualTo("SETTLED");
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
                .containsExactly("ONE_TO_ONE", "PAYOUT_PROVIDER_REF", 1, "MATCHED");
        assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                + " external_item_id = ?", item)).isEqualTo(1);
        assertThat(allocationsOn(payout)).isEqualTo(1);
        assertThat(count("SELECT allocated_minor FROM reconciliation.expectation WHERE id = ?",
                payout)).isEqualTo(90_00);
        assertThat(count("SELECT count(*) FROM reconciliation.expectation_event WHERE"
                + " expectation_id = ? AND event_type = 'ALLOCATED'", payout)).isEqualTo(1);
        assertThat(settledEvents(payout)).isEqualTo(1);
        assertThat(timingBreaksOn(rematch)).isZero();
    }

    // ----------------------------------------------------------------- the race

    @Test
    @Order(10)
    @DisplayName("(j) ten sweepers over one payout run of five lines (two executed by the"
            + " provider's reference, one by OUR_REF, a fee, a return with its PAYOUT_RETURN):"
            + " one decision per line, one allocation and one settle per expectation, the run"
            + " completed once")
    void tenSweepersOneEffect() throws Exception {
        PayoutRun payouts = fiveLinePayoutRun("j", day(9));

        race(matching);

        assertThat(runStatus(payouts.runId())).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " run_id = ?", payouts.runId()))
                .as("with the try-lock the losers skip: one decision per line, exactly")
                .isEqualTo(5);
        for (int lineNo = 1; lineNo <= 5; lineNo++) {
            assertThat(decisionCount(payouts.runId(), lineNo)).isEqualTo(1);
        }
        for (UUID expectation : payouts.expectations()) {
            assertSettledOnce(expectation);
            assertThat(count("SELECT count(*) FROM reconciliation.expectation_event WHERE"
                    + " expectation_id = ? AND event_type = 'ALLOCATED'", expectation))
                    .isEqualTo(1);
            assertThat(settledEvents(expectation)).isEqualTo(1);
        }
        assertLinesDisposed(payouts);
        assertThat(row("SELECT matched_key_kind, rule_priority FROM"
                + " reconciliation.match_decision WHERE id = ?", decisionOf(payouts.runId(), 3)))
                .containsExactly("OUR_REF", 2);
        UUID returnDecision = decisionOf(payouts.runId(), 5);
        assertThat(row("SELECT matched_key_kind, rule_priority FROM"
                + " reconciliation.match_decision WHERE id = ?", returnDecision))
                .containsExactly("PAYOUT_PROVIDER_REF", 3);
        assertThat(one("SELECT expectation_id FROM reconciliation.allocation WHERE"
                + " decision_id = ?", returnDecision))
                .as("the same report's return reaches its operation's return, never the payout"
                        + " line 1 just settled")
                .isEqualTo(payouts.expectations().get(3));
        assertThat(count("SELECT count(*) FROM reconciliation.break b JOIN"
                + " reconciliation.external_item i ON b.external_item_id = i.id WHERE"
                + " i.run_id = ?", payouts.runId())).isZero();
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.RunCompleted' AND target_id = ?",
                payouts.runId().toString())).isEqualTo(1);
    }

    // ----------------------------------------------------------------- the V010 guard

    @Test
    @Order(11)
    @DisplayName("(l) V010's in-place completion refuses once the payout rule set v1 has deciding"
            + " history - a run pinned to it, or a decision naming it - as the migrator, rolled"
            + " back so nothing persists")
    void theV010GuardRefusesOnceHistoryExists() throws SQLException {
        String guard = guardBlock();
        assertThat(guard).startsWith("DO $$").endsWith("$$;").contains("RAISE EXCEPTION");
        LocalDate day = day(11);
        UUID pinnedRun = IDS.next();
        UUID privateRun = IDS.next();
        UUID decision = IDS.next();
        boolean historyAlready;
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            // A RUN pinned to the real payout rule set v1, as its acceptance would birth one.
            try {
                historyAlready = payoutHistoryExists(migrator);
                if (!historyAlready) {
                    assertThat(catchThrowable(() -> runScript(migrator, guard)))
                            .as("with no history the completion is admitted")
                            .isNull();
                }
                runs.birth(migrator,
                        newRun(pinnedRun, PAYOUT_SOURCE, PAYOUT_RULE_SET, day, 0));
                assertGuardRefuses(migrator, guard);
            } finally {
                migrator.rollback();
            }
            // A DECISION naming it, its run pinned elsewhere: the guard's second arm.
            try {
                runs.birth(migrator, newRun(privateRun, SOURCE, RULE_SET, day, 1));
                UUID item = IDS.next();
                items.birthAll(migrator, PLATFORM, List.of(newItem(item, privateRun,
                        returned(1, 10_00, day,
                                Map.of(ItemKeyKind.OUR_REF, "pyo-" + UUID.randomUUID())))));
                if (!historyAlready) {
                    assertThat(catchThrowable(() -> runScript(migrator, guard)))
                            .as("a run under another rule set is no payout history")
                            .isNull();
                }
                execute(migrator,
                        "INSERT INTO reconciliation.match_decision (id, external_item_id,"
                                + " run_id, origin, rule_set_id, outcome, decided_by,"
                                + " decided_by_type, decided_at, decided_on, correlation_id)"
                                + " VALUES (?, ?, ?, 'RUN', ?, 'UNMATCHED', 'system',"
                                + " 'SYSTEM', now(), current_date, 'p8-tsk-018-probe')",
                        decision, item, privateRun, PAYOUT_RULE_SET);
                assertGuardRefuses(migrator, guard);
            } finally {
                migrator.rollback();
            }
        }
        assertThat(count("SELECT count(*) FROM reconciliation.reconciliation_batch WHERE"
                + " id IN (?, ?)", pinnedRun, privateRun))
                .as("rolled back: no history persists")
                .isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE id = ?",
                decision)).isZero();
    }

    /** The guard block refuses in the migration's own words, by a PL/pgSQL RAISE. */
    private static void assertGuardRefuses(Connection migrator, String guard) {
        Throwable refusal = catchThrowable(() -> runScript(migrator, guard));
        assertThat(refusal)
                .as("the guard refuses once history exists")
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("the payout rule set v1 has deciding history");
        assertThat(sqlState(refusal)).as("a RAISE EXCEPTION").isEqualTo("P0001");
    }

    private static boolean payoutHistoryExists(Connection unitOfWork) throws SQLException {
        try (PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT EXISTS (SELECT 1 FROM reconciliation.reconciliation_batch"
                                + " WHERE rule_set_id = ?) OR EXISTS (SELECT 1 FROM"
                                + " reconciliation.match_decision WHERE rule_set_id = ?)")) {
            read.setObject(1, PAYOUT_RULE_SET);
            read.setObject(2, PAYOUT_RULE_SET);
            try (ResultSet row = read.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        }
    }

    /** The migration's {@code DO $$ ... $$;} block, read from the test classpath verbatim. */
    private static String guardBlock() {
        String sql;
        try (InputStream migration =
                PayoutMatchingDatabaseTest.class.getClassLoader().getResourceAsStream(V010)) {
            if (migration == null) {
                throw new IllegalStateException("Migration not on the test classpath: " + V010);
            }
            sql = new String(migration.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Could not read " + V010, failure);
        }
        int start = sql.indexOf("DO $$");
        int end = sql.indexOf("$$;", start + "DO $$".length());
        if (start < 0 || end < 0) {
            throw new IllegalStateException("V010 carries no DO $$ ... $$; guard block");
        }
        return sql.substring(start, end + "$$;".length());
    }

    private static void runScript(Connection unitOfWork, String sql) throws SQLException {
        try (Statement statement = unitOfWork.createStatement()) {
            statement.execute(sql);
        }
    }

    // ----------------------------------------------------------------- the lock only orders

    @Test
    @Order(12)
    @DisplayName("(k) the lock only ORDERS: bypassed, ten sweepers over a fresh five-line payout"
            + " run still allocate each (item, expectation) at most once, never above an"
            + " expectation's amount, park nothing, and complete the run once")
    void theLockOnlyOrders() throws Exception {
        PayoutRun payouts = fiveLinePayoutRun("k", day(12));

        race(matching(true));

        // A bypassed loser may record its losing evaluation - money may not move twice.
        assertThat(runStatus(payouts.runId())).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM (SELECT a.external_item_id, a.expectation_id"
                + " FROM reconciliation.allocation a JOIN reconciliation.external_item i ON"
                + " i.id = a.external_item_id WHERE i.run_id = ? AND"
                + " a.reverses_allocation_id IS NULL GROUP BY a.external_item_id,"
                + " a.expectation_id HAVING count(*) > 1) doubled", payouts.runId()))
                .as("at most one positive allocation per (item, expectation)")
                .isZero();
        for (UUID expectation : payouts.expectations()) {
            assertSettledOnce(expectation);
        }
        assertThat(count("SELECT count(*) FROM reconciliation.allocation a JOIN"
                + " reconciliation.external_item i ON i.id = a.external_item_id WHERE"
                + " i.run_id = ? AND a.expectation_id = ?", payouts.runId(),
                payouts.expectations().get(0)))
                .as("the payout whose keys line 5's return quotes: allocated by line 1 alone")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item s JOIN"
                + " reconciliation.external_item i ON s.external_item_id = i.id WHERE"
                + " i.run_id = ?", payouts.runId()))
                .as("no loser parked a line another sweeper matched")
                .isZero();
        assertLinesDisposed(payouts);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.RunCompleted' AND target_id = ?",
                payouts.runId().toString())).isEqualTo(1);
    }

    // ----------------------------------------------------------------- the five-line run

    /** One payout run's facts: its id and its four expectations (three payouts, one return). */
    private record PayoutRun(UUID runId, List<UUID> expectations) {}

    /**
     * A committed payout run of five lines and its four expectations: payout 1 executed by its
     * provider reference alone, payout 2 by both keys (the provider's first), payout 3 by OUR_REF
     * alone, payout 1's fee at its exact terms, and payout 1 RETURNED in the same report with its
     * PAYOUT_RETURN already open - the anchor and its return side by side in one chunk.
     */
    private static PayoutRun fiveLinePayoutRun(String tag, LocalDate day) {
        Payout first = newPayout("po_" + tag + "1_");
        Payout second = newPayout("po_" + tag + "2_");
        Payout third = newPayout("po_" + tag + "3_");
        List<UUID> opened =
                List.of(
                        openPayout(first, 100_00, day),
                        openPayout(second, 200_00, day),
                        openPayout(third, 300_00, day),
                        openReturn(first, 100_00, day));
        UUID runId =
                seedRun(
                        executed(1, 100_00, day,
                                Map.of(ItemKeyKind.PAYOUT_PROVIDER_REF, first.providerRef())),
                        executed(2, 200_00, day, refs(second)),
                        executed(3, 300_00, day, Map.of(ItemKeyKind.OUR_REF, third.ourRef())),
                        fee(4, 25, day, first.providerRef()),
                        returned(5, 100_00, day, refs(first)));
        return new PayoutRun(runId, opened);
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

    /** Four lines MATCHED and the fee CHECKED; no cycle learned - a payout report has none. */
    private static void assertLinesDisposed(PayoutRun payouts) throws SQLException {
        for (int lineNo : new int[] {1, 2, 3, 5}) {
            assertThat(itemStatus(payouts.runId(), lineNo)).isEqualTo("MATCHED");
            assertThat(learnedCycle(itemId(payouts.runId(), lineNo))).isNull();
        }
        assertThat(itemStatus(payouts.runId(), 4)).isEqualTo("CHECKED");
    }

    // ----------------------------------------------------------------- seeding

    /** A payout as merchant knows it: its id (the operation), the provider's and our reference. */
    private record Payout(String id, String providerRef, String ourRef) {}

    /** A fresh payout whose provider reference carries {@code prefix} - the lookup's plant. */
    private static Payout newPayout(String prefix) {
        Payout payout = new Payout(IDS.next().toString(), providerRef(prefix),
                "pyo-" + UUID.randomUUID());
        PAYOUT_OF.put(payout.providerRef(), payout.id());
        return payout;
    }

    private static String providerRef(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 13);
    }

    /** Both of the payout's references, as a report line quotes them. */
    private static Map<ItemKeyKind, String> refs(Payout payout) {
        return Map.of(
                ItemKeyKind.PAYOUT_PROVIDER_REF, payout.providerRef(),
                ItemKeyKind.OUR_REF, payout.ourRef());
    }

    private record Line(
            int lineNo,
            ExternalLineType type,
            ExpectationDirection direction,
            long minor,
            LocalDate settlementDate,
            Map<ItemKeyKind, String> keys) {}

    /** Money out to the merchant's destination: OUTBOUND. */
    private static Line executed(
            int lineNo, long minor, LocalDate day, Map<ItemKeyKind, String> keys) {
        return new Line(lineNo, ExternalLineType.PAYOUT_EXECUTED, ExpectationDirection.OUTBOUND,
                minor, day, keys);
    }

    /** The beneficiary bank sent it back: INBOUND. */
    private static Line returned(
            int lineNo, long minor, LocalDate day, Map<ItemKeyKind, String> keys) {
        return new Line(lineNo, ExternalLineType.PAYOUT_RETURNED, ExpectationDirection.INBOUND,
                minor, day, keys);
    }

    /** The provider's charge, naming its payout by the provider's reference. */
    private static Line fee(int lineNo, long minor, LocalDate day, String originalProviderRef) {
        return new Line(lineNo, ExternalLineType.PAYOUT_FEE, ExpectationDirection.OUTBOUND,
                minor, day, Map.of(ItemKeyKind.ORIGINAL_REF, originalProviderRef));
    }

    private static LocalDate day(int caseNo) {
        return BASE.plusDays(10L * caseNo);
    }

    private static byte[] fingerprint() {
        byte[] print = new byte[32];
        new SecureRandom().nextBytes(print);
        return print;
    }

    private static ReconciliationRuns.NewRun newRun(
            UUID runId, UUID source, UUID ruleSet, LocalDate businessDate, int itemCount) {
        return new ReconciliationRuns.NewRun(
                runId,
                source,
                Optional.of(IDS.next()),
                RunKind.BATCH,
                ruleSet,
                businessDate,
                Optional.of(SEQUENCES.incrementAndGet()),
                itemCount,
                Optional.empty(),
                Optional.empty(),
                PLATFORM,
                Instant.now(CLOCK),
                CorrelationId.generate(IDS),
                Optional.empty());
    }

    /**
     * The working copy a payout line's acceptance births: the payout clearing position, the
     * provider's settlement date as business, settlement and value date, PENDING.
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
                AccountPurpose.PAYOUT_CLEARING,
                line.settlementDate(),
                Optional.of(line.settlementDate()),
                Optional.of(line.settlementDate()),
                fingerprint(),
                line.keys(),
                Instant.now(CLOCK),
                CorrelationId.generate(IDS));
    }

    /** A committed payout report's run - no cycle - with its items. */
    private static UUID seedRun(Line... lines) {
        UUID runId = IDS.next();
        return runner().inTransaction(unitOfWork -> {
            runs.birth(unitOfWork,
                    newRun(runId, SOURCE, RULE_SET, lines[0].settlementDate(), lines.length));
            List<ExternalItems.NewItem> newItems = new ArrayList<>();
            for (Line line : lines) {
                newItems.add(newItem(IDS.next(), runId, line));
            }
            items.birthAll(unitOfWork, PLATFORM, newItems);
            return runId;
        });
    }

    /** The payout's completion: CR PAYOUT_CLEARING, so OUTBOUND, keyed by both references. */
    private static UUID openPayout(Payout payout, long minor, LocalDate expectedBy) {
        return open(ExpectationKind.MERCHANT_PAYOUT, payout.id(),
                "merchant-payout:" + payout.id(), ExpectationDirection.OUTBOUND, minor,
                expectedBy,
                new NewExpectation.ExpectationKey(
                        KeyKind.PAYOUT_PROVIDER_REF, payout.providerRef()),
                new NewExpectation.ExpectationKey(KeyKind.OUR_REF, payout.ourRef()));
    }

    /**
     * The return's application: DR PAYOUT_CLEARING, so INBOUND, under the SAME operation as its
     * payout and opening NO key of its own - the anchored rule reaches it through the operation.
     */
    private static UUID openReturn(Payout payout, long minor, LocalDate expectedBy) {
        return open(ExpectationKind.PAYOUT_RETURN, payout.id(),
                "merchant-payout-return:" + payout.id(), ExpectationDirection.INBOUND, minor,
                expectedBy);
    }

    /**
     * An expectation under the private source, as its completing transaction opens it - in the
     * payout clearing position, no cycle announced - opened on the REAL clock (the class note).
     */
    private static UUID open(
            ExpectationKind kind,
            String operationRef,
            String postingKey,
            ExpectationDirection direction,
            long minor,
            LocalDate expectedBy,
            NewExpectation.ExpectationKey... keys) {
        return runner().inTransaction(unitOfWork -> {
            UUID position =
                    new JdbcLedgerAccountStore()
                            .findOperational(unitOfWork, AccountPurpose.PAYOUT_CLEARING, EUR)
                            .orElseThrow()
                            .id()
                            .value();
            expectations.open(
                    unitOfWork,
                    new NewExpectation(
                            kind,
                            operationRef,
                            postingKey,
                            SOURCE,
                            AccountPurpose.PAYOUT_CLEARING,
                            position,
                            direction,
                            Money.ofPersisted(minor, EUR, 2),
                            Optional.of(IDS.next()),
                            expectedBy.minusDays(2),
                            Optional.empty(),
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
                    return row.getObject("id", UUID.class);
                }
            } catch (SQLException failure) {
                throw new ReconciliationStorageException("could not seed", failure);
            }
        });
    }

    /** The stored window moved, never the clock (the {@code GraceAndRematchDatabaseTest} way). */
    private static void expireGrace(UUID runId) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            execute(app, "UPDATE reconciliation.external_item SET grace_until = now()"
                    + " - interval '1 hour' WHERE run_id = ?", runId);
            app.commit();
        }
    }

    private static UUID operational(AccountPurpose purpose) {
        return runner().inTransaction(
                unitOfWork ->
                        new JdbcLedgerAccountStore()
                                .findOperational(unitOfWork, purpose, EUR)
                                .orElseThrow()
                                .id()
                                .value());
    }

    // ----------------------------------------------------------------- raw writers

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

    /** What an untouched payout keeps: its status, its allocation, when it last moved. */
    private static Object[] payoutFacts(UUID expectationId) throws SQLException {
        return row("SELECT status, allocated_minor, status_changed_at FROM"
                + " reconciliation.expectation WHERE id = ?", expectationId);
    }

    private static long allocationsOn(UUID expectationId) throws SQLException {
        return count("SELECT count(*) FROM reconciliation.allocation WHERE expectation_id = ?",
                expectationId);
    }

    /** Candidate rows naming {@code expectationId} among {@code runId}'s decisions. */
    private static long candidatesNaming(UUID expectationId, UUID runId) throws SQLException {
        return count("SELECT count(*) FROM reconciliation.match_candidate c JOIN"
                + " reconciliation.match_decision d ON d.id = c.decision_id WHERE"
                + " d.run_id = ? AND c.expectation_id = ?", runId, expectationId);
    }

    /** The only decision on a line - never a pick among several (decided_at ties here). */
    private static UUID decisionOf(UUID runId, int lineNo) throws SQLException {
        assertThat(decisionCount(runId, lineNo)).as("line %d's one decision", lineNo)
                .isEqualTo(1);
        return (UUID) one("SELECT d.id FROM reconciliation.match_decision d JOIN"
                + " reconciliation.external_item i ON i.id = d.external_item_id WHERE"
                + " i.run_id = ? AND i.line_no = " + lineNo, runId);
    }

    /** The one decision of {@code outcome} on an item - decided_at ties under the pinned clock. */
    private static UUID decisionWith(UUID itemId, String outcome) throws SQLException {
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " external_item_id = ? AND outcome = ?", itemId, outcome)).isEqualTo(1);
        return (UUID) one("SELECT id FROM reconciliation.match_decision WHERE"
                + " external_item_id = ? AND outcome = ?", itemId, outcome);
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

    /** The entry's lines as {@code DIRECTION:account:minor}, straight from the ledger. */
    private static List<String> entryLines(UUID entryId) throws SQLException {
        List<String> lines = new ArrayList<>();
        try (PreparedStatement read =
                application.prepareStatement(
                        "SELECT direction, ledger_account_id, amount_minor FROM"
                                + " ledger.journal_line WHERE entry_id = ?")) {
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

    private static long count(String sql, Object... args) throws SQLException {
        return ((Number) one(sql, args)).longValue();
    }

    private static String string(String sql, Object... args) throws SQLException {
        return (String) one(sql, args);
    }

    private static boolean bool(String sql, Object... args) throws SQLException {
        return (Boolean) one(sql, args);
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
