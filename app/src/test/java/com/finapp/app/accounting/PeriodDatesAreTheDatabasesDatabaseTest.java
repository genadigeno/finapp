package com.finapp.app.accounting;

import static com.finapp.app.accounting.AccountingPeriodFixture.JANUARY;
import static com.finapp.app.accounting.AccountingPeriodFixture.NOW;
import static com.finapp.app.accounting.AccountingPeriodFixture.STORE;
import static com.finapp.app.accounting.AccountingPeriodFixture.correlation;
import static com.finapp.app.accounting.AccountingPeriodFixture.id;
import static com.finapp.app.accounting.AccountingPeriodFixture.inScope;
import static com.finapp.app.accounting.AccountingPeriodFixture.on;
import static com.finapp.app.accounting.AccountingPeriodFixture.onInstanceClock;
import static com.finapp.app.accounting.AccountingPeriodFixture.periods;
import static com.finapp.app.accounting.AccountingPeriodFixture.person;
import static com.finapp.app.accounting.AccountingPeriodFixture.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.LockGrace;
import com.finapp.accounting.PeriodStatus;
import com.finapp.platform.security.Actor;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * A period's dates on the database's own clock (`P14-REM-009`, the Phase 14 exit review's A4-F1; {@code INV-ACC-08}): the store's real reading, on a session whose clock {@link ShadowClock} has moved, with this instance's clock held apart from it. Each case asserts its
 * precondition - that the two clocks really do disagree - before it asserts the judgement, so a clock that never moved cannot pass as one that did.
 *
 * <p><strong>Written and owed</strong> (C14: the database tiers run once, at {@code P15-TST-002}). <strong>A database of its own</strong> ({@code own-container}): it locks January 1999 and opens months after February, as the suites of 1999 do. JUnit runs the cases in its
 * default order, and none depends on another: {@link #januaryIsOpen} returns January to {@code OPEN} through the machine's own doors before each, and the open leg's cases read the latest month rather than name one.
 */
@Tag("database")
@Tag("own-container") // its own JVM and database: ownContainerDatabaseTest
@DisplayName("a period's dates are the database's, whatever an instance's clock reads (P14-REM-009)")
class PeriodDatesAreTheDatabasesDatabaseTest {

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

    private static LocalDate lockableFrom() {
        return LockGrace.standard().lockableFrom(JANUARY);
    }

    private static LocalDate instanceDate(Clock clock) {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    @Test
    @DisplayName("inside the grace on the database's clock, refused and nothing moved, though this instance's clock is decades past it")
    void insideTheGraceOnTheDatabasesClockIsRefused() {
        AccountingPeriodId january = id(JANUARY);
        Actor accountant = person();
        LocalDate insideIt = lockableFrom().minusDays(1);
        assertThat(instanceDate(NOW)).as("the precondition: this instance's clock is past the grace").isAfter(lockableFrom());
        assertThat(ShadowClock.on(insideIt, accountant, STORE::today)).as("the precondition: the store's real reading, on the stepped session, is inside it").isEqualTo(insideIt);

        assertThatThrownBy(() -> ShadowClock.on(insideIt, accountant, uow -> periods().lock(uow, january, accountant, null, correlation())))
                .isInstanceOf(AccountingPeriods.PeriodNotLockable.class).hasMessageContaining(lockableFrom().toString());

        assertThat(row(JANUARY).status()).isEqualTo(PeriodStatus.OPEN);
    }

    @Test
    @DisplayName("past the grace on the database's clock it locks, though this instance's clock says the grace has a day to run")
    void pastTheGraceOnTheDatabasesClockLocks() {
        AccountingPeriodId january = id(JANUARY);
        Actor accountant = person();
        Clock insideIt = on(lockableFrom().minusDays(1).toString());
        assertThat(instanceDate(insideIt)).as("the precondition: this instance's clock is inside the grace").isBefore(lockableFrom());
        assertThat(inScope(accountant, STORE::today)).as("the precondition: the database's is past it").isAfter(lockableFrom());

        assertThat(inScope(accountant, uow -> onInstanceClock(insideIt).lock(uow, january, accountant, null, correlation())).status()).isEqualTo(PeriodStatus.LOCKED);
    }

    @Test
    @DisplayName("the open leg opens through the database's month and no further: an instance decades ahead of it would have opened every month to the present")
    void theOpenLegStopsAtTheDatabasesMonth() {
        YearMonth next = inScope(Actor.SYSTEM, STORE::latest).orElseThrow().plusMonths(1);
        LocalDate within = next.atDay(15);
        assertThat(instanceDate(NOW)).as("the precondition: this instance's clock is past that month").isAfter(next.atEndOfMonth());
        assertThat(ShadowClock.on(within, Actor.SYSTEM, STORE::today)).as("the precondition: the store's real reading, on the stepped session").isEqualTo(within);

        List<YearMonth> born = ShadowClock.on(within, Actor.SYSTEM, uow -> periods().openNext(uow, Actor.SYSTEM, correlation()));

        assertThat(born).containsExactly(next);
    }

    @Test
    @DisplayName("the open leg opens nothing early: on the database's clock the latest month is the present one, whatever this instance's clock reads")
    void theOpenLegOpensNothingEarly() {
        YearMonth latest = inScope(Actor.SYSTEM, STORE::latest).orElseThrow();
        LocalDate within = latest.atDay(15);
        assertThat(instanceDate(NOW)).as("the precondition: this instance's clock is past that month").isAfter(latest.atEndOfMonth());

        List<YearMonth> born = ShadowClock.on(within, Actor.SYSTEM, uow -> periods().openNext(uow, Actor.SYSTEM, correlation()));

        assertThat(born).isEmpty();
    }
}
