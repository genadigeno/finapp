package com.finapp.app.accounting;

import static com.finapp.app.accounting.AccountingPeriodFixture.JANUARY;
import static com.finapp.app.accounting.AccountingPeriodFixture.correlation;
import static com.finapp.app.accounting.AccountingPeriodFixture.id;
import static com.finapp.app.accounting.AccountingPeriodFixture.inScope;
import static com.finapp.app.accounting.AccountingPeriodFixture.periods;
import static com.finapp.app.accounting.AccountingPeriodFixture.person;
import static com.finapp.app.accounting.AccountingPeriodFixture.row;
import static com.finapp.app.accounting.PeriodCloseFixture.ACCOUNTANT;
import static com.finapp.app.accounting.PeriodCloseFixture.CLOSES;
import static com.finapp.app.accounting.PeriodCloseFixture.CONTROLLER;
import static com.finapp.app.accounting.PeriodCloseFixture.MACHINE;
import static com.finapp.app.accounting.PeriodCloseFixture.REASON;
import static com.finapp.app.accounting.PeriodCloseFixture.RUNS;
import static com.finapp.app.accounting.PeriodCloseFixture.RUN_STORE;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.CloseRunId;
import com.finapp.accounting.CloseRunStore;
import com.finapp.accounting.CloseRuns;
import com.finapp.accounting.PeriodCloseId;
import com.finapp.accounting.PeriodCloseStatus;
import com.finapp.accounting.PeriodCloseStore;
import com.finapp.accounting.PeriodCloses;
import com.finapp.accounting.PeriodStatus;
import com.finapp.platform.security.Actor;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * PI-013's reproduction, kept as its regression case (`P15-TSK-033`; {@code INV-ACC-09}): a month's close is read on the run of its present lock and on its
 * standing proposal, whatever the database's clock did. The Phase 14 database pass found {@code PeriodCloses$CloseNotReady: the period's present lock has no
 * close run yet} on a month whose present lock's run had concluded: Docker Desktop's VM clock - the database's {@code clock_timestamp()} - stepped back between
 * an earlier lock's run and the present lock's, so {@code ORDER BY run.started_at DESC} named the earlier run the latest. Waiting for a step is a flake, so the
 * step is made here: the session that births the second lock's run, and the one that makes the second proposal, read a database clock {@link ShadowClock#BEHIND}
 * behind - the clock having stepped back by that much since the first - through the production code and the production triggers, which stamp {@code
 * started_at} and {@code proposed_at} with an unqualified {@code clock_timestamp()} and so read the shadow on the opted-in session alone.
 *
 * <p><strong>Written and owed</strong> (C14: the database tiers run once, at {@code P15-TST-002}). <strong>A database of its own</strong> ({@code
 * own-container}): it locks, unlocks and proposes January 1999, as {@code PeriodCloseDatabaseTest} does, and activates a GL mapping from it. JUnit runs the two
 * cases in its default order, by the hash of each name; neither depends on the other: {@link #januaryIsOpen} returns January to {@code OPEN} through the
 * machine's own doors before each, and each counts only its own lock's run and its own proposals.
 */
@Tag("database")
@Tag("own-container") // its own JVM and database: ownContainerDatabaseTest, the javadoc says why
@DisplayName("PI-013: a month's close reads its present lock's run and its standing proposal, whatever the database's clock did (P15-TSK-033)")
class CloseRunClockStepDatabaseTest {

    @BeforeAll
    static void installTheShadowClock() throws SQLException {
        ShadowClock.install();
    }

    @AfterAll
    static void dropTheShadowClock() throws SQLException {
        ShadowClock.uninstall();
    }

    @BeforeEach
    void januaryIsOpen() {
        AccountingPeriodFixture.openJanuaryAndFebruary();
        PeriodCloseFixture.mappingInForce();
        PeriodCloseFixture.everyPeriodOpen();
    }

    @Test
    @DisplayName("PI-013's reproduction: the present lock's run born on a clock stepped back behind an earlier lock's run is the period's run - the"
            + " store's latest, the view's, the readiness's - and the month is proposed on it; it was refused 'the period's present lock has no"
            + " close run yet' while the latest run was read by started_at")
    void aRunBornBehindAnEarlierLocksRunIsThePresentLocks() {
        AccountingPeriodId january = PeriodCloseFixture.locked(JANUARY);
        CloseRunId earlier = PeriodCloseFixture.started(january);
        unlock(january);
        PeriodCloseFixture.locked(JANUARY);
        ShadowClock.behind(Actor.SYSTEM, uow -> RUNS.startMissing(uow, Actor.SYSTEM));
        CloseRunStore.RunRow present = inScope(Actor.SYSTEM, uow -> RUN_STORE.runUnder(uow, january, row(JANUARY).lockedAt().orElseThrow()))
                .orElseThrow();
        CloseRunStore.RunRow first = inScope(Actor.SYSTEM, uow -> RUN_STORE.run(uow, earlier)).orElseThrow();
        assertThat(present.id()).isNotEqualTo(earlier);
        assertThat(present.startedAt()).as("the precondition: the present lock's run is stamped before the earlier lock's - the step is real")
                .isBefore(first.startedAt());

        PeriodCloseFixture.concludeAll(Set.of(), Set.of());

        assertThat(inScope(Actor.SYSTEM, uow -> RUN_STORE.latestRun(uow, january)).orElseThrow().id())
                .as("the store's latest: the present lock's run first, ranked by the rows (P15-TSK-033's probe (a))").isEqualTo(present.id());
        CloseRuns.RunView view = inScope(Actor.SYSTEM, uow -> RUNS.view(uow, january)).orElseThrow();
        assertThat(view.run().id()).as("the view names the present lock's run by its identity").isEqualTo(present.id());
        assertThat(view.current()).isTrue();
        assertThat(view.complete()).isTrue();

        PeriodCloses.Acted proposed = inScope(ACCOUNTANT, uow -> MACHINE.propose(uow, january, present.id(), List.of(), ACCOUNTANT, REASON, correlation()));
        assertThat(proposed.status()).as("proposed on the present lock's run - PI-013 refused it CloseNotReady").isEqualTo(PeriodStatus.CLOSE_PROPOSED);
        assertThat(proposed.run()).contains(present.id());
    }

    @Test
    @DisplayName("the latest close behind a step: a proposal made on a clock stepped back behind a rejected one is the period's latest - the"
            + " repeated proposal converges on it, never on the rejected one the stamp put later, and the approver's read names it standing")
    void aProposalMadeBehindARejectedOneIsTheLatest() {
        CloseRunId run = PeriodCloseFixture.ready(JANUARY);
        AccountingPeriodId january = id(JANUARY);
        PeriodCloseId rejected = inScope(ACCOUNTANT, uow -> MACHINE.propose(uow, january, run, List.of(), ACCOUNTANT, REASON, correlation()))
                .proposal().orElseThrow();
        inScope(CONTROLLER, uow -> MACHINE.reject(uow, january, rejected, CONTROLLER, "a second look", correlation()));
        PeriodCloseId standing = ShadowClock.behind(ACCOUNTANT, uow -> MACHINE.propose(uow, january, run, List.of(), ACCOUNTANT, REASON, correlation()))
                .proposal().orElseThrow();
        PeriodCloseStore.CloseRow first = inScope(Actor.SYSTEM, uow -> CLOSES.find(uow, rejected)).orElseThrow();
        PeriodCloseStore.CloseRow second = inScope(Actor.SYSTEM, uow -> CLOSES.find(uow, standing)).orElseThrow();
        assertThat(first.status()).isEqualTo(PeriodCloseStatus.REJECTED);
        assertThat(second.proposedAt()).as("the precondition: the standing proposal is stamped before the rejected one - the step is real")
                .isBefore(first.proposedAt());

        assertThat(inScope(Actor.SYSTEM, uow -> CLOSES.latest(uow, january)).orElseThrow().id())
                .as("the store's latest: the PROPOSED one first, ranked by the rows").isEqualTo(standing);
        PeriodCloses.Acted repeated = inScope(ACCOUNTANT, uow -> MACHINE.propose(uow, january, run, List.of(), ACCOUNTANT, REASON, correlation()));
        assertThat(repeated.replayed()).isTrue();
        assertThat(repeated.proposal()).as("the repeat converges on the proposal that stands, never the rejected one").contains(standing);
        PeriodCloses.ProposalView read = inScope(CONTROLLER, uow -> MACHINE.proposalOf(uow, january)).orElseThrow();
        assertThat(read.proposal().id()).isEqualTo(standing);
        assertThat(read.stillStands()).isTrue();
    }

    // ------------------------------------------------------------------ plumbing

    /** A four-eyes unlock of the period's present lock: one person proposes, another approves. */
    private static void unlock(AccountingPeriodId period) {
        Actor proposer = person();
        inScope(proposer, uow -> periods().proposeUnlock(uow, period, proposer, "a correction is owed", correlation()));
        Actor approver = person();
        inScope(approver, uow -> periods().approveUnlock(uow, period, periods().pendingUnlock(uow, period).orElseThrow(), approver,
                "a correction is owed", correlation()));
    }
}
