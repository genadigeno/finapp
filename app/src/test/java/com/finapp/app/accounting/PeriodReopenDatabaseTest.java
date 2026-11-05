package com.finapp.app.accounting;

import static com.finapp.app.accounting.AccountingPeriodFixture.FEBRUARY;
import static com.finapp.app.accounting.AccountingPeriodFixture.IDS;
import static com.finapp.app.accounting.AccountingPeriodFixture.JANUARY;
import static com.finapp.app.accounting.AccountingPeriodFixture.NOW;
import static com.finapp.app.accounting.AccountingPeriodFixture.correlation;
import static com.finapp.app.accounting.AccountingPeriodFixture.id;
import static com.finapp.app.accounting.AccountingPeriodFixture.inScope;
import static com.finapp.app.accounting.AccountingPeriodFixture.person;
import static com.finapp.app.accounting.AccountingPeriodFixture.row;
import static com.finapp.app.accounting.PeriodCloseFixture.ACCOUNTANT;
import static com.finapp.app.accounting.PeriodCloseFixture.CLOSES;
import static com.finapp.app.accounting.PeriodCloseFixture.CONTROLLER;
import static com.finapp.app.accounting.PeriodCloseFixture.MACHINE;
import static com.finapp.app.accounting.PeriodCloseFixture.REASON;
import static com.finapp.app.accounting.PeriodCloseFixture.RUN_STORE;
import static com.finapp.app.accounting.PeriodCloseFixture.count;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.CloseRunId;
import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.JdbcPriorPeriodAdjustmentStore;
import com.finapp.accounting.JdbcRegulatoryObligationStore;
import com.finapp.accounting.JdbcRegulatorySubmissionStore;
import com.finapp.accounting.JdbcReportRunStore;
import com.finapp.accounting.JdbcReturnsAReopenOvertakes;
import com.finapp.accounting.JdbcValuationRateSetStore;
import com.finapp.accounting.PeriodCloses;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.PostingCutoff;
import com.finapp.accounting.PriorPeriodAdjustments;
import com.finapp.accounting.RegulatoryAdapters;
import com.finapp.accounting.RegulatoryObligationId;
import com.finapp.accounting.RegulatoryObligations;
import com.finapp.accounting.RegulatoryReportingProvider.Answer;
import com.finapp.accounting.RegulatorySubmissionId;
import com.finapp.accounting.RegulatorySubmissions;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.SubmissionStatus;
import com.finapp.accounting.ValuationRateSetStore;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.reporting.TrialBalanceDefinition;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.accounting.testing.InMemoryPriorPeriodPostings;
import com.finapp.accounting.testing.RegulatoryWorld;
import com.finapp.accounting.testing.ScriptedRegulatoryProvider;
import com.finapp.accounting.testing.TestReportDefinition;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.PeriodClosedException;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingCutoffs;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The reopen against a live PostgreSQL (`P14-TSK-012`; {@code PHASE_14_PLAN.md} sections 6, 8.1 T-reopen, 8.3 row 9, 13.5; {@code INV-ACC-03}, {@code INV-ACC-10}, {@code INV-AUD-04}): only the latest closed period, the next not locked; the cutoff back exactly one period; {@code revision
 * + 1}; FINAL runs {@code SUPERSEDED} and nothing deleted, in one transaction; row 9, the reopen racing postings into the next period; a re-close a new run under the new lock. {@code accounting V008} executes here, and nowhere before the phase exit. <strong>Written and owed</strong> (owner decision C5).
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): it closes and posts into January and February 1999, shared fixtures, and commits ledger lines.
 *
 * <p><strong>No case's month is clean by luck.</strong> A reopen moves a revision for good and a return is never deleted, so a month holding a return holds it for every later case. {@link #aReopensReceiptsListTheReturnsItOvertakes} is the only case here that makes one and the only one that reads any, and it asserts January's list empty <em>before</em> it makes one, whatever order JUnit runs the cases in. Every other case calls the machine and never reads a list, so what it leaves behind asks nothing of them: a stale draft or a return standing sent refuses no period's edge.
 */
