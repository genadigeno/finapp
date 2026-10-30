package com.finapp.app.accounting;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.JdbcAccountingPeriodStore;
import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.LockGrace;
import com.finapp.accounting.PeriodStatus;
import com.finapp.ledger.PostingCutoffs;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * The accounting periods against the suite's shared database (`P14-TSK-005`), for {@code AccountingPeriodDatabaseTest}
 * and {@code GlMappingDatabaseTest}: the periods and the ledger's cutoff are global rows, never deleted, so - the
 * {@code PostingCutoffDatabaseTest} precedent - only <strong>January and February 1999</strong> are ever opened (a clock
 * in 1999, so nothing another suite posts in 2026 is ever refused), and {@link #restore} returns every one of them to
 * {@code OPEN} through the machine's own four-eyes unlock, leaving the cutoff unset.
 */
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
final class AccountingPeriodFixture {

    static final Clock NOW = Clock.systemUTC();
    static final IdGenerator IDS = new IdGenerator(NOW, new SecureRandom());
    static final YearMonth JANUARY = YearMonth.of(1999, 1);
    static final YearMonth FEBRUARY = YearMonth.of(1999, 2);
    static final JdbcAccountingPeriodStore STORE = new JdbcAccountingPeriodStore();

    private AccountingPeriodFixture() {}

    /** The machine judged on {@code clock} - the grace, the open - over the ledger's own cutoff API on the real clock. */
    static AccountingPeriods periods(Clock clock) {
        return new AccountingPeriods(STORE, new JdbcGlMappingStore(), new LedgerPostingCutoff(new PostingCutoffs(IDS, NOW)),
                LockGrace.standard(), new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, clock);
    }

    static AccountingPeriods periods() {
        return periods(NOW);
    }

    static Clock on(String day) {
        return Clock.fixed(Instant.parse(day + "T12:00:00Z"), ZoneOffset.UTC);
    }

    /** January and February 1999 exist (the first periods this database holds, or already there). */
    static void openJanuaryAndFebruary() {
        for (String day : new String[] {"1999-01-15", "1999-02-15"}) {
            inScope(Actor.SYSTEM, uow -> periods(on(day)).openNext(uow, Actor.SYSTEM, correlation()));
        }
    }

    static AccountingPeriodId id(YearMonth month) {
        return row(month).id();
    }

    static AccountingPeriodStore.PeriodRow row(YearMonth month) {
        return inScope(Actor.SYSTEM, uow -> STORE.recent(uow, 1000).stream()
                .filter(row -> row.month().equals(month)).findFirst().orElseThrow());
    }

    /** Every 1999 period back to {@code OPEN} through the four-eyes unlock - the cutoff unset with it. */
    static void restore() {
        openJanuaryAndFebruary();
        for (YearMonth month : new YearMonth[] {FEBRUARY, JANUARY}) {
            AccountingPeriodStore.PeriodRow row = row(month);
            if (row.status() != PeriodStatus.LOCKED) {
                continue;
            }
            if (!row.unlockPending()) {
                inScope(person(), uow -> periods().proposeUnlock(uow, row.id(), SecurityContext.require(),
                        "the test restores the period", correlation()));
            }
            inScope(person(), uow -> periods().approveUnlock(uow, row.id(), SecurityContext.require(),
                    "the test restores the period", correlation()));
        }
    }

    static Actor person() {
        return new Actor("operator-" + UUID.randomUUID(), ActorType.CUSTOMER);
    }

    static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }

    /** {@code work} in one transaction, as {@code actor}, in a correlation scope - the ledger's move reads both. */
    static <R> R inScope(Actor actor, Function<Connection, R> work) {
        return inScope(actor, correlation(), work);
    }

    static <R> R inScope(Actor actor, CorrelationId correlation, Function<Connection, R> work) {
        try (SecurityContext.Scope who = SecurityContext.enter(actor);
                CorrelationContext.Scope flow = CorrelationContext.enter(Correlation.startingWith(correlation));
                Connection connection = DatabaseRoles.application()) {
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

    /** The ledger's cutoff now. */
    static Optional<java.time.LocalDate> closedThrough() {
        return inScope(Actor.SYSTEM, uow -> new PostingCutoffs(IDS, NOW).closedThrough(uow));
    }
}
