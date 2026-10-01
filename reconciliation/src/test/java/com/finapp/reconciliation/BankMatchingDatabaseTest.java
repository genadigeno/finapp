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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The bank statement's matching against the real schema, the real ledger and real commits
 * (`P8-TSK-016`, ADR-0068 §§1-3): a bank line attributed to a report source is judged in THAT
 * source's key scope — by its {@code REMITTANCE_REF} first, and only when the reference reaches
 * nothing, by the value date's untouched {@code REMITTANCE}s, matched iff the line equals their
 * total exactly (never a subset); a difference against a remittance is
 * {@code SETTLEMENT_MISMATCH(REMITTANCE_DIFFERS)}; a waiting line rematches when its remittance
 * (or its date's group) opens later; a line born disposed is never decided and its run still
 * completes; a bank fee is judged flat against its pinned terms; ten sweepers produce one
 * decision per line and one settle per remittance.
 *
 * <p>The bank items live under the real bank source and its `V002` rule set; the remittances
 * under a PRIVATE report source (the `MatchingDatabaseTest` discipline): a value-date group
 * judges EVERY untouched remittance of its source and date, so no other suite's rows may stand
 * there, and each case owns its own dates. The Matching clock is pinned months behind the
 * database clock (the `GraceAndRematchDatabaseTest` discipline) and the remittances open on the
 * real clock, so "opened after the item's latest decision" is true exactly for a remittance
 * opened after a sweep.
 */
@Tag("database")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the bank statement's matching: by reference and by value date (P8-TSK-016)")
class BankMatchingDatabaseTest {

    /** Months behind the database clock, on purpose — see the class note. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-06-01T12:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor PLATFORM = new Actor("system", ActorType.SYSTEM);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    /** The simulated bank statement's source and its v1 rule set, both seeded by `V002`. */
    private static final UUID BANK = UUID.fromString("01a0e2bc-8200-7004-8000-000000000004");
    private static final UUID BANK_RULE_SET =
            UUID.fromString("01a0e2bd-8300-7004-8000-000000000004");
    /** A private report source standing for the PSP: its remittances are this suite's alone. */
    private static final UUID PSP = UUID.fromString("01a0e2bc-8200-7016-8000-000000000016");
    private static final UUID PSP_RULE_SET =
            UUID.fromString("01a0e2bd-8300-7016-8000-000000000016");
    /** A fresh span of dates per suite run: each case owns the dates its groups judge. */
    private static final LocalDate BASE =
            LocalDate.parse("2026-11-01").plusDays(100L * new SecureRandom().nextInt(30));
    private static final AtomicLong SEQUENCES = new AtomicLong(System.nanoTime() % 80_000);

    /** A remittance reference is known to no internal register: the app lookup's answer. */
    private static final InternalReferenceLookup LOOKUP =
            (unitOfWork, subject) -> InternalReferenceLookup.InternalReference.unknown();

    private static Connection application;
    private static JdbcMatchingStore store;
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
        store = new JdbcMatchingStore();
        runs = new JdbcReconciliationRuns();
        items = new JdbcExternalItems();
        expectations = new JdbcExpectationRegister(IDS);
        breakRegister =
                new JdbcBreakRegister(new JdbcOutboxWriter(), new JdbcAuditWriter(), IDS);
        matching =
                new Matching(
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
        seedPrivateReportSource();
    }

    @AfterAll
    static void disconnect() throws SQLException {
        if (application != null) {
            application.rollback();
            application.close();
        }
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

    /** The private report source's rule set: only what its remittances and breaks pin. */
    private static void seedPrivateReportSource() {
        runner().inTransaction(
                unitOfWork -> {
                    execute(unitOfWork,
                            "INSERT INTO reconciliation.rule_set (id, source_id, version,"
                                    + " status, funding_lag_days, gain_min_age_days,"
                                    + " effective_from, proposed_by, decided_by, reason,"
                                    + " created_at, correlation_id) VALUES (?, ?, 1,"
                                    + " 'PROPOSED', 2, 90, ?, 'test', NULL,"
                                    + " 'BankMatchingDatabaseTest private report source',"
                                    + " now(), 'p8-tsk-016-test') ON CONFLICT (id) DO NOTHING",
                            PSP_RULE_SET, PSP, java.sql.Date.valueOf(LocalDate.parse("2026-09-29")));
                    execute(unitOfWork,
                            "INSERT INTO reconciliation.severity_threshold (rule_set_id,"
                                    + " currency, high_value_minor) VALUES (?, 'EUR', 100000)"
                                    + " ON CONFLICT DO NOTHING",
                            PSP_RULE_SET);
                    execute(unitOfWork,
                            "UPDATE reconciliation.rule_set SET status = 'ACTIVE', decided_by = 'test-activator',"
                                    + " decided_at = now() WHERE id = ? AND status = 'PROPOSED'",
                            PSP_RULE_SET);
                    return null;
                });
    }

    // ----------------------------------------------------------------- by reference

    @Test
    @Order(1)
    @DisplayName("(a) a credit quoting the remittance's reference settles it in its ATTRIBUTED"
            + " source's key scope - the candidate reached by REMITTANCE_REF - and closes the"
            + " report source's overdue break EVIDENCED")
    void aReferenceMatchSettlesTheRemittance() throws SQLException {
        LocalDate day = day(0);
        String reference = reference("A");
        UUID remittance = openRemittance(reference, 100_00, day);
        UUID overdue = raiseOverdue(remittance, 100_00);
        UUID runId = seedRun(credit(1, 100_00, reference, day));

        matching.sweep();

        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(itemStatus(runId, 1)).isEqualTo("MATCHED");
        assertThat(expectationStatus(remittance)).isEqualTo("SETTLED");
        UUID decision = latestDecision(runId, 1);
        assertThat(row("SELECT strategy, matched_key_kind, rule_priority, outcome, origin"
                + " FROM reconciliation.match_decision WHERE id = ?", decision))
                .containsExactly("ONE_TO_ONE", "REMITTANCE_REF", 1, "MATCHED", "RUN");
        assertThat(row("SELECT expectation_id, key_kind FROM reconciliation.match_candidate"
                + " WHERE decision_id = ?", decision))
                .containsExactly(remittance, "REMITTANCE_REF");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.SettlementExpectationSettled' AND aggregate_id = ?",
                remittance)).isEqualTo(1);
        // The item's own keys stay stored under its own source - the bank's.
        assertThat(count("SELECT count(*) FROM reconciliation.external_item_key k JOIN"
                + " reconciliation.external_item i ON i.id = k.item_id WHERE i.run_id = ?"
                + " AND k.source_id = ?", runId, BANK)).isEqualTo(1);

        // L1 across the attribution: the report source's break, locked under its own
        // source's advisory by a BANK chunk, explained to zero by the decision.
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", overdue))
                .isEqualTo("RESOLVED");
        assertThat(row("SELECT kind, decision_id, journal_entry_id FROM"
                + " reconciliation.resolution WHERE break_id = ?", overdue))
                .containsExactly("EVIDENCED", decision, null);
        assertThat(count("SELECT count(*) FROM reconciliation.break b JOIN"
                + " reconciliation.match_decision d ON b.decision_id = d.id WHERE"
                + " d.run_id = ? AND b.type = 'TIMING_DIFFERENCE'", runId)).isZero();
    }

    @Test
    @Order(2)
    @DisplayName("(b) a SHORT credit allocates whole and matches; the remittance's shortfall is"
            + " SETTLEMENT_MISMATCH(REMITTANCE_DIFFERS) on the EXPECTATION, stamped with the"
            + " expectation's own source and rule set, and nothing parks")
    void aShortCreditLeavesTheShortfallOnTheRemittance() throws SQLException {
        LocalDate day = day(1);
        String reference = reference("B");
        UUID remittance = openRemittance(reference, 100_00, day);
        UUID runId = seedRun(credit(1, 60_00, reference, day));

        matching.sweep();

        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(itemStatus(runId, 1)).isEqualTo("MATCHED");
        assertThat(expectationStatus(remittance)).isEqualTo("PARTIALLY_SETTLED");
        assertThat(count("SELECT allocated_minor FROM reconciliation.expectation WHERE"
                + " id = ?", remittance)).isEqualTo(60_00);
        assertThat(row("SELECT type, cause, value_at_issue_minor, source_id, rule_set_id,"
                + " status FROM reconciliation.break WHERE expectation_id = ? AND type <>"
                + " 'MISSING_EXTERNAL'", remittance))
                .as("the remainder's breaks share the report source's advisory (the lock order)")
                .containsExactly(
                        "SETTLEMENT_MISMATCH", "REMITTANCE_DIFFERS", 40_00L, PSP, PSP_RULE_SET,
                        "OPEN");
        assertThat(count("SELECT count(*) FROM reconciliation.break b WHERE b.type ="
                + " 'AMOUNT_MISMATCH' AND (b.expectation_id = ? OR b.external_item_id ="
                + " (SELECT id FROM reconciliation.external_item WHERE run_id = ?))",
                remittance, runId))
                .as("a remittance difference is never an AMOUNT_MISMATCH")
                .isZero();
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item s JOIN"
                + " reconciliation.external_item i ON s.external_item_id = i.id WHERE"
                + " i.run_id = ?", runId))
                .as("the shortfall is an open remainder, never suspense")
                .isZero();
    }

    @Test
    @Order(3)
    @DisplayName("(c) a LONG credit settles the remittance and parks its excess under"
            + " SETTLEMENT_MISMATCH(REMITTANCE_DIFFERS) on the item, in the attributed"
            + " source's clearing position")
    void aLongCreditParksTheExcess() throws SQLException {
        LocalDate day = day(2);
        String reference = reference("C");
        UUID remittance = openRemittance(reference, 100_00, day);
        UUID runId = seedRun(credit(1, 130_00, reference, day));

        matching.sweep();

        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(expectationStatus(remittance)).isEqualTo("SETTLED");
        assertThat(itemStatus(runId, 1)).isEqualTo("PARKED");
        assertThat(row("SELECT allocated_minor, parked_minor FROM"
                + " reconciliation.external_item WHERE run_id = ?", runId))
                .containsExactly(100_00L, 30_00L);
        assertThat(row("SELECT b.type, b.cause, b.value_at_issue_minor FROM"
                + " reconciliation.break b JOIN reconciliation.external_item i ON"
                + " b.external_item_id = i.id WHERE i.run_id = ?", runId))
                .containsExactly("SETTLEMENT_MISMATCH", "REMITTANCE_DIFFERS", 30_00L);
        assertThat(row("SELECT s.origin, s.amount_minor - s.released_minor, s.side FROM"
                + " reconciliation.suspense_item s JOIN reconciliation.external_item i ON"
                + " s.external_item_id = i.id WHERE i.run_id = ?", runId))
                .containsExactly("RECON_PARK", 30_00L, "CREDIT");
        UUID clearing =
                new JdbcLedgerAccountStore()
                        .findOperational(application, AccountPurpose.SETTLEMENT_CLEARING, EUR)
                        .orElseThrow()
                        .id()
                        .value();
        assertThat(one("SELECT s.position_account_id FROM reconciliation.suspense_item s"
                + " JOIN reconciliation.external_item i ON s.external_item_id = i.id WHERE"
                + " i.run_id = ?", runId))
                .as("parked against the ATTRIBUTED source's clearing position")
                .isEqualTo(clearing);
        assertThat(string("SELECT outcome FROM reconciliation.match_decision WHERE id = ?",
                latestDecision(runId, 1))).isEqualTo("PARKED");
    }

    // ----------------------------------------------------------------- by value date

    @Test
    @Order(4)
    @DisplayName("(d) a credit whose reference reaches nothing equals its date's untouched"
            + " remittances' total: ONE GROUP_BY_VALUE_DATE decision, a keyless candidate and"
            + " a whole allocation per member, each SETTLED - a remittance another line's KEY"
            + " claims is no member - and the stored snapshot re-decides exactly")
    void anExactValueDateGroupSettlesEveryMember() throws SQLException {
        LocalDate day = day(3);
        UUID thirty = openRemittance(reference("D1"), 30_00, day);
        UUID seventy = openRemittance(reference("D2"), 70_00, day);
        String claimedReference = reference("D3");
        UUID claimed = openRemittance(claimedReference, 25_00, day);
        UUID overdue = raiseOverdue(seventy, 70_00);
        // The unreferenced line FIRST in claimant order: without the chunk's key claims the
        // group would see 125.00 and wait; with them it sees exactly its own 100.00.
        UUID runId =
                seedRun(
                        credit(1, 100_00, reference("D-UNKNOWN"), day),
                        credit(2, 25_00, claimedReference, day));

        matching.sweep();

        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(itemStatus(runId, 1)).isEqualTo("MATCHED");
        assertThat(itemStatus(runId, 2)).isEqualTo("MATCHED");
        UUID group = latestDecision(runId, 1);
        assertThat(row("SELECT strategy, matched_key_kind, rule_priority, outcome,"
                + " claimant_rank, claimant_count, date_deviation_days FROM"
                + " reconciliation.match_decision WHERE id = ?", group))
                .containsExactly("GROUP_BY_VALUE_DATE", null, 2, "MATCHED", 1, 2, 0);
        assertThat(keylessCandidates(group)).containsExactlyInAnyOrder(thirty, seventy);
        assertThat(count("SELECT count(*) FROM reconciliation.match_candidate WHERE"
                + " decision_id = ?", group)).isEqualTo(2);
        Map<UUID, Long> allocated = allocationsOf(group);
        assertThat(allocated).containsExactlyInAnyOrderEntriesOf(
                Map.of(thirty, 30_00L, seventy, 70_00L));
        assertThat(count("SELECT allocated_minor FROM reconciliation.external_item WHERE"
                + " run_id = ? AND line_no = 1", runId)).isEqualTo(100_00);
        for (UUID member : List.of(thirty, seventy, claimed)) {
            assertThat(expectationStatus(member)).isEqualTo("SETTLED");
            assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                    + " 'reconciliation.SettlementExpectationSettled' AND aggregate_id = ?",
                    member)).isEqualTo(1);
        }
        assertThat(string("SELECT strategy FROM reconciliation.match_decision WHERE id = ?",
                latestDecision(runId, 2)))
                .as("the claimed remittance went to its own reference")
                .isEqualTo("ONE_TO_ONE");
        // L1, member by member.
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?", overdue))
                .isEqualTo("RESOLVED");
        assertThat(row("SELECT kind, decision_id, proposed_amount_minor FROM"
                + " reconciliation.resolution WHERE break_id = ?", overdue))
                .containsExactly("EVIDENCED", group, 70_00L);

        // THE RE-DECIDE (INV-REC-04 as amended): the stored snapshot and the item's stored
        // facts reproduce the group exactly - members and whole allocations.
        MatchingStore.DecisionRow stored = store.decision(application, group).orElseThrow();
        Object[] facts = row("SELECT line_type, direction, amount_minor, currency, scale,"
                + " business_date, value_date FROM reconciliation.external_item WHERE"
                + " id = ?", stored.externalItemId());
        LocalDate valueDate = ((java.sql.Date) facts[6]).toLocalDate();
        MatchEngine.ItemFacts replayedItem =
                new MatchEngine.ItemFacts(
                        stored.externalItemId(),
                        ExternalLineType.valueOf((String) facts[0]),
                        ExpectationDirection.valueOf((String) facts[1]),
                        Money.ofPersisted(
                                ((Number) facts[2]).longValue(),
                                CurrencyCode.of(((String) facts[3]).trim()),
                                ((Number) facts[4]).intValue()),
                        ((java.sql.Date) facts[5]).toLocalDate(),
                        Optional.empty(),
                        false);
        // expected_by is not part of the snapshot; the stored deviation implies it.
        LocalDate expectedBy = valueDate.minusDays(stored.dateDeviationDays().orElseThrow());
        List<MatchEngine.HitFacts> seen =
                store.candidatesOf(application, group).stream()
                        .map(candidate ->
                                new MatchEngine.HitFacts(
                                        candidate.expectationId(),
                                        ExpectationKind.REMITTANCE,
                                        ExpectationDirection.valueOf(candidate.direction()),
                                        candidate.amount(),
                                        candidate.remainderBeforeMinor(),
                                        candidate.openedAt(),
                                        expectedBy,
                                        Optional.ofNullable(candidate.keyKind())
                                                .map(KeyKind::valueOf),
                                        "op-replay"))
                        .toList();
        GroupMatch.Verdict replayed =
                GroupMatch.decide(
                        replayedItem, valueDate, Optional.of(ExpectationKind.REMITTANCE), seen,
                        true);
        assertThat(replayed.kind()).isEqualTo(GroupMatch.Kind.MATCH);
        assertThat(replayed.candidates().stream()
                        .collect(Collectors.toMap(
                                MatchEngine.HitFacts::expectationId,
                                hit -> hit.remainderMinor())))
                .as("the stored inputs reproduce the stored allocations")
                .isEqualTo(allocated);
    }

    @Test
    @Order(5)
    @DisplayName("(e) a total that differs is a WAIT, never a subset: the line UNMATCHED with"
            + " its grace, nothing allocated, every candidate untouched, and the evaluation"
            + " stored with its keyless snapshot")
    void aDifferingGroupWaitsAndNeverTakesASubset() throws SQLException {
        LocalDate day = day(4);
        // 40 + 60 would equal the line; the third makes the total 125.00.
        List<UUID> candidates =
                List.of(
                        openRemittance(reference("E1"), 40_00, day),
                        openRemittance(reference("E2"), 60_00, day),
                        openRemittance(reference("E3"), 25_00, day));
        UUID runId = seedRun(credit(1, 100_00, reference("E-UNKNOWN"), day));

        matching.sweep();

        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(itemStatus(runId, 1)).isEqualTo("UNMATCHED");
        assertThat(one("SELECT grace_until FROM reconciliation.external_item WHERE"
                + " run_id = ?", runId)).isNotNull();
        assertThat(count("SELECT count(*) FROM reconciliation.allocation a JOIN"
                + " reconciliation.external_item i ON a.external_item_id = i.id WHERE"
                + " i.run_id = ?", runId)).isZero();
        for (UUID candidate : candidates) {
            assertThat(row("SELECT status, allocated_minor FROM reconciliation.expectation"
                    + " WHERE id = ?", candidate)).containsExactly("OPEN", 0L);
        }
        UUID waiting = latestDecision(runId, 1);
        assertThat(row("SELECT outcome, strategy, rule_priority, claimant_count FROM"
                + " reconciliation.match_decision WHERE id = ?", waiting))
                .containsExactly("UNMATCHED", "GROUP_BY_VALUE_DATE", 2, 3);
        assertThat(keylessCandidates(waiting)).containsExactlyInAnyOrderElementsOf(candidates);
    }

    // ----------------------------------------------------------------- later evidence

    @Test
    @Order(6)
    @DisplayName("(f) lines that arrive first wait; the remittance - and a date's group -"
            + " opening later are found by the rematch leg in the attributed scope, origin"
            + " REMATCH")
    void waitingLinesRematchWhenTheirRemittancesOpen() throws SQLException {
        LocalDate referenceDay = day(5);
        LocalDate groupDay = day(6);
        String lateReference = reference("F1");
        UUID runId =
                seedRun(
                        credit(1, 45_00, lateReference, referenceDay),
                        credit(2, 80_00, reference("F-UNKNOWN"), groupDay));
        matching.sweep();
        assertThat(itemStatus(runId, 1)).isEqualTo("UNMATCHED");
        assertThat(itemStatus(runId, 2)).isEqualTo("UNMATCHED");

        UUID late = openRemittance(lateReference, 45_00, referenceDay);
        UUID fifty = openRemittance(reference("F2"), 50_00, groupDay);
        UUID thirty = openRemittance(reference("F3"), 30_00, groupDay);
        Matching.SweepResult swept = matching.sweep();

        assertThat(swept.rematched()).isGreaterThanOrEqualTo(2);
        assertThat(itemStatus(runId, 1)).isEqualTo("MATCHED");
        assertThat(itemStatus(runId, 2)).isEqualTo("MATCHED");
        assertThat(row("SELECT origin, strategy, matched_key_kind FROM"
                + " reconciliation.match_decision WHERE id = ?", latestDecision(runId, 1)))
                .containsExactly("REMATCH", "ONE_TO_ONE", "REMITTANCE_REF");
        UUID group = latestDecision(runId, 2);
        assertThat(row("SELECT origin, strategy, matched_key_kind FROM"
                + " reconciliation.match_decision WHERE id = ?", group))
                .containsExactly("REMATCH", "GROUP_BY_VALUE_DATE", null);
        assertThat(keylessCandidates(group)).containsExactlyInAnyOrder(fifty, thirty);
        for (UUID remittance : List.of(late, fifty, thirty)) {
            assertThat(expectationStatus(remittance)).isEqualTo("SETTLED");
        }

        long decisions = count("SELECT count(*) FROM reconciliation.match_decision d JOIN"
                + " reconciliation.external_item i ON i.id = d.external_item_id WHERE"
                + " i.run_id = ?", runId);
        matching.sweep();
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision d JOIN"
                + " reconciliation.external_item i ON i.id = d.external_item_id WHERE"
                + " i.run_id = ?", runId))
                .as("matched lines leave the worklist: nothing is re-judged")
                .isEqualTo(decisions);
    }

    // ----------------------------------------------------------------- disposed at birth

    @Test
    @Order(7)
    @DisplayName("(g) a line born PARKED (an unattributed bank line, as the intake leaves it)"
            + " is never decided - not by the chunk, not by the rematch leg - and its run, and"
            + " a run of nothing else, still COMPLETE")
    void aLineBornParkedIsNeverDecided() throws SQLException {
        LocalDate day = day(7);
        String reference = reference("G");
        UUID remittance = openRemittance(reference, 20_00, day);
        UUID runId =
                seedRun(
                        credit(1, 20_00, reference, day),
                        unattributed(2, 33_00, day));
        UUID parkedOnly = seedRun(unattributed(1, 11_00, day));

        matching.sweep();

        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(itemStatus(runId, 1)).isEqualTo("MATCHED");
        assertThat(expectationStatus(remittance)).isEqualTo("SETTLED");
        assertThat(runStatus(parkedOnly))
                .as("a run whose remaining items are all disposed reads an empty chunk and"
                        + " completes")
                .isEqualTo("COMPLETED");
        for (Object[] parked : List.of(new Object[] {runId, 2}, new Object[] {parkedOnly, 1})) {
            UUID run = (UUID) parked[0];
            int lineNo = (Integer) parked[1];
            assertThat(itemStatus(run, lineNo)).isEqualTo("PARKED");
            assertThat(decisionCount(run, lineNo)).isZero();
            assertThat(row("SELECT s.origin, s.status FROM reconciliation.suspense_item s"
                    + " JOIN reconciliation.external_item i ON s.external_item_id = i.id"
                    + " WHERE i.run_id = ? AND i.line_no = " + lineNo, run))
                    .containsExactly("BANK_UNATTRIBUTED", "OPEN");
            assertThat(row("SELECT b.type, b.cause, b.status FROM reconciliation.break b"
                    + " JOIN reconciliation.external_item i ON b.external_item_id = i.id"
                    + " WHERE i.run_id = ? AND i.line_no = " + lineNo, run))
                    .containsExactly("UNKNOWN_EXTERNAL", "BANK_LINE_UNATTRIBUTED", "OPEN");
        }

        matching.sweep();
        assertThat(decisionCount(runId, 2))
                .as("the rematch leg never reads a line whose suspense is not a park")
                .isZero();
        assertThat(decisionCount(parkedOnly, 1)).isZero();
    }

    // ----------------------------------------------------------------- bank fees

    @Test
    @Order(8)
    @DisplayName("(h) a bank fee is judged FLAT against its pinned terms (fixed 0.50, no"
            + " tolerance): 0.50 CHECKED with no break, 0.70 CHECKED with FEE_MISMATCH 0.20 -"
            + " and no per-batch fold misfires on the exact one")
    void bankFeesAreJudgedFlatPerLine() throws SQLException {
        LocalDate day = day(8);
        UUID runId = seedRun(fee(1, 50, day), fee(2, 70, day));

        matching.sweep();

        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(itemStatus(runId, 1)).isEqualTo("CHECKED");
        assertThat(itemStatus(runId, 2)).isEqualTo("CHECKED");
        assertThat(row("SELECT strategy, rule_priority, fee_expected_minor,"
                + " fee_reported_minor, fee_tolerance_minor FROM"
                + " reconciliation.match_decision WHERE id = ?", latestDecision(runId, 1)))
                .containsExactly("CHECK", 5, 50L, 50L, 0L);
        assertThat(row("SELECT fee_expected_minor, fee_reported_minor FROM"
                + " reconciliation.match_decision WHERE id = ?", latestDecision(runId, 2)))
                .containsExactly(50L, 70L);
        assertThat(count("SELECT count(*) FROM reconciliation.break b JOIN"
                + " reconciliation.external_item i ON b.external_item_id = i.id WHERE"
                + " i.run_id = ? AND i.line_no = 1", runId))
                .as("exactly the terms: nothing - the per-batch fold is the processing fee's")
                .isZero();
        assertThat(row("SELECT b.type, b.cause, b.value_at_issue_minor FROM"
                + " reconciliation.break b JOIN reconciliation.external_item i ON"
                + " b.external_item_id = i.id WHERE i.run_id = ? AND i.line_no = 2", runId))
                .containsExactly("FEE_MISMATCH", "FEE_BEYOND_TOLERANCE", 20L);
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_item s JOIN"
                + " reconciliation.external_item i ON s.external_item_id = i.id WHERE"
                + " i.run_id = ?", runId))
                .as("an expensed fee is judged, never parked")
                .isZero();
    }

    // ----------------------------------------------------------------- the race

    @Test
    @Order(9)
    @DisplayName("(i) ten sweepers over one bank run: exactly one decision per line, one"
            + " allocation and one settle per remittance, the run completed once")
    void tenSweepersOneEffect() throws Exception {
        LocalDate day = day(9);
        LocalDate groupDay = day.plusDays(1);
        List<UUID> remittances = new ArrayList<>();
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String reference = reference("I" + i);
            remittances.add(openRemittance(reference, (i + 1) * 10_00L, day));
            lines.add(credit(i + 1, (i + 1) * 10_00L, reference, day));
        }
        remittances.add(openRemittance(reference("I-G1"), 55_00, groupDay));
        remittances.add(openRemittance(reference("I-G2"), 35_00, groupDay));
        lines.add(credit(5, 90_00, reference("I-UNKNOWN"), groupDay));
        lines.add(fee(6, 50, day));
        UUID runId = seedRun(lines.toArray(Line[]::new));

        ExecutorService racers = Executors.newFixedThreadPool(10);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> outcomes = new ArrayList<>();
            for (int racer = 0; racer < 10; racer++) {
                outcomes.add(racers.submit(() -> {
                    start.await();
                    matching.sweep();
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

        assertThat(runStatus(runId)).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision WHERE"
                + " run_id = ?", runId))
                .as("one decision per line, exactly")
                .isEqualTo(6);
        for (int lineNo = 1; lineNo <= 6; lineNo++) {
            assertThat(decisionCount(runId, lineNo)).isEqualTo(1);
        }
        for (UUID remittance : remittances) {
            assertThat(expectationStatus(remittance)).isEqualTo("SETTLED");
            assertThat(count("SELECT count(*) FROM reconciliation.allocation WHERE"
                    + " expectation_id = ?", remittance)).isEqualTo(1);
            assertThat(count("SELECT count(*) FROM reconciliation.expectation_event WHERE"
                    + " expectation_id = ? AND event_type = 'ALLOCATED'", remittance))
                    .isEqualTo(1);
            assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                    + " 'reconciliation.SettlementExpectationSettled' AND aggregate_id = ?",
                    remittance)).isEqualTo(1);
        }
        assertThat(count("SELECT count(*) FROM reconciliation.external_item WHERE"
                + " run_id = ? AND status = 'MATCHED'", runId)).isEqualTo(5);
        assertThat(itemStatus(runId, 6)).isEqualTo("CHECKED");
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.RunCompleted' AND target_id = ?", runId.toString()))
                .isEqualTo(1);
    }

    @Test
    @Order(10)
    @DisplayName("(j) a remittance paid in two tranches: the first leaves its shortfall OPEN, the"
            + " second settles the remainder and closes that shortfall EVIDENCED by its own"
            + " decision - never a break left over a SETTLED remittance")
    void aSecondTrancheClosesTheShortfall() throws SQLException {
        LocalDate day = day(10);
        String reference = reference("J");
        UUID remittance = openRemittance(reference, 100_00, day);
        seedRun(credit(1, 60_00, reference, day));
        matching.sweep();
        assertThat(expectationStatus(remittance)).isEqualTo("PARTIALLY_SETTLED");
        UUID shortfall =
                (UUID) row("SELECT id FROM reconciliation.break WHERE expectation_id = ? AND"
                        + " cause = 'REMITTANCE_DIFFERS'", remittance)[0];
        assertThat(row("SELECT status FROM reconciliation.break WHERE id = ?", shortfall))
                .containsExactly("OPEN");

        UUID secondRun = seedRun(credit(1, 40_00, reference, day));
        matching.sweep();
        assertThat(expectationStatus(remittance)).isEqualTo("SETTLED");
        assertThat(row("SELECT status FROM reconciliation.break WHERE id = ?", shortfall))
                .as("the last tranche explains the earlier shortfall to zero")
                .containsExactly("RESOLVED");
        assertThat(row("SELECT b.status, r.kind, r.reason_code, (r.decision_id = d.id)"
                        + " FROM reconciliation.break b JOIN reconciliation.resolution r ON"
                        + " r.break_id = b.id JOIN reconciliation.match_decision d ON"
                        + " d.run_id = ? WHERE b.id = ?", secondRun, shortfall))
                .as("the last tranche's decision is the evidence")
                .containsExactly("RESOLVED", "EVIDENCED", "EVIDENCE_RECEIVED", true);
        assertThat(count("SELECT count(*) FROM reconciliation.break WHERE expectation_id = ?"
                + " AND status <> 'RESOLVED'", remittance))
                .as("nothing stands over the settled remittance")
                .isZero();
    }

    @Test
    @Order(11)
    @DisplayName("(k) one credit aggregating THREE remittances of its value date matches all three"
            + " whole; a FOURTH remittance of the same date outside the credit prevents the group"
            + " (exact totals only, no subset search)")
    void threeRemittancesAggregateAndAFourthPrevents() throws SQLException {
        LocalDate day = day(11);
        List<UUID> three = List.of(
                openRemittance(reference("K1"), 10_00, day),
                openRemittance(reference("K2"), 20_00, day),
                openRemittance(reference("K3"), 30_00, day));
        UUID aggregated = seedRun(credit(1, 60_00, reference("K-UNKNOWN"), day));
        matching.sweep();
        assertThat(itemStatus(aggregated, 1)).isEqualTo("MATCHED");
        for (UUID remittance : three) {
            assertThat(expectationStatus(remittance)).isEqualTo("SETTLED");
        }
        assertThat(count("SELECT count(*) FROM reconciliation.allocation a JOIN"
                + " reconciliation.external_item i ON i.id = a.external_item_id WHERE"
                + " i.run_id = ?", aggregated)).isEqualTo(3);

        LocalDate other = day.plusDays(1);
        List<UUID> four = List.of(
                openRemittance(reference("K4"), 10_00, other),
                openRemittance(reference("K5"), 20_00, other),
                openRemittance(reference("K6"), 30_00, other),
                openRemittance(reference("K7"), 5_00, other));
        UUID prevented = seedRun(credit(1, 60_00, reference("K-UNKNOWN2"), other));
        matching.sweep();
        assertThat(itemStatus(prevented, 1))
                .as("10 + 20 + 30 would meet the line, but the date's whole is 65: no subset")
                .isEqualTo("UNMATCHED");
        for (UUID remittance : four) {
            assertThat(expectationStatus(remittance)).isEqualTo("OPEN");
        }
    }

    // ----------------------------------------------------------------- seeding

    private enum Shape { ATTRIBUTED, UNATTRIBUTED_PARKED, FEE }

    private record Line(
            int lineNo,
            ExternalLineType type,
            ExpectationDirection direction,
            long minor,
            Optional<String> reference,
            LocalDate valueDate,
            Shape shape) {}

    /** An attributed bank credit quoting a remittance reference. */
    private static Line credit(int lineNo, long minor, String reference, LocalDate valueDate) {
        return new Line(lineNo, ExternalLineType.BANK_CREDIT, ExpectationDirection.INBOUND,
                minor, Optional.of(reference), valueDate, Shape.ATTRIBUTED);
    }

    /** A bank credit no remittance pattern claimed - born PARKED, as the intake leaves it. */
    private static Line unattributed(int lineNo, long minor, LocalDate valueDate) {
        return new Line(lineNo, ExternalLineType.BANK_CREDIT, ExpectationDirection.INBOUND,
                minor, Optional.of("BANK-REF-" + UUID.randomUUID()), valueDate,
                Shape.UNATTRIBUTED_PARKED);
    }

    /** A bank fee: no position, no attribution, no key. */
    private static Line fee(int lineNo, long minor, LocalDate valueDate) {
        return new Line(lineNo, ExternalLineType.BANK_FEE, ExpectationDirection.OUTBOUND,
                minor, Optional.empty(), valueDate, Shape.FEE);
    }

    private static LocalDate day(int caseNo) {
        return BASE.plusDays(10L * caseNo);
    }

    private static String reference(String tag) {
        return "PSP-REM-" + tag + "-" + UUID.randomUUID().toString().substring(0, 13);
    }

    /**
     * A committed bank BATCH run with its items — what the statement's acceptance births: the
     * dates the statement adapter gives a bank line (business = value date, no settlement
     * date), the attributed line's position the PSP's clearing, an unattributed line moved
     * PARKED in the same transaction with its break and BANK_UNATTRIBUTED suspense item.
     */
    private static UUID seedRun(Line... lines) {
        UUID runId = IDS.next();
        long sequence = SEQUENCES.incrementAndGet();
        return runner().inTransaction(unitOfWork -> {
            runs.birth(
                    unitOfWork,
                    new ReconciliationRuns.NewRun(
                            runId,
                            BANK,
                            Optional.of(IDS.next()),
                            RunKind.BATCH,
                            BANK_RULE_SET,
                            lines[0].valueDate(),
                            Optional.of(sequence),
                            lines.length,
                            Optional.empty(),
                            Optional.empty(),
                            PLATFORM,
                            Instant.now(CLOCK),
                            CorrelationId.generate(IDS)));
            List<ExternalItems.NewItem> newItems = new ArrayList<>();
            Map<UUID, Line> unattributed = new HashMap<>();
            for (Line line : lines) {
                byte[] fingerprint = new byte[32];
                new SecureRandom().nextBytes(fingerprint);
                boolean attributed = line.shape() == Shape.ATTRIBUTED;
                Map<ItemKeyKind, String> keys = new EnumMap<>(ItemKeyKind.class);
                line.reference().ifPresent(value -> keys.put(ItemKeyKind.REMITTANCE_REF, value));
                UUID itemId = IDS.next();
                newItems.add(
                        new ExternalItems.NewItem(
                                itemId,
                                runId,
                                BANK,
                                IDS.next(),
                                line.lineNo(),
                                line.type(),
                                line.direction(),
                                Money.ofPersisted(line.minor(), EUR, 2),
                                attributed
                                        ? Optional.of(AccountPurpose.SETTLEMENT_CLEARING)
                                        : Optional.empty(),
                                attributed ? Optional.of(PSP) : Optional.empty(),
                                line.valueDate(),
                                Optional.empty(),
                                Optional.of(line.valueDate()),
                                fingerprint,
                                keys,
                                Instant.now(CLOCK),
                                CorrelationId.generate(IDS)));
                if (line.shape() == Shape.UNATTRIBUTED_PARKED) {
                    unattributed.put(itemId, line);
                }
            }
            items.birthAll(unitOfWork, PLATFORM, newItems);
            unattributed.forEach((itemId, line) -> parkUnattributed(unitOfWork, itemId, line));
            return runId;
        });
    }

    /** The intake's owned-from-birth shape for an unattributed line (`INV-REC-09`). */
    private static void parkUnattributed(Connection unitOfWork, UUID itemId, Line line) {
        Money amount = Money.ofPersisted(line.minor(), EUR, 2);
        BreakRegister.Raised raised =
                breakRegister.raise(
                        unitOfWork,
                        new BreakRegister.NewBreak(
                                IDS.next(),
                                BreakType.UNKNOWN_EXTERNAL,
                                BreakCause.BANK_LINE_UNATTRIBUTED,
                                BreakRegister.Subject.externalItem(itemId),
                                BANK,
                                BANK_RULE_SET,
                                amount,
                                Optional.of(line.direction()),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                PLATFORM,
                                Instant.now(CLOCK),
                                CorrelationId.generate(IDS)));
        execute(unitOfWork,
                "INSERT INTO reconciliation.suspense_item (id, break_id, external_item_id,"
                        + " origin, origin_ref, side, amount_minor, currency, scale,"
                        + " released_minor, status, opened_on, entry_id, status_changed_at,"
                        + " correlation_id) VALUES (?, ?, ?, 'BANK_UNATTRIBUTED', ?, ?, ?,"
                        + " 'EUR', 2, 0, 'OPEN', ?, ?, now(), 'p8-tsk-016-test')",
                IDS.next(), raised.breakId(), itemId, itemId.toString(),
                line.direction() == ExpectationDirection.INBOUND ? "CREDIT" : "DEBIT",
                amount.minorUnits(), java.sql.Date.valueOf(line.valueDate()), IDS.next());
        execute(unitOfWork,
                "UPDATE reconciliation.external_item SET status = 'PARKED', parked_minor ="
                        + " amount_minor, status_changed_at = now() WHERE id = ?",
                itemId);
        execute(unitOfWork,
                "INSERT INTO reconciliation.external_item_event (item_id, from_status,"
                        + " to_status, actor, actor_type, reason, occurred_at,"
                        + " correlation_id) VALUES (?, 'PENDING', 'PARKED', 'system',"
                        + " 'SYSTEM', NULL, now(), 'p8-tsk-016-test')",
                itemId);
    }

    /**
     * A report's remittance under the private report source, keyed by its reference — what the
     * report's acceptance opens — opened on the REAL clock (see the class note).
     */
    private static UUID openRemittance(String reference, long minor, LocalDate expectedBy) {
        String batchId = UUID.randomUUID().toString();
        return runner().inTransaction(unitOfWork -> {
            UUID position =
                    new JdbcLedgerAccountStore()
                            .findOperational(unitOfWork, AccountPurpose.SETTLEMENT_CLEARING, EUR)
                            .orElseThrow()
                            .id()
                            .value();
            expectations.open(
                    unitOfWork,
                    new NewExpectation(
                            ExpectationKind.REMITTANCE,
                            batchId,
                            "settlement-batch:" + batchId,
                            PSP,
                            AccountPurpose.SETTLEMENT_CLEARING,
                            position,
                            ExpectationDirection.INBOUND,
                            Money.ofPersisted(minor, EUR, 2),
                            Optional.empty(),
                            expectedBy.minusDays(2),
                            Optional.empty(),
                            expectedBy,
                            PSP_RULE_SET,
                            List.of(new NewExpectation.ExpectationKey(
                                    KeyKind.REMITTANCE_REF, reference)),
                            PLATFORM,
                            Instant.now(),
                            CorrelationId.generate(IDS)));
            try (PreparedStatement read =
                    unitOfWork.prepareStatement(
                            "SELECT id FROM reconciliation.expectation WHERE kind ="
                                    + " 'REMITTANCE' AND operation_ref = ?")) {
                read.setString(1, batchId);
                try (ResultSet row = read.executeQuery()) {
                    row.next();
                    return row.getObject("id", UUID.class);
                }
            } catch (SQLException failure) {
                throw new ReconciliationStorageException("could not seed", failure);
            }
        });
    }

    /** Ageing's overdue break on a remittance, stamped with the REPORT source as ageing does. */
    private static UUID raiseOverdue(UUID remittance, long minor) {
        return runner().inTransaction(
                unitOfWork ->
                        breakRegister
                                .raise(
                                        unitOfWork,
                                        new BreakRegister.NewBreak(
                                                IDS.next(),
                                                BreakType.MISSING_EXTERNAL,
                                                BreakCause.EXPECTATION_OVERDUE,
                                                BreakRegister.Subject.expectation(remittance),
                                                PSP,
                                                PSP_RULE_SET,
                                                Money.ofPersisted(minor, EUR, 2),
                                                Optional.of(ExpectationDirection.INBOUND),
                                                Optional.of(ExpectationKind.REMITTANCE),
                                                Optional.empty(),
                                                Optional.empty(),
                                                Optional.empty(),
                                                Optional.empty(),
                                                PLATFORM,
                                                Instant.now(),
                                                CorrelationId.generate(IDS)))
                                .breakId());
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

    private String runStatus(UUID runId) throws SQLException {
        return string("SELECT status FROM reconciliation.reconciliation_batch WHERE id = ?",
                runId);
    }

    private String itemStatus(UUID runId, int lineNo) throws SQLException {
        return string("SELECT status FROM reconciliation.external_item WHERE run_id = ?"
                + " AND line_no = " + lineNo, runId);
    }

    private String expectationStatus(UUID expectationId) throws SQLException {
        return string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                expectationId);
    }

    private UUID latestDecision(UUID runId, int lineNo) throws SQLException {
        return (UUID) one("SELECT d.id FROM reconciliation.match_decision d JOIN"
                + " reconciliation.external_item i ON i.id = d.external_item_id WHERE"
                + " i.run_id = ? AND i.line_no = " + lineNo
                + " ORDER BY d.decided_at DESC, d.id DESC LIMIT 1", runId);
    }

    private long decisionCount(UUID runId, int lineNo) throws SQLException {
        return count("SELECT count(*) FROM reconciliation.match_decision d JOIN"
                + " reconciliation.external_item i ON i.id = d.external_item_id WHERE"
                + " i.run_id = ? AND i.line_no = " + lineNo, runId);
    }

    /** The decision's candidates reached by no key — a value-date group's snapshot. */
    private Set<UUID> keylessCandidates(UUID decisionId) throws SQLException {
        Set<UUID> candidates = new java.util.HashSet<>();
        try (PreparedStatement read =
                application.prepareStatement(
                        "SELECT expectation_id FROM reconciliation.match_candidate WHERE"
                                + " decision_id = ? AND key_kind IS NULL")) {
            read.setObject(1, decisionId);
            try (ResultSet rows = read.executeQuery()) {
                while (rows.next()) {
                    candidates.add(rows.getObject("expectation_id", UUID.class));
                }
            }
        }
        return candidates;
    }

    private Map<UUID, Long> allocationsOf(UUID decisionId) {
        return store.allocationsOfDecision(application, decisionId).stream()
                .collect(Collectors.toMap(
                        MatchingStore.AllocationRow::expectationId,
                        allocation -> allocation.amount().minorUnits()));
    }

    private long count(String sql, Object... args) throws SQLException {
        return ((Number) one(sql, args)).longValue();
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