@Tag("database")
@Tag("own-container") // its own JVM and database: ownContainerDatabaseTest, the javadoc says why
@DisplayName("the reopen against the database (P14-TSK-012)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class PeriodReopenDatabaseTest {

    private static final int POSTERS = 10;
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    private static final LocalDate IN_JANUARY = JANUARY.atDay(20);
    private static final LocalDate IN_FEBRUARY = FEBRUARY.atDay(10);

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

    private final JdbcLedgerAccountStore accounts = new JdbcLedgerAccountStore();

    @BeforeEach
    void everyPeriodIsOpen() {
        AccountingPeriodFixture.openJanuaryAndFebruary();
        PeriodCloseFixture.mappingInForce();
        PeriodCloseFixture.everyPeriodOpen();
    }

    @Test
    @DisplayName("a reopen supersedes and deletes nothing: the FINAL run SUPERSEDED, its content kept; the cutoff back one period; revision + 1; lock and pin released; the proposal, the close record, the run and the attempts all stand; one event, one audit record")
    void aReopenSupersedesAndDeletesNothing() throws Exception {
        CloseRunId run = PeriodCloseFixture.ready(JANUARY);
        AccountingPeriodId january = id(JANUARY);
        TrialBalanceDefinition trialBalance = new TrialBalanceDefinition(new LedgerTrialBalanceReads(new JdbcGlMappingStore()));
        ReportDefinitions definitions = ReportDefinitions.of(trialBalance);
        ReportRuns reports = new ReportRuns(new JdbcReportRunStore(), AccountingPeriodFixture.STORE, new JdbcGlMappingStore(), definitions, ReportRuns.Leases.STANDARD, new JdbcAuditWriter(),
                new JdbcOutboxWriter(), IDS, NOW);
        ReportRunGenerator generator = new ReportRunGenerator(reports, new ReportGeneration(ReportGeneration.FROM_THE_SNAPSHOT, ReportRuns.Leases.STANDARD.generationTimeout(), NOW),
                new JdbcReportSnapshots(DatabaseRoles::application, ReportRuns.Leases.STANDARD.generationTimeout()), TRANSACTIONS, new ReportRunMetrics(new SimpleMeterRegistry(), definitions));
        Actor generating = person();
        ReportRuns.Claimed claimed = TRANSACTIONS.inTransaction(uow -> reports.claim(uow, TrialBalanceDefinition.CODE, january, RunParameters.none(), generating, correlation()));
        if (claimed.mine()) {
            generator.generateAndComplete(claimed.run(), generating);
        } else {
            generator.drive(claimed.run().id(), generating);
        }
        long finalRuns = count("SELECT count(*) FROM accounting.report_run WHERE period_id = '" + january.value() + "' AND status = 'FINAL'");
        assertThat(finalRuns).isPositive();

        PeriodCloses.Acted proposal = inScope(ACCOUNTANT, uow -> MACHINE.propose(uow, january, run, List.of(), ACCOUNTANT, REASON, correlation()));
        inScope(CONTROLLER, uow -> MACHINE.approve(uow, january, proposal.proposal().orElseThrow(), CONTROLLER, REASON, correlation()));
        long attempts = count("SELECT count(*) FROM accounting.close_check WHERE close_run_id = '" + run.value() + "'");
        long allRuns = count("SELECT count(*) FROM accounting.report_run WHERE period_id = '" + january.value() + "'");
        int revision = row(JANUARY).revision();
        assertThat(closedThrough()).contains(JANUARY.atEndOfMonth());

        PeriodCloses.Acted proposed = inScope(ACCOUNTANT, uow -> MACHINE.proposeReopen(uow, january, ACCOUNTANT, PeriodCloseFixture.posted(uow, january), "a correction was found", correlation()));
        assertThat(proposed.status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(row(JANUARY).status()).as("nothing moves on a proposal").isEqualTo(PeriodStatus.CLOSED);
        assertThat(closedThrough()).contains(JANUARY.atEndOfMonth());

        PeriodCloses.Acted acted = inScope(CONTROLLER, uow -> MACHINE.approveReopen(uow, january, CONTROLLER, PeriodCloseFixture.posted(uow, january), "agreed", correlation()));
        assertThat(acted.status()).isEqualTo(PeriodStatus.OPEN);
        assertThat(acted.revision()).isEqualTo(revision + 1);
        assertThat(row(JANUARY).revision()).isEqualTo(revision + 1);
        assertThat(row(JANUARY).lockedAt()).isEmpty();
        assertThat(row(JANUARY).pinned()).isEmpty();
        assertThat(closedThrough()).as("back exactly one period: nothing before January").isEmpty();

        assertThat(count("SELECT count(*) FROM accounting.report_run WHERE period_id = '" + january.value() + "' AND status = 'FINAL'")).isZero();
        assertThat(count("SELECT count(*) FROM accounting.report_run WHERE period_id = '" + january.value() + "'")).as("nothing deleted").isEqualTo(allRuns);
        assertThat(count("SELECT count(*) FROM accounting.report_run WHERE period_id = '" + january.value() + "' AND status = 'SUPERSEDED' AND content IS NOT NULL")).isGreaterThanOrEqualTo(finalRuns);
        assertThat(count("SELECT count(*) FROM accounting.close_check WHERE close_run_id = '" + run.value() + "'")).isEqualTo(attempts);
        assertThat(count("SELECT count(*) FROM accounting.period_close WHERE period_id = '" + january.value() + "' AND status = 'APPROVED'")).isPositive();
        assertThat(count("SELECT count(*) FROM accounting.period_close_check WHERE close_id = '" + proposal.proposal().orElseThrow().value() + "'")).isEqualTo(9);
        assertThat(count("SELECT count(*) FROM accounting.period_reopen WHERE period_id = '" + january.value() + "' AND status = 'APPROVED' AND period_revision = " + revision)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = '" + PeriodCloses.REOPENED_EVENT + "' AND aggregate_id = '" + january.value() + "'")).isPositive();
        assertThat(count("SELECT count(*) FROM accounting.period_event WHERE act = 'REOPENED' AND period_id = '" + january.value() + "' AND revision = " + (revision + 1))).isEqualTo(1);
    }

    @Test
    @DisplayName("contention row 9: a reopen refuses nothing outside its period: ten posters dated in the next period race the approval, every one commits; dated in the reopened period each refused before, floored or stated after")
    void aReopenRefusesNothingOutsideItsPeriod() throws Exception {
        LedgerAccount wallet = wallet();
        LedgerAccount revenue = operational(AccountPurpose.FEE_REVENUE);
        PeriodCloseFixture.closed(JANUARY);
        AccountingPeriodId january = id(JANUARY);
        assertThatThrownBy(() -> post(IN_JANUARY, wallet, revenue, PostingCommand.Dating.AS_STATED)).as("closed: refused").isInstanceOf(PeriodClosedException.class);
        inScope(ACCOUNTANT, uow -> MACHINE.proposeReopen(uow, january, ACCOUNTANT, PeriodCloseFixture.posted(uow, january), "a correction was found", correlation()));

        ExecutorService pool = Executors.newFixedThreadPool(POSTERS + 1);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> racing = new ArrayList<>();
        try {
            for (int poster = 0; poster < POSTERS; poster++) {
                racing.add(pool.submit(() -> {
                    start.await();
                    post(IN_FEBRUARY, wallet, revenue, PostingCommand.Dating.AS_STATED);
                    return null;
                }));
            }
            racing.add(pool.submit(() -> {
                start.await();
                // The cutoff's key answers busy after the ledger's bounded attempts, nothing changed; the person retries.
                while (true) {
                    try {
                        return inScope(CONTROLLER, uow -> MACHINE.approveReopen(uow, january, CONTROLLER, PeriodCloseFixture.posted(uow, january), "agreed", correlation()));
                    } catch (PostingCutoff.CutoffBusy busy) {
                        Thread.onSpinWait();
                    }
                }
            }));
            start.countDown();
            for (Future<?> outcome : racing) {
                outcome.get(2, TimeUnit.MINUTES);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(row(JANUARY).status()).isEqualTo(PeriodStatus.OPEN);
        assertThat(closedThrough()).isEmpty();
        post(IN_JANUARY, wallet, revenue, PostingCommand.Dating.AS_STATED);
    }

    @Test
    @DisplayName("only the latest: the next period locked refuses the proposal and the approval, and the trigger refuses a raw edge; not a CLOSED period is stale; the proposer cannot approve, by the domain, the row and the history")
    void onlyTheLatest() throws Exception {
        PeriodCloseFixture.closed(JANUARY);
        AccountingPeriodId january = id(JANUARY);
        AccountingPeriodId february = id(FEBRUARY);
        assertThatThrownBy(() -> inScope(ACCOUNTANT, uow -> MACHINE.proposeReopen(uow, february, ACCOUNTANT, PeriodCloseFixture.posted(uow, february), "open", correlation()))).isInstanceOf(AccountingPeriods.PeriodStale.class);

        inScope(ACCOUNTANT, uow -> MACHINE.proposeReopen(uow, january, ACCOUNTANT, PeriodCloseFixture.posted(uow, january), "a correction", correlation()));
        assertThatThrownBy(() -> inScope(ACCOUNTANT, uow -> MACHINE.approveReopen(uow, january, ACCOUNTANT, PeriodCloseFixture.posted(uow, january), "mine", correlation()))).isInstanceOf(AccountingPeriods.SelfApprovalRefused.class);
        refused("UPDATE accounting.period_reopen SET status = 'APPROVED', approved_by = proposed_by, approved_by_type = 'CUSTOMER', approval_reason = 'raw' WHERE status = 'PROPOSED' AND period_id = '" + january.value() + "'", "period_reopen_four_eyes");
        refused("INSERT INTO accounting.period_event (id, period_id, act, from_status, to_status, revision, actor, actor_type, proposed_by, reason, occurred_at, correlation_id) VALUES ('"
                + IDS.next() + "', '" + january.value() + "', 'REOPENED', 'CLOSED', 'OPEN', 2, 'same', 'CUSTOMER', 'same', 'raw', clock_timestamp(), 'raw')", "period_event_four_eyes");
        refused("UPDATE accounting.accounting_period SET status = 'OPEN', revision = revision + 1, locked_by = NULL, locked_by_type = NULL, locked_at = NULL, pinned_mapping_version_id = NULL WHERE id = '" + january.value() + "'",
                "a second person");

        PeriodCloseFixture.locked(FEBRUARY);
        assertThatThrownBy(() -> inScope(CONTROLLER, uow -> MACHINE.approveReopen(uow, january, CONTROLLER, PeriodCloseFixture.posted(uow, january), "agreed", correlation()))).as("the next locked since").isInstanceOf(AccountingPeriods.PeriodStale.class);
        assertThat(row(JANUARY).status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(closedThrough()).contains(FEBRUARY.atEndOfMonth());
        refused("UPDATE accounting.accounting_period SET status = 'OPEN', revision = revision + 1, locked_by = NULL, locked_by_type = NULL, locked_at = NULL, pinned_mapping_version_id = NULL WHERE id = '" + january.value() + "'",
                "only the latest closed period");
        assertThatThrownBy(() -> inScope(ACCOUNTANT, uow -> MACHINE.proposeReopen(uow, january, person(), PeriodCloseFixture.posted(uow, january), "another", correlation()))).isInstanceOf(AccountingPeriods.PeriodStale.class);
    }

    @Test
    @DisplayName("a reopen's approval races a lock of the next period on the period rows, oldest first: exactly one wins; never a reopen with the next period locked")
    void aReopenRacingTheNextLock() throws Exception {
        PeriodCloseFixture.closed(JANUARY);
        AccountingPeriodId january = id(JANUARY);
        inScope(ACCOUNTANT, uow -> MACHINE.proposeReopen(uow, january, ACCOUNTANT, PeriodCloseFixture.posted(uow, january), "a correction", correlation()));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> reopen = pool.submit(() -> {
                start.await();
                try {
                    inScope(CONTROLLER, uow -> MACHINE.approveReopen(uow, january, CONTROLLER, PeriodCloseFixture.posted(uow, january), "agreed", correlation()));
                    return true;
                } catch (AccountingPeriods.PeriodStale stale) {
                    return false;
                }
            });
            Future<Boolean> lock = pool.submit(() -> {
                start.await();
                try {
                    PeriodCloseFixture.locked(FEBRUARY);
                    return true;
                } catch (AccountingPeriods.PeriodNotLockable early) {
                    return false;
                }
            });
            start.countDown();
            boolean reopened = reopen.get(2, TimeUnit.MINUTES);
            boolean locked = lock.get(2, TimeUnit.MINUTES);
            assertThat(reopened).as("one of them, never both").isNotEqualTo(locked);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a re-close is a new run under the new lock, at revision + 1; the first close's record and run stand; the old run's attempts are never claimed again")
    void aReCloseIsANewRun() throws Exception {
        PeriodCloseFixture.closed(JANUARY);
        AccountingPeriodId january = id(JANUARY);
        CloseRunId first = inScope(Actor.SYSTEM, uow -> RUN_STORE.latestRun(uow, january)).orElseThrow().id();
        inScope(ACCOUNTANT, uow -> MACHINE.proposeReopen(uow, january, ACCOUNTANT, PeriodCloseFixture.posted(uow, january), "a correction", correlation()));
        inScope(CONTROLLER, uow -> MACHINE.approveReopen(uow, january, CONTROLLER, PeriodCloseFixture.posted(uow, january), "agreed", correlation()));

        CloseRunId second = PeriodCloseFixture.ready(JANUARY);
        assertThat(second).isNotEqualTo(first);
        Optional<ValuationRateSetStore.SetRow> set = inScope(Actor.SYSTEM, uow -> new JdbcValuationRateSetStore().approvedFor(uow, january));
        assertThat(set).as("the set stands").isPresent();
        PeriodCloses.Acted proposal = inScope(ACCOUNTANT, uow -> MACHINE.propose(uow, january, second, List.of(), ACCOUNTANT, REASON, correlation()));
        PeriodCloses.Acted closed = inScope(CONTROLLER, uow -> MACHINE.approve(uow, january, proposal.proposal().orElseThrow(), CONTROLLER, REASON, correlation()));
        assertThat(closed.status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(closed.revision()).isEqualTo(row(JANUARY).revision());
        assertThat(inScope(Actor.SYSTEM, uow -> CLOSES.latest(uow, january)).orElseThrow().id()).isEqualTo(proposal.proposal().orElseThrow());
        assertThat(count("SELECT count(*) FROM accounting.period_close WHERE period_id = '" + january.value() + "' AND status = 'APPROVED'")).isGreaterThanOrEqualTo(2);
    }

    // ------------------------------------------------------------------ what a reopen's two receipts list (P14-TSK-023)

    private static final JdbcRegulatoryObligationStore OBLIGATIONS = new JdbcRegulatoryObligationStore();
    private static final JdbcRegulatorySubmissionStore SUBMISSIONS = new JdbcRegulatorySubmissionStore();
    private static final ReportDefinitions SOURCES = ReportDefinitions.of(new TestReportDefinition(RegulatoryWorld.SOURCE, 1, Set.of(), false, (snapshot, inputs) -> CanonicalJson.object()));
    private static final ReportRuns SOURCE_RUNS = new ReportRuns(new JdbcReportRunStore(), AccountingPeriodFixture.STORE, new JdbcGlMappingStore(), SOURCES, ReportRuns.Leases.STANDARD, new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW);

    private final ScriptedRegulatoryProvider provider = new ScriptedRegulatoryProvider();
    private final RegulatoryAdapters adapters = new RegulatoryAdapters(List.of(provider));
    private final RegulatorySubmissions returns = new RegulatorySubmissions(AccountingPeriodFixture.STORE, SUBMISSIONS, OBLIGATIONS, new JdbcReportRunStore(), adapters, new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW, Duration.ofDays(1));
    private final RegulatoryObligations obligations = new RegulatoryObligations(OBLIGATIONS, adapters, SOURCES, new JdbcAuditWriter(), IDS, NOW);
    private final RegulatorySubmissionDriver driver = new RegulatorySubmissionDriver(returns, TRANSACTIONS, new RegulatorySubmissionMetrics(new SimpleMeterRegistry()), new CloseRunMetrics(new SimpleMeterRegistry()), AccountingFlowScope.NONE, Duration.ofMinutes(2), Duration.ofMinutes(15), 100);
    private final PeriodCloseDesk desk = new PeriodCloseDesk(MACHINE,
            new PriorPeriodAdjustments(AccountingPeriodFixture.STORE, new JdbcPriorPeriodAdjustmentStore(), new InMemoryPriorPeriodPostings(), new InMemoryGlMappingStore(), new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW),
            new JdbcReturnsAReopenOvertakes(), new IdempotentExecutor(new JdbcIdempotencyRecordStore(), NOW, Duration.ofDays(1), Duration.ofMinutes(5)), TRANSACTIONS, AccountingFlowScope.NONE);

    private static <R> R as(Actor actor, Supplier<R> work) {
        try (SecurityContext.Scope scope = SecurityContext.enter(actor); CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            return work.get();
        }
    }

    private static List<String> named(PeriodCloseDesk.PeriodCloseReceipt receipt) {
        return receipt.overtakenReturns().stream().map(PeriodCloseDesk.OvertakenReturn::id).toList();
    }

    /** A return of a form of its own for {@code period}, as far as {@code to}, by the machine's own acts; a sending committed and no call made. Its form's own, so none of them meets another's one-live row. */
    private RegulatorySubmissions.Acted aReturn(AccountingPeriodId period, SubmissionStatus to) {
        String form = "REF-REOPEN-" + to.name();
        Actor proposer = person();
        RegulatoryObligations.Acted proposed = inScope(proposer, uow -> obligations.propose(uow, new RegulatoryObligations.Proposal(ScriptedRegulatoryProvider.JURISDICTION, form, 1, ScriptedRegulatoryProvider.NAME, RegulatoryWorld.configuration()), REASON, proposer, correlation()));
        Actor activator = person();
        inScope(activator, uow -> obligations.activate(uow, proposed.id(), proposed.configurationSha256(), REASON, activator, correlation()));
        RegulatoryObligationId obligation = proposed.id();
        Actor preparer = person();
        RegulatorySubmissions.Acted acted = inScope(preparer, uow -> returns.draft(uow, new RegulatorySubmissions.Draft(obligation, period, Optional.empty(), REASON), preparer, correlation()));
        RegulatorySubmissionId id = acted.id();
        if (to == SubmissionStatus.DRAFT) {
            return acted;
        }
        inScope(preparer, uow -> returns.validate(uow, id, preparer, correlation()));
        if (to == SubmissionStatus.VALIDATED) {
            return acted;
        }
        Actor approver = person();
        RegulatorySubmissions.Attested read = new RegulatorySubmissions.Attested(acted.contentSha256(), acted.periodRevision());
        if (to == SubmissionStatus.REFUSED) {
            inScope(approver, uow -> returns.refuse(uow, id, REASON, approver, correlation()));
            return acted;
        }
        inScope(approver, uow -> returns.approve(uow, id, read, REASON, approver, correlation()));
        if (to == SubmissionStatus.APPROVED) {
            return acted;
        }
        if (to == SubmissionStatus.WITHDRAWN) {
            inScope(preparer, uow -> returns.withdraw(uow, id, REASON, preparer, correlation()));
            return acted;
        }
        Actor sender = person();
        inScope(sender, uow -> returns.submit(uow, id, read, REASON, sender, correlation()));
        return acted;
    }

    @Test
    @DisplayName("P14-TSK-023 (a): a reopen's two receipts through the real desk and statement: January with no return reads an empty list on both; closed again with a return of each status, only SUBMITTED and ACKNOWLEDGED are listed, newest sent first; the approval reads its own, holding what was sent since the proposal; a key's replay returns what it stored though the row moved; a repeat under a new key reads what stands; the next reopen lists every revision's")
    void aReopensReceiptsListTheReturnsItOvertakes() {
        PeriodCloseFixture.closed(JANUARY);
        AccountingPeriodId january = id(JANUARY);
        String period = january.value().toString();
        List<String> none = inScope(Actor.SYSTEM, uow -> PeriodCloseFixture.posted(uow, january)).stream().map(Object::toString).toList();
        assertThat(none).as("this suite posts no adjustment against a month, so both acts name none").isEmpty();

        PeriodCloseDesk.PeriodCloseReceipt firstProposal = as(ACCOUNTANT, () -> desk.proposeReopen("reopen-none-p", period, none, "a correction was found"));
        PeriodCloseDesk.PeriodCloseReceipt firstApproval = as(CONTROLLER, () -> desk.approveReopen("reopen-none-a", period, none, "agreed"));
        for (PeriodCloseDesk.PeriodCloseReceipt receipt : List.of(firstProposal, firstApproval)) {
            assertThat(receipt.overtakenReturns()).as("this suite's first return comes below, so no case's order can hold one here").isEmpty();
            assertThat(receipt.overtakenReturnsTotal()).isZero();
            assertThat(receipt.overtakenReturnsNote()).isEqualTo(PeriodCloseDesk.OVERTAKEN_RETURNS_NOTE);
        }

        PeriodCloseFixture.closed(JANUARY);
        issued(january);
        for (SubmissionStatus unsent : List.of(SubmissionStatus.DRAFT, SubmissionStatus.VALIDATED, SubmissionStatus.REFUSED, SubmissionStatus.WITHDRAWN)) {
            aReturn(january, unsent);
        }
        RegulatorySubmissions.Acted approved = aReturn(january, SubmissionStatus.APPROVED);
        RegulatorySubmissions.Acted acknowledged = aReturn(january, SubmissionStatus.ACKNOWLEDGED);
        assertThat(driver.submitIfDue(acknowledged.id())).isTrue();
        RegulatorySubmissions.Acted rejected = aReturn(january, SubmissionStatus.REJECTED);
        provider.nextSubmit(() -> Answer.rejected(Optional.empty(), "NOT_ACCEPTED", "{}".getBytes(StandardCharsets.UTF_8)));
        assertThat(driver.submitIfDue(rejected.id())).isTrue();
        RegulatorySubmissions.Acted notReceived = aReturn(january, SubmissionStatus.UNDELIVERED);
        // Not received, then not received again on the resend the driver permits while the month stands: SUBMITTED, a person's door to come.
        provider.nextSubmit(() -> Answer.notReceived("{}".getBytes(StandardCharsets.UTF_8))).nextSubmit(() -> Answer.notReceived("{}".getBytes(StandardCharsets.UTF_8)));
        assertThat(driver.submitIfDue(notReceived.id())).isTrue();
        RegulatorySubmissions.Acted submitted = aReturn(january, SubmissionStatus.SUBMITTED);
        assertThat(rows(acknowledged.id())).isEqualTo(SubmissionStatus.ACKNOWLEDGED);
        assertThat(rows(rejected.id())).isEqualTo(SubmissionStatus.REJECTED);
        assertThat(rows(submitted.id())).isEqualTo(SubmissionStatus.SUBMITTED);
        assertThat(rows(notReceived.id())).as("not received, and still SUBMITTED until a person's door").isEqualTo(SubmissionStatus.SUBMITTED);

        List<String> listed = List.of(submitted.id().value().toString(), notReceived.id().value().toString(), acknowledged.id().value().toString());
        PeriodCloseDesk.PeriodCloseReceipt proposal = as(ACCOUNTANT, () -> desk.proposeReopen("reopen-each-p", period, none, "a correction was found"));

        assertThat(named(proposal)).as("SUBMITTED and ACKNOWLEDGED alone, newest sent first").containsExactlyElementsOf(listed);
        assertThat(proposal.overtakenReturnsTotal()).isEqualTo(3);
        assertThat(proposal.overtakenReturns()).extracting(PeriodCloseDesk.OvertakenReturn::status).containsExactly("SUBMITTED", "SUBMITTED", "ACKNOWLEDGED");
        assertThat(proposal.overtakenReturns()).extracting(PeriodCloseDesk.OvertakenReturn::form).containsExactly("REF-REOPEN-SUBMITTED", "REF-REOPEN-UNDELIVERED", "REF-REOPEN-ACKNOWLEDGED");

        // A proposal stops no send: the APPROVED one goes now, and the approval's read has it.
        Actor sender = person();
        RegulatorySubmissions.Attested attested = new RegulatorySubmissions.Attested(approved.contentSha256(), approved.periodRevision());
        inScope(sender, uow -> returns.submit(uow, approved.id(), attested, REASON, sender, correlation()));

        PeriodCloseDesk.PeriodCloseReceipt approval = as(CONTROLLER, () -> desk.approveReopen("reopen-each-a", period, none, "agreed"));

        assertThat(approval.status()).as("it stopped nothing").isEqualTo("OPEN");
        assertThat(named(approval)).as("its own read").hasSize(4).startsWith(approved.id().value().toString());
        assertThat(approval.overtakenReturnsTotal()).isEqualTo(4);
        assertThat(as(ACCOUNTANT, () -> desk.proposeReopen("reopen-each-p", period, none, "a correction was found"))).as("the proposal's stored receipt, three, and the month now OPEN").isEqualTo(proposal);

        // A status moved: the month having moved opens the door, and the return it held is UNDELIVERED. The key's replay says what was stored, a repeat under a new key what stands.
        Optional<RegulatorySubmissions.Recorded> asked = driver.askBeforeNonDelivery(inScope(Actor.SYSTEM, uow -> SUBMISSIONS.find(uow, notReceived.id())).orElseThrow(), correlation());
        Actor closer = person();
        inScope(closer, uow -> returns.declareUndelivered(uow, notReceived.id(), asked, REASON, closer, correlation()));
        assertThat(rows(notReceived.id())).isEqualTo(SubmissionStatus.UNDELIVERED);
        assertThat(as(CONTROLLER, () -> desk.approveReopen("reopen-each-a", period, none, "agreed"))).as("the stored list, the row since moved").isEqualTo(approval);
        PeriodCloseDesk.PeriodCloseReceipt repeated = as(CONTROLLER, () -> desk.approveReopen("reopen-each-a2", period, none, "agreed"));
        assertThat(repeated.overtakenReturnsNote()).isEqualTo(PeriodCloseDesk.OVERTAKEN_RETURNS_NOTE);
        List<String> standing = List.of(approved.id().value().toString(), submitted.id().value().toString(), acknowledged.id().value().toString());
        assertThat(named(repeated)).as("the same person's repeat converges, reading what stands: an UNDELIVERED one is not on it").containsExactlyElementsOf(standing);

        // The next reopen lists every revision's: read at the revision before this one, each of them.
        PeriodCloseFixture.closed(JANUARY);
        PeriodCloseDesk.PeriodCloseReceipt next = as(ACCOUNTANT, () -> desk.proposeReopen("reopen-next-p", period, none, "another correction"));
        assertThat(named(next)).containsExactlyElementsOf(standing);
        as(CONTROLLER, () -> desk.approveReopen("reopen-next-a", period, none, "agreed"));
    }

    private static SubmissionStatus rows(RegulatorySubmissionId id) {
        return inScope(Actor.SYSTEM, uow -> SUBMISSIONS.find(uow, id)).orElseThrow().status();
    }

    /** A FINAL run of the source for the month at its present revision, its content the suite's, through the machine's own claim and completion. */
    private static void issued(AccountingPeriodId period) {
        Actor accountant = person();
        inScope(accountant, uow -> {
            ReportRuns.Claimed claimed = SOURCE_RUNS.claim(uow, RegulatoryWorld.SOURCE, period, RunParameters.none(), accountant, correlation());
            ReportRunStore.RunRow run = claimed.run();
            String content = CanonicalJson.text(CanonicalJson.object().put("definition", RegulatoryWorld.SOURCE).put("period", JANUARY.toString()).put("body", RegulatoryWorld.body(100_00, 100_00, 50_00, 50_00)));
            return SOURCE_RUNS.complete(uow, run.id(), run.attempt(), new ReportGeneration.Generated(content, CanonicalJson.sha256(content), Optional.empty()), accountant);
        });
    }

    // ------------------------------------------------------------------ plumbing

    private LedgerAccount wallet() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount account = accounts.createOrConverge(app, LedgerAccount.owned(IDS, NOW, AccountType.LIABILITY, AccountPurpose.CUSTOMER_WALLET, EUR, IDS.next())).account();
            app.commit();
            return account;
        }
    }

    private LedgerAccount operational(AccountPurpose purpose) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return accounts.findOperational(app, purpose, EUR).orElseThrow();
        }
    }

    private static PostingService posting() {
        return new PostingService(new IdempotentExecutor(new JdbcIdempotencyRecordStore(), NOW, Duration.ofDays(1), Duration.ofMinutes(5)), new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(),
                new JdbcOutboxWriter(), new JdbcBalanceProjection(), IDS, NOW, PostingObserver.NONE);
    }

    /** One balanced entry through the ledger's own door, committed. */
    private static void post(LocalDate day, LedgerAccount debit, LedgerAccount credit, PostingCommand.Dating dating) throws SQLException {
        try (Connection app = DatabaseRoles.application();
                SecurityContext.Scope actor = SecurityContext.enterSystem();
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(IDS)))) {
            app.setAutoCommit(false);
            posting().post(app, new PostingCommand("period-reopen-suite-" + IDS.next(), day, day, "period-reopen-suite",
                    List.of(new JournalLine(debit.id(), Direction.DEBIT, Money.ofMinorUnits(25, EUR)), new JournalLine(credit.id(), Direction.CREDIT, Money.ofMinorUnits(25, EUR))), dating));
            app.commit();
        }
    }

    private static Optional<LocalDate> closedThrough() throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return new PostingCutoffs(IDS, NOW).closedThrough(app);
        }
    }

    private static void refused(String sql, String words) throws SQLException {
        try (Connection owner = DatabaseRoles.migrator(); Statement statement = owner.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate(sql)).isInstanceOf(SQLException.class).hasMessageContaining(words);
        }
    }
}
