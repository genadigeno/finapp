package com.finapp.app.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.accounting.AccountingBreakStore;
import com.finapp.accounting.AccountingBreaks;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.BreakFinding;
import com.finapp.accounting.BreakType;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.CloseRunStall;
import com.finapp.accounting.CloseRunStore;
import com.finapp.accounting.CloseRuns;
import com.finapp.accounting.ClosedMonthRuns;
import com.finapp.accounting.PriorPeriodAdjustmentId;
import com.finapp.accounting.PriorPeriodAdjustmentStore;
import com.finapp.accounting.ReconciliationRunCoverage;
import com.finapp.accounting.testing.InMemoryAccountingBreakStore;
import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryCloseRunStore;
import com.finapp.accounting.testing.InMemoryJournal;
import com.finapp.accounting.testing.InMemoryPriorPeriodAdjustmentStore;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The four gauges `P14-TSK-020` adds, over the in-memory stores: what each counts, what it must not, and that an unreadable database is no value. <strong>The in-memory stores restate the statements' rules</strong>, so this proves the gauges' wiring and the rules as restated;
 * the statements' words are {@code CloseRunStatementsTest}'s and {@code AccountingGaugeStatementsTest}'s, and what PostgreSQL answers is the database cases owed (C5).
 */
@DisplayName("the gauges P14-TSK-020 adds (P14-TSK-020)")
class AccountingNewGaugesTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2097-04-05T09:00:00Z"), ZoneOffset.UTC);
    private static final IdGenerator IDS = new IdGenerator(FIXED, new SecureRandom());
    private static final Actor ACCOUNTANT = new Actor("accountant-1", ActorType.CUSTOMER);
    private static final Actor CONTROLLER = new Actor("controller-1", ActorType.CUSTOMER);
    private static final Connection CONNECTION = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
    private static final CloseRunStall STALL = new CloseRunStall(Duration.ofSeconds(30), CloseRunStall.STANDARD_POLLS);
    private static final YearMonth MARCH = YearMonth.of(2097, 3);

    private final MovingClock clock = new MovingClock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final InMemoryAccountingPeriodStore periods = new InMemoryAccountingPeriodStore();
    private final InMemoryCloseRunStore runs = new InMemoryCloseRunStore(periods);
    private boolean unreadable;
    private AccountingPeriodId march;

    @BeforeEach
    void marchIsLockedAndItsRunBorn() {
        march = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(CONNECTION, new AccountingPeriodStore.NewPeriod(march, MARCH, CorrelationId.generate(IDS)));
        periods.markLocked(CONNECTION, march, 1, ACCOUNTANT, Optional.empty());
        Instant lockedAt = periods.find(CONNECTION, march).orElseThrow().lockedAt().orElseThrow();
        runs.advance(Duration.between(Instant.parse("2097-04-05T09:00:00Z"), lockedAt));
        new CloseRuns(runs, periods, new InMemoryAccountingBreakStore(), CloseRuns.Leases.STANDARD, (uow, record) -> { }, IDS, FIXED)
                .startMissing(CONNECTION, Actor.SYSTEM);
    }

    // ------------------------------------------------------------------ close.check.stalled

    @Test
    @DisplayName("close.check.stalled: silent inside the bound; past it the eight attempts nobody claimed, OPEN_ACCOUNTING_BREAKS not among them while a raising check stands, since its wait is a dependency's")
    void theStallSpeaksPastTheBoundAndNotForADependency() {
        new CloseCheckStallMetrics(runs, STALL, snapshots(), clock, registry);
        runs.advance(STALL.after().minusMillis(1));
        assertThat(gauge(CloseCheckStallMetrics.STALLED)).as("a millisecond inside the bound").isZero();
        runs.advance(Duration.ofMillis(1));
        clock.advance(Duration.ofSeconds(16));
        assertThat(gauge(CloseCheckStallMetrics.STALLED)).as("nine attempts, one held back by the claim").isEqualTo(8.0);
    }

    @Test
    @DisplayName("close.check.stalled: the raising checks concluded, the check held back behind them stands claimable and counts")
    void theHeldBackCheckCountsOnceReleased() {
        new CloseCheckStallMetrics(runs, STALL, snapshots(), clock, registry);
        UUID holder = UUID.randomUUID();
        for (int claimed = 0; claimed < 8; claimed++) {
            CloseRunStore.AttemptRow attempt = runs.claimNext(CONNECTION, holder, Duration.ofMinutes(2)).orElseThrow();
            if (attempt.check().raisesBreaks()) {
                runs.conclude(CONNECTION, attempt.id(), holder, com.finapp.accounting.CheckVerdict.passed(attempt.check(), "close-run:v1",
                        com.finapp.accounting.reporting.CanonicalJson.object().put("planted", true)));
            } else {
                runs.defer(CONNECTION, attempt.id(), holder, Duration.ZERO);
            }
        }
        runs.advance(STALL.after());
        assertThat(gauge(CloseCheckStallMetrics.STALLED)).as("five given back and the breaks' check released").isEqualTo(6.0);
    }

    @Test
    @DisplayName("close.check.stalled: a live claim is a leg at work, and the queue behind it no stall - until its lease lapses and the bound passes")
    void aLiveClaimIsALegAtWork() {
        new CloseCheckStallMetrics(runs, STALL, snapshots(), clock, registry);
        CloseRunStore.AttemptRow claimed = runs.claimNext(CONNECTION, UUID.randomUUID(), Duration.ofMinutes(10)).orElseThrow();
        assertThat(claimed.check()).isNotEqualTo(CloseCheckCode.OPEN_ACCOUNTING_BREAKS);
        runs.advance(STALL.after());
        assertThat(gauge(CloseCheckStallMetrics.STALLED)).as("seven behind a live worker").isZero();
        runs.advance(Duration.ofMinutes(10));
        clock.advance(Duration.ofSeconds(16));
        assertThat(gauge(CloseCheckStallMetrics.STALLED)).as("the holder died: its lease lapsed, and every attempt stands claimable past the bound").isEqualTo(8.0);
    }

    @Test
    @DisplayName("close.check.stalled: a deferred attempt moves its lease forward, so a set awaited reads no stall while the leg keeps coming back")
    void aDeferredAttemptIsNoStall() {
        new CloseCheckStallMetrics(runs, STALL, snapshots(), clock, registry);
        UUID holder = UUID.randomUUID();
        List<CloseRunStore.AttemptRow> claimed = new ArrayList<>();
        for (Optional<CloseRunStore.AttemptRow> next = runs.claimNext(CONNECTION, holder, Duration.ofMinutes(2)); next.isPresent();
                next = runs.claimNext(CONNECTION, holder, Duration.ofMinutes(2))) {
            claimed.add(next.get());
        }
        assertThat(claimed).hasSize(8);
        claimed.forEach(attempt -> runs.defer(CONNECTION, attempt.id(), holder, STALL.after().multipliedBy(2)));
        runs.advance(STALL.after());
        assertThat(gauge(CloseCheckStallMetrics.STALLED)).as("each deferred past now").isZero();
    }

    @Test
    @DisplayName("close.check.stalled: unreadable is no value, never zero; no tag; ten instances read one number")
    void theStallUnreadableAndAcrossInstances() {
        unreadable = true;
        new CloseCheckStallMetrics(runs, STALL, snapshots(), clock, registry);
        assertThat(gauge(CloseCheckStallMetrics.STALLED)).isNaN();
        assertThat(registry.get(CloseCheckStallMetrics.STALLED).gauge().getId().getTags()).isEmpty();
        unreadable = false;
        runs.advance(STALL.after());
        for (int instance = 0; instance < 10; instance++) {
            SimpleMeterRegistry own = new SimpleMeterRegistry();
            new CloseCheckStallMetrics(runs, STALL, snapshots(), clock, own);
            assertThat(own.get(CloseCheckStallMetrics.STALLED).gauge().value()).as("instance %s", instance).isEqualTo(8.0);
        }
    }

    // ------------------------------------------------------------------ break.aged

    @Test
    @DisplayName("break.aged: every type from the start at 0; a break OPEN past the bound counts under its type; acknowledged, it never does - that is what break.open still counts, and why no alert reads that")
    void aBreakAgesOnlyWhileOpen() {
        InMemoryAccountingBreakStore store = new InMemoryAccountingBreakStore();
        AccountingBreaks breaks = new AccountingBreaks(store, new InMemoryJournal(), (uow, record) -> { }, (uow, envelope, payload, mediaType) -> { }, IDS, clock);
        new AccountingBreakAgeMetrics(store, Duration.ofDays(7), snapshots(), clock, registry);
        new AccountingBreakMetrics(breaks, snapshots(), clock, registry);
        for (BreakType type : BreakType.values()) {
            assertThat(registry.get(AccountingBreakAgeMetrics.AGED).tag("type", type.name()).gauge().value()).as(type.name()).isZero();
        }
        breaks.raise(CONNECTION, MARCH, new BreakFinding(CloseCheckCode.GL_EQUALS_LEDGER, BreakType.MAPPING_GAP, "key:A/OPERATIONAL", "evidence", "gl-mapping:v1"), Actor.SYSTEM, CorrelationId.generate(IDS));
        store.advance(Duration.ofDays(7).minusSeconds(2));
        clock.advance(Duration.ofSeconds(16));
        assertThat(registry.get(AccountingBreakAgeMetrics.AGED).tag("type", "MAPPING_GAP").gauge().value()).as("inside the week").isZero();
        store.advance(Duration.ofSeconds(2));
        clock.advance(Duration.ofSeconds(16));
        assertThat(registry.get(AccountingBreakAgeMetrics.AGED).tag("type", "MAPPING_GAP").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get(AccountingBreakAgeMetrics.AGED).tag("type", "SHAPE_MISMATCH").gauge().value()).isZero();
        assertThat(registry.get(AccountingBreakMetrics.OPEN).tag("type", "MAPPING_GAP").gauge().value()).isEqualTo(1.0);

        AccountingBreakStore.BreakRow raised = store.rows().stream().findFirst().orElseThrow();
        assertThat(store.proposeAcknowledgement(CONNECTION, raised.id(), ACCOUNTANT, "disclosed")).isTrue();
        assertThat(store.approveAcknowledgement(CONNECTION, raised.id(), CONTROLLER, "agreed")).isTrue();
        clock.advance(Duration.ofSeconds(16));
        assertThat(registry.get(AccountingBreakAgeMetrics.AGED).tag("type", "MAPPING_GAP").gauge().value()).as("decided: it asks nothing of anyone").isZero();
        assertThat(registry.get(AccountingBreakMetrics.OPEN).tag("type", "MAPPING_GAP").gauge().value()).as("and it stands in the count, for good").isEqualTo(1.0);
    }

    @Test
    @DisplayName("break.aged: unreadable is no value; a bound of nothing is refused at start-up, since every break raised would read aged")
    void theAgedGaugeUnreadableAndItsBound() {
        unreadable = true;
        new AccountingBreakAgeMetrics(new InMemoryAccountingBreakStore(), Duration.ofDays(7), snapshots(), clock, registry);
        assertThat(registry.get(AccountingBreakAgeMetrics.AGED).tag("type", "MAPPING_GAP").gauge().value()).isNaN();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AccountingBreakAgeMetrics(new InMemoryAccountingBreakStore(), Duration.ZERO, snapshots(), clock, new SimpleMeterRegistry()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ adjustment.standing

    @Test
    @DisplayName("adjustment.standing: PROPOSED past the bound counts, a decided one never; no tag; unreadable is no value")
    void aProposalStands() {
        AccountingPeriodId april = AccountingPeriodId.next(IDS);
        periods.insertIfAbsent(CONNECTION, new AccountingPeriodStore.NewPeriod(april, MARCH.plusMonths(1), CorrelationId.generate(IDS)));
        InMemoryPriorPeriodAdjustmentStore store = new InMemoryPriorPeriodAdjustmentStore(periods);
        new PriorPeriodAdjustmentMetrics(store, Duration.ofDays(7), snapshots(), clock, registry);
        assertThat(gauge(PriorPeriodAdjustmentMetrics.STANDING)).isZero();
        PriorPeriodAdjustmentId standing = propose(store, april);
        PriorPeriodAdjustmentId decided = propose(store, april);
        store.advance(Duration.ofDays(7).minusSeconds(1));
        clock.advance(Duration.ofSeconds(16));
        assertThat(gauge(PriorPeriodAdjustmentMetrics.STANDING)).as("the first is at its week, the second a second inside it").isEqualTo(1.0);
        store.advance(Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(16));
        assertThat(gauge(PriorPeriodAdjustmentMetrics.STANDING)).isEqualTo(2.0);
        assertThat(store.markRejected(CONNECTION, decided, ACCOUNTANT, "no longer owed")).isTrue();
        clock.advance(Duration.ofSeconds(16));
        assertThat(gauge(PriorPeriodAdjustmentMetrics.STANDING)).as("only a person's decision ends it").isEqualTo(1.0);
        assertThat(store.find(CONNECTION, standing)).isPresent();
        assertThat(registry.get(PriorPeriodAdjustmentMetrics.STANDING).gauge().getId().getTags()).isEmpty();
        unreadable = true;
        clock.advance(Duration.ofSeconds(16));
        assertThat(gauge(PriorPeriodAdjustmentMetrics.STANDING)).isNaN();
    }

    // ------------------------------------------------------------------ close.month.unrun

    private static final UUID SOURCE = UUID.randomUUID();
    private static final UUID NEWCOMER = UUID.randomUUID();

    @Test
    @DisplayName("close.month.unrun: no closed month is 0; the latest closed month's source with a repudiated run and no counting run is 1; a counting run beside it is 0")
    void aRepudiationTakesTheRun() {
        LineSource coverage = new LineSource();
        new ClosedMonthRunMetrics(new ClosedMonthRuns(periods, coverage), snapshots(), clock, registry);
        assertThat(gauge(ClosedMonthRunMetrics.UNRUN)).as("March is LOCKED: nothing is closed").isZero();
        periods.closeForTest(MARCH);
        coverage.lines.add(line(SOURCE, "COMPLETED", true));
        clock.advance(Duration.ofSeconds(61));
        assertThat(gauge(ClosedMonthRunMetrics.UNRUN)).isEqualTo(1.0);
        assertThat(coverage.asked).containsOnly(MARCH);
        coverage.lines.add(line(SOURCE, "COMPLETED", false));
        clock.advance(Duration.ofSeconds(61));
        assertThat(gauge(ClosedMonthRunMetrics.UNRUN)).as("a corrected file's run counts").isZero();
    }

    @Test
    @DisplayName("close.month.unrun: a source admitted after the close has no run and lost none, so it never counts - MonthRuns.ran alone would read false; unreadable is no value")
    void aNewcomerIsNoRepudiation() {
        LineSource coverage = new LineSource();
        coverage.sources.add(new ReconciliationRunCoverage.Source(NEWCOMER, "newcomer", ReconciliationRunCoverage.SourceState.ACTIVE));
        periods.closeForTest(MARCH);
        new ClosedMonthRunMetrics(new ClosedMonthRuns(periods, coverage), snapshots(), clock, registry);
        assertThat(gauge(ClosedMonthRunMetrics.UNRUN)).isZero();
        SimpleMeterRegistry second = new SimpleMeterRegistry();
        unreadable = true;
        new ClosedMonthRunMetrics(new ClosedMonthRuns(periods, coverage), snapshots(), clock, second);
        assertThat(second.get(ClosedMonthRunMetrics.UNRUN).gauge().value()).isNaN();
    }

    private static ReconciliationRunCoverage.Line line(UUID source, String status, boolean repudiated) {
        return new ReconciliationRunCoverage.Line(source, ReconciliationRunCoverage.Coverage.BATCH, status, LocalDate.of(2097, 3, 31), repudiated, 1);
    }

    /** A month's runs as a test states them; the source register holds {@link #SOURCE} unless told more. */
    private static final class LineSource implements ReconciliationRunCoverage {
        final List<Line> lines = new ArrayList<>();
        final List<Source> sources = new ArrayList<>(List.of(new Source(SOURCE, "source", SourceState.ACTIVE)));
        final List<YearMonth> asked = new ArrayList<>();

        @Override
        public Coverage ofMonth(Connection connection, YearMonth month) {
            asked.add(month);
            return new Coverage(month, lines, sources);
        }
    }

    // ------------------------------------------------------------------

    private PriorPeriodAdjustmentId propose(InMemoryPriorPeriodAdjustmentStore store, AccountingPeriodId target) {
        PriorPeriodAdjustmentId id = PriorPeriodAdjustmentId.next(IDS);
        store.insert(CONNECTION, new PriorPeriodAdjustmentStore.NewAdjustment(id, march, 1, target, 1, LocalDate.of(2097, 4, 5), UUID.randomUUID(), ACCOUNTANT, "a correction", CorrelationId.generate(IDS)));
        return id;
    }

    private GlSnapshots snapshots() {
        return new GlSnapshots(this::open, Duration.ofSeconds(2));
    }

    private Connection open() throws SQLException {
        if (unreadable) {
            throw new SQLException("the database is away", "08006");
        }
        return new SnapshotSession().connection();
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    private static final class MovingClock extends Clock {
        private Instant now = Instant.parse("2097-04-05T09:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
