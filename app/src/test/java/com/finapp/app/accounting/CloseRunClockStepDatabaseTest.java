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
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
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
@SuppressWarnings("try") // Scopes are used for their close side effect (the idiom).
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

    /**
     * The database's clock read {@link #BEHIND} behind, on demand, on one session (`P15-TSK-033`; lending's {@code ClockStep} and {@code
     * MerchantBnplEnablementDatabaseTest}'s shadow, which this package's suites cannot reach): the four clock functions shadowed in a schema of the bootstrap
     * superuser's, {@code closeclock} - its own name, so no other suite's install or drop meets it - which a session opts into by putting it before {@code
     * pg_catalog} on its {@code search_path}. Accounting's triggers are invoker's rights with no path of their own, so the production code and the production
     * triggers run unchanged on the shadowed clock. {@code closeclock.back}, set for the session before its transaction begins, is taken from every reading -
     * the transaction's start included - so the whole transaction stands where a clock stepped back by that much would put it. Only the opted-in session
     * sees it.
     */
    private static final class ShadowClock {

        /** Far beyond any real step measured (2.8 s) and any gap between two of this suite's acts, so the planted step alone decides the order. */
        static final Duration BEHIND = Duration.ofMinutes(10);

        private ShadowClock() {}

        static void install() throws SQLException {
            bootstrap("CREATE SCHEMA IF NOT EXISTS closeclock;"
                    + " GRANT USAGE ON SCHEMA closeclock TO PUBLIC;"
                    + " CREATE OR REPLACE FUNCTION closeclock.back() RETURNS interval LANGUAGE sql STABLE AS"
                    + " $$ SELECT coalesce(nullif(pg_catalog.current_setting('closeclock.back', true), ''), '0')::interval $$;"
                    + " CREATE OR REPLACE FUNCTION closeclock.transaction_timestamp() RETURNS timestamptz LANGUAGE sql STABLE"
                    + " AS $$ SELECT pg_catalog.transaction_timestamp() - closeclock.back() $$;"
                    + " CREATE OR REPLACE FUNCTION closeclock.now() RETURNS timestamptz LANGUAGE sql STABLE AS"
                    + " $$ SELECT pg_catalog.transaction_timestamp() - closeclock.back() $$;"
                    + " CREATE OR REPLACE FUNCTION closeclock.statement_timestamp() RETURNS timestamptz LANGUAGE sql STABLE"
                    + " AS $$ SELECT pg_catalog.statement_timestamp() - closeclock.back() $$;"
                    + " CREATE OR REPLACE FUNCTION closeclock.clock_timestamp() RETURNS timestamptz LANGUAGE sql VOLATILE AS"
                    + " $$ SELECT pg_catalog.clock_timestamp() - closeclock.back() $$;");
        }

        static void uninstall() throws SQLException {
            bootstrap("DROP SCHEMA IF EXISTS closeclock CASCADE");
        }

        /** {@code work} in one transaction of a fresh application-role session whose database clock reads {@link #BEHIND} behind, as {@code actor}. */
        static <R> R behind(Actor actor, Function<Connection, R> work) {
            try (SecurityContext.Scope who = SecurityContext.enter(actor);
                    CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(correlation()));
                    Connection connection = DatabaseRoles.application()) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET search_path = closeclock, pg_catalog, \"$user\", public");
                    statement.execute("SET closeclock.back = '" + BEHIND.toSeconds() + " seconds'");
                }
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

        private static void bootstrap(String sql) throws SQLException {
            try (Connection owner = DatabaseRoles.bootstrap(); Statement statement = owner.createStatement()) {
                statement.execute(sql);
            }
        }
    }
}
