package com.finapp.app.accounting;

import static com.finapp.app.accounting.AccountingPeriodFixture.FEBRUARY;
import static com.finapp.app.accounting.AccountingPeriodFixture.IDS;
import static com.finapp.app.accounting.AccountingPeriodFixture.JANUARY;
import static com.finapp.app.accounting.AccountingPeriodFixture.NOW;
import static com.finapp.app.accounting.AccountingPeriodFixture.correlation;
import static com.finapp.app.accounting.AccountingPeriodFixture.id;
import static com.finapp.app.accounting.AccountingPeriodFixture.inScope;
import static com.finapp.app.accounting.AccountingPeriodFixture.periods;
import static com.finapp.app.accounting.AccountingPeriodFixture.person;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.JdbcReportRunStore;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.RunBasis;
import com.finapp.accounting.RunFailure;
import com.finapp.accounting.RunStatus;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.testing.TestReportDefinition;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.testing.database.DatabaseRoles;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Report runs against a live PostgreSQL (`P14-TSK-008`; {@code accounting V004}; ADR-0115; {@code PHASE_14_PLAN.md}
 * section 8.3 row 7; {@code INV-ACC-04}, {@code INV-ACC-14}) - the backlog entry's named cases and what the design needs:
 * two (and ten) generators make one run; a write inside generation is refused by the database ({@code 25006}); a FINAL
 * run regenerates identical, a definition reading what the lock does not freeze diverges; a dead generator's run is
 * reclaimed by one attempt more and its late completion moves nothing; the content is frozen <em>by its own trigger</em>
 * (the entry's probe target); the unlock supersedes FINAL runs and a raw unlock leaving one is refused; an open period's
 * run is PROVISIONAL and a FINAL birth for it refused; the database recomputes the hash. Only January and February 1999
 * are used ({@link AccountingPeriodFixture}), every question carries its own probe, and each case returns the periods to
 * {@code OPEN} - superseding its FINAL runs on the way. <strong>Written and owed</strong> (owner decision C5: the database
 * tiers are skipped per task).
 */
@Tag("database")
@DisplayName("report runs against the database (P14-TSK-008)")
class ReportRunDatabaseTest {

    private static final int CLAIMERS = 10;

    /** The books a FINAL run reads: the journal entries dated in its period - frozen by the cutoff once it locks. */
    private static final TestReportDefinition JOURNAL = new TestReportDefinition("TEST_JOURNAL", 1, Set.of("probe"),
            false, (snapshot, inputs) -> CanonicalJson.object()
                    .put("entries", count(snapshot, "SELECT count(*) FROM ledger.journal_entry WHERE posting_date"
                            + " BETWEEN DATE '" + inputs.start() + "' AND DATE '" + inputs.end() + "'"))
                    .put("probe", inputs.parameters().values().get("probe")));

    /** What a lock does not freeze: the report runs themselves - a definition reading them cannot reproduce. */
    private static final TestReportDefinition LEAKY = new TestReportDefinition("TEST_LEAKY", 1, Set.of("probe"),
            false, (snapshot, inputs) -> CanonicalJson.object()
                    .put("runs", count(snapshot, "SELECT count(*) FROM accounting.report_run")));

    /** The SQLSTATE the writer's refused statement raised - read by the case. */
    private static final AtomicReference<String> REFUSED = new AtomicReference<>();

    /**
     * A definition that tries to write inside its snapshot the one way its view lets a string through - a data-modifying
     * {@code WITH}, a single statement beginning with {@code WITH} - so the database's own refusal ({@code 25006}) is what
     * stops it.
     */
    private static final TestReportDefinition WRITER = new TestReportDefinition("TEST_WRITER", 1, Set.of("probe"),
            false, (snapshot, inputs) -> {
                try (PreparedStatement write = snapshot.prepareStatement("WITH touched AS (UPDATE"
                        + " accounting.accounting_period SET status = status WHERE id = ? RETURNING id)"
                        + " SELECT count(*) FROM touched")) {
                    write.setObject(1, inputs.periodId().value());
                    write.executeQuery();
                    return CanonicalJson.object().put("wrote", true);
                } catch (SQLException refused) {
                    REFUSED.set(refused.getSQLState());
                    throw new IllegalStateException("the snapshot refused the write: " + refused.getSQLState());
                }
            });

    /** A definition that tries to reopen its transaction writable - the view refuses it before the database sees it. */
    private static final TestReportDefinition REOPENER = new TestReportDefinition("TEST_REOPENER", 1, Set.of("probe"),
            false, (snapshot, inputs) -> {
                try {
                    snapshot.commit();
                    snapshot.setReadOnly(false);
                    return CanonicalJson.object().put("reopened", true);
                } catch (SQLException impossible) {
                    throw new IllegalStateException(impossible.getSQLState());
                }
            });

    /** A definition whose one statement sleeps past any bound a suite sets. */
    private static final TestReportDefinition SLEEPER = new TestReportDefinition("TEST_SLEEPER", 1, Set.of("probe"),
            false, (snapshot, inputs) -> CanonicalJson.object().put("slept",
                    count(snapshot, "SELECT count(*) FROM generate_series(1, 200000000)")));

    private static final ReportDefinitions DEFINITIONS = ReportDefinitions.of(TestReportDefinition.echo(), JOURNAL,
            LEAKY, WRITER, REOPENER, SLEEPER);

    /** accounting's unit of work over the application role: one transaction, committed or rolled back whole. */
    private static final AccountingTransactions TRANSACTIONS = new AccountingTransactions() {
        @Override
        public <R> R inTransaction(Function<Connection, R> work) {
            try (Connection connection = DatabaseRoles.application()) {
                connection.setAutoCommit(false);
                try {
                    R result = work.apply(connection);
                    connection.commit();
                    return result;
                } catch (RuntimeException failure) {
                    connection.rollback();
                    throw failure;
                }
            } catch (SQLException failure) {
                throw new IllegalStateException(failure);
            }
        }
    };

    private static final JdbcReportSnapshots SNAPSHOTS = new JdbcReportSnapshots(DatabaseRoles::application,
            ReportRuns.Leases.STANDARD.generationTimeout());

    @BeforeEach
    void openAndRestore() {
        AccountingPeriodFixture.restore();
        REFUSED.set(null);
    }

    @AfterEach
    void leaveOpen() {
        AccountingPeriodFixture.restore();
    }

    @Test
    @DisplayName("contention row 7: two generators asking for the same run on two connections - one run born, generated"
            + " once, one completion edge, one event, one audit record; both answer it FINAL")
    void twoGeneratorsOneRun() throws Exception {
        AccountingPeriodId january = lockJanuary();
        RunParameters probe = probe();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<ReportRunStore.RunRow> answered = new ArrayList<>();
        try {
            List<Future<ReportRunStore.RunRow>> racing = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                racing.add(pool.submit(() -> {
                    start.await();
                    return generate(january, probe, JOURNAL.code(), ReportRuns.Leases.STANDARD);
                }));
            }
            start.countDown();
            for (Future<ReportRunStore.RunRow> run : racing) {
                answered.add(run.get(2, TimeUnit.MINUTES));
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(answered).extracting(ReportRunStore.RunRow::id).containsOnly(answered.get(0).id());
        String run = answered.get(0).id().value().toString();
        assertThat(count("SELECT count(*) FROM accounting.report_run WHERE parameters_hash = '" + probe.hash() + "'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM accounting.report_run WHERE id = '" + run + "' AND status = 'FINAL'"))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = '" + ReportRuns.COMPLETED_EVENT
                + "' AND aggregate_id = '" + run + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.ReportGenerated'"
                + " AND target_id = '" + run + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("ten claimers on ten connections: one INSERT wins the plan's key, nine read it back - one run, one holder")
    void tenClaimersOneRun() throws Exception {
        AccountingPeriodId january = lockJanuary();
        RunParameters probe = probe();
        ExecutorService pool = Executors.newFixedThreadPool(CLAIMERS);
        CountDownLatch start = new CountDownLatch(1);
        List<ReportRuns.Claimed> claims = new ArrayList<>();
        try {
            List<Future<ReportRuns.Claimed>> racing = new ArrayList<>();
            for (int i = 0; i < CLAIMERS; i++) {
                racing.add(pool.submit(() -> {
                    start.await();
                    return TRANSACTIONS.inTransaction(uow -> runs(ReportRuns.Leases.STANDARD).claim(uow,
                            JOURNAL.code(), january, probe, person(), correlation()));
                }));
            }
            start.countDown();
            for (Future<ReportRuns.Claimed> claim : racing) {
                claims.add(claim.get(2, TimeUnit.MINUTES));
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(claims).extracting(claim -> claim.run().id()).containsOnly(claims.get(0).run().id());
        assertThat(claims).filteredOn(ReportRuns.Claimed::born).hasSize(1);
        assertThat(claims).filteredOn(ReportRuns.Claimed::mine).hasSize(1);
        assertThat(count("SELECT count(*) FROM accounting.report_run WHERE parameters_hash = '" + probe.hash() + "'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("INV-ACC-14: a write inside generation is refused by the database (25006) - on the snapshot directly, and"
            + " through a definition: the run FAILED DEFINITION_FAILED, nothing written")
    void aWriteInsideGenerationIsRefused() throws Exception {
        AccountingPeriodId january = lockJanuary();
        String state = SNAPSHOTS.read(snapshot -> {
            try (Statement write = snapshot.createStatement()) {
                write.executeUpdate("INSERT INTO accounting.period_event (id, period_id, act, from_status, to_status,"
                        + " revision, actor, actor_type, occurred_at, correlation_id) VALUES ('" + IDS.next() + "', '"
                        + january.value() + "', 'OPENED', NULL, 'OPEN', 1, 'raw', 'SYSTEM', clock_timestamp(), 'raw')");
                return "written";
            } catch (SQLException refused) {
                return refused.getSQLState();
            }
        });
        assertThat(state).isEqualTo("25006");
        ReportRunStore.RunRow run = generate(january, probe(), WRITER.code(), ReportRuns.Leases.STANDARD);
        assertThat(REFUSED.get()).isEqualTo("25006");
        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.failure()).contains(RunFailure.DEFINITION_FAILED);
        assertThat(count("SELECT count(*) FROM accounting.period_event WHERE actor = 'raw'")).isZero();
        // The review's I-2: a commit and a writable reopen are refused by the view - the transaction never ends early.
        ReportRunStore.RunRow reopened = generate(january, probe(), REOPENER.code(), ReportRuns.Leases.STANDARD);
        assertThat(reopened.status()).isEqualTo(RunStatus.FAILED);
        assertThat(reopened.failure()).contains(RunFailure.DEFINITION_FAILED);
    }

    @Test
    @DisplayName("the review's I-1: a statement past the generation's bound is cancelled by PostgreSQL (57014) and the run"
            + " fails GENERATION_TIMED_OUT - inside its lease, never overtaken by its own reclaim")
    void aGenerationPastItsBoundTimesOut() throws Exception {
        AccountingPeriodId january = lockJanuary();
        ReportRuns.Leases tight = new ReportRuns.Leases(Duration.ofSeconds(5), 5, Duration.ofMillis(300));
        ReportRunStore.RunRow run = generate(january, probe(), SLEEPER.code(), tight);
        assertThat(run.status()).isEqualTo(RunStatus.FAILED);
        assertThat(run.failure()).contains(RunFailure.GENERATION_TIMED_OUT);
        assertThat(run.attempt()).as("ended at its first attempt, not reclaimed").isEqualTo(1);
    }

    @Test
    @DisplayName("an unlock racing a FINAL claim, on separate connections: a claim holding the period FOR SHARE makes the"
            + " unlock wait, and the run it claimed fails PERIOD_MOVED; an unlock holding it FOR UPDATE makes the claim"
            + " wait, which then claims PROVISIONAL")
    void anUnlockRacingAFinalClaim() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // The claim first.
            AccountingPeriodId january = lockJanuary();
            proposeUnlock(january);
            ReportRuns.Claimed claimed;
            Future<?> unlock;
            try (Connection claimer = DatabaseRoles.application()) {
                claimer.setAutoCommit(false);
                claimed = runs(ReportRuns.Leases.STANDARD).claim(claimer, JOURNAL.code(), january, probe(), person(),
                        correlation());
                assertThat(claimed.run().basis()).isEqualTo(RunBasis.FINAL);
                unlock = pool.submit(() -> approveUnlock(january));
                assertThat(stillWaiting(unlock)).as("the unlock waits for the claim's FOR SHARE").isTrue();
                claimer.commit();
            }
            unlock.get(2, TimeUnit.MINUTES);
            ReportRunStore.RunRow concluded = generator(ReportRuns.Leases.STANDARD)
                    .generateAndComplete(claimed.run(), person());
            assertThat(concluded.status()).isEqualTo(RunStatus.FAILED);
            assertThat(concluded.failure()).contains(RunFailure.PERIOD_MOVED);
            // The unlock first.
            lockJanuary();
            proposeUnlock(january);
            Future<ReportRuns.Claimed> waiting;
            try (Connection unlocker = DatabaseRoles.application()) {
                unlocker.setAutoCommit(false);
                Actor approver = person();
                held(approver, () -> periods().approveUnlock(unlocker, january, approver, "racing", correlation()));
                waiting = pool.submit(() -> TRANSACTIONS.inTransaction(uow -> runs(ReportRuns.Leases.STANDARD)
                        .claim(uow, JOURNAL.code(), january, probe(), person(), correlation())));
                assertThat(stillWaiting(waiting)).as("the claim waits for the unlock's FOR UPDATE").isTrue();
                unlocker.commit();
            }
            assertThat(waiting.get(2, TimeUnit.MINUTES).run().basis()).isEqualTo(RunBasis.PROVISIONAL);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("an unlock racing a completion, on separate connections: a completion holding the period FOR SHARE"
            + " commits FINAL first and the unlock supersedes it; an unlock holding it FOR UPDATE commits first and the"
            + " completion fails PERIOD_MOVED - never a FINAL run over an open period")
    void anUnlockRacingACompletion() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // The completion first.
            AccountingPeriodId january = lockJanuary();
            proposeUnlock(january);
            ReportRunStore.RunRow first = claimOnly(january);
            ReportGeneration.Generated content = generated(first);
            Future<?> unlock;
            try (Connection completer = DatabaseRoles.application()) {
                completer.setAutoCommit(false);
                assertThat(runs(ReportRuns.Leases.STANDARD).complete(completer, first.id(), first.attempt(), content,
                        person()).moved()).isTrue();
                unlock = pool.submit(() -> approveUnlock(january));
                assertThat(stillWaiting(unlock)).as("the unlock waits for the completion's FOR SHARE").isTrue();
                completer.commit();
            }
            unlock.get(2, TimeUnit.MINUTES);
            assertThat(count("SELECT count(*) FROM accounting.report_run WHERE id = '" + first.id().value()
                    + "' AND status = 'SUPERSEDED'")).isEqualTo(1);
            // The unlock first.
            lockJanuary();
            proposeUnlock(january);
            ReportRunStore.RunRow second = claimOnly(january);
            ReportGeneration.Generated again = generated(second);
            Future<ReportRuns.Concluded> completing;
            try (Connection unlocker = DatabaseRoles.application()) {
                unlocker.setAutoCommit(false);
                Actor approver = person();
                held(approver, () -> periods().approveUnlock(unlocker, january, approver, "racing", correlation()));
                completing = pool.submit(() -> TRANSACTIONS.inTransaction(uow -> runs(ReportRuns.Leases.STANDARD)
                        .complete(uow, second.id(), second.attempt(), again, person())));
                assertThat(stillWaiting(completing)).as("the completion waits for the unlock's FOR UPDATE").isTrue();
                unlocker.commit();
            }
            ReportRuns.Concluded concluded = completing.get(2, TimeUnit.MINUTES);
            assertThat(concluded.run().status()).isEqualTo(RunStatus.FAILED);
            assertThat(concluded.run().failure()).contains(RunFailure.PERIOD_MOVED);
            assertThat(count("SELECT count(*) FROM accounting.report_run r JOIN accounting.accounting_period p ON"
                    + " p.id = r.period_id WHERE r.status = 'FINAL' AND p.status = 'OPEN'")).isZero();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("INV-ACC-04: a FINAL run regenerates IDENTICAL - its period's range frozen by the cutoff, an entry dated"
            + " in it refused at insert")
    void regenerationIsIdentical() throws Exception {
        AccountingPeriodId january = lockJanuary();
        ReportRunStore.RunRow run = generate(january, probe(), JOURNAL.code(), ReportRuns.Leases.STANDARD);
        assertThat(run.status()).isEqualTo(RunStatus.FINAL);
        ReportRunGenerator.Compared compared = generator(ReportRuns.Leases.STANDARD).regenerate(run.id());
        assertThat(compared.identical()).isTrue();
        assertThat(compared.regeneratedSha256()).isEqualTo(run.contentSha256().orElseThrow());
        assertThat(count("SELECT count(*) FROM accounting.report_run WHERE id = '" + run.id().value() + "' AND"
                + " content_sha256 = encode(sha256(convert_to(content, 'UTF8')), 'hex')")).isEqualTo(1);
    }

    @Test
    @DisplayName("a definition reading what the lock does not freeze regenerates DIVERGED - never repaired")
    void aDivergenceIsDetectedAndNeverRepaired() throws Exception {
        AccountingPeriodId january = lockJanuary();
        ReportRunStore.RunRow leaky = generate(january, probe(), LEAKY.code(), ReportRuns.Leases.STANDARD);
        generate(january, probe(), TestReportDefinition.CODE, ReportRuns.Leases.STANDARD); // one run more
        ReportRunGenerator.Compared compared = generator(ReportRuns.Leases.STANDARD).regenerate(leaky.id());
        assertThat(compared.identical()).isFalse();
        TRANSACTIONS.inTransaction(uow -> {
            runs(ReportRuns.Leases.STANDARD).recordRegeneration(uow, compared.run(), false, person(), correlation());
            return null;
        });
        assertThat(count("SELECT count(*) FROM accounting.report_run WHERE id = '" + leaky.id().value()
                + "' AND content_sha256 = '" + leaky.contentSha256().orElseThrow() + "'")).as("never repaired")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.ReportRegenerated'"
                + " AND target_id = '" + leaky.id().value() + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("a dead generator is reclaimed: past its lease the orphan leg takes it by one attempt more and completes"
            + " it; the dead attempt's late completion moves nothing - one run, one event")
    void aDeadGeneratorIsReclaimed() throws Exception {
        AccountingPeriodId january = lockJanuary();
        ReportRuns.Leases short_ = new ReportRuns.Leases(Duration.ofMillis(500), 5, Duration.ofMillis(400));
        ReportRunStore.RunRow dead = TRANSACTIONS.inTransaction(uow -> runs(short_).claim(uow, JOURNAL.code(),
                january, probe(), person(), correlation())).run();
        assertThat(dead.attempt()).isEqualTo(1);
        Thread.sleep(800);
        assertThat(generator(short_).completeOrphaned(100, Actor.SYSTEM)).isPositive();
        ReportRunStore.RunRow done = TRANSACTIONS.inTransaction(uow -> runs(short_).find(uow, dead.id())).orElseThrow();
        assertThat(done.status()).isEqualTo(RunStatus.FINAL);
        assertThat(done.attempt()).isEqualTo(2);
        ReportGeneration.Generated late = SNAPSHOTS.read(snapshot -> new ReportGeneration(
                ReportGeneration.FROM_THE_SNAPSHOT, short_.generationTimeout()).generate(snapshot, JOURNAL, dead.inputs()));
        assertThat(TRANSACTIONS.inTransaction(uow -> runs(short_).complete(uow, dead.id(), 1, late, person())).moved())
                .isFalse();
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = '" + ReportRuns.COMPLETED_EVENT
                + "' AND aggregate_id = '" + dead.id().value() + "'")).isEqualTo(1);
        // The reclaim is the database's rank too: a live lease is not taken.
        ReportRunStore.RunRow live = TRANSACTIONS.inTransaction(uow -> runs(ReportRuns.Leases.STANDARD).claim(uow,
                JOURNAL.code(), january, probe(), person(), correlation())).run();
        try (Connection owner = DatabaseRoles.migrator()) {
            refusedBy(owner, "UPDATE accounting.report_run SET attempt = attempt + 1, lease_until = clock_timestamp()"
                    + " + interval '1 minute' WHERE id = '" + live.id().value() + "'", "P0001", "past its lease");
        }
    }

    @Test
    @DisplayName("the content is frozen by its own trigger: a completed run's content, hash or completion changed by the"
            + " owner itself is refused by report_run_content_is_frozen; never deleted, never truncated - the probe target")
    void contentIsFrozen() throws Exception {
        AccountingPeriodId january = lockJanuary();
        ReportRunStore.RunRow run = generate(january, probe(), JOURNAL.code(), ReportRuns.Leases.STANDARD);
        String other = "{\"tampered\":\"true\"}";
        try (Connection owner = DatabaseRoles.migrator()) {
            refusedBy(owner, "UPDATE accounting.report_run SET content = '" + other + "', content_sha256 = encode(sha256("
                    + "convert_to('" + other + "', 'UTF8')), 'hex') WHERE id = '" + run.id().value() + "'", "P0001",
                    "report_run_content_is_frozen");
            refusedBy(owner, "UPDATE accounting.report_run SET completed_at = completed_at + interval '1 second' WHERE"
                    + " id = '" + run.id().value() + "'", "P0001", "report_run_content_is_frozen");
            refusedBy(owner, "DELETE FROM accounting.report_run WHERE id = '" + run.id().value() + "'", "P0001",
                    "never deleted");
            refusedBy(owner, "TRUNCATE accounting.report_run", "P0001", "never truncated");
        }
        assertThat(count("SELECT count(*) FROM accounting.report_run WHERE id = '" + run.id().value()
                + "' AND content_sha256 = '" + run.contentSha256().orElseThrow() + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("the unlock supersedes the period's FINAL runs - content kept; a raw unlock leaving a FINAL run is refused"
            + " by accounting_period_reopens_with_its_runs_superseded")
    void anUnlockSupersedesTheFinalRuns() throws Exception {
        AccountingPeriodId january = lockJanuary();
        ReportRunStore.RunRow run = generate(january, probe(), JOURNAL.code(), ReportRuns.Leases.STANDARD);
        Actor accountant = person();
        inScope(accountant, uow -> periods().proposeUnlock(uow, january, accountant, "a raw approval", correlation()));
        try (Connection owner = DatabaseRoles.migrator()) {
            refusedBy(owner, "UPDATE accounting.accounting_period SET status = 'OPEN', locked_by = NULL, locked_by_type ="
                    + " NULL, locked_at = NULL, pinned_mapping_version_id = NULL, unlock_approved_by = 'raw-approver',"
                    + " unlock_approved_by_type = 'CUSTOMER', unlock_approved_at = clock_timestamp(),"
                    + " unlock_approval_reason = 'raw' WHERE id = '" + january.value() + "'", "P0001",
                    "FINAL report runs superseded");
        }
        AccountingPeriodFixture.restore(); // the machine's own approval - which supersedes
        assertThat(count("SELECT count(*) FROM accounting.report_run WHERE id = '" + run.id().value()
                + "' AND status = 'SUPERSEDED' AND content IS NOT NULL AND superseded_at IS NOT NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.PeriodUnlocked'"
                + " AND target_id = '" + january.value() + "' AND change_summary LIKE '%supersededRuns=1'"))
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("an open period's run is PROVISIONAL, cut at its claim; a FINAL birth for an open period is refused for"
            + " every writer")
    void anOpenPeriodGetsAProvisionalRun() throws Exception {
        AccountingPeriodId february = id(FEBRUARY);
        ReportRunStore.RunRow run = generate(february, probe(), JOURNAL.code(), ReportRuns.Leases.STANDARD);
        assertThat(run.basis()).isEqualTo(RunBasis.PROVISIONAL);
        assertThat(run.status()).isEqualTo(RunStatus.PROVISIONAL);
        assertThat(run.periodLockedAt()).isEmpty();
        String parameters = RunParameters.none().canonical();
        try (Connection owner = DatabaseRoles.migrator()) {
            refusedBy(owner, "INSERT INTO accounting.report_run (id, definition, definition_version, period_id, revision,"
                    + " parameters, parameters_hash, period_revision, basis, period_locked_at, cut_at, requested_by,"
                    + " requested_by_type, claimed_at, status, attempt, lease_until, correlation_id) VALUES ('"
                    + IDS.next() + "', 'TEST_RAW', 1, '" + february.value() + "', 1, '" + parameters + "', encode(sha256("
                    + "convert_to('" + parameters + "', 'UTF8')), 'hex'), 1, 'FINAL', clock_timestamp(),"
                    + " clock_timestamp(), 'raw', 'CUSTOMER', clock_timestamp(), 'GENERATING', 1, clock_timestamp()"
                    + " + interval '1 minute', 'raw')", "P0001", "no longer OPEN");
        }
    }

    @Test
    @DisplayName("the database recomputes the hash: a completion whose content_sha256 is not its content's is refused by"
            + " report_run_content_hashed")
    void theDatabaseVerifiesTheHash() throws Exception {
        AccountingPeriodId january = lockJanuary();
        ReportRunStore.RunRow claimed = TRANSACTIONS.inTransaction(uow -> runs(ReportRuns.Leases.STANDARD).claim(uow,
                JOURNAL.code(), january, probe(), person(), correlation())).run();
        try (Connection owner = DatabaseRoles.migrator()) {
            refusedBy(owner, "UPDATE accounting.report_run SET status = 'FINAL', content = '{}', content_sha256 ="
                    + " repeat('0', 64), completed_at = clock_timestamp() WHERE id = '" + claimed.id().value() + "'",
                    "23514", "report_run_content_hashed");
        }
    }

    // -----------------------------------------------------------------

    /** An unlock of {@code period} proposed by one person - the racing approval is another's. */
    private static void proposeUnlock(AccountingPeriodId period) {
        Actor proposer = person();
        inScope(proposer, uow -> periods().proposeUnlock(uow, period, proposer, "a racing unlock", correlation()));
    }

    /** The unlock approved and committed by a second person. */
    private static Object approveUnlock(AccountingPeriodId period) {
        Actor approver = person();
        return inScope(approver, uow -> periods().approveUnlock(uow, period, approver, "racing", correlation()));
    }

    /** {@code work} inside the actor's and a correlation's scopes, its transaction left open for the caller to commit. */
    @SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
    private static <R> R held(Actor actor, java.util.function.Supplier<R> work) {
        try (com.finapp.platform.security.SecurityContext.Scope who =
                        com.finapp.platform.security.SecurityContext.enter(actor);
                com.finapp.platform.correlation.CorrelationContext.Scope flow =
                        com.finapp.platform.correlation.CorrelationContext.enter(
                                com.finapp.sharedkernel.correlation.Correlation.startingWith(correlation()))) {
            return work.get();
        }
    }

    /** True when {@code racing} is still blocked half a second later - waiting on the other side's row lock. */
    private static boolean stillWaiting(Future<?> racing) throws InterruptedException {
        Thread.sleep(500);
        return !racing.isDone();
    }

    /** A FINAL-basis run claimed and committed, not generated. */
    private static ReportRunStore.RunRow claimOnly(AccountingPeriodId period) {
        return TRANSACTIONS.inTransaction(uow -> runs(ReportRuns.Leases.STANDARD).claim(uow, JOURNAL.code(), period,
                probe(), person(), correlation())).run();
    }

    /** The run's content, generated in a snapshot of its own. */
    private static ReportGeneration.Generated generated(ReportRunStore.RunRow run) {
        return SNAPSHOTS.read(snapshot -> new ReportGeneration(ReportGeneration.FROM_THE_SNAPSHOT,
                ReportRuns.Leases.STANDARD.generationTimeout()).generate(snapshot, JOURNAL, run.inputs()));
    }

    /** January 1999 locked by a person (the grace long past on the real clock). */
    private static AccountingPeriodId lockJanuary() {
        AccountingPeriodId january = id(JANUARY);
        Actor accountant = person();
        inScope(accountant, uow -> periods().lock(uow, january, accountant, null, correlation()));
        return january;
    }

    /** Claim, then generate and complete when this caller holds the run - the desk's path without the key. */
    private static ReportRunStore.RunRow generate(AccountingPeriodId period, RunParameters parameters, String code,
            ReportRuns.Leases leases) {
        Actor accountant = person();
        ReportRuns.Claimed claimed = TRANSACTIONS.inTransaction(uow -> runs(leases).claim(uow, code, period, parameters,
                accountant, correlation()));
        ReportRunGenerator generator = generator(leases);
        return claimed.mine() ? generator.generateAndComplete(claimed.run(), accountant)
                : generator.drive(claimed.run().id(), accountant);
    }

    private static ReportRuns runs(ReportRuns.Leases leases) {
        return new ReportRuns(new JdbcReportRunStore(), AccountingPeriodFixture.STORE, new JdbcGlMappingStore(),
                DEFINITIONS, leases, new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW);
    }

    private static ReportRunGenerator generator(ReportRuns.Leases leases) {
        return new ReportRunGenerator(runs(leases), new ReportGeneration(ReportGeneration.FROM_THE_SNAPSHOT,
                leases.generationTimeout()), SNAPSHOTS,
                TRANSACTIONS, new ReportRunMetrics(new SimpleMeterRegistry(), DEFINITIONS));
    }

    private static RunParameters probe() {
        return RunParameters.of(Map.of("probe", UUID.randomUUID().toString()), Optional.empty());
    }

    /** A test definition's read - a prepared statement, the only kind its {@code ReadOnlySnapshot} view runs. */
    private static long count(Connection snapshot, String sql) {
        try (PreparedStatement statement = snapshot.prepareStatement(sql); ResultSet row = statement.executeQuery()) {
            row.next();
            return row.getLong(1);
        } catch (SQLException failure) {
            throw new IllegalStateException("a test definition's read failed: " + failure.getSQLState());
        }
    }

    private static long count(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement();
                ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getLong(1);
        }
    }

    /** Refused with {@code sqlState}, its message naming {@code rank} - not another rank raising the same state. */
    private static void refusedBy(Connection connection, String sql, String sqlState, String rank) {
        assertThatThrownBy(() -> {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute(sql);
                    }
                })
                .as(sql)
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> {
                    assertThat(((SQLException) failure).getSQLState()).as(sql).isEqualTo(sqlState);
                    assertThat(failure.getMessage()).as("refused by %s itself", rank).contains(rank);
                });
    }
}
