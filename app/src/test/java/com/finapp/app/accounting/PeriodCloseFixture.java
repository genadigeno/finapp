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
import static com.finapp.app.accounting.AccountingPeriodFixture.row;

import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.CheckVerdict;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.CloseRunId;
import com.finapp.accounting.CloseRuns;
import com.finapp.accounting.GlMappingAdministration;
import com.finapp.accounting.JdbcAccountingBreakStore;
import com.finapp.accounting.JdbcCloseRunStore;
import com.finapp.accounting.JdbcGlMappingStore;
import com.finapp.accounting.JdbcPeriodCloseStore;
import com.finapp.accounting.JdbcReportRunStore;
import com.finapp.accounting.JdbcValuationRateSetStore;
import com.finapp.accounting.PeriodCloseStatus;
import com.finapp.accounting.PeriodCloseStore;
import com.finapp.accounting.PeriodCloses;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.ValuationRateSets;
import com.finapp.accounting.ValuationRates;
import com.finapp.accounting.ValuationStaleness;
import com.finapp.accounting.reporting.CanonicalJson;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.ledger.PostingCutoffs;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.platform.testing.database.DatabaseRoles;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.YearMonth;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The close's and the reopen's suites against PostgreSQL (`P14-TSK-012`), each in a database of its own ({@code own-container}: they close January 1999 for real, and activate a GL mapping from it). The machine over the real stores; a period brought to a ready run through the real
 * {@code V007} attempts, whose verdicts are the suite's own, since what is proven here is the machine and {@code accounting V008}, and the judges are {@code CloseRunDatabaseTest}'s; the month's run is {@code QuietMonthDatabaseTest}'s, so every month ran here. A set is approved once per period for good ({@code V005}), so it is approved at most once here.
 */
final class PeriodCloseFixture {

    static final JdbcCloseRunStore RUN_STORE = new JdbcCloseRunStore();
    static final JdbcPeriodCloseStore CLOSES = new JdbcPeriodCloseStore();
    static final JdbcAccountingBreakStore BREAKS = new JdbcAccountingBreakStore();
    static final JdbcValuationRateSetStore SETS = new JdbcValuationRateSetStore();
    static final CloseRuns RUNS = new CloseRuns(RUN_STORE, AccountingPeriodFixture.STORE, BREAKS, CloseRuns.Leases.STANDARD, new JdbcAuditWriter(), IDS, NOW);
    static final PeriodCloses MACHINE = new PeriodCloses(AccountingPeriodFixture.STORE, CLOSES, RUNS, BREAKS, SETS, com.finapp.accounting.testing.MonthsThatRan.EVERY_MONTH, new LedgerPostingCutoff(new PostingCutoffs(IDS, NOW)),
            (unitOfWork, period, actor, correlation) -> new JdbcReportRunStore().supersedeFinal(unitOfWork, period, correlation), new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW);
    static final ValuationRateSets VALUATION = new ValuationRateSets(SETS, AccountingPeriodFixture.STORE, new OneRate(), ValuationStaleness.standard(), new JdbcAuditWriter(), IDS, NOW);
    static final Actor ACCOUNTANT = person();
    static final Actor CONTROLLER = person();
    static final String REASON = "read and agreed";

    private PeriodCloseFixture() {}

    /** A GL mapping version in force from January 1999 - a close needs a pin; activated while January is {@code OPEN}, once. */
    static void mappingInForce() {
        boolean present = inScope(Actor.SYSTEM, uow -> new JdbcGlMappingStore().inForceFor(uow, JANUARY).isPresent());
        if (present) {
            return;
        }
        GlMappingAdministration administration = new GlMappingAdministration(new JdbcGlMappingStore(), new LedgerPurposeChart(), new JdbcAuditWriter(), new JdbcOutboxWriter(), IDS, NOW);
        Actor proposer = person();
        GlMappingAdministration.Proposed proposed = inScope(proposer, uow -> administration.propose(uow, ReferenceGlMapping.version(JANUARY), "the suite's chart", proposer, correlation()));
        Actor approver = person();
        inScope(approver, uow -> administration.activate(uow, proposed.id(), approver, "the suite's chart", correlation()));
    }

    /** Every 1999 period back to {@code OPEN}, newest first, by the machine's own doors: nothing here is deleted, so a period's history simply grows. */
    static void everyPeriodOpen() {
        AccountingPeriodFixture.openJanuaryAndFebruary();
        for (YearMonth month : new YearMonth[] {FEBRUARY, JANUARY}) {
            AccountingPeriodStore.PeriodRow period = row(month);
            if (period.status() == PeriodStatus.CLOSED) {
                reopen(period.id());
            } else if (period.status() == PeriodStatus.CLOSE_PROPOSED) {
                Actor rejecter = person();
                PeriodCloseStore.CloseRow standing = inScope(rejecter, uow -> CLOSES.latest(uow, period.id())).orElseThrow();
                inScope(rejecter, uow -> MACHINE.reject(uow, period.id(), standing.id(), rejecter, "the suite restores the period", correlation()));
            }
            unlock(month);
        }
    }

    private static void unlock(YearMonth month) {
        AccountingPeriodStore.PeriodRow period = row(month);
        if (period.status() != PeriodStatus.LOCKED) {
            return;
        }
        if (!period.unlockPending()) {
            Actor proposer = person();
            inScope(proposer, uow -> periods().proposeUnlock(uow, period.id(), proposer, "the suite restores the period", correlation()));
        }
        Actor approver = person();
        inScope(approver, uow -> periods().approveUnlock(uow, period.id(), approver, "the suite restores the period", correlation()));
    }

