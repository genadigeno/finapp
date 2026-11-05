package com.finapp.app.accounting;

import static com.finapp.app.accounting.AccountingPeriodFixture.IDS;
import static com.finapp.app.accounting.AccountingPeriodFixture.JANUARY;
import static com.finapp.app.accounting.AccountingPeriodFixture.NOW;
import static com.finapp.app.accounting.AccountingPeriodFixture.correlation;
import static com.finapp.app.accounting.AccountingPeriodFixture.id;
import static com.finapp.app.accounting.AccountingPeriodFixture.inScope;
import static com.finapp.app.accounting.AccountingPeriodFixture.periods;
import static com.finapp.app.accounting.AccountingPeriodFixture.person;
import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.CheckVerdict;
import com.finapp.accounting.CloseRunStore;
import com.finapp.accounting.CloseRuns;
import com.finapp.accounting.JdbcAccountingBreakStore;
import com.finapp.accounting.JdbcCloseRunStore;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * {@code finapp.accounting.close.run.stalled}'s read against a live PostgreSQL (`P14-TSK-017`; the owner's decision of 2026-11-02):
 * on the database's clock, within the bound silent, past it one, cleared by the run's birth and by an unlock - the statement every
 * instance runs, read-only. Uses only the fixture's January 1999 ({@link AccountingPeriodFixture}: `P14-TSK-012`'s close suites close it only in databases
 * of their own, so a shared database can lock only January), returning it to {@code OPEN}; every other row a shared database holds is not {@code LOCKED} before this
 * suite locks. <strong>Written and owed</strong> (owner decision C5).
 */
@Tag("database")
@DisplayName("the stalled close run's read against the database (P14-TSK-017)")
class CloseRunStallDatabaseTest {

    private static final JdbcCloseRunStore STORE = new JdbcCloseRunStore();

    @BeforeEach
    void open() {
        AccountingPeriodFixture.restore();
    }

    @AfterEach
    void leaveOpen() {
        AccountingPeriodFixture.restore();
    }

    @Test
    @DisplayName("a lock with no run: silent within the bound, counted past it - and the run's birth clears it")
    void aLockWithNoRun() throws Exception {
        lockJanuary();
        assertThat(stalled(Duration.ofHours(1))).as("just locked: inside an hour's bound").isZero();
        // A bound so short the lock is already past it: a lock is committed well over a millisecond before this read.
        assertThat(stalled(Duration.ofMillis(1))).isEqualTo(1);

        inScope(Actor.SYSTEM, uow -> new CloseRuns(STORE, AccountingPeriodFixture.STORE, new JdbcAccountingBreakStore(),
                CloseRuns.Leases.STANDARD, new JdbcAuditWriter(), IDS, NOW).startMissing(uow, Actor.SYSTEM));
        assertThat(stalled(Duration.ofMillis(1))).as("the check leg ran").isZero();
    }

    @Test
    @DisplayName("an unlock clears it: only a period LOCKED now counts, and a run an unlock left behind answers no later lock")
    void anUnlockClearsIt() throws Exception {
        lockJanuary();
        assertThat(stalled(Duration.ofMillis(1))).isEqualTo(1);
        AccountingPeriodFixture.restore();
        assertThat(stalled(Duration.ofMillis(1))).isZero();

        lockJanuary();
        inScope(Actor.SYSTEM, uow -> new CloseRuns(STORE, AccountingPeriodFixture.STORE, new JdbcAccountingBreakStore(),
                CloseRuns.Leases.STANDARD, new JdbcAuditWriter(), IDS, NOW).startMissing(uow, Actor.SYSTEM));
        assertThat(stalled(Duration.ofMillis(1))).isZero();
        AccountingPeriodFixture.restore();
        lockJanuary();
        assertThat(stalled(Duration.ofMillis(1))).as("the new lock has no run: the old run answers the old lock").isEqualTo(1);
    }

    @Test
    @DisplayName("ten instances, one number: the read needs no leader and takes no lock, on a read-only connection")
    void tenInstancesOneNumber() throws Exception {
        lockJanuary();
        for (int instance = 0; instance < 10; instance++) {
            assertThat(stalled(Duration.ofMillis(1))).as("instance %s", instance).isEqualTo(1);
        }
    }

