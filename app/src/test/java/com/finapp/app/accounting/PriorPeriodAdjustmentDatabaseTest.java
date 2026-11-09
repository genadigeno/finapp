package com.finapp.app.accounting;

import static com.finapp.app.accounting.AccountingPeriodFixture.FEBRUARY;
import static com.finapp.app.accounting.AccountingPeriodFixture.IDS;
import static com.finapp.app.accounting.AccountingPeriodFixture.JANUARY;
import static com.finapp.app.accounting.AccountingPeriodFixture.NOW;
import static com.finapp.app.accounting.AccountingPeriodFixture.correlation;
import static com.finapp.app.accounting.AccountingPeriodFixture.id;
import static com.finapp.app.accounting.AccountingPeriodFixture.inScope;
import static com.finapp.app.accounting.AccountingPeriodFixture.on;
import static com.finapp.app.accounting.AccountingPeriodFixture.person;
import static com.finapp.app.accounting.AccountingPeriodFixture.row;
import static com.finapp.app.accounting.PeriodCloseFixture.ACCOUNTANT;
import static com.finapp.app.accounting.PeriodCloseFixture.CONTROLLER;
import static com.finapp.app.accounting.PeriodCloseFixture.MACHINE;
import static com.finapp.app.accounting.PeriodCloseFixture.REASON;
import static com.finapp.app.accounting.PeriodCloseFixture.count;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingBreaks;
import com.finapp.accounting.AccountingChecks;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.AccrualCompleteness;
import com.finapp.accounting.BreakFinding;
import com.finapp.accounting.BreakStatus;
import com.finapp.accounting.BreakType;
import com.finapp.accounting.CheckVerdict;
import com.finapp.accounting.CheckOutcome;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.CloseCheckId;
import com.finapp.accounting.CloseCheckStatus;
import com.finapp.accounting.CloseChecks;
import com.finapp.accounting.CloseRunId;
import com.finapp.accounting.CloseRuns;
import com.finapp.accounting.FinalTrialBalance;
import com.finapp.accounting.GlPins;
import com.finapp.accounting.JdbcAccountingBreakStore;
import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.JdbcPeriodPins;
import com.finapp.accounting.JdbcPriorPeriodAdjustmentStore;
import com.finapp.accounting.JdbcReportRunStore;
import com.finapp.accounting.JdbcValuationRateSetStore;
import com.finapp.accounting.PeriodCloses;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.PostingCutoff;
import com.finapp.accounting.PostingRuleRegister;
import com.finapp.accounting.PriorPeriodAdjustmentStatus;
import com.finapp.accounting.PriorPeriodAdjustments;
import com.finapp.accounting.PriorPeriodCorrections;
import com.finapp.accounting.PriorPeriodPostings;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.RunStatus;
import com.finapp.accounting.Side;
import com.finapp.accounting.SourceProofs;
import com.finapp.accounting.ValuationRateSets;
import com.finapp.accounting.ValuationRates;
import com.finapp.accounting.ValuationStaleness;
import com.finapp.accounting.reporting.BalanceSheetDefinition;
import com.finapp.accounting.reporting.GeneralLedger;
import com.finapp.accounting.reporting.GlActivityDefinition;
import com.finapp.accounting.reporting.IncomeStatementDefinition;
import com.finapp.accounting.reporting.PurposeReportDefinition;
import com.finapp.accounting.reporting.ReportDefinitions;
import com.finapp.accounting.reporting.ReportGeneration;
import com.finapp.accounting.reporting.RunParameters;
import com.finapp.accounting.reporting.StatementNotes;
import com.finapp.accounting.reporting.SuspenseDefinition;
import com.finapp.accounting.reporting.TrialBalanceDefinition;
import com.finapp.ledger.AccountPurpose;
import com.finapp.ledger.AccountType;
import com.finapp.ledger.AdjustmentCommand;
import com.finapp.ledger.AdjustmentOriginMismatchException;
import com.finapp.ledger.AdjustmentProposalId;
import com.finapp.ledger.AdjustmentReasonCode;
import com.finapp.ledger.AdjustmentService;
import com.finapp.ledger.Direction;
import com.finapp.ledger.JdbcAdjustmentProposalStore;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcBalanceProjection;
import com.finapp.ledger.JdbcJournalEntryStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JdbcPositionBreakdown;
import com.finapp.ledger.JdbcStatementDerivation;
import com.finapp.ledger.JournalEntryId;
import com.finapp.ledger.JournalEntryType;
import com.finapp.ledger.JournalLine;
import com.finapp.ledger.LedgerAccount;
import com.finapp.ledger.OwnedAdjustmentCommand;
import com.finapp.ledger.PeriodClosedException;
import com.finapp.ledger.PostingCommand;
import com.finapp.ledger.PostingObserver;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.ProjectionVerification;
import com.finapp.ledger.ReversalCommand;
import com.finapp.ledger.ReversalService;
import com.finapp.ledger.StatementDerivation;
import com.finapp.merchant.MerchantPayable;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.reconciliation.JdbcReportReadings;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The prior-period adjustment against a live PostgreSQL (`P14-TSK-013`; ADR-0114 decision 5; {@code PHASE_14_PLAN.md} sections 8.1 T-ppa, 8.2, 8.3 row 12, 13.5; {@code INV-ACC-03}, {@code -04}, {@code -10}, {@code INV-REV-04}, {@code INV-AUD-04}, {@code INV-LED-01}): both rows moving in one
 * transaction, the ledger's refusal of a date in the corrected month, the generic and the other owner's door refused, four eyes for every writer, ledger {@code V030} and accounting {@code V009} executing for the first time - and the races: ten approvers one entry; two approvals of one month; an approval against a
 * rejection, a lock of its target, and a reopen of what it corrects; and the two worst failures themselves, the corrected month's FINAL runs regenerating byte-identical and the month it lands in passing its real checks.
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): it closes and posts into January and February 1999, shared fixtures, and commits ledger lines. <strong>Written and owed</strong> (owner decision C5): the database tiers run at the phase's one pass. Refusals are asserted by exception type, or raw by SQLSTATE: a store's failure carries its
 * operation and SQLSTATE only, never the driver's text.
 *
 * <p><strong>One case runs last, and every other holds in any order</strong> (`P14-TSK-016`'s fix round, on this suite's own review): a reopen moves a revision for good and a proposal is refused into a month ever reopened, while nearly every case proposes into February. So {@link #theTargetReopenedMeanwhile}, the one case that closes and reopens February, is ordered last, and {@link #januaryClosedFebruaryOpen} says so if February ever moved. A case may lock
 * February, which an unlock lifts with no revision moved; {@link #theTargetLockedMeanwhile} leaves March open, which nothing else reads; January is reopened and closed by every case, each reading its revision from the row, and what posts stays, every comparison a relation or by the case's own ids.
 */
@Tag("database")
@Tag("own-container") // its own JVM and database: ownContainerDatabaseTest, the javadoc says why
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("the prior-period adjustment against the database (P14-TSK-013)")
class PriorPeriodAdjustmentDatabaseTest {

    private static final int APPROVERS = 10;
    private static final CurrencyCode EUR = CurrencyCode.of("EUR");
    /** Today, for every proposal here: in February, January being what is corrected. */
    private static final Clock FEBRUARY_TENTH = on("1999-02-10");
    private static final LocalDate LANDS_ON = LocalDate.of(1999, 2, 10);
    private static final String CHECK_VIOLATION = "23514";
    private static final String RAISED = "P0001";

    private final JdbcLedgerAccountStore accounts = new JdbcLedgerAccountStore();
    private final JdbcPriorPeriodAdjustmentStore store = new JdbcPriorPeriodAdjustmentStore();

    @BeforeAll
    static void installTheShadowClock() throws SQLException {
        ShadowClock.install();
    }

    @AfterAll
    static void dropTheShadowClock() throws SQLException {
        ShadowClock.uninstall();
    }

    @BeforeEach
    void januaryClosedFebruaryOpen() {
        AccountingPeriodFixture.openJanuaryAndFebruary();
        PeriodCloseFixture.mappingInForce();
        PeriodCloseFixture.everyPeriodOpen();
        assertThat(row(FEBRUARY).revision()).as("only the last case closes February: a proposal is refused into a month ever reopened, and a reopen is for good").isEqualTo(1);
        PeriodCloseFixture.closed(JANUARY);
    }

    // ------------------------------------------------------------------ the gauge (P14-TSK-020)

    /** A delta over this suite's own database: a case before it may leave one standing, and this one rejects its own, as {@code standing} counts only {@code PROPOSED}. */
    @Test
    @DisplayName("adjustment.standing's read: a proposal counts past the bound and not inside it, and a rejection ends it")
    void aProposalStandsPastItsBound() throws Exception {
        long before = standing(java.time.Duration.ofMillis(1));
        long aDecadeBefore = standing(java.time.Duration.ofDays(3650));
        PriorPeriodAdjustments.Acted proposed = propose();
        Thread.sleep(5); // The statement measures "<= now - 1 ms": a real millisecond must pass, on the database's clock.
        assertThat(standing(java.time.Duration.ofMillis(1))).isEqualTo(before + 1);
        assertThat(standing(java.time.Duration.ofDays(3650))).as("inside the bound").isEqualTo(aDecadeBefore);
        Actor rejecter = person();
        inScope(rejecter, uow -> machine().reject(uow, proposed.id(), rejecter, "no longer owed", correlation()));
        assertThat(standing(java.time.Duration.ofMillis(1))).isEqualTo(before);
    }

    /** Through the real store, so the real {@code TODAY_SQL} on a session whose clock {@link ShadowClock} moved: every other case here is {@link AccountingPeriodFixture#datedBy dated by a test double}. */
    @Test
    @DisplayName("the month a correction lands in and its date are the database's own reading, with this instance's clock decades away and a month with no period on it (P14-REM-009)")
    void theMonthIsTheDatabasesOwnReading() throws Exception {
        assertThat(LocalDate.ofInstant(NOW.instant(), java.time.ZoneOffset.UTC)).as("the precondition: this instance's clock is past February 1999").isAfter(FEBRUARY.atEndOfMonth());
        assertThat(ShadowClock.on(LANDS_ON, ACCOUNTANT, AccountingPeriodFixture.STORE::today)).as("the precondition: the store's real reading, on the stepped session").isEqualTo(LANDS_ON);
        PriorPeriodAdjustments onTheRealStore = new PriorPeriodAdjustments(AccountingPeriodFixture.STORE, store, postings(), new JdbcGlMappingStore(), new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW);
        AccountingPeriodId january = id(JANUARY);
        int revision = row(JANUARY).revision();
        List<PriorPeriodPostings.Line> lines = lines();

        PriorPeriodAdjustments.Acted proposed = ShadowClock.on(LANDS_ON, ACCOUNTANT, uow -> onTheRealStore.propose(uow, january, revision, lines, ACCOUNTANT, REASON, correlation()));

        assertThat(proposed.targetMonth()).as("on this instance's clock the month has no period, and it was refused").isEqualTo(FEBRUARY);
        assertThat(proposed.postingDate()).isEqualTo(LANDS_ON);
        Actor rejecter = person();
        inScope(rejecter, uow -> machine().reject(uow, proposed.id(), rejecter, "the clock's case is done", correlation()));
    }

    private long standing(java.time.Duration bound) throws SQLException {
        return inScope(Actor.SYSTEM, uow -> store.standingPast(uow, bound));
    }

    // ------------------------------------------------------------------ the machine

    @Test
    @DisplayName("it posts in the open period referencing the closed one: the ledger's proposal ACCOUNTING with both dates the one date, nothing posted; the approval posts it there, the row and the proposal moving together; audited and published once, the entry's own event beside it")
    void postsInTheOpenPeriodReferencingTheClosedOne() throws Exception {
        PriorPeriodAdjustments.Acted proposed = propose();
        String proposal = "'" + proposed.proposal() + "'";

        assertThat(proposed.postingDate()).isEqualTo(LANDS_ON);
        assertThat(proposed.targetMonth()).isEqualTo(FEBRUARY);
        assertThat(text("SELECT origin || '|' || reason_code || '|' || status || '|' || posting_date || '|' || value_date FROM ledger.adjustment_proposal WHERE id = " + proposal))
                .isEqualTo("ACCOUNTING|PRIOR_PERIOD_ADJUSTMENT|PROPOSED|1999-02-10|1999-02-10");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = 'ledger.adjust.approve:" + proposed.proposal() + "'")).as("nothing posts at a proposal").isZero();

        Actor approver = person();
        PriorPeriodAdjustments.Acted posted = approve(proposed, approver);

        UUID entry = posted.entry().orElseThrow();
        assertThat(posted.status()).isEqualTo(PriorPeriodAdjustmentStatus.POSTED);
        assertThat(text("SELECT entry_type || '|' || posting_date || '|' || value_date || '|' || idempotency_scope FROM ledger.journal_entry WHERE id = '" + entry + "'"))
                .isEqualTo("ADJUSTMENT|1999-02-10|1999-02-10|ledger.adjust.approve:" + proposed.proposal());
        assertThat(LANDS_ON).as("never in or before what it corrects").isAfter(JANUARY.atEndOfMonth());
        assertThat(text("SELECT status || '|' || journal_entry_id FROM ledger.adjustment_proposal WHERE id = " + proposal)).isEqualTo("APPROVED|" + entry);
        assertThat(text("SELECT status || '|' || journal_entry_id || '|' || corrected_period_id || '|' || corrected_revision FROM accounting.prior_period_adjustment WHERE id = '" + proposed.id().value() + "'"))
                .isEqualTo("POSTED|" + entry + "|" + id(JANUARY).value() + "|" + row(JANUARY).revision());
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = '" + PriorPeriodAdjustments.POSTED_EVENT + "' AND aggregate_id = '" + proposed.id().value() + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = 'ledger.JournalEntryPosted' AND aggregate_id = '" + entry + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.PriorPeriodAdjustmentProposed' AND target_id = '" + proposed.id().value() + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.PriorPeriodAdjustmentPosted' AND target_id = '" + proposed.id().value() + "' AND reason IS NOT NULL")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'ledger.AdjustmentPosted' AND target_id = '" + entry + "'")).isEqualTo(1);
    }

    @Test
    @DisplayName("contention row 12: ten approvers on ten connections, one entry: one POSTED, nine told it is stale; then ten calls by the one who won, one entry still - the ledger's one-way machine and the conditional edge")
    void aReplayedApprovalPostsOnce() throws Exception {
        PriorPeriodAdjustments.Acted proposed = propose();
        PriorPeriodAdjustments.Approval reading = reading(proposed);
        List<Actor> approvers = new ArrayList<>();
        List<Callable<PriorPeriodAdjustments.Acted>> racers = new ArrayList<>();
        for (int at = 0; at < APPROVERS; at++) {
            Actor approver = person();
            approvers.add(approver);
            racers.add(() -> inScope(approver, uow -> machine().approve(uow, proposed.id(), reading, approver, "agreed", correlation())));
        }

        List<Object> outcomes = race(racers);

        assertThat(outcomes).filteredOn(PriorPeriodAdjustments.Acted.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(PriorPeriodAdjustments.AdjustmentStale.class::isInstance).hasSize(APPROVERS - 1);
        assertOneEntry(proposed);

        String winner = text("SELECT decided_by FROM accounting.prior_period_adjustment WHERE id = '" + proposed.id().value() + "'");
        Actor won = approvers.stream().filter(actor -> actor.id().equals(winner)).findFirst().orElseThrow();
        List<Callable<PriorPeriodAdjustments.Acted>> repeats = new ArrayList<>();
        for (int at = 0; at < APPROVERS; at++) {
            repeats.add(() -> inScope(won, uow -> machine().approve(uow, proposed.id(), reading, won, "agreed", correlation())));
        }
        assertThat(race(repeats)).allSatisfy(outcome -> assertThat(((PriorPeriodAdjustments.Acted) outcome).replayed()).isTrue());
        assertOneEntry(proposed);
    }

    @Test
    @DisplayName("two approvals of two adjustments of one month, on two connections, each read with nothing posted: exactly one posts; the sibling rows, locked in id order, leave the other refused, so neither posts unaware of the other and neither deadlocks")
    void twoApprovalsOfOneMonthCannotBothBeUnaware() throws Exception {
        PriorPeriodAdjustments.Acted first = propose();
        PriorPeriodAdjustments.Acted second = propose();
        Actor one = person();
        Actor other = person();
        PriorPeriodAdjustments.Approval readFirst = reading(first);
        PriorPeriodAdjustments.Approval readSecond = reading(second);

        List<Object> outcomes = race(List.of(
                () -> inScope(one, uow -> machine().approve(uow, first.id(), readFirst, one, "agreed", correlation())),
                () -> inScope(other, uow -> machine().approve(uow, second.id(), readSecond, other, "agreed", correlation()))));

        assertThat(outcomes).filteredOn(PriorPeriodAdjustments.Acted.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(PriorPeriodAdjustments.AdjustmentStale.class::isInstance).hasSize(1);
        assertThat(count("SELECT count(*) FROM accounting.prior_period_adjustment WHERE id IN ('" + first.id().value() + "', '" + second.id().value() + "') AND status = 'POSTED'")).isEqualTo(1);
    }

    @Test
    @DisplayName("an approval and a rejection on two connections: whichever the row admits first stands, the other is stale; the two rows agree, and an entry exists exactly when it was approved")
    void anApprovalAndARejectionRaceOneWins() throws Exception {
        PriorPeriodAdjustments.Acted proposed = propose();
        Actor approver = person();
        Actor rejecter = person();
        PriorPeriodAdjustments.Approval read = reading(proposed);

        List<Object> outcomes = race(List.of(
                () -> inScope(approver, uow -> machine().approve(uow, proposed.id(), read, approver, "agreed", correlation())),
                () -> inScope(rejecter, uow -> machine().reject(uow, proposed.id(), rejecter, "no", correlation()))));

        assertThat(outcomes).filteredOn(PriorPeriodAdjustments.Acted.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(PriorPeriodAdjustments.AdjustmentStale.class::isInstance).hasSize(1);
        PriorPeriodAdjustments.Acted won = (PriorPeriodAdjustments.Acted) outcomes.stream().filter(PriorPeriodAdjustments.Acted.class::isInstance).findFirst().orElseThrow();
        assertThat(text("SELECT status FROM ledger.adjustment_proposal WHERE id = '" + proposed.proposal() + "'")).isEqualTo(won.status() == PriorPeriodAdjustmentStatus.POSTED ? "APPROVED" : "REJECTED");
        assertThat(text("SELECT status FROM accounting.prior_period_adjustment WHERE id = '" + proposed.id().value() + "'")).isEqualTo(won.status().name());
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = 'ledger.adjust.approve:" + proposed.proposal() + "'")).isEqualTo(won.status() == PriorPeriodAdjustmentStatus.POSTED ? 1 : 0);
    }

    // ------------------------------------------------------------------ what can move

    @Test
    @DisplayName("the month reopened between proposal and approval: refused, nothing posted, both rows PROPOSED; closed again, refused for its revision; the rejection takes both rows out; a proposal against the new revision posts")
    void aReopenBetweenProposalAndApproval() throws Exception {
        PriorPeriodAdjustments.Acted proposed = propose();
        int revision = row(JANUARY).revision();
        inScope(ACCOUNTANT, uow -> MACHINE.proposeReopen(uow, id(JANUARY), ACCOUNTANT, PeriodCloseFixture.posted(uow, id(JANUARY)), "a correction was found", correlation()));
        inScope(CONTROLLER, uow -> MACHINE.approveReopen(uow, id(JANUARY), MACHINE.pendingOf(uow, id(JANUARY)).reopen().orElseThrow().id(), CONTROLLER, PeriodCloseFixture.posted(uow, id(JANUARY)), "agreed", correlation()));

        assertThatThrownBy(() -> approve(proposed, person())).isInstanceOf(AccountingPeriods.PeriodStale.class);
        assertStillProposed(proposed);

        PeriodCloseFixture.closed(JANUARY);
        assertThat(row(JANUARY).revision()).isEqualTo(revision + 1);
        assertThatThrownBy(() -> approve(proposed, person())).as("CLOSED again, the figures superseded").isInstanceOf(PriorPeriodAdjustments.AdjustmentStale.class);
        assertStillProposed(proposed);

        Actor rejecter = person();
        inScope(rejecter, uow -> machine().reject(uow, proposed.id(), rejecter, "it can no longer post", correlation()));
        assertThat(text("SELECT status FROM accounting.prior_period_adjustment WHERE id = '" + proposed.id().value() + "'")).isEqualTo("REJECTED");
        assertThat(text("SELECT status FROM ledger.adjustment_proposal WHERE id = '" + proposed.proposal() + "'")).isEqualTo("REJECTED");

        PriorPeriodAdjustments.Acted again = propose();
        assertThat(again.correctedRevision()).isEqualTo(revision + 1);
        assertThat(approve(again, person()).status()).isEqualTo(PriorPeriodAdjustmentStatus.POSTED);
    }

    @Test
    @DisplayName("the month it lands in locked before the approval: refused, it stands, the rejection is the way on; proposed again it lands in the next month")
    void theTargetLockedMeanwhile() throws Exception {
        PriorPeriodAdjustments.Acted proposed = propose();
        PeriodCloseFixture.locked(FEBRUARY);

        assertThatThrownBy(() -> approve(proposed, person())).isInstanceOf(AccountingPeriods.PeriodStale.class);
        assertStillProposed(proposed);

        Actor rejecter = person();
        inScope(rejecter, uow -> machine().reject(uow, proposed.id(), rejecter, "the month was locked", correlation()));
        Clock march = on("1999-03-10");
        inScope(Actor.SYSTEM, uow -> AccountingPeriodFixture.periods(on("1999-03-15")).openNext(uow, Actor.SYSTEM, correlation()));
        List<PriorPeriodPostings.Line> lines = lines();
        PriorPeriodAdjustments.Acted again = inScope(ACCOUNTANT, uow -> machine(march).propose(uow, id(JANUARY), row(JANUARY).revision(), lines, ACCOUNTANT, REASON, correlation()));
        assertThat(again.postingDate()).isEqualTo(LocalDate.of(1999, 3, 10));
        Actor approver = person();
        PriorPeriodAdjustments.Acted posted = inScope(approver, uow -> machine(march).approve(uow, again.id(), reading(again), approver, "agreed", correlation()));
        assertThat(posted.status()).isEqualTo(PriorPeriodAdjustmentStatus.POSTED);
    }

    @Test
    @Order(Integer.MAX_VALUE) // It closes and reopens February, the one case that does: every case proposing into February must run before it.
    @DisplayName("the month it lands in really closed and reopened meanwhile, the corrected month untouched: the approval refused for the revision, a raw POST refused for every writer, it stands, the rejection is the way on; nothing is born into that month either")
    void theTargetReopenedMeanwhile() throws Exception {
        PriorPeriodAdjustments.Acted proposed = propose();
        int corrected = row(JANUARY).revision();
        assertThat(text("SELECT target_revision::text FROM accounting.prior_period_adjustment WHERE id = '" + proposed.id().value() + "'")).isEqualTo("1");
        PeriodCloseFixture.closed(FEBRUARY);
        inScope(ACCOUNTANT, uow -> MACHINE.proposeReopen(uow, id(FEBRUARY), ACCOUNTANT, PeriodCloseFixture.posted(uow, id(FEBRUARY)), "a correction was found", correlation()));
        inScope(CONTROLLER, uow -> MACHINE.approveReopen(uow, id(FEBRUARY), MACHINE.pendingOf(uow, id(FEBRUARY)).reopen().orElseThrow().id(), CONTROLLER, PeriodCloseFixture.posted(uow, id(FEBRUARY)), "agreed", correlation()));
        assertThat(row(FEBRUARY).status()).isEqualTo(PeriodStatus.OPEN);
        assertThat(row(FEBRUARY).revision()).isEqualTo(2);
        assertThat(row(JANUARY).status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(row(JANUARY).revision()).isEqualTo(corrected);

        assertThat(inScope(Actor.SYSTEM, uow -> machine().view(uow, proposed.id())).blockedBy()).contains(PriorPeriodAdjustments.Blocked.TARGET_REVISION_MOVED);
        assertThatThrownBy(() -> approve(proposed, person())).isInstanceOf(PriorPeriodAdjustments.AdjustmentStale.class);
        assertStillProposed(proposed);
        ownerRefused("UPDATE accounting.prior_period_adjustment SET status = 'POSTED', journal_entry_id = '" + IDS.next() + "', decided_by = 'raw-approver', decided_by_type = 'EMPLOYEE', decided_at = now(), decision_reason = 'raw' WHERE id = '" + proposed.id().value() + "'", RAISED);
        ownerRefused("UPDATE accounting.prior_period_adjustment SET target_revision = 2 WHERE id = '" + proposed.id().value() + "'", RAISED);

        assertThatThrownBy(() -> propose()).as("the domain refuses the reopened month").isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("reopened");
        rawRefused(rowSql(id(JANUARY).value(), corrected, id(FEBRUARY).value(), 2, "1999-02-10", "PROPOSED", null), RAISED);
        rawRefused(rowSql(id(JANUARY).value(), corrected, id(FEBRUARY).value(), 1, "1999-02-10", "PROPOSED", null), RAISED);

        Actor rejecter = person();
        inScope(rejecter, uow -> machine().reject(uow, proposed.id(), rejecter, "it can no longer post", correlation()));
        assertThat(text("SELECT status FROM accounting.prior_period_adjustment WHERE id = '" + proposed.id().value() + "'")).isEqualTo("REJECTED");
        assertThat(text("SELECT status FROM ledger.adjustment_proposal WHERE id = '" + proposed.proposal() + "'")).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("T-lock against an approval, on two connections: whichever takes the target's row first, no entry of the origin stands after the cutoff unaccounted: it posted before the lock committed and is in the month, or it was refused and nothing posted; no deadlock either way")
    void anApprovalRacingALockOfItsTarget() throws Exception {
        PriorPeriodAdjustments.Acted proposed = propose();
        Actor approver = person();
        Actor locker = person();
        PriorPeriodAdjustments.Approval read = reading(proposed);
        AccountingPeriodId february = id(FEBRUARY);

        List<Object> outcomes = race(List.of(
                () -> inScope(approver, uow -> machine().approve(uow, proposed.id(), read, approver, "agreed", correlation())),
                () -> inScope(locker, uow -> AccountingPeriodFixture.periods().lock(uow, february, locker, null, correlation()))));

        assertThat(row(FEBRUARY).status()).as("the lock took").isEqualTo(PeriodStatus.LOCKED);
        boolean posted = outcomes.stream().anyMatch(PriorPeriodAdjustments.Acted.class::isInstance);
        if (!posted) {
            assertThat(outcomes).filteredOn(AccountingPeriods.PeriodStale.class::isInstance).hasSize(1);
        }
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = 'ledger.adjust.approve:" + proposed.proposal() + "' AND posting_date = '1999-02-10'")).isEqualTo(posted ? 1 : 0);
        assertThat(text("SELECT status FROM accounting.prior_period_adjustment WHERE id = '" + proposed.id().value() + "'")).isEqualTo(posted ? "POSTED" : "PROPOSED");
    }

    @Test
    @DisplayName("T-reopen against an approval, on two connections: serialised on the corrected month's row, no inversion. Either it posted at the revision it read and the reopen, naming what stood before it, is refused Stale with nothing moved until it names it (P14-TSK-022), or the reopen went first and it was refused; a busy cutoff is the reopen's own to retry")
    void anApprovalRacingAReopenOfWhatItCorrects() throws Exception {
        PriorPeriodAdjustments.Acted proposed = propose();
        int revision = row(JANUARY).revision();
        AccountingPeriodId january = id(JANUARY);
        inScope(ACCOUNTANT, uow -> MACHINE.proposeReopen(uow, january, ACCOUNTANT, PeriodCloseFixture.posted(uow, january), "a correction was found", correlation()));
        Actor approver = person();
        PriorPeriodAdjustments.Approval read = reading(proposed);
        Set<UUID> namedBeforeTheRace = inScope(CONTROLLER, uow -> PeriodCloseFixture.posted(uow, january));

        List<Object> outcomes = race(List.of(
                () -> inScope(approver, uow -> machine().approve(uow, proposed.id(), read, approver, "agreed", correlation())),
                () -> {
                    while (true) {
                        try {
                            return inScope(CONTROLLER, uow -> MACHINE.approveReopen(uow, january, MACHINE.pendingOf(uow, january).reopen().orElseThrow().id(), CONTROLLER, namedBeforeTheRace, "agreed", correlation()));
                        } catch (PostingCutoff.CutoffBusy busy) {
                            Thread.onSpinWait();
                        }
                    }
                }));

        String status = text("SELECT status FROM accounting.prior_period_adjustment WHERE id = '" + proposed.id().value() + "'");
        if (status.equals("POSTED")) {
            assertThat(count("SELECT count(*) FROM accounting.prior_period_adjustment WHERE id = '" + proposed.id().value() + "' AND corrected_revision = " + revision)).isEqualTo(1);
            assertThat(outcomes).as("the reopen waited on the row, read it posted, and named what stood before it").filteredOn(PriorPeriodAdjustments.AdjustmentStale.class::isInstance).hasSize(1);
            assertThat(row(JANUARY).status()).as("nothing moved").isEqualTo(PeriodStatus.CLOSED);
            assertThat(row(JANUARY).revision()).isEqualTo(revision);
            assertThat(count("SELECT count(*) FROM accounting.report_run WHERE period_id = '" + january.value() + "' AND status = 'SUPERSEDED' AND period_revision = " + revision)).as("no run superseded by a refused reopen").isZero();
            while (true) {
                try {
                    inScope(CONTROLLER, uow -> MACHINE.approveReopen(uow, january, MACHINE.pendingOf(uow, january).reopen().orElseThrow().id(), CONTROLLER, PeriodCloseFixture.posted(uow, january), "agreed", correlation()));
                    break;
                } catch (PostingCutoff.CutoffBusy busy) {
                    Thread.onSpinWait();
                }
            }
        } else {
            assertThat(status).isEqualTo("PROPOSED");
            assertThat(outcomes).filteredOn(PeriodCloses.Acted.class::isInstance).hasSize(1);
            assertThat(outcomes).filteredOn(AccountingPeriods.PeriodStale.class::isInstance).hasSize(1);
            assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = 'ledger.adjust.approve:" + proposed.proposal() + "'")).isZero();
        }
        assertThat(row(JANUARY).status()).isEqualTo(PeriodStatus.OPEN);
        assertThat(row(JANUARY).revision()).isEqualTo(revision + 1);
    }

    @Test
    @DisplayName("P14-TSK-022 (m): a reopen names what has posted, judged at each act under the period's row. A proposal naming what stood before one posted is refused and writes nothing; the owner's (n): one posted while the reopen is pending lands; the approval naming the proposal's list is refused, the month CLOSED at its revision and its FINAL runs standing; naming what stands, it reopens")
    void aReopenNamesWhatHasPosted() throws Exception {
        AccountingPeriodId january = id(JANUARY);
        int revision = row(JANUARY).revision();
        Set<UUID> before = inScope(ACCOUNTANT, uow -> PeriodCloseFixture.posted(uow, january));
        PriorPeriodAdjustments.Acted first = approve(propose(), person());
        Set<UUID> afterFirst = inScope(ACCOUNTANT, uow -> PeriodCloseFixture.posted(uow, january));
        assertThat(afterFirst).contains(first.id().value()).hasSize(before.size() + 1);
        long reopens = count("SELECT count(*) FROM accounting.period_reopen WHERE period_id = '" + january.value() + "'");

        assertThatThrownBy(() -> inScope(ACCOUNTANT, uow -> MACHINE.proposeReopen(uow, january, ACCOUNTANT, before, "a correction was found", correlation()))).isInstanceOf(PriorPeriodAdjustments.AdjustmentStale.class);
        assertThat(count("SELECT count(*) FROM accounting.period_reopen WHERE period_id = '" + january.value() + "'")).as("nothing written").isEqualTo(reopens);

        inScope(ACCOUNTANT, uow -> MACHINE.proposeReopen(uow, january, ACCOUNTANT, afterFirst, "a correction was found", correlation()));
        PriorPeriodAdjustments.Acted second = approve(propose(), person());
        assertThat(second.status()).as("a pending reopen does not stop an adjustment's approval").isEqualTo(PriorPeriodAdjustmentStatus.POSTED);
        Set<UUID> afterSecond = inScope(CONTROLLER, uow -> PeriodCloseFixture.posted(uow, january));
        assertThat(afterSecond).contains(second.id().value()).hasSize(afterFirst.size() + 1);

        assertThatThrownBy(() -> inScope(CONTROLLER, uow -> MACHINE.approveReopen(uow, january, MACHINE.pendingOf(uow, january).reopen().orElseThrow().id(), CONTROLLER, afterFirst, "agreed", correlation()))).isInstanceOf(PriorPeriodAdjustments.AdjustmentStale.class);
        assertThat(row(JANUARY).status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(row(JANUARY).revision()).isEqualTo(revision);
        assertThat(count("SELECT count(*) FROM accounting.period_reopen WHERE period_id = '" + january.value() + "' AND period_revision = " + revision + " AND status = 'PROPOSED'")).as("it stands").isEqualTo(1);

        PeriodCloses.Acted reopened = inScope(CONTROLLER, uow -> MACHINE.approveReopen(uow, january, MACHINE.pendingOf(uow, january).reopen().orElseThrow().id(), CONTROLLER, afterSecond, "agreed", correlation()));
        assertThat(reopened.status()).isEqualTo(PeriodStatus.OPEN);
        assertThat(reopened.revision()).isEqualTo(revision + 1);
    }

    // ------------------------------------------------------------------ the two worst failures

    @Test
    @DisplayName("the corrected month must not move: each of January's eleven FINAL definitions, regenerated after the adjustment posted, is byte-identical - the nine generated at the lock, the two statements, which wait for it, at the close; the month's lines are as they were")
    void theCorrectedMonthsElevenFinalRunsRegenerateByteIdentical() throws Exception {
        PeriodCloseFixture.everyPeriodOpen();
        com.finapp.accounting.CloseRunId closeRun = PeriodCloseFixture.ready(JANUARY);
        ReportRuns reports = reports();
        ReportRunGenerator generator = generator(reports);
        Actor generating = person();
        List<ReportRunStore.RunRow> runs = new ArrayList<>();
        for (String code : List.of(TrialBalanceDefinition.CODE, GlActivityDefinition.CODE, "REVENUE", "RECEIVABLES_PAYABLES", "SETTLEMENT_POSITIONS", "FX_RESULTS", "LOAN_PORTFOLIO", "BNPL_EXPOSURE", SuspenseDefinition.CODE)) {
            ReportRuns.Claimed claimed = inTransaction(uow -> reports.claim(uow, code, id(JANUARY), RunParameters.none(), generating, correlation()));
            runs.add(claimed.mine() ? generator.generateAndComplete(claimed.run(), generating) : generator.drive(claimed.run().id(), generating));
        }
        assertThat(runs).as("generated at the lock").hasSize(9);
        PeriodCloses.Acted close = inScope(ACCOUNTANT, uow -> MACHINE.propose(uow, id(JANUARY), closeRun, List.of(), ACCOUNTANT, REASON, correlation()));
        inScope(CONTROLLER, uow -> MACHINE.approve(uow, id(JANUARY), close.proposal().orElseThrow(), CONTROLLER, REASON, correlation()));
        for (String code : List.of(BalanceSheetDefinition.CODE, IncomeStatementDefinition.CODE)) {
            ReportRuns.Claimed claimed = inTransaction(uow -> reports.claim(uow, code, id(JANUARY), RunParameters.none(), generating, correlation()));
            runs.add(claimed.mine() ? generator.generateAndComplete(claimed.run(), generating) : generator.drive(claimed.run().id(), generating));
        }
        assertThat(runs).allSatisfy(run -> assertThat(run.status()).isEqualTo(RunStatus.FINAL));
        assertThat(runs).as("every FINAL definition").hasSize(11);
        long januaryLines = count("SELECT count(*) FROM ledger.journal_entry WHERE posting_date BETWEEN '1999-01-01' AND '1999-01-31'");

        PriorPeriodAdjustments.Acted posted = approve(propose(), person());
        assertThat(posted.status()).isEqualTo(PriorPeriodAdjustmentStatus.POSTED);

        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE posting_date BETWEEN '1999-01-01' AND '1999-01-31'")).isEqualTo(januaryLines);
        for (ReportRunStore.RunRow run : runs) {
            ReportRunGenerator.Compared compared = generator.regenerate(run.id());
            assertThat(compared.identical()).as(run.id().toString()).isTrue();
            assertThat(compared.regeneratedSha256()).isEqualTo(run.contentSha256().orElseThrow());
        }
    }

    @Test
    @DisplayName("the month it lands in passes its real checks over lines on a merchant's payable: LEDGER_TRIAL_BALANCE and PROJECTION_VERIFIED judged over a real FINAL trial balance, fully mapped; the real payable proof finds the terms summing, SOURCE_COMPLETENESS with the other sources stood in; RULE_CONFORMANCE and GL_EQUALS_LEDGER pass; the disclosure's past-the-cutoff read sees nothing")
    void theMonthItLandsInPassesItsRealChecksOverAMerchantPayable() throws Exception {
        UUID merchant = IDS.next();
        LedgerAccount payable = ownedBy(merchant, AccountType.LIABILITY, AccountPurpose.MERCHANT_PAYABLE);
        // Facing a sale's counterparty, the line a merchant's drill-down reads as a term; then facing a purpose it names none of.
        approved(List.of(new PriorPeriodPostings.Line(payable.id().value(), Side.DEBIT, Money.ofMinorUnits(1500, EUR)), new PriorPeriodPostings.Line(wallet().id().value(), Side.CREDIT, Money.ofMinorUnits(1500, EUR))));
        approved(List.of(new PriorPeriodPostings.Line(operational(AccountPurpose.FEE_REVENUE).id().value(), Side.DEBIT, Money.ofMinorUnits(700, EUR)), new PriorPeriodPostings.Line(payable.id().value(), Side.CREDIT, Money.ofMinorUnits(700, EUR))));
        PeriodCloseFixture.locked(FEBRUARY);

        GlSnapshots snapshots = new GlSnapshots(DatabaseRoles::application, Duration.ofSeconds(30));
        Function<Connection, List<ProductSourceProofs.PayableTerms>> payables = ProductSourceProofs.merchantPayables(new MerchantPayable(new JdbcLedgerAccountStore(), new JdbcPositionBreakdown()));
        SourceProofs real = new ProductSourceProofs(notRead(), notRead(), notRead(), notRead(), notRead(), payables, ProductSourceProofs.projections(new ProjectionVerification(new JdbcBalanceDerivation())));
        SourceProofs proofs = (snapshot, source) -> source == SourceProofs.Source.MERCHANT_PAYABLE || source == SourceProofs.Source.PROJECTION ? real.read(snapshot, source)
                : source.proofs().stream().map(proof -> new SourceProofs.Reading(proof, true, List.of())).toList();

        List<ProductSourceProofs.PayableTerms> terms = snapshots.read(payables::apply);
        assertThat(terms).as("the proof reads this merchant, and its terms sum").contains(new ProductSourceProofs.PayableTerms(merchant, "EUR", true));
        List<SourceProofs.Reading> readings = snapshots.read(snapshot -> real.read(snapshot, SourceProofs.Source.MERCHANT_PAYABLE));
        assertThat(readings).singleElement().satisfies(reading -> {
            assertThat(reading.complete()).isTrue();
            assertThat(reading.discrepancies()).isEmpty();
        });

        ReportRuns reports = reports();
        FinalTrialBalance trialBalance = new FinalTrialBalanceReader(reports, generator(reports), PriorPeriodAdjustmentDatabaseTest::inTransaction, FinalTrialBalanceReader.overSnapshots(reportSnapshots()));
        assertThat(trialBalance.read(id(FEBRUARY), Actor.SYSTEM, correlation())).isInstanceOfSatisfying(FinalTrialBalance.Verdict.class, verdict -> {
            assertThat(verdict.fullyMapped()).as("every line of the adjustments mapped under the pin").isTrue();
            assertThat(verdict.holds()).isTrue();
        });
        CloseChecks closeChecks = closeChecks(trialBalance, proofs, snapshots);
        for (CloseCheckCode check : List.of(CloseCheckCode.LEDGER_TRIAL_BALANCE, CloseCheckCode.PROJECTION_VERIFIED)) {
            CheckVerdict verdict = closeChecks.judge(new CloseRuns.ClaimedCheck(CloseCheckId.next(IDS), CloseRunId.next(IDS), id(FEBRUARY), FEBRUARY, check, 1, IDS.next(), 0, false, correlation(), Actor.SYSTEM)).orElseThrow();
            assertThat(verdict.status()).as(check.name()).isEqualTo(CloseCheckStatus.PASSED);
        }

        AccountingChecks checks = new AccountingChecks(new GeneralLedger(new LedgerJournalReads()), new GlPins(new JdbcGlMappingStore(), new JdbcPeriodPins()), new LedgerJournalShapes(), proofs, PostingRuleRegister.CURRENT, breaks(),
                snapshots::read, PriorPeriodAdjustmentDatabaseTest::inTransaction);
        assertThat(checks.run(FEBRUARY, CloseCheckCode.RULE_CONFORMANCE, Actor.SYSTEM, correlation()).result()).as("the rule carries the origin, on an owner's purpose too").isEqualTo(CheckOutcome.Result.PASSED);
        assertThat(checks.run(FEBRUARY, CloseCheckCode.GL_EQUALS_LEDGER, Actor.SYSTEM, correlation()).result()).as("every line mapped under the pin").isEqualTo(CheckOutcome.Result.PASSED);
        assertThat(checks.run(FEBRUARY, CloseCheckCode.SOURCE_COMPLETENESS, Actor.SYSTEM, correlation()).result()).as("the real payable and projection proofs, the others stood in").isEqualTo(CheckOutcome.Result.PASSED);
        assertThat(checks.run(JANUARY, CloseCheckCode.GL_EQUALS_LEDGER, Actor.SYSTEM, correlation()).result()).as("the corrected month, as it was").isEqualTo(CheckOutcome.Result.PASSED);
        assertThat(count("SELECT count(*) FROM accounting.accounting_break WHERE period_start = '1999-02-01' AND status = 'OPEN'")).isZero();

        java.util.SortedMap<String, com.finapp.accounting.PastTheCutoffPostings.Split> pastTheCutoff = snapshots.read(snapshot -> new LedgerFlooredPostings().pastTheCutoff(snapshot, JANUARY.atEndOfMonth()));
        assertThat(pastTheCutoff).as("never floored, never unrecorded: another scope, and valued in the month it is dated").isEmpty();
        // No close here: what is left of one is the fixture's own concluded checks, the proof being above, and the next case would reopen February for good. LOCKED, an unlock lifts it.
    }

    @Test
    @DisplayName("what a statement shows of it: on a customer's wallet and a merchant's payable, ADJUSTMENT, the one constant reference, no attribution, no key - so no adjustment, month or proposal is served, and BNPL_THREE_WAY's leg, which compares keyed lines only, does not compare it")
    void whatAStatementShowsOfIt() throws Exception {
        LedgerAccount customers = wallet();
        UUID merchant = IDS.next();
        LedgerAccount payable = ownedBy(merchant, AccountType.LIABILITY, AccountPurpose.MERCHANT_PAYABLE);
        PriorPeriodAdjustments.Acted posted = approved(List.of(new PriorPeriodPostings.Line(payable.id().value(), Side.DEBIT, Money.ofMinorUnits(1500, EUR)), new PriorPeriodPostings.Line(customers.id().value(), Side.CREDIT, Money.ofMinorUnits(1500, EUR))));

        GlSnapshots snapshots = new GlSnapshots(DatabaseRoles::application, Duration.ofSeconds(30));
        JdbcStatementDerivation statements = new JdbcStatementDerivation(new JdbcBalanceDerivation());
        List<StatementDerivation.AccountStatement> ofTheCustomer = snapshots.read(snapshot -> statements.statementsFor(snapshot, customers.ownerRef().orElseThrow(), JANUARY.atDay(1), FEBRUARY.atEndOfMonth()));
        List<StatementDerivation.AccountStatement> ofTheMerchant = snapshots.read(snapshot -> statements.statementsFor(snapshot, merchant, JANUARY.atDay(1), FEBRUARY.atEndOfMonth(), List.of(MerchantPayable.BNPL_FINANCING_NAMESPACE, MerchantPayable.BNPL_REFUND_NAMESPACE, "bnpl.")));

        assertThat(List.of(ofTheCustomer, ofTheMerchant)).allSatisfy(read -> assertThat(read).singleElement().satisfies(statement -> assertThat(statement.lines()).singleElement().satisfies(line -> {
            assertThat(line.entry().value()).isEqualTo(posted.entry().orElseThrow());
            assertThat(line.entryType()).isEqualTo(JournalEntryType.ADJUSTMENT);
            assertThat(line.reference()).isEqualTo(PriorPeriodAdjustments.ENTRY_REFERENCE);
            assertThat(line.reconciliationAttribution()).isFalse();
            assertThat(line.namespace()).isEmpty();
            assertThat(line.postingDate()).isEqualTo(LANDS_ON);
            assertThat(line.valueDate()).isEqualTo(LANDS_ON);
        })));

        // P14-TSK-022 (q): the real fold over the real line. It faces a customer's wallet, so read by its counterparty this debit would be a refund; ledger V030's origin reads first.
        MerchantPayable.Payable owed = snapshots.read(snapshot -> new MerchantPayable(new JdbcLedgerAccountStore(), new JdbcPositionBreakdown()).payablesOf(snapshot, com.finapp.merchant.MerchantId.of(merchant)).get(0));
        assertThat(owed.accountingCorrections()).isEqualTo(Money.ofMinorUnits(-1500, EUR));
        assertThat(owed.refunded()).isEqualTo(Money.ofMinorUnits(0, EUR));
        assertThat(owed.other()).isEqualTo(Money.ofMinorUnits(0, EUR));
        assertThat(owed.terms()).isEqualTo(owed.position());

        // P14-TSK-023 (ac): the same fold over a MANUAL proposal's real line: the statement's own p.origin, a posted proposal's, so facing the wallet it reads other. The report's OTHER for the same origin is BnplMerchantDrillDownTest#aManualAdjustmentIsNamedOther's, hermetic.
        Actor operator = person();
        AdjustmentProposalId manual = inScope(operator, uow -> adjustments().propose(uow, new AdjustmentCommand("ppa-suite-" + IDS.next(), LANDS_ON, LANDS_ON, "manual", "a person's", List.of(new JournalLine(customers.id(), Direction.DEBIT, Money.ofMinorUnits(700, EUR)), new JournalLine(payable.id(), Direction.CREDIT, Money.ofMinorUnits(700, EUR))))).proposalId());
        Actor secondPerson = person();
        inScope(secondPerson, uow -> adjustments().approve(uow, manual));

        MerchantPayable.Payable afterManual = snapshots.read(snapshot -> new MerchantPayable(new JdbcLedgerAccountStore(), new JdbcPositionBreakdown()).payablesOf(snapshot, com.finapp.merchant.MerchantId.of(merchant)).get(0));
        assertThat(afterManual.other()).as("by its origin, whatever it faced").isEqualTo(Money.ofMinorUnits(700, EUR));
        assertThat(afterManual.captured()).as("no sale happened").isEqualTo(Money.ofMinorUnits(0, EUR));
        assertThat(afterManual.accountingCorrections()).isEqualTo(owed.accountingCorrections());
        assertThat(afterManual.terms()).isEqualTo(afterManual.position());
        assertThat(afterManual.position()).isEqualTo(owed.position().plus(Money.ofMinorUnits(700, EUR)));
    }

    @Test
    @DisplayName("the read for P14-TSK-016: the month's adjustment traced to the period and revision it corrects; a domain's reversal of an entry dated in the closed month classified by derivation; nothing untraced")
    void theCorrectionsRead() throws Exception {
        PeriodCloseFixture.everyPeriodOpen();
        // A transfer's own namespace and shape, so the entries conform and no rule-conformance case here reads them as a finding.
        LedgerAccount sender = wallet();
        LedgerAccount receiver = wallet();
        List<JournalLine> original = List.of(new JournalLine(sender.id(), Direction.DEBIT, Money.ofMinorUnits(25, EUR)), new JournalLine(receiver.id(), Direction.CREDIT, Money.ofMinorUnits(25, EUR)));
        JournalEntryId entry = inScope(Actor.SYSTEM, uow -> posting().post(uow, new PostingCommand("transfer:" + IDS.next(), JANUARY.atDay(20), JANUARY.atDay(20), "ppa-suite", original, PostingCommand.Dating.AS_STATED))).entryId();
        PeriodCloseFixture.closed(JANUARY);
        inScope(Actor.SYSTEM, uow -> reversal().reverse(uow, new ReversalCommand("transfer:" + IDS.next(), entry, LANDS_ON, LANDS_ON, "ppa-suite-reversal",
                List.of(new JournalLine(sender.id(), Direction.CREDIT, Money.ofMinorUnits(25, EUR)), new JournalLine(receiver.id(), Direction.DEBIT, Money.ofMinorUnits(25, EUR))))));
        PriorPeriodAdjustments.Acted posted = approve(propose(), person());

        PriorPeriodCorrections.Corrections read = new GlSnapshots(DatabaseRoles::application, Duration.ofSeconds(30)).read(snapshot -> new LedgerPriorPeriodCorrections().ofMonth(snapshot, FEBRUARY));

        assertThat(read.adjustments()).extracting(PriorPeriodCorrections.Adjustment::id).contains(posted.id().value());
        assertThat(read.adjustments()).filteredOn(adjustment -> adjustment.id().equals(posted.id().value())).singleElement().satisfies(adjustment -> {
            assertThat(adjustment.correctedMonth()).isEqualTo(JANUARY);
            assertThat(adjustment.correctedRevision()).isEqualTo(posted.correctedRevision());
            assertThat(adjustment.entry()).isEqualTo(posted.entry().orElseThrow());
        });
        assertThat(read.reversals()).anySatisfy(reversal -> {
            assertThat(reversal.namespace()).isEqualTo("transfer");
            assertThat(reversal.originalMonth()).isEqualTo(JANUARY);
            assertThat(reversal.entries()).isPositive();
        });
        assertThat(read.untraced()).isZero();
    }

    // ------------------------------------------------------------------ the seam

    @Test
    @DisplayName("the generic door refuses the origin, both acts; the proposal stands for its own door")
    void theGenericDoorRefusesTheOrigin() throws Exception {
        PriorPeriodAdjustments.Acted proposed = propose();
        AdjustmentProposalId proposal = AdjustmentProposalId.of(proposed.proposal());
        Actor holder = person();

        assertThatThrownBy(() -> inScope(holder, uow -> adjustments().approve(uow, proposal))).isInstanceOf(AdjustmentOriginMismatchException.class);
        assertThatThrownBy(() -> inScope(holder, uow -> adjustments().reject(uow, proposal))).isInstanceOf(AdjustmentOriginMismatchException.class);

        assertStillProposed(proposed);
    }

    @Test
    @DisplayName("each owner refuses the other's, both ways: reconciliation's door on a prior-period adjustment, the accounting door on a resolution's and on a MANUAL proposal, approval and rejection")
    void theOwnersRefuseEachOther() throws Exception {
        PriorPeriodAdjustments.Acted accounting = propose();
        AdjustmentProposalId ofAccounting = AdjustmentProposalId.of(accounting.proposal());
        Actor holder = person();
        assertThatThrownBy(() -> inScope(holder, uow -> adjustments().approveOwned(uow, ofAccounting))).isInstanceOf(AdjustmentOriginMismatchException.class);
        assertThatThrownBy(() -> inScope(holder, uow -> adjustments().rejectOwned(uow, ofAccounting))).isInstanceOf(AdjustmentOriginMismatchException.class);
        assertStillProposed(accounting);

        Actor resolver = person();
        List<JournalLine> lines = journalLines();
        AdjustmentProposalId resolution = inScope(resolver, uow -> adjustments().proposeOwned(uow, new OwnedAdjustmentCommand(LANDS_ON, LANDS_ON, "resolution=" + IDS.next(), "a write-off", AdjustmentReasonCode.RECONCILIATION_WRITE_OFF, lines)));
        AdjustmentProposalId manual = inScope(resolver, uow -> adjustments().propose(uow, new AdjustmentCommand("ppa-suite-" + IDS.next(), LANDS_ON, LANDS_ON, "manual", "a person's", lines)).proposalId());
        for (AdjustmentProposalId other : List.of(resolution, manual)) {
            assertThatThrownBy(() -> inScope(holder, uow -> adjustments().approvePriorPeriod(uow, other))).isInstanceOf(AdjustmentOriginMismatchException.class);
            assertThatThrownBy(() -> inScope(holder, uow -> adjustments().rejectPriorPeriod(uow, other))).isInstanceOf(AdjustmentOriginMismatchException.class);
        }
    }

    @Test
    @DisplayName("a purpose closed to free adjustments is refused at each rank: the machine, the ledger's service, and V030's trigger for a raw writer, an MANUAL-origin line's own binding unchanged beside it")
    void aClosedToFreeAdjustmentsPurposeIsRefused() throws Exception {
        LedgerAccount loan = owned(AccountType.ASSET, AccountPurpose.LOAN_PRINCIPAL);
        LedgerAccount wallet = wallet();
        List<PriorPeriodPostings.Line> lines = List.of(new PriorPeriodPostings.Line(loan.id().value(), Side.DEBIT, Money.ofMinorUnits(10, EUR)), new PriorPeriodPostings.Line(wallet.id().value(), Side.CREDIT, Money.ofMinorUnits(10, EUR)));

        assertThatThrownBy(() -> inScope(ACCOUNTANT, uow -> machine().propose(uow, id(JANUARY), row(JANUARY).revision(), lines, ACCOUNTANT, REASON, correlation())))
                .isInstanceOf(PriorPeriodAdjustments.PurposeClosed.class);
        assertThatThrownBy(() -> inScope(ACCOUNTANT, uow -> postings().propose(uow, LANDS_ON, "reference", "identifiers only", lines)))
                .isInstanceOf(com.finapp.ledger.AdjustmentOnReconciledPositionException.class);

        UUID accounting = rawProposal("ACCOUNTING", "PRIOR_PERIOD_ADJUSTMENT", LANDS_ON, LANDS_ON);
        rawRefused("INSERT INTO ledger.adjustment_proposal_line (proposal_id, seq, ledger_account_id, direction, amount_minor, currency, scale) VALUES ('" + accounting + "', 0, '" + loan.id().value() + "', 'DEBIT', 10, 'EUR', 2)", RAISED);
        UUID manual = rawProposal("MANUAL", "MANUAL_CORRECTION", LANDS_ON, LANDS_ON);
        rawRefused("INSERT INTO ledger.adjustment_proposal_line (proposal_id, seq, ledger_account_id, direction, amount_minor, currency, scale) VALUES ('" + manual + "', 0, '" + loan.id().value() + "', 'DEBIT', 10, 'EUR', 2)", RAISED);
        raw("INSERT INTO ledger.adjustment_proposal_line (proposal_id, seq, ledger_account_id, direction, amount_minor, currency, scale) VALUES ('" + accounting + "', 0, '" + wallet.id().value() + "', 'DEBIT', 10, 'EUR', 2)");
    }

    @Test
    @DisplayName("V030 for every writer: the origin and the code pair, one date for an ACCOUNTING proposal, UNCODED still refused; the ledger's four eyes hold for this origin")
    void theLedgersMigrationBindsEveryWriter() throws Exception {
        rawRefused(rawProposalSql(UUID.randomUUID(), "ACCOUNTING", "MANUAL_CORRECTION", LANDS_ON, LANDS_ON), CHECK_VIOLATION);
        rawRefused(rawProposalSql(UUID.randomUUID(), "RECONCILIATION", "PRIOR_PERIOD_ADJUSTMENT", LANDS_ON, LANDS_ON), CHECK_VIOLATION);
        rawRefused(rawProposalSql(UUID.randomUUID(), "SOMETHING", "PRIOR_PERIOD_ADJUSTMENT", LANDS_ON, LANDS_ON), CHECK_VIOLATION);
        rawRefused(rawProposalSql(UUID.randomUUID(), "ACCOUNTING", "PRIOR_PERIOD_ADJUSTMENT", LANDS_ON, JANUARY.atDay(20)), CHECK_VIOLATION);
        rawRefused(rawProposalSql(UUID.randomUUID(), "MANUAL", "UNCODED", LANDS_ON, LANDS_ON), RAISED);

        UUID proposal = rawProposal("ACCOUNTING", "PRIOR_PERIOD_ADJUSTMENT", LANDS_ON, LANDS_ON);
        ownerRefused("UPDATE ledger.adjustment_proposal SET origin = 'MANUAL', reason_code = 'MANUAL_CORRECTION' WHERE id = '" + proposal + "'", RAISED);
        rawRefused("UPDATE ledger.adjustment_proposal SET origin = 'MANUAL' WHERE id = '" + proposal + "'", "42501");
        UUID entry = approve(propose(), person()).entry().orElseThrow();
        try (Connection app = DatabaseRoles.application(); Statement statement = app.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE ledger.adjustment_proposal SET status = 'APPROVED', decided_by = proposed_by, decided_at = now(), journal_entry_id = '" + entry + "' WHERE id = '" + proposal + "'"))
                    .isInstanceOfSatisfying(SQLException.class, refused -> assertThat(refused.getSQLState()).isEqualTo(CHECK_VIOLATION))
                    .hasMessageContaining("adjustment_proposal_approver_is_not_initiator");
        }
    }

    @Test
    @DisplayName("dated into the month it corrects, whoever built the proposal, the ledger refuses it at the approval, before anything is written, and it stays PROPOSED: the rule the machine's date stands on (the backlog's probe)")
    void datedIntoTheCorrectedPeriodTheLedgerRefuses() throws Exception {
        List<PriorPeriodPostings.Line> lines = lines();
        UUID proposal = inScope(ACCOUNTANT, uow -> postings().propose(uow, JANUARY.atDay(20), "prior-period-adjustment:raw", "identifiers only", lines));
        Actor approver = person();

        assertThatThrownBy(() -> inScope(approver, uow -> postings().approve(uow, proposal))).isInstanceOf(PeriodClosedException.class);

        assertThat(text("SELECT status FROM ledger.adjustment_proposal WHERE id = '" + proposal + "'")).isEqualTo("PROPOSED");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = 'ledger.adjust.approve:" + proposal + "'")).isZero();
    }

    // ------------------------------------------------------------------ V009 for every writer

    @Test
    @DisplayName("V009 for every writer: a birth not PROPOSED, a corrected month not CLOSED or at another revision, a target not OPEN, a date in or before the corrected month or outside its target: each refused raw. The future's branch needs an open month in the future, which contiguous periods up to today cannot give: PriorPeriodAdjustmentMigrationTest pins its text")
    void theMigrationBindsEveryBirth() throws Exception {
        UUID january = id(JANUARY).value();
        UUID february = id(FEBRUARY).value();
        int revision = row(JANUARY).revision();
        rawRefused(rowSql(january, revision, february, "1999-02-10", "POSTED", "journal_entry_id"), RAISED);
        rawRefused(rowSql(february, 1, february, "1999-02-10", "PROPOSED", null), RAISED);
        rawRefused(rowSql(january, revision + 1, february, "1999-02-10", "PROPOSED", null), RAISED);
        rawRefused(rowSql(january, revision, january, "1999-02-10", "PROPOSED", null), RAISED);
        rawRefused(rowSql(january, revision, february, "1999-01-31", "PROPOSED", null), RAISED);
        rawRefused(rowSql(january, revision, february, "1999-03-01", "PROPOSED", null), RAISED);
        raw(rowSql(january, revision, february, "1999-02-10", "PROPOSED", null));
    }

    @Test
    @DisplayName("V009's machine for every writer: four eyes by CHECK; the payload frozen; no third edge; a POST while the corrected month is not CLOSED refused; never deleted or truncated; the application role holds the decision's columns only")
    void theMigrationBindsEveryEdge() throws Exception {
        PriorPeriodAdjustments.Acted proposed = propose();
        String row = " WHERE id = '" + proposed.id().value() + "'";
        rawRefused("UPDATE accounting.prior_period_adjustment SET status = 'POSTED', decided_by = proposed_by, decided_by_type = proposed_by_type, decided_at = now(), decision_reason = 'raw', journal_entry_id = '" + IDS.next() + "'" + row, CHECK_VIOLATION);
        rawRefused("UPDATE accounting.prior_period_adjustment SET posting_date = '1999-02-11'" + row, "42501");
        ownerRefused("UPDATE accounting.prior_period_adjustment SET posting_date = '1999-02-11'" + row, RAISED);
        ownerRefused("UPDATE accounting.prior_period_adjustment SET corrected_revision = corrected_revision + 1" + row, RAISED);
        ownerRefused("UPDATE accounting.prior_period_adjustment SET status = 'POSTED'" + row, CHECK_VIOLATION);
        ownerRefused("DELETE FROM accounting.prior_period_adjustment" + row, RAISED);
        ownerRefused("TRUNCATE accounting.prior_period_adjustment", RAISED);
        rawRefused("DELETE FROM accounting.prior_period_adjustment" + row, "42501");

        inScope(ACCOUNTANT, uow -> MACHINE.proposeReopen(uow, id(JANUARY), ACCOUNTANT, PeriodCloseFixture.posted(uow, id(JANUARY)), "a correction was found", correlation()));
        inScope(CONTROLLER, uow -> MACHINE.approveReopen(uow, id(JANUARY), MACHINE.pendingOf(uow, id(JANUARY)).reopen().orElseThrow().id(), CONTROLLER, PeriodCloseFixture.posted(uow, id(JANUARY)), "agreed", correlation()));
        rawRefused("UPDATE accounting.prior_period_adjustment SET status = 'POSTED', decided_by = 'someone-else', decided_by_type = 'EMPLOYEE', decided_at = now(), decision_reason = 'raw', journal_entry_id = '" + IDS.next() + "'" + row, RAISED);

        raw("UPDATE accounting.prior_period_adjustment SET status = 'REJECTED', decided_by = 'someone-else', decided_by_type = 'EMPLOYEE', decided_at = now(), decision_reason = 'raw'" + row);
        rawRefused("UPDATE accounting.prior_period_adjustment SET status = 'POSTED', decided_by = 'x', decided_by_type = 'EMPLOYEE', decided_at = now(), decision_reason = 'raw', journal_entry_id = '" + IDS.next() + "'" + row, RAISED);
    }

    @Test
    @DisplayName("the proposer cannot approve: refused by the machine with nothing written; the ledger's CHECK and V009's beneath it are this suite's raw cases")
    void theProposerCannotApprove() throws Exception {
        PriorPeriodAdjustments.Acted proposed = propose();

        assertThatThrownBy(() -> approve(proposed, ACCOUNTANT)).isInstanceOf(PriorPeriodAdjustments.SelfApprovalRefused.class);

        assertStillProposed(proposed);
    }

    @Test
    @DisplayName("a break's correction: a break of an earlier month is resolved by reference to the adjustment's entry; the resolution posts nothing, and a person goes from the break to the adjustment to the break resolved with no new link")
    void aBreakIsResolvedByTheAdjustmentsEntry() throws Exception {
        YearMonth quiet = YearMonth.of(1998, 12);
        String subject = "namespace:POSTING ppa-suite-" + IDS.next() + ":";
        boolean raised = inScope(Actor.SYSTEM, uow -> breaks().raise(uow, quiet, new BreakFinding(CloseCheckCode.RULE_CONFORMANCE, BreakType.UNREGISTERED_POSTING, subject, "entries=1", "posting-rules:v" + PostingRuleRegister.CATALOGUE_VERSION),
                Actor.SYSTEM, correlation()));
        assertThat(raised).isTrue();
        String breakId = text("SELECT id FROM accounting.accounting_break WHERE subject_ref = '" + subject + "'");
        UUID entry = approve(propose(), person()).entry().orElseThrow();
        long entries = count("SELECT count(*) FROM ledger.journal_entry");

        Actor controller = person();
        AccountingBreaks.Acted resolved = inScope(controller, uow -> breaks().resolve(uow, com.finapp.accounting.AccountingBreakId.of(UUID.fromString(breakId)), controller, entry, "corrected by the prior-period adjustment", correlation()));

        assertThat(resolved.status()).isEqualTo(BreakStatus.RESOLVED);
        assertThat(count("SELECT count(*) FROM ledger.journal_entry")).as("nothing posts from a break").isEqualTo(entries);
        assertThat(text("SELECT resolving_entry_id FROM accounting.accounting_break WHERE id = '" + breakId + "'")).isEqualTo(entry.toString());
    }

    // ------------------------------------------------------------------ plumbing

    private PriorPeriodAdjustments.Acted propose() throws SQLException {
        List<PriorPeriodPostings.Line> lines = lines();
        return inScope(ACCOUNTANT, uow -> machine().propose(uow, id(JANUARY), row(JANUARY).revision(), lines, ACCOUNTANT, REASON, correlation()));
    }

    /** What an approver reads: the date, the revision and what has posted against the month as it stands now. */
    private PriorPeriodAdjustments.Approval reading(PriorPeriodAdjustments.Acted proposed) {
        Set<UUID> posted = new HashSet<>(inScope(Actor.SYSTEM, uow -> machine().postedAgainst(uow, id(JANUARY))));
        return new PriorPeriodAdjustments.Approval(proposed.postingDate(), proposed.correctedRevision(), posted);
    }

    private PriorPeriodAdjustments.Acted approve(PriorPeriodAdjustments.Acted proposed, Actor approver) {
        PriorPeriodAdjustments.Approval reading = reading(proposed);
        return inScope(approver, uow -> machine().approve(uow, proposed.id(), reading, approver, "read and agreed", correlation()));
    }

    /** Proposed by the accountant and approved by a second person, on these lines. */
    private PriorPeriodAdjustments.Acted approved(List<PriorPeriodPostings.Line> lines) {
        PriorPeriodAdjustments.Acted proposed = inScope(ACCOUNTANT, uow -> machine().propose(uow, id(JANUARY), row(JANUARY).revision(), lines, ACCOUNTANT, REASON, correlation()));
        PriorPeriodAdjustments.Acted posted = approve(proposed, person());
        assertThat(posted.status()).isEqualTo(PriorPeriodAdjustmentStatus.POSTED);
        return posted;
    }

    private void assertStillProposed(PriorPeriodAdjustments.Acted proposed) throws SQLException {
        assertThat(text("SELECT status FROM accounting.prior_period_adjustment WHERE id = '" + proposed.id().value() + "'")).isEqualTo("PROPOSED");
        assertThat(text("SELECT status FROM ledger.adjustment_proposal WHERE id = '" + proposed.proposal() + "'")).isEqualTo("PROPOSED");
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = 'ledger.adjust.approve:" + proposed.proposal() + "'")).isZero();
    }

    private void assertOneEntry(PriorPeriodAdjustments.Acted proposed) throws SQLException {
        assertThat(count("SELECT count(*) FROM ledger.journal_entry WHERE idempotency_scope = 'ledger.adjust.approve:" + proposed.proposal() + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = '" + PriorPeriodAdjustments.POSTED_EVENT + "' AND aggregate_id = '" + proposed.id().value() + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.PriorPeriodAdjustmentPosted' AND target_id = '" + proposed.id().value() + "'")).isEqualTo(1);
    }

    /** Each racer on its own thread and connection, started together; each outcome, a refusal's exception included. */
    private static <R> List<Object> race(List<Callable<R>> racers) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(racers.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<R>> running = new ArrayList<>();
            for (Callable<R> racer : racers) {
                running.add(pool.submit(() -> {
                    start.await();
                    return racer.call();
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<R> outcome : running) {
                try {
                    outcomes.add(outcome.get(2, TimeUnit.MINUTES));
                } catch (ExecutionException refused) {
                    outcomes.add(refused.getCause());
                } catch (TimeoutException stuck) {
                    throw new AssertionError("a racer did not finish: a deadlock or a lost wake-up", stuck);
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private PriorPeriodAdjustments machine() {
        return machine(FEBRUARY_TENTH);
    }

    private PriorPeriodAdjustments machine(Clock clock) {
        return new PriorPeriodAdjustments(AccountingPeriodFixture.datedBy(clock), store, postings(), new JdbcGlMappingStore(), new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, clock);
    }

    private LedgerPriorPeriodPostings postings() {
        return new LedgerPriorPeriodPostings(adjustments(), accounts);
    }

    private AdjustmentService adjustments() {
        return new AdjustmentService(new IdempotentExecutor(new JdbcIdempotencyRecordStore(), NOW, Duration.ofDays(1), Duration.ofMinutes(5)), new JdbcJournalEntryStore(IDS), new JdbcAdjustmentProposalStore(), new JdbcAuditWriter(),
                new JdbcOutboxWriter(), new JdbcBalanceProjection(), IDS, NOW, PostingObserver.NONE, accounts);
    }

    /** Every FINAL definition, as {@code OperationalReportsDatabaseTest} builds them. */
    private static ReportDefinitions finalDefinitions() {
        LedgerTrialBalanceReads reads = new LedgerTrialBalanceReads(new JdbcGlMappingStore());
        LedgerCounterpartyPositionReads positions = new LedgerCounterpartyPositionReads();
        return ReportDefinitions.of(new TrialBalanceDefinition(reads), new GlActivityDefinition(reads, positions), PurposeReportDefinition.revenue(reads), PurposeReportDefinition.receivablesPayables(reads),
                PurposeReportDefinition.settlementPositions(reads, positions), PurposeReportDefinition.fxResults(reads), PurposeReportDefinition.loanPortfolio(reads), PurposeReportDefinition.bnplExposure(reads),
                new SuspenseDefinition(reads, new ReconciliationMonthReads(new JdbcReportReadings(), AccountingPeriodFixture.DECLARED_SOURCES)),
                new BalanceSheetDefinition(reads, positions, new PinnedValuationRates(new JdbcValuationRateSetStore()), statementNotes()),
                new IncomeStatementDefinition(reads, positions, new PinnedValuationRates(new JdbcValuationRateSetStore()), statementNotes()));
    }

    private static StatementNotes statementNotes() {
        return new StatementNotes(new ReconciliationMonthReads(new JdbcReportReadings(), AccountingPeriodFixture.DECLARED_SOURCES), new ReconciliationAsOfCutoffReads(new JdbcReportReadings()), new LedgerPriorPeriodCorrections(), new AccountingCloseRecordReads());
    }

    private static ReportRuns reports() {
        return new ReportRuns(new JdbcReportRunStore(), AccountingPeriodFixture.STORE, new JdbcGlMappingStore(), finalDefinitions(), ReportRuns.Leases.STANDARD, new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW);
    }

    private static ReportSnapshots reportSnapshots() {
        return new JdbcReportSnapshots(DatabaseRoles::application, ReportRuns.Leases.STANDARD.generationTimeout());
    }

    private static ReportRunGenerator generator(ReportRuns reports) {
        return new ReportRunGenerator(reports, new ReportGeneration(ReportGeneration.FROM_THE_SNAPSHOT, ReportRuns.Leases.STANDARD.generationTimeout(), NOW), reportSnapshots(), PriorPeriodAdjustmentDatabaseTest::inTransaction,
                new ReportRunMetrics(new SimpleMeterRegistry(), finalDefinitions()));
    }

    /** The two checks that case judges; whatever else {@link CloseChecks} holds is never asked. */
    private static CloseChecks closeChecks(FinalTrialBalance trialBalance, SourceProofs proofs, GlSnapshots snapshots) {
        ValuationRates noRates = new ValuationRates() {
            @Override
            public List<CurrencyCode> valued() {
                return List.of();
            }

            @Override
            public List<Observed> lastObservedBefore(Connection unitOfWork, java.time.Instant before) {
                return List.of();
            }
        };
        ValuationRateSets sets = new ValuationRateSets(new JdbcValuationRateSetStore(), AccountingPeriodFixture.STORE, noRates, ValuationStaleness.standard(), new JdbcAuditWriter(), IDS, NOW);
        AccrualCompleteness noAccruals = new AccrualCompleteness() {
            @Override
            public List<Lagging> lagging(Connection unitOfWork) {
                throw new AssertionError("this case judges no accrual");
            }

            @Override
            public List<Floored> flooredPast(Connection unitOfWork, LocalDate periodEnd) {
                throw new AssertionError("this case judges no accrual");
            }
        };
        return new CloseChecks((month, check, actor, correlation) -> {
            throw new AssertionError("this case raises no break through the close");
        }, trialBalance, noAccruals, proofs, (snapshot, month) -> {
            throw new AssertionError("this case judges no disclosure");
        }, (snapshot, month) -> {
            throw new AssertionError("this case judges no coverage");
        }, sets, AccountingPeriodFixture.STORE, new AccountingChecks.Snapshots() {
            @Override
            public <R> R read(Function<Connection, R> work) {
                return snapshots.read(work);
            }
        });
    }

    /** A product proof this case does not read: every purpose it walks is the payable's or a projection's. */
    private static <R> Function<Connection, R> notRead() {
        return snapshot -> {
            throw new AssertionError("this case reads the merchant payables and the projections only");
        };
    }

    private static AccountingBreaks breaks() {
        return new AccountingBreaks(new JdbcAccountingBreakStore(), new LedgerJournalReads(), new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW);
    }

    private static PostingService posting() {
        return new PostingService(new IdempotentExecutor(new JdbcIdempotencyRecordStore(), NOW, Duration.ofDays(1), Duration.ofMinutes(5)), new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(),
                new JdbcBalanceProjection(), IDS, NOW, PostingObserver.NONE);
    }

    private static ReversalService reversal() {
        return new ReversalService(new IdempotentExecutor(new JdbcIdempotencyRecordStore(), NOW, Duration.ofDays(1), Duration.ofMinutes(5)), new JdbcJournalEntryStore(IDS), new JdbcAuditWriter(), new JdbcOutboxWriter(),
                new JdbcBalanceProjection(), IDS, NOW, PostingObserver.NONE);
    }

    private List<PriorPeriodPostings.Line> lines() throws SQLException {
        return List.of(new PriorPeriodPostings.Line(operational(AccountPurpose.FEE_REVENUE).id().value(), Side.DEBIT, Money.ofMinorUnits(1500, EUR)), new PriorPeriodPostings.Line(wallet().id().value(), Side.CREDIT, Money.ofMinorUnits(1500, EUR)));
    }

    private List<JournalLine> journalLines() throws SQLException {
        return List.of(new JournalLine(operational(AccountPurpose.FEE_REVENUE).id(), Direction.DEBIT, Money.ofMinorUnits(10, EUR)), new JournalLine(wallet().id(), Direction.CREDIT, Money.ofMinorUnits(10, EUR)));
    }

    private LedgerAccount wallet() throws SQLException {
        return owned(AccountType.LIABILITY, AccountPurpose.CUSTOMER_WALLET);
    }

    private LedgerAccount owned(AccountType type, AccountPurpose purpose) throws SQLException {
        return ownedBy(IDS.next(), type, purpose);
    }

    private LedgerAccount ownedBy(UUID owner, AccountType type, AccountPurpose purpose) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            app.setAutoCommit(false);
            LedgerAccount account = accounts.createOrConverge(app, LedgerAccount.owned(IDS, NOW, type, purpose, EUR, owner)).account();
            app.commit();
            return account;
        }
    }

    private LedgerAccount operational(AccountPurpose purpose) throws SQLException {
        try (Connection app = DatabaseRoles.application()) {
            return accounts.findOperational(app, purpose, EUR).orElseThrow();
        }
    }

    private static <R> R inTransaction(Function<Connection, R> work) {
        return inScope(Actor.SYSTEM, CorrelationId.generate(IDS), work);
    }

    private static String text(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); Statement statement = migrator.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            assertThat(row.next()).as(sql).isTrue();
            return row.getString(1);
        }
    }

    private UUID rawProposal(String origin, String code, LocalDate posting, LocalDate value) throws SQLException {
        UUID id = IDS.next();
        raw(rawProposalSql(id, origin, code, posting, value));
        return id;
    }

    private static String rawProposalSql(UUID id, String origin, String code, LocalDate posting, LocalDate value) {
        return "INSERT INTO ledger.adjustment_proposal (id, status, posting_date, value_date, reference, reason, proposed_by, proposed_at, reason_code, origin) VALUES ('" + id + "', 'PROPOSED', '" + posting + "', '" + value
                + "', 'raw', 'raw', 'raw-writer', now(), '" + code + "', '" + origin + "')";
    }

    private String rowSql(UUID corrected, int revision, UUID target, String date, String status, String entryColumn) {
        return rowSql(corrected, revision, target, 1, date, status, entryColumn);
    }

    private String rowSql(UUID corrected, int revision, UUID target, int targetRevision, String date, String status, String entryColumn) {
        return "INSERT INTO accounting.prior_period_adjustment (id, corrected_period_id, corrected_revision, target_period_id, target_revision, posting_date, adjustment_proposal_id, status, proposed_by, proposed_by_type, proposed_at, proposal_reason, correlation_id"
                + (entryColumn == null ? "" : ", " + entryColumn) + ") VALUES ('" + IDS.next() + "', '" + corrected + "', " + revision + ", '" + target + "', " + targetRevision + ", '" + date + "', '" + IDS.next() + "', '" + status + "', 'raw', 'EMPLOYEE', now(), 'raw', 'raw-flow'"
                + (entryColumn == null ? "" : ", '" + IDS.next() + "'") + ")";
    }

    /** As the application role: the writer every door uses. */
    private static void raw(String sql) throws SQLException {
        try (Connection app = DatabaseRoles.application(); Statement statement = app.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private static void rawRefused(String sql, String sqlState) throws SQLException {
        try (Connection app = DatabaseRoles.application(); Statement statement = app.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate(sql)).as(sql).isInstanceOfSatisfying(SQLException.class, refused -> assertThat(refused.getSQLState()).isEqualTo(sqlState));
        }
    }

    /** As the migrator: privilege no help - the triggers bind every writer. */
    private static void ownerRefused(String sql, String sqlState) throws SQLException {
        try (Connection owner = DatabaseRoles.migrator(); Statement statement = owner.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate(sql)).as(sql).isInstanceOfSatisfying(SQLException.class, refused -> assertThat(refused.getSQLState()).isEqualTo(sqlState));
        }
    }
}
