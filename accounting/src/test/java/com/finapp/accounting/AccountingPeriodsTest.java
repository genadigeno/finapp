package com.finapp.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.testing.InMemoryAccountingPeriodStore;
import com.finapp.accounting.testing.InMemoryGlMappingStore;
import com.finapp.accounting.testing.ReferenceGlMapping;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.id.IdGenerator;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The accounting period's machine - open, lock, unlock (`P14-TSK-005`; ADR-0114 decision 3; {@code PHASE_14_PLAN.md}
 * sections 6, 8.1, 13.5; {@code INV-ACC-03}, {@code -07}, {@code -08}, {@code INV-AUD-04}): contiguous months opened once;
 * the lock in order, after the grace, pinning the version in force under the mapping key shared and advancing the
 * ledger's cutoff in the same act - a stand-in ledger here refuses what the cutoff covers; a second lock converges; the
 * unlock four-eyes, moving the cutoff back. Hermetic: the stores, the cutoff and the clock are in memory; the live ranks
 * are {@code AccountingPeriodDatabaseTest}'s, owed (C5).
 */
@DisplayName("the accounting period's machine: open, lock, unlock (P14-TSK-005)")
class AccountingPeriodsTest {

    private static final IdGenerator IDS =
            new IdGenerator(Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneOffset.UTC), new SecureRandom());
    private static final Connection UOW = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class}, (proxy, method, args) -> null);
    private static final YearMonth AUGUST = YearMonth.of(2026, 8);
    private static final YearMonth SEPTEMBER = YearMonth.of(2026, 9);

    private final InMemoryAccountingPeriodStore store = new InMemoryAccountingPeriodStore();
    private final InMemoryGlMappingStore mappings = new InMemoryGlMappingStore();
    private final List<AuditRecord> audit = new ArrayList<>();
    private final List<EventEnvelope> events = new ArrayList<>();
    private final List<byte[]> payloads = new ArrayList<>();
    private final List<String> cutoffMoves = new ArrayList<>();
    private Optional<LocalDate> closedThrough = Optional.empty();

    /** The ledger's cutoff, as the port promises it: one period, from where it stands; a replay answers false. */
    private final PostingCutoff cutoff = new PostingCutoff() {
        @Override
        public Optional<LocalDate> closedThrough(Connection unitOfWork) {
            return closedThrough;
        }

        @Override
        public boolean advance(Connection unitOfWork, Optional<LocalDate> from, LocalDate through, String reason) {
            if (closedThrough.equals(Optional.of(through))) {
                return false;
            }
            if (!closedThrough.equals(from)) {
                throw new IllegalStateException("the posting cutoff stands elsewhere");
            }
            closedThrough = Optional.of(through);
            cutoffMoves.add("advance " + from.map(LocalDate::toString).orElse("unset") + " -> " + through);
            return true;
        }

        @Override
        public boolean moveBack(Connection unitOfWork, LocalDate from, Optional<LocalDate> to, String reason) {
            if (closedThrough.equals(to)) {
                return false;
            }
            if (!closedThrough.equals(Optional.of(from))) {
                throw new IllegalStateException("the posting cutoff stands elsewhere");
            }
            closedThrough = to;
            cutoffMoves.add("back " + from + " -> " + to.map(LocalDate::toString).orElse("unset"));
            return true;
        }
    };

    private final Actor accountant = new Actor("accountant-1", ActorType.CUSTOMER);
    private final Actor controller = new Actor("controller-1", ActorType.CUSTOMER);

    AccountingPeriodsTest() {
        mappings.pinnedPeriods(store::firstPinnedFrom);
    }

    @Test
    @DisplayName("open next: today's month when none exists, then every month through today's - contiguous, each born"
            + " once, evented and audited once; a repeat opens nothing")
    void openNextOpensContiguousMonthsOnce() {
        assertThat(periods("2026-10-30").openNext(UOW, Actor.SYSTEM, correlation())).containsExactly(YearMonth.of(2026, 10));
        assertThat(periods("2026-10-31").openNext(UOW, Actor.SYSTEM, correlation())).isEmpty();
        assertThat(periods("2027-01-04").openNext(UOW, Actor.SYSTEM, correlation()))
                .containsExactly(YearMonth.of(2026, 11), YearMonth.of(2026, 12), YearMonth.of(2027, 1));
        assertThat(store.all()).extracting(AccountingPeriodStore.PeriodRow::status).containsOnly(PeriodStatus.OPEN);
        assertThat(events).extracting(EventEnvelope::eventType).hasSize(4).containsOnly(AccountingPeriods.OPENED_EVENT);
        assertThat(audit).extracting(record -> record.operation().code()).hasSize(4)
                .containsOnly("accounting.PeriodOpened");
        assertThat(store.history()).extracting(AccountingPeriodStore.EventRow::act)
                .containsOnly(AccountingPeriodStore.Act.OPENED);
        assertThat(periods("2027-01-04").firstOpen(UOW)).map(AccountingPeriodStore.PeriodRow::month)
                .contains(YearMonth.of(2026, 10));
    }

    @Test
    @DisplayName("from the lock, no entry dated in the period is admitted: the cutoff advanced to the period end in the"
            + " lock itself - the day after is admitted (the backlog's probe target, hermetic half)")
    void aLockStopsPostingsDatedInThePeriod() {
        AccountingPeriodId september = open(SEPTEMBER);
        assertThat(ledgerAdmits(LocalDate.of(2026, 9, 20))).isTrue();

        AccountingPeriods.Acted locked = periods("2026-10-30").lock(UOW, september, accountant, null, correlation());

        assertThat(locked.status()).isEqualTo(PeriodStatus.LOCKED);
        assertThat(ledgerAdmits(LocalDate.of(2026, 9, 20))).as("INV-ACC-08: dated in the locked period").isFalse();
        assertThat(ledgerAdmits(LocalDate.of(2026, 9, 30))).as("its last day").isFalse();
        assertThat(ledgerAdmits(LocalDate.of(2026, 10, 1))).as("the next period stays open").isTrue();
        assertThat(cutoffMoves).containsExactly("advance unset -> 2026-09-30");
        assertThat(events).extracting(EventEnvelope::eventType).last().isEqualTo(AccountingPeriods.LOCKED_EVENT);
        assertThat(new String(payloads.get(payloads.size() - 1), StandardCharsets.UTF_8))
                .contains("2026-09").contains("2026-09-30").doesNotContain("accountant-1");
        assertThat(audit).extracting(record -> record.operation().code()).last().isEqualTo("accounting.PeriodLocked");
    }

    @Test
    @DisplayName("two locks, one edge: the second re-reads LOCKED and converges - no second history row, event, audit"
            + " record or cutoff move")
    void twoLocksOneEdge() {
        AccountingPeriodId september = open(SEPTEMBER);
        int eventsBefore = events.size();
        AccountingPeriods.Acted first = periods("2026-10-30").lock(UOW, september, accountant, "month end", correlation());
        AccountingPeriods.Acted second = periods("2026-10-30").lock(UOW, september, controller, null, correlation());

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.status()).isEqualTo(PeriodStatus.LOCKED);
        assertThat(store.history()).filteredOn(row -> row.act() == AccountingPeriodStore.Act.LOCKED).hasSize(1);
        assertThat(events.size() - eventsBefore).isEqualTo(1);
        assertThat(audit).filteredOn(record -> record.operation().code().equals("accounting.PeriodLocked")).hasSize(1);
        assertThat(cutoffMoves).hasSize(1);
    }

    @Test
    @DisplayName("a lock before the grace is refused and changes nothing; from the grace's last business day it locks"
            + " (C9: 2 business days, weekends skipped)")
    void aLockBeforeTheGraceIsRefused() {
        AccountingPeriodId september = open(SEPTEMBER);
        // September 2026 ends on a Wednesday: Thursday 1 and Friday 2 October are the grace.
        assertThat(LockGrace.standard().lockableFrom(SEPTEMBER)).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThatThrownBy(() -> periods("2026-10-01").lock(UOW, september, accountant, null, correlation()))
                .isInstanceOf(AccountingPeriods.PeriodNotLockable.class).hasMessageContaining("2026-10-02");
        assertThat(store.month(SEPTEMBER)).map(AccountingPeriodStore.PeriodRow::status).contains(PeriodStatus.OPEN);
        assertThat(closedThrough).isEmpty();
        assertThat(periods("2026-10-02").lock(UOW, september, accountant, null, correlation()).status())
                .isEqualTo(PeriodStatus.LOCKED);
        // October 2026 ends on a Saturday: Monday 2 and Tuesday 3 November.
        assertThat(LockGrace.standard().lockableFrom(YearMonth.of(2026, 10))).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(new LockGrace(1).lockableFrom(YearMonth.of(2026, 10))).isEqualTo(LocalDate.of(2026, 11, 2));
        assertThatThrownBy(() -> new LockGrace(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("periods lock in order: not while the previous one is OPEN or LOCKED; once it is CLOSED, from its end")
    void locksInOrder() {
        AccountingPeriodId august = open(AUGUST);
        AccountingPeriodId september = open(SEPTEMBER);
        assertThatThrownBy(() -> periods("2026-10-30").lock(UOW, september, accountant, null, correlation()))
                .isInstanceOf(AccountingPeriods.PeriodNotLockable.class).hasMessageContaining("2026-08 is OPEN");
        periods("2026-10-30").lock(UOW, august, accountant, null, correlation());
        assertThatThrownBy(() -> periods("2026-10-30").lock(UOW, september, accountant, null, correlation()))
                .isInstanceOf(AccountingPeriods.PeriodNotLockable.class).hasMessageContaining("2026-08 is LOCKED");
        store.closeForTest(AUGUST);
        assertThat(periods("2026-10-30").lock(UOW, september, accountant, null, correlation()).status())
                .isEqualTo(PeriodStatus.LOCKED);
        assertThat(cutoffMoves).containsExactly("advance unset -> 2026-08-31", "advance 2026-08-31 -> 2026-09-30");
    }

    @Test
    @DisplayName("the lock pins the version in force for its month under the mapping key shared; an activation into a"
            + " locked month is then retroactive, one into the open month after it activates")
    void aLockPinsTheVersionInForce() {
        GlMappingAdministration administration = new GlMappingAdministration(mappings,
                unitOfWork -> ReferenceGlMapping.agreeingChart(purpose -> true), (unitOfWork, record) -> {},
                (unitOfWork, envelope, payload, mediaType) -> {}, IDS, Clock.systemUTC());
        GlMappingAdministration.Proposed first = administration.propose(UOW, ReferenceGlMapping.version(SEPTEMBER),
                "the chart", accountant, correlation());
        administration.activate(UOW, first.id(), controller, "reviewed", correlation());
        AccountingPeriodId september = open(SEPTEMBER);

        AccountingPeriods.Acted locked = periods("2026-10-30").lock(UOW, september, accountant, null, correlation());

        assertThat(locked.pinnedVersion()).contains(1);
        assertThat(store.month(SEPTEMBER).orElseThrow().pinned()).map(AccountingPeriodStore.Pin::id).contains(first.id());
        assertThat(mappings.sharedLocks()).as("the pin read under namespace 14 key mapping, shared").isEqualTo(1);
        GlMappingAdministration.Proposed intoLocked = administration.propose(UOW, ReferenceGlMapping.version(SEPTEMBER),
                "same month", accountant, correlation());
        assertThatThrownBy(() -> administration.activate(UOW, intoLocked.id(), controller, "late", correlation()))
                .isInstanceOf(GlMappingAdministration.MappingRetroactive.class).hasMessageContaining("2026-09");
        GlMappingAdministration.Proposed next = administration.propose(UOW,
                ReferenceGlMapping.version(SEPTEMBER.plusMonths(1)), "next month", accountant, correlation());
        assertThat(administration.activate(UOW, next.id(), controller, "fine", correlation()).status())
                .isEqualTo(GlMappingVersionStatus.ACTIVE);
        assertThat(mappings.inForceFor(UOW, SEPTEMBER)).map(GlMappingStore.VersionRow::id).contains(first.id());
    }

    @Test
    @DisplayName("an unlock is four-eyes: proposed by one person (nothing moves), refused to the proposer, approved by"
            + " another - the cutoff moved back one period and the pin released; the repeats converge")
    void anUnlockIsFourEyesAndMovesTheCutoffBack() {
        AccountingPeriodId september = open(SEPTEMBER);
        periods("2026-10-30").lock(UOW, september, accountant, null, correlation());
        assertThatThrownBy(() -> periods("2026-10-30").approveUnlock(UOW, september, controller, "why", correlation()))
                .isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("no unlock");

        AccountingPeriods.Acted proposed =
                periods("2026-10-30").proposeUnlock(UOW, september, accountant, "a missed accrual", correlation());
        assertThat(proposed.unlockPending()).isTrue();
        assertThat(closedThrough).as("a proposal moves nothing").contains(LocalDate.of(2026, 9, 30));
        assertThat(periods("2026-10-30").proposeUnlock(UOW, september, accountant, "again", correlation()).replayed())
                .isTrue();
        assertThatThrownBy(() -> periods("2026-10-30").proposeUnlock(UOW, september, controller, "mine", correlation()))
                .isInstanceOf(AccountingPeriods.PeriodStale.class);
        assertThatThrownBy(() -> periods("2026-10-30").approveUnlock(UOW, september, accountant, "mine", correlation()))
                .isInstanceOf(AccountingPeriods.SelfApprovalRefused.class);
        assertThat(closedThrough).contains(LocalDate.of(2026, 9, 30));

        AccountingPeriods.Acted unlocked =
                periods("2026-10-30").approveUnlock(UOW, september, controller, "reviewed", correlation());
        assertThat(unlocked.status()).isEqualTo(PeriodStatus.OPEN);
        assertThat(closedThrough).as("moved back one period - the first lock's, to unset").isEmpty();
        assertThat(ledgerAdmits(LocalDate.of(2026, 9, 20))).isTrue();
        assertThat(store.month(SEPTEMBER).orElseThrow().pinned()).isEmpty();
        assertThat(periods("2026-10-30").approveUnlock(UOW, september, controller, "again", correlation()).replayed())
                .isTrue();
        assertThat(store.history()).extracting(AccountingPeriodStore.EventRow::act).containsExactly(
                AccountingPeriodStore.Act.OPENED, AccountingPeriodStore.Act.LOCKED,
                AccountingPeriodStore.Act.UNLOCK_PROPOSED, AccountingPeriodStore.Act.UNLOCKED);
        assertThat(store.history().get(3).proposedBy()).contains(accountant.id());
        assertThat(audit).extracting(record -> record.operation().code()).containsSubsequence(
                "accounting.PeriodLocked", "accounting.PeriodUnlockProposed", "accounting.PeriodUnlocked");
        assertThat(audit).allMatch(record -> record.outcome() == AuditOutcome.SUCCEEDED);
        // Locked again: the next lock starts afresh, and the period's history keeps both cycles.
        assertThat(periods("2026-10-30").lock(UOW, september, accountant, null, correlation()).replayed()).isFalse();
        assertThat(cutoffMoves).containsExactly("advance unset -> 2026-09-30", "back 2026-09-30 -> unset",
                "advance unset -> 2026-09-30");
    }

    @Test
    @DisplayName("a lock or unlock is a person's - never the platform's; a CLOSED period is past the lock")
    void personsOnlyAndPastTheLock() {
        AccountingPeriodId september = open(SEPTEMBER);
        assertThatThrownBy(() -> periods("2026-10-30").lock(UOW, september, Actor.SYSTEM, null, correlation()))
                .isInstanceOf(IllegalArgumentException.class);
        store.closeForTest(SEPTEMBER);
        assertThatThrownBy(() -> periods("2026-10-30").lock(UOW, september, accountant, null, correlation()))
                .isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("CLOSED");
        assertThatThrownBy(() -> periods("2026-10-30").lock(UOW, AccountingPeriodId.next(IDS), accountant, null,
                correlation())).isInstanceOf(AccountingPeriods.PeriodNotFound.class);
    }

    @Test
    @DisplayName("finapp.accounting.period.open.age: whole days the oldest unclosed period is past its grace, 0 before")
    void overdueDays() {
        assertThat(periods("2026-10-30").overdueDays(UOW)).isZero();
        open(SEPTEMBER);
        assertThat(periods("2026-10-01").overdueDays(UOW)).isZero();
        assertThat(periods("2026-10-12").overdueDays(UOW)).isEqualTo(10);
        store.closeForTest(SEPTEMBER);
        assertThat(periods("2026-10-12").overdueDays(UOW)).isZero();
    }

    // -----------------------------------------------------------------

    private AccountingPeriods periods(String day) {
        Clock clock = Clock.fixed(Instant.parse(day + "T09:00:00Z"), ZoneOffset.UTC);
        return new AccountingPeriods(store, mappings, cutoff, LockGrace.standard(),
                (unitOfWork, record) -> audit.add(record),
                (unitOfWork, envelope, payload, mediaType) -> {
                    events.add(envelope);
                    payloads.add(payload);
                },
                IDS, clock);
    }

    /** Opens {@code month} - the first period when none exists - and answers its id. */
    private AccountingPeriodId open(YearMonth month) {
        periods(month.atDay(15).toString()).openNext(UOW, Actor.SYSTEM, correlation());
        return store.month(month).orElseThrow().id();
    }

    /** The stand-in ledger: an entry dated on or before the cutoff is refused at insert. */
    private boolean ledgerAdmits(LocalDate postingDate) {
        return closedThrough.map(postingDate::isAfter).orElse(true);
    }

    private static CorrelationId correlation() {
        return CorrelationId.generate(IDS);
    }
}