    /**
     * `P14-TSK-020`, the owner's ruling on `P14-TSK-017`'s I-5: the statement every instance runs. <strong>Walked against JUnit's order</strong>: {@code @BeforeEach} and {@code @AfterEach} return January to {@code OPEN}, so an earlier case's run is left behind under a lock
     * no longer present, and neither {@code candidate} nor {@code live} reads it; each case's run is its own, and nine attempts are born per run.
     */
    @Test
    @DisplayName("a run's attempts: silent within the bound; past it eight, OPEN_ACCOUNTING_BREAKS held back as the claim holds it; a live claim holds the count at zero; given back, the count returns")
    void aRunsAttemptsStand() throws Exception {
        lockJanuary();
        startTheRun();
        assertThat(stalledAttempts(Duration.ofHours(1))).as("just born: inside an hour's bound").isZero();
        assertThat(stalledAttempts(Duration.ofMillis(1))).as("nine born, one held back behind the raising checks").isEqualTo(8);

        UUID holder = UUID.randomUUID();
        CloseRunStore.AttemptRow claimed = inScope(Actor.SYSTEM, uow -> STORE.claimNext(uow, holder, Duration.ofMinutes(10))).orElseThrow();
        assertThat(stalledAttempts(Duration.ofMillis(1))).as("a live claim: a leg at work").isZero();

        boolean givenBack = inScope(Actor.SYSTEM, uow -> STORE.defer(uow, claimed.id(), holder, Duration.ZERO));
        assertThat(givenBack).isTrue();
        assertThat(stalledAttempts(Duration.ofMillis(1))).as("given back, and nobody came").isEqualTo(8);
    }

    @Test
    @DisplayName("the raising checks concluded, OPEN_ACCOUNTING_BREAKS stands claimable like any other, and counts: the claim's own predicate")
    void theHeldBackCheckCountsOnceReleased() throws Exception {
        lockJanuary();
        startTheRun();
        UUID holder = UUID.randomUUID();
        for (int claimed = 0; claimed < 8; claimed++) {
            CloseRunStore.AttemptRow attempt = inScope(Actor.SYSTEM, uow -> STORE.claimNext(uow, holder, Duration.ofMinutes(10))).orElseThrow();
            boolean moved = inScope(Actor.SYSTEM, uow -> attempt.check().raisesBreaks()
                    ? STORE.conclude(uow, attempt.id(), holder, CheckVerdict.passed(attempt.check(), "close-run:v1", CanonicalJson.object().put("planted", true)))
                    : STORE.defer(uow, attempt.id(), holder, Duration.ZERO));
            assertThat(moved).as(attempt.check().name()).isTrue();
        }
        assertThat(stalledAttempts(Duration.ofMillis(1))).as("five given back, and the breaks' check released").isEqualTo(6);
    }

    @Test
    @DisplayName("an unlock leaves the run behind: nobody's, and no count; and a run left behind holds no live claim over a new one")
    void aRunAnUnlockLeftBehindIsNobodys() throws Exception {
        lockJanuary();
        startTheRun();
        UUID holder = UUID.randomUUID();
        inScope(Actor.SYSTEM, uow -> STORE.claimNext(uow, holder, Duration.ofMinutes(10))).orElseThrow();
        AccountingPeriodFixture.restore();
        assertThat(stalledAttempts(Duration.ofMillis(1))).as("no month LOCKED").isZero();

        lockJanuary();
        startTheRun();
        assertThat(stalledAttempts(Duration.ofMillis(1))).as("the old run's live claim proves nothing about this one").isEqualTo(8);
    }

    private static void startTheRun() {
        inScope(Actor.SYSTEM, uow -> new CloseRuns(STORE, AccountingPeriodFixture.STORE, new JdbcAccountingBreakStore(),
                CloseRuns.Leases.STANDARD, new JdbcAuditWriter(), IDS, NOW).startMissing(uow, Actor.SYSTEM));
    }

    private static long stalledAttempts(Duration bound) throws SQLException {
        try (Connection connection = DatabaseRoles.application()) {
            connection.setReadOnly(true);
            return STORE.stalledAttempts(connection, bound);
        }
    }

    private static long stalled(Duration bound) throws SQLException {
        try (Connection connection = DatabaseRoles.application()) {
            connection.setReadOnly(true);
            return STORE.stalledLocks(connection, bound);
        }
    }

    private static AccountingPeriodId lockJanuary() {
        AccountingPeriodId january = id(JANUARY);
        Actor accountant = person();
        inScope(accountant, uow -> periods().lock(uow, january, accountant, null, correlation()));
        return january;
    }
}