    private static void reopen(AccountingPeriodId period) {
        Actor proposer = person();
        Optional<PeriodCloseStore.ReopenRow> pending = inScope(proposer, uow -> CLOSES.reopenAt(uow, period, rowOf(period).revision()));
        if (pending.isEmpty()) {
            inScope(proposer, uow -> MACHINE.proposeReopen(uow, period, proposer, "the suite restores the period", correlation()));
        }
        Actor approver = person();
        inScope(approver, uow -> MACHINE.approveReopen(uow, period, approver, "the suite restores the period", correlation()));
    }

    private static AccountingPeriodStore.PeriodRow rowOf(AccountingPeriodId period) {
        return inScope(Actor.SYSTEM, uow -> AccountingPeriodFixture.STORE.find(uow, period)).orElseThrow();
    }

    /** {@code month} locked. */
    static AccountingPeriodId locked(YearMonth month) {
        AccountingPeriodId period = id(month);
        Actor locker = person();
        inScope(locker, uow -> periods().lock(uow, period, locker, null, correlation()));
        return period;
    }

    /** The period's run started, and its approved set in place. */
    static CloseRunId started(AccountingPeriodId period) {
        Actor capturer = person();
        boolean approved = inScope(capturer, uow -> SETS.approvedFor(uow, period).isPresent());
        if (!approved) {
            ValuationRateSets.Valued captured = inScope(capturer, uow -> VALUATION.capture(uow, period, capturer, null, correlation()));
            Actor approver = person();
            inScope(approver, uow -> VALUATION.approve(uow, captured.id(), approver, "the rates agree", correlation()));
        }
        inScope(Actor.SYSTEM, uow -> RUNS.startMissing(uow, Actor.SYSTEM));
        AccountingPeriodStore.PeriodRow row = rowOf(period);
        return inScope(Actor.SYSTEM, uow -> RUN_STORE.runUnder(uow, period, row.lockedAt().orElseThrow())).orElseThrow().id();
    }

    /** Every claimable attempt concluded {@code PASSED}, those named {@code DISCLOSED}, those failing {@code FAILED}. */
    static void concludeAll(Set<CloseCheckCode> disclosed, Set<CloseCheckCode> failing) {
        while (true) {
            Optional<CloseRuns.ClaimedCheck> held = inScope(Actor.SYSTEM, uow -> RUNS.claim(uow, Actor.SYSTEM));
            if (held.isEmpty()) {
                return;
            }
            CloseRuns.ClaimedCheck claimed = held.get();
            inScope(Actor.SYSTEM, uow -> {
                if (claimed.check() == CloseCheckCode.OPEN_ACCOUNTING_BREAKS) {
                    return RUNS.concludeOpenBreaks(uow, claimed);
                }
                CanonicalJson.Obj evidence = CanonicalJson.object().put("attempt", claimed.attempt()).put("check", claimed.check().name());
                CheckVerdict verdict = failing.contains(claimed.check()) ? CheckVerdict.failed(claimed.check(), "judge:v1", List.of("SOMETHING"), evidence)
                        : disclosed.contains(claimed.check()) ? CheckVerdict.disclosed(claimed.check(), "judge:v1", evidence)
                        : CheckVerdict.passed(claimed.check(), "judge:v1", evidence);
                return RUNS.conclude(uow, claimed, verdict);
            });
        }
    }

    /** {@code month} locked, its run ready: every check {@code PASSED}, those named {@code DISCLOSED}. */
    static CloseRunId ready(YearMonth month, CloseCheckCode... disclosed) {
        AccountingPeriodId period = locked(month);
        CloseRunId run = started(period);
        Set<CloseCheckCode> named = disclosed.length == 0 ? EnumSet.noneOf(CloseCheckCode.class) : EnumSet.of(disclosed[0], disclosed);
        concludeAll(named, Set.of());
        return run;
    }

    /** {@code month} proposed, by {@link #ACCOUNTANT}, with no check disclosed. */
    static PeriodCloses.Acted proposed(YearMonth month) {
        CloseRunId run = ready(month);
        return inScope(ACCOUNTANT, uow -> MACHINE.propose(uow, id(month), run, List.of(), ACCOUNTANT, REASON, correlation()));
    }

    /** {@code month} closed, by {@link #ACCOUNTANT} and {@link #CONTROLLER}. */
    static void closed(YearMonth month) {
        PeriodCloses.Acted acted = proposed(month);
        inScope(CONTROLLER, uow -> MACHINE.approve(uow, id(month), acted.proposal().orElseThrow(), CONTROLLER, REASON, correlation()));
    }

    static PeriodCloseStatus proposalStatus(AccountingPeriodId period) {
        return inScope(Actor.SYSTEM, uow -> CLOSES.latest(uow, period)).orElseThrow().status();
    }

    static long count(String sql) throws SQLException {
        try (Connection migrator = DatabaseRoles.migrator(); PreparedStatement read = migrator.prepareStatement(sql); ResultSet row = read.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    /** The reference: one valued pair, observed the hour before the period's end. Provenance is {@code ValuationRateSetDatabaseTest}'s; here a set need only stand. */
    private static final class OneRate implements ValuationRates {

        private static final CurrencyCode USD = CurrencyCode.of("USD");

        @Override
        public List<CurrencyCode> valued() {
            return List.of(USD);
        }

        @Override
        public List<Observed> lastObservedBefore(Connection unitOfWork, Instant before) {
            return List.of(new Observed(USD, Optional.of(new Observation(UUID.randomUUID(), "simulated-reference", CurrencyCode.of("EUR"), USD, new BigDecimal("1.1734000000"),
                    before.minusSeconds(3_600), before.minusSeconds(3_598)))));
        }
    }
}
