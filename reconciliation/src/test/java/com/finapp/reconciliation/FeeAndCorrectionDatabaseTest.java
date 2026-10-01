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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.AfterAll;

/**
 * Fees and corrections against the real schema and the real ledger (`P8-TSK-012`): the
 * fee check strict at its boundary with the expected fee reproduced from the pinned
 * terms, the per-batch fold on the run's first fee item, the corrections netting an
 * under-payment (top-up) and an over-payment (offset — the park's exact inverse in
 * `ledger.journal_line`), a claw-back offsetting a parked duplicate, the non-exact
 * correction resolving NOTHING, `EVIDENCED` once under ten instances, and the `V006`
 * authority rules refusing every raw writer.
 *
 * <p>The suite owns a private source (the `MatchingDatabaseTest` discipline) so its
 * commits never race another suite's; identity discipline likewise — every waiting or
 * parked value is balanced by its own expectation, so the shared container's proofs
 * stay exact for the suites that assert them.
 */
@Tag("database")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("fees and corrections (P8-TSK-012)")
class FeeAndCorrectionDatabaseTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-09-30T16:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(CLOCK, new SecureRandom());
    private static final Actor PLATFORM = new Actor("system", ActorType.SYSTEM);
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final UUID SOURCE =
            UUID.fromString("01a0e2bc-8200-7013-8000-000000000013");
    private static final UUID RULE_SET =
            UUID.fromString("01a0e2bd-8300-7013-8000-000000000013");
    private static final LocalDate SETTLED_ON = LocalDate.parse("2026-09-25");
    private static final AtomicLong SEQUENCES = new AtomicLong(System.nanoTime() % 80_000);

    private static final InternalReferenceLookup LOOKUP =
            (unitOfWork, subject) -> InternalReferenceLookup.InternalReference.unknown();

    private static Connection application;
    private static JdbcMatchingStore store;
    private static JdbcReconciliationRuns runs;
    private static JdbcExternalItems items;
    private static JdbcExpectationRegister expectations;
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
        matching = matching();
        seedPrivateRuleSet();
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

    private static void seedPrivateRuleSet() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            execute(app,
                    "INSERT INTO reconciliation.rule_set (id, source_id, version, status,"
                            + " funding_lag_days, gain_min_age_days, effective_from,"
                            + " proposed_by, decided_by, reason, created_at,"
                            + " correlation_id) VALUES (?, ?, 1, 'PROPOSED', 2, 90, ?,"
                            + " 'test', NULL, 'FeeAndCorrectionDatabaseTest private"
                            + " rule set', now(), 'p8-tsk-012-test')"
                            + " ON CONFLICT (id) DO NOTHING",
                    RULE_SET, SOURCE, java.sql.Date.valueOf(SETTLED_ON));
            execute(app,
                    "INSERT INTO reconciliation.rule (rule_set_id, priority, line_type,"
                            + " key_kind, expectation_kind, cardinality,"
                            + " operation_anchored, grace_hours) VALUES"
                            + " (?, 1, 'CAPTURE', 'PSP_CAPTURE_REF', 'CARD_CAPTURE',"
                            + " 'ONE_TO_ONE', false, 48),"
                            + " (?, 2, 'PROCESSING_FEE', 'ORIGINAL_REF', NULL, 'CHECK',"
                            + " false, 48),"
                            + " (?, 3, 'COUNTERPARTY_ADJUSTMENT', 'ORIGINAL_REF', NULL,"
                            + " 'CORRECTION', false, 48),"
                            + " (?, 4, 'DISPUTE_FEE', 'DISPUTE_FEE_REF', 'DISPUTE_FEE',"
                            + " 'ONE_TO_ONE', false, 48)"
                            + " ON CONFLICT DO NOTHING",
                    RULE_SET, RULE_SET, RULE_SET, RULE_SET);
            execute(app,
                    "INSERT INTO reconciliation.tolerance (rule_set_id, comparison,"
                            + " currency, absolute_minor, days) VALUES"
                            + " (?, 'SETTLEMENT_DATE_DAYS', NULL, NULL, 2),"
                            + " (?, 'PROCESSING_FEE_PER_LINE', 'EUR', 2, NULL),"
                            + " (?, 'PROCESSING_FEE_PER_BATCH', 'EUR', 50, NULL)"
                            + " ON CONFLICT DO NOTHING",
                    RULE_SET, RULE_SET, RULE_SET);
            execute(app,
                    "INSERT INTO reconciliation.provider_fee_schedule (rule_set_id,"
                            + " line_type, currency, rate, fixed_minor, scale,"
                            + " rounding_policy) VALUES (?, 'PROCESSING_FEE', 'EUR',"
                            + " 0.015000, 25, 2, 'HALF_UP') ON CONFLICT DO NOTHING",
                    RULE_SET);
            execute(app,
                    "INSERT INTO reconciliation.severity_threshold (rule_set_id,"
                            + " currency, high_value_minor) VALUES (?, 'EUR', 100000)"
                            + " ON CONFLICT DO NOTHING",
                    RULE_SET);
            execute(app,
                    "UPDATE reconciliation.rule_set SET status = 'ACTIVE', decided_by = 'test-activator',"
                            + " decided_at = now() WHERE id = ? AND status = 'PROPOSED'",
                    RULE_SET);
            app.commit();
        }
    }

    // ----------------------------------------------------------------- scenario 7

    @Test
    @Order(1)
    @DisplayName("scenario 7: the expected fee reproduced from the pinned terms, the"
            + " boundary strict, the unreachable original priced at zero, nothing ever"
            + " parked - and the per-batch fold on the run's first fee item")
    void theFeeCheckJudgesAgainstThePinnedTerms() throws SQLException {
        openExpectation("CAP-F1", 100_00);
        openExpectation("CAP-F2", 100_00);
        openExpectation("CAP-F3", 100_00);
        UUID runId =
                seedRun(
                        line(1, ExternalLineType.PROCESSING_FEE,
                                ExpectationDirection.INBOUND, 1_75,
                                ItemKeyKind.ORIGINAL_REF, "CAP-F1"),
                        line(2, ExternalLineType.CAPTURE, ExpectationDirection.INBOUND,
                                100_00, ItemKeyKind.PSP_CAPTURE_REF, "CAP-F1"),
                        line(3, ExternalLineType.PROCESSING_FEE,
                                ExpectationDirection.INBOUND, 1_77,
                                ItemKeyKind.ORIGINAL_REF, "CAP-F2"),
                        line(4, ExternalLineType.CAPTURE, ExpectationDirection.INBOUND,
                                100_00, ItemKeyKind.PSP_CAPTURE_REF, "CAP-F2"),
                        line(5, ExternalLineType.PROCESSING_FEE,
                                ExpectationDirection.INBOUND, 1_78,
                                ItemKeyKind.ORIGINAL_REF, "CAP-F3"),
                        line(6, ExternalLineType.CAPTURE, ExpectationDirection.INBOUND,
                                100_00, ItemKeyKind.PSP_CAPTURE_REF, "CAP-F3"),
                        line(7, ExternalLineType.PROCESSING_FEE,
                                ExpectationDirection.INBOUND, 50,
                                ItemKeyKind.ORIGINAL_REF,
                                "CAP-NOWHERE-" + UUID.randomUUID()));

        matching.sweep();

        assertThat(status(runId)).isEqualTo("COMPLETED");
        for (int lineNo : new int[] {1, 3, 5, 7}) {
            assertThat(itemStatus(runId, lineNo)).isEqualTo("CHECKED");
            assertThat(count("SELECT count(*) FROM reconciliation.suspense_item s JOIN"
                    + " reconciliation.external_item i ON s.external_item_id = i.id"
                    + " WHERE i.run_id = ? AND i.line_no = " + lineNo, runId))
                    .as("a fee's value was expensed at acceptance: NEVER parked")
                    .isZero();
        }

        // The decision freezes what was judged: expected from the PINNED terms.
        Object[] exact = feeTriple(runId, 1);
        assertThat(exact).containsExactly(175L, 175L, 2L);
        assertThat(feeTriple(runId, 3)).containsExactly(175L, 177L, 2L);
        assertThat(feeTriple(runId, 5)).containsExactly(175L, 178L, 2L);
        assertThat(feeTriple(runId, 7)[0]).isEqualTo(0L);

        // The boundary, strict: 2 at tolerance 2 raises nothing; 3 does; F1's zero
        // prices the whole reported fee at issue. (Line 1's own per-line check raised
        // nothing - the ONE break it carries below is the batch verdict's, by design D.)
        assertThat(feeBreak(runId, 3)).isZero();
        assertThat(feeBreak(runId, 5)).isEqualTo(1);
        assertThat(scalar("SELECT b.value_at_issue_minor FROM reconciliation.break b"
                + " JOIN reconciliation.external_item i ON b.external_item_id = i.id"
                + " WHERE i.run_id = ? AND i.line_no = 5", runId)).isEqualTo(3L);
        assertThat(feeBreak(runId, 7)).isEqualTo(1);

        // The per-batch fold: 0 + 2 + 3 + 50 = 55 > 50, on the run's FIRST fee item.
        assertThat(scalar("SELECT b.value_at_issue_minor FROM reconciliation.break b"
                + " JOIN reconciliation.external_item i ON b.external_item_id = i.id"
                + " WHERE i.run_id = ? AND i.line_no = 1 AND b.type = 'FEE_MISMATCH'",
                runId))
                .as("the batch's own verdict names the first fee line (design D)")
                .isEqualTo(55L);
    }

    @Test
    @Order(2)
    @DisplayName("a DISPUTE_FEE line differing from its expectation stays AMOUNT_MISMATCH"
            + " under ONE_TO_ONE - never a fee check")
    void aDisputeFeeIsNeverFeeChecked() throws SQLException {
        openExpectation(
                "DF-1", 10_00, ExpectationKind.DISPUTE_FEE, KeyKind.DISPUTE_FEE_REF);
        UUID runId =
                seedRun(line(1, ExternalLineType.DISPUTE_FEE,
                        ExpectationDirection.INBOUND, 12_00, ItemKeyKind.DISPUTE_REF,
                        "DF-1"));
        matching.sweep();

        assertThat(status(runId)).isEqualTo("COMPLETED");
        assertThat(itemStatus(runId, 1)).isEqualTo("PARKED");
        assertThat(count("SELECT count(*) FROM reconciliation.break b JOIN"
                + " reconciliation.external_item i ON b.external_item_id = i.id WHERE"
                + " i.run_id = ? AND b.type = 'AMOUNT_MISMATCH' AND b.cause ="
                + " 'AMOUNT_DIFFERS'", runId)).isEqualTo(1);
        assertThat(one("SELECT d.fee_reported_minor FROM reconciliation.match_decision d"
                + " JOIN reconciliation.external_item i ON i.id = d.external_item_id"
                + " WHERE d.run_id = ?", runId))
                .as("no fee comparison rides an allocating decision")
                .isNull();
    }

    // ----------------------------------------------------------------- scenario 6

    @Test
    @Order(3)
    @DisplayName("a correction tops up an under-payment to zero and the break resolves"
            + " EVIDENCED - the resolution naming the decision, no posting of its own")
    void aTopUpResolvesTheUnderPaymentEvidenced() throws SQLException {
        UUID under = openExpectation("CAP-U", 100_00);
        UUID runA = seedRun(line(1, ExternalLineType.CAPTURE,
                ExpectationDirection.INBOUND, 60_00, ItemKeyKind.PSP_CAPTURE_REF,
                "CAP-U"));
        matching.sweep();
        UUID underBreak = (UUID) one("SELECT id FROM reconciliation.break WHERE"
                + " expectation_id = ? AND type = 'AMOUNT_MISMATCH'", under);
        assertThat(underBreak).isNotNull();
        assertThat(status(runA)).isEqualTo("COMPLETED");

        UUID runB = seedRun(line(1, ExternalLineType.COUNTERPARTY_ADJUSTMENT,
                ExpectationDirection.INBOUND, 40_00, ItemKeyKind.ORIGINAL_REF, "CAP-U"));
        matching.sweep();

        assertThat(status(runB)).isEqualTo("COMPLETED");
        assertThat(itemStatus(runB, 1)).isEqualTo("MATCHED");
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                under)).isEqualTo("SETTLED");
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?",
                underBreak)).isEqualTo("RESOLVED");
        Object[] resolution = row("SELECT kind, status, reason_code, proposed_by_type,"
                + " journal_entry_id, proposed_amount_minor, narrative FROM"
                + " reconciliation.resolution WHERE break_id = ?", underBreak);
        assertThat(resolution[0]).isEqualTo("EVIDENCED");
        assertThat(resolution[1]).isEqualTo("APPROVED");
        assertThat(resolution[2]).isEqualTo("EVIDENCE_RECEIVED");
        assertThat(resolution[3]).isEqualTo("SYSTEM");
        assertThat(resolution[4]).as("a top-up posts nothing of its own").isNull();
        assertThat(resolution[5]).isEqualTo(40_00L);
        assertThat((String) resolution[6]).contains("decision=");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.BreakResolved' AND aggregate_id = ?", underBreak))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation ="
                + " 'reconciliation.BreakResolvedByEvidence' AND change_summary LIKE"
                + " '%" + underBreak + "%'")).isEqualTo(1);
    }

    @Test
    @Order(4)
    @DisplayName("an opposite correction offsets the parked excess exactly: the unpark is"
            + " the park's inverse in the ledger's own lines, the release cause"
            + " CORRECTION_OFFSET, the resolution carrying the entry, the break RESOLVED")
    void anOffsetReleasesTheParkedExcessEvidenced() throws SQLException {
        UUID over = openExpectation("CAP-O", 60_00);
        UUID runC = seedRun(line(1, ExternalLineType.CAPTURE,
                ExpectationDirection.INBOUND, 100_00, ItemKeyKind.PSP_CAPTURE_REF,
                "CAP-O"));
        matching.sweep();
        assertThat(status(runC)).isEqualTo("COMPLETED");
        UUID suspenseItem = (UUID) one("SELECT s.id FROM reconciliation.suspense_item s"
                + " JOIN reconciliation.external_item i ON s.external_item_id = i.id"
                + " WHERE i.run_id = ?", runC);
        UUID parkEntry = (UUID) one("SELECT p.journal_entry_id FROM reconciliation.park"
                + " p JOIN reconciliation.suspense_item s ON s.park_id = p.id WHERE"
                + " s.id = ?", suspenseItem);
        UUID overBreak = (UUID) one("SELECT break_id FROM reconciliation.suspense_item"
                + " WHERE id = ?", suspenseItem);

        UUID runD = seedRun(line(1, ExternalLineType.COUNTERPARTY_ADJUSTMENT,
                ExpectationDirection.OUTBOUND, 40_00, ItemKeyKind.ORIGINAL_REF, "CAP-O"));
        matching.sweep();

        assertThat(status(runD)).isEqualTo("COMPLETED");
        assertThat(itemStatus(runD, 1)).isEqualTo("OFFSET");
        assertThat(itemStatus(runC, 1)).isEqualTo("RESOLVED");
        assertThat(string("SELECT status FROM reconciliation.suspense_item WHERE id = ?",
                suspenseItem)).isEqualTo("RELEASED");
        Object[] release = row("SELECT cause, amount_minor, cause_ref FROM"
                + " reconciliation.suspense_release WHERE item_id = ?", suspenseItem);
        assertThat(release[0]).isEqualTo("CORRECTION_OFFSET");
        assertThat(release[1]).isEqualTo(40_00L);
        assertThat((String) release[2]).startsWith("decision=");

        UUID unparkEntry = (UUID) one("SELECT journal_entry_id FROM"
                + " reconciliation.resolution WHERE break_id = ?", overBreak);
        assertThat(unparkEntry).isNotNull();
        // The exact inverse, in the ledger's own words: the park's DR/CR swapped.
        assertThat(entryLines(unparkEntry))
                .containsExactlyInAnyOrderElementsOf(
                        entryLines(parkEntry).stream()
                                .map(lineText -> lineText.startsWith("DEBIT:")
                                        ? "CREDIT:" + lineText.substring(6)
                                        : "DEBIT:" + lineText.substring(7))
                                .toList());
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?",
                overBreak)).isEqualTo("RESOLVED");
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.BreakResolved' AND aggregate_id = ?", overBreak))
                .isEqualTo(1);
    }

    @Test
    @Order(5)
    @DisplayName("a counterparty's claw-back offsets a parked duplicate the same way")
    void aClawBackOffsetsAParkedDuplicate() throws SQLException {
        openExpectation("CAP-D", 50_00);
        UUID runE =
                seedRun(
                        line(1, ExternalLineType.CAPTURE, ExpectationDirection.INBOUND,
                                50_00, ItemKeyKind.PSP_CAPTURE_REF, "CAP-D"),
                        line(2, ExternalLineType.CAPTURE, ExpectationDirection.INBOUND,
                                50_00, ItemKeyKind.PSP_CAPTURE_REF, "CAP-D"));
        matching.sweep();
        assertThat(itemStatus(runE, 2)).isEqualTo("PARKED");
        UUID duplicateBreak = (UUID) one("SELECT b.id FROM reconciliation.break b JOIN"
                + " reconciliation.external_item i ON b.external_item_id = i.id WHERE"
                + " i.run_id = ? AND i.line_no = 2 AND b.type = 'DUPLICATE_EXTERNAL'",
                runE);

        UUID runF = seedRun(line(1, ExternalLineType.COUNTERPARTY_ADJUSTMENT,
                ExpectationDirection.OUTBOUND, 50_00, ItemKeyKind.ORIGINAL_REF, "CAP-D"));
        matching.sweep();

        assertThat(status(runF)).isEqualTo("COMPLETED");
        assertThat(itemStatus(runF, 1)).isEqualTo("OFFSET");
        assertThat(itemStatus(runE, 2)).isEqualTo("RESOLVED");
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?",
                duplicateBreak)).isEqualTo("RESOLVED");
        assertThat(string("SELECT kind FROM reconciliation.resolution WHERE break_id = ?",
                duplicateBreak)).isEqualTo("EVIDENCED");
    }

    @Test
    @Order(6)
    @DisplayName("a correction matching neither exactly resolves NOTHING: never a partial"
            + " offset, a non-zero residual never resolves - it waits under its rule's"
            + " grace like any remainder")
    void aNonExactCorrectionResolvesNothing() throws SQLException {
        openExpectation("CAP-N", 60_00);
        // The waiting correction's own balancing expectation (the identity discipline).
        openExpectation("CAP-N-BAL-" + UUID.randomUUID(), 39_99);
        UUID runG = seedRun(line(1, ExternalLineType.CAPTURE,
                ExpectationDirection.INBOUND, 100_00, ItemKeyKind.PSP_CAPTURE_REF,
                "CAP-N"));
        matching.sweep();
        UUID suspenseItem = (UUID) one("SELECT s.id FROM reconciliation.suspense_item s"
                + " JOIN reconciliation.external_item i ON s.external_item_id = i.id"
                + " WHERE i.run_id = ?", runG);

        UUID runH = seedRun(line(1, ExternalLineType.COUNTERPARTY_ADJUSTMENT,
                ExpectationDirection.OUTBOUND, 39_99, ItemKeyKind.ORIGINAL_REF, "CAP-N"));
        matching.sweep();

        assertThat(status(runH)).isEqualTo("COMPLETED");
        assertThat(itemStatus(runH, 1)).isEqualTo("UNMATCHED");
        assertThat(one("SELECT grace_until FROM reconciliation.external_item WHERE"
                + " run_id = ? AND line_no = 1", runH))
                .as("it waits under the CORRECTION rule's own clock")
                .isNotNull();
        assertThat(string("SELECT status FROM reconciliation.suspense_item WHERE id = ?",
                suspenseItem)).isEqualTo("OPEN");
        assertThat(count("SELECT count(*) FROM reconciliation.resolution r JOIN"
                + " reconciliation.break b ON r.break_id = b.id JOIN"
                + " reconciliation.suspense_item s ON s.break_id = b.id WHERE s.id = ?",
                suspenseItem))
                .as("a non-zero residual never resolves")
                .isZero();

        // The allocating half of the same rule: a PARTIAL top-up leaves the remainder
        // and its break OPEN - EVIDENCED only at zero.
        UUID partial = openExpectation("CAP-P", 100_00);
        seedRun(line(1, ExternalLineType.CAPTURE, ExpectationDirection.INBOUND, 20_00,
                ItemKeyKind.PSP_CAPTURE_REF, "CAP-P"));
        matching.sweep();
        UUID partialBreak = (UUID) one("SELECT id FROM reconciliation.break WHERE"
                + " expectation_id = ? AND type = 'AMOUNT_MISMATCH'", partial);
        assertThat(partialBreak).isNotNull();
        seedRun(line(1, ExternalLineType.COUNTERPARTY_ADJUSTMENT,
                ExpectationDirection.INBOUND, 30_00, ItemKeyKind.ORIGINAL_REF, "CAP-P"));
        matching.sweep();
        assertThat(string("SELECT status FROM reconciliation.expectation WHERE id = ?",
                partial)).isEqualTo("PARTIALLY_SETTLED");
        assertThat(string("SELECT status FROM reconciliation.break WHERE id = ?",
                partialBreak))
                .as("a 50.00 remainder still stands: the break stays OPEN")
                .isEqualTo("OPEN");
        assertThat(count("SELECT count(*) FROM reconciliation.resolution WHERE"
                + " break_id = ?", partialBreak))
                .as("EVIDENCED only at zero residual")
                .isZero();
    }

    // ----------------------------------------------------------------- the race

    @Test
    @Order(7)
    @DisplayName("EVIDENCED once under ten instances: one offset, one release, one"
            + " resolution, one BreakResolved - the arbiters converge the racers")
    void evidencedOnceUnderTenInstances() throws Exception {
        openExpectation("CAP-R", 60_00);
        UUID runI = seedRun(line(1, ExternalLineType.CAPTURE,
                ExpectationDirection.INBOUND, 100_00, ItemKeyKind.PSP_CAPTURE_REF,
                "CAP-R"));
        matching.sweep();
        UUID suspenseItem = (UUID) one("SELECT s.id FROM reconciliation.suspense_item s"
                + " JOIN reconciliation.external_item i ON s.external_item_id = i.id"
                + " WHERE i.run_id = ?", runI);
        UUID breakId = (UUID) one("SELECT break_id FROM reconciliation.suspense_item"
                + " WHERE id = ?", suspenseItem);
        UUID runJ = seedRun(line(1, ExternalLineType.COUNTERPARTY_ADJUSTMENT,
                ExpectationDirection.OUTBOUND, 40_00, ItemKeyKind.ORIGINAL_REF, "CAP-R"));

        ExecutorService racers = Executors.newFixedThreadPool(10);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> outcomes = new ArrayList<>();
            for (int racer = 0; racer < 10; racer++) {
                outcomes.add(racers.submit(() -> {
                    start.await();
                    matching().sweep();
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

        assertThat(status(runJ)).isEqualTo("COMPLETED");
        assertThat(count("SELECT count(*) FROM reconciliation.suspense_release WHERE"
                + " item_id = ?", suspenseItem)).isEqualTo(1);
        assertThat(scalar("SELECT released_minor FROM reconciliation.suspense_item"
                + " WHERE id = ?", suspenseItem)).isEqualTo(40_00L);
        assertThat(count("SELECT count(*) FROM reconciliation.resolution WHERE"
                + " break_id = ?", breakId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type ="
                + " 'reconciliation.BreakResolved' AND aggregate_id = ?", breakId))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM reconciliation.match_decision d JOIN"
                + " reconciliation.external_item i ON i.id = d.external_item_id WHERE"
                + " i.run_id = ? AND d.outcome = 'OFFSET'", runJ)).isEqualTo(1);
    }

    // ----------------------------------------------------------------- authority

    @Test
    @Order(8)
    @DisplayName("V006 binds every raw writer: a person as EVIDENCED's proposer refused,"
            + " an undecided row refused, and the record immutable for app and migrator")
    void theAuthorityRulesBindRawSql() throws SQLException {
        UUID resolutionId = (UUID) one(
                "SELECT id FROM reconciliation.resolution LIMIT 1");
        assertThat(resolutionId).as("earlier tests committed resolutions").isNotNull();
        UUID breakId = (UUID) one("SELECT break_id FROM reconciliation.resolution WHERE"
                + " id = ?", resolutionId);

        String insert = "INSERT INTO reconciliation.resolution (id, break_id, kind,"
                + " status, reason_code, narrative, four_eyes, proposed_amount_minor,"
                + " currency, scale, residual_version, rule_set_id, proposed_by,"
                + " proposed_by_type, proposed_at, decided_by, decided_by_type,"
                + " decided_at, created_at, status_changed_at, correlation_id) VALUES"
                + " (?, ?, 'EVIDENCED', %s, 'EVIDENCE_RECEIVED', 'raw probe', false, 1,"
                + " 'EUR', 2, 0, ?, 'op-1', %s, now(), 'op-1', 'EMPLOYEE', now(), now(),"
                + " now(), 'corr')";
        try (Connection raw = DatabaseRoles.application()) {
            raw.setAutoCommit(false);
            assertThatThrownBy(() -> execute(raw,
                    insert.formatted("'APPROVED'", "'EMPLOYEE'"),
                    IDS.next(), breakId, RULE_SET))
                    .as("EVIDENCED is the platform's alone, for every writer")
                    .hasStackTraceContaining("resolution_evidenced_is_platform");
            raw.rollback();
            assertThatThrownBy(() -> execute(raw,
                    insert.formatted("'PROPOSED'", "'SYSTEM'"),
                    IDS.next(), breakId, RULE_SET))
                    .as("an undecided row is V007's to admit, not V006's - refused by"
                            + " the narrowed rank (whichever of its two CHECKs answers"
                            + " first)")
                    .hasStackTraceContaining("violates check constraint")
                    .hasStackTraceContaining("resolution_");
            raw.rollback();
            assertThatThrownBy(() -> execute(raw,
                    "UPDATE reconciliation.resolution SET narrative = 'edited' WHERE"
                            + " id = '" + resolutionId + "'"))
                    .as("the application role holds no UPDATE")
                    .hasStackTraceContaining("permission denied");
        }
        // Since V007 the resolution's own trigger is the machine's (P8-TSK-015): a decided
        // row's payload stays frozen and no row is ever deleted, for the migrator too; the
        // history stays V006's append-only.
        try (Connection migrator = DatabaseRoles.migrator()) {
            migrator.setAutoCommit(false);
            for (Map.Entry<String, String> refused :
                    List.of(
                            Map.entry(
                                    "UPDATE reconciliation.resolution SET narrative = 'edited'"
                                            + " WHERE id = '" + resolutionId + "'",
                                    "frozen when proposed"),
                            Map.entry(
                                    "DELETE FROM reconciliation.resolution WHERE id = '"
                                            + resolutionId + "'",
                                    "never deleted"),
                            Map.entry(
                                    "DELETE FROM reconciliation.resolution_event WHERE"
                                            + " resolution_id = '" + resolutionId + "'",
                                    "append-only"))) {
                assertThatThrownBy(() -> execute(migrator, refused.getKey()))
                        .as("the trigger refuses the migrator too")
                        .hasStackTraceContaining(refused.getValue());
                migrator.rollback();
            }
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

    private static UUID seedRun(Line... lines) throws SQLException {
        UUID runId = IDS.next();
        long sequence = SEQUENCES.incrementAndGet();
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            runs.birth(
                    app,
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
                byte[] fingerprint = new byte[32];
                new SecureRandom().nextBytes(fingerprint);
                newItems.add(
                        new ExternalItems.NewItem(
                                IDS.next(),
                                runId,
                                SOURCE,
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

    private static UUID openExpectation(String keyValue, long minor) throws SQLException {
        return openExpectation(
                keyValue, minor, ExpectationKind.CARD_CAPTURE, KeyKind.PSP_CAPTURE_REF);
    }

    private static UUID openExpectation(
            String keyValue, long minor, ExpectationKind kind, KeyKind keyKind)
            throws SQLException {
        String operationRef =
                "op-" + keyValue + "-" + IDS.next().toString().substring(24);
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
                            kind,
                            operationRef,
                            "p8t12:" + operationRef,
                            SOURCE,
                            AccountPurpose.SETTLEMENT_CLEARING,
                            position,
                            ExpectationDirection.INBOUND,
                            Money.ofPersisted(minor, EUR, 2),
                            Optional.of(IDS.next()),
                            SETTLED_ON,
                            Optional.empty(),
                            SETTLED_ON.plusDays(3),
                            RULE_SET,
                            List.of(new NewExpectation.ExpectationKey(keyKind, keyValue)),
                            PLATFORM,
                            Instant.now(CLOCK),
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

    // ----------------------------------------------------------------- plumbing

    private static void execute(Connection unitOfWork, String sql, Object... args) {
        try (PreparedStatement statement = unitOfWork.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new ReconciliationStorageException("statement failed: " + sql, failure);
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

    private Object[] feeTriple(UUID runId, int lineNo) throws SQLException {
        return row("SELECT d.fee_expected_minor, d.fee_reported_minor,"
                + " d.fee_tolerance_minor FROM reconciliation.match_decision d JOIN"
                + " reconciliation.external_item i ON i.id = d.external_item_id WHERE"
                + " d.run_id = ? AND i.line_no = " + lineNo, runId);
    }

    private long feeBreak(UUID runId, int lineNo) throws SQLException {
        return count("SELECT count(*) FROM reconciliation.break b JOIN"
                + " reconciliation.external_item i ON b.external_item_id = i.id WHERE"
                + " i.run_id = ? AND i.line_no = " + lineNo
                + " AND b.type = 'FEE_MISMATCH' AND b.cause = 'FEE_BEYOND_TOLERANCE'",
                runId);
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
