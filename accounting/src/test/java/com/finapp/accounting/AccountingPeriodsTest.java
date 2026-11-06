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
    /** An identity no request has: what an act names when none is pending, which is refused by name whatever it names. */
    private static final Instant NOTHING_READ = Instant.parse("2000-01-01T00:00:00Z");

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
        assertThat(new LockGrace(3).lockableFrom(YearMonth.of(2026, 10))).isEqualTo(LocalDate.of(2026, 11, 4));
        // P14-TSK-011 (P14-TSK-006's review, M-1): a grace of one could cover a western servicing zone's today.
        assertThatThrownBy(() -> new LockGrace(1)).isInstanceOf(IllegalArgumentException.class);
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
        assertThatThrownBy(() -> periods("2026-10-30").approveUnlock(UOW, september, NOTHING_READ, controller, "why", correlation()))
                .isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("no unlock");

        AccountingPeriods.Acted proposed =
                periods("2026-10-30").proposeUnlock(UOW, september, accountant, "a missed accrual", correlation());
        assertThat(proposed.unlockPending()).isTrue();
        assertThat(closedThrough).as("a proposal moves nothing").contains(LocalDate.of(2026, 9, 30));
        assertThat(periods("2026-10-30").proposeUnlock(UOW, september, accountant, "again", correlation()).replayed())
                .isTrue();
        assertThatThrownBy(() -> periods("2026-10-30").proposeUnlock(UOW, september, controller, "mine", correlation()))
                .isInstanceOf(AccountingPeriods.PeriodStale.class);
        Instant read = pending(september);
        assertThatThrownBy(() -> periods("2026-10-30").approveUnlock(UOW, september, read, accountant, "mine", correlation()))
                .isInstanceOf(AccountingPeriods.SelfApprovalRefused.class);
        assertThat(closedThrough).contains(LocalDate.of(2026, 9, 30));

        AccountingPeriods.Acted unlocked =
                periods("2026-10-30").approveUnlock(UOW, september, read, controller, "reviewed", correlation());
        assertThat(unlocked.status()).isEqualTo(PeriodStatus.OPEN);
        assertThat(closedThrough).as("moved back one period - the first lock's, to unset").isEmpty();
        assertThat(ledgerAdmits(LocalDate.of(2026, 9, 20))).isTrue();
        assertThat(store.month(SEPTEMBER).orElseThrow().pinned()).isEmpty();
        assertThat(periods("2026-10-30").approveUnlock(UOW, september, read, controller, "again", correlation()).replayed())
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

    // ------------------------------------------------------------------ an unlock's end (P14-TSK-024)

    /** September locked and an unlock of it proposed by the accountant: the cutoff at its end, the pin held. */
    private AccountingPeriodId lockedWithAnUnlockPending() {
        AccountingPeriodId september = open(SEPTEMBER);
        periods("2026-10-30").lock(UOW, september, accountant, null, correlation());
        periods("2026-10-30").proposeUnlock(UOW, september, accountant, "a missed accrual", correlation());
        return september;
    }

    /** What a person reads before deciding the pending unlock: when it was proposed, its identity (`P14-TSK-024`). */
    private Instant pending(AccountingPeriodId period) {
        return periods("2026-10-30").pendingUnlock(UOW, period).orElseThrow();
    }

    @Test
    @DisplayName("P14-TSK-024's fix round (M-1): each act on a pending unlock names the one read - withdrawn and another proposed, the approval, the rejection and the withdrawal naming the first are each RequestStale, nothing moved, the second still pending; naming the second, it is decided")
    void anActNamesTheUnlockItRead() {
        AccountingPeriodId september = lockedWithAnUnlockPending();
        Instant first = pending(september);
        periods("2026-10-30").withdrawUnlock(UOW, september, first, accountant, "the wrong month", correlation());
        periods("2026-10-30").proposeUnlock(UOW, september, new Actor("accountant-2", ActorType.CUSTOMER), "a different accrual", correlation());
        Instant second = pending(september);
        assertThat(second).as("two requests never share an instant").isNotEqualTo(first);
        List<String> moves = List.copyOf(cutoffMoves);
        int ends = store.unlockEnds().size();

        assertThatThrownBy(() -> periods("2026-10-30").approveUnlock(UOW, september, first, controller, "the one I read", correlation()))
                .as("the approval Carol meant for the first does not approve the second").isInstanceOfSatisfying(AccountingPeriods.RequestStale.class, stale -> {
                    assertThat(stale).hasMessageContaining("is not the one pending").hasMessageContaining(second.toString()).hasMessageContaining("nothing was changed");
                    assertThat(stale.action()).isEqualTo(AccountingAuditAction.REQUEST_STALE);
                    assertThat(stale.summary()).isEqualTo("period=2026-09, act=period:unlock-approval");
                });
        assertThatThrownBy(() -> periods("2026-10-30").rejectUnlock(UOW, september, first, controller, "the one I read", correlation()))
                .isInstanceOfSatisfying(AccountingPeriods.RequestStale.class, stale -> assertThat(stale.summary()).endsWith("act=period:unlock-rejection"));
        assertThatThrownBy(() -> periods("2026-10-30").withdrawUnlock(UOW, september, first, new Actor("accountant-2", ActorType.CUSTOMER), "the one I read", correlation()))
                .isInstanceOfSatisfying(AccountingPeriods.RequestStale.class, stale -> assertThat(stale.summary()).endsWith("act=period:unlock-withdrawal"));
        assertThat(cutoffMoves).as("nothing moved").isEqualTo(moves);
        assertThat(store.unlockEnds()).as("nothing ended").hasSize(ends);
        assertThat(store.month(SEPTEMBER).orElseThrow().unlockProposedAt()).as("the second still pending").contains(second);

        assertThat(periods("2026-10-30").approveUnlock(UOW, september, second, controller, "the one I read", correlation()).status()).isEqualTo(PeriodStatus.OPEN);
        assertThat(periods("2026-10-30").approveUnlock(UOW, september, second, controller, "again", correlation()).replayed()).as("the same approver's repeat, naming it, converges").isTrue();
        assertThatThrownBy(() -> periods("2026-10-30").approveUnlock(UOW, september, first, controller, "again", correlation()))
                .as("naming another is no repeat").isInstanceOf(AccountingPeriods.PeriodStale.class);
    }

    @Test
    @DisplayName("P14-TSK-024's fix round (M-1, M-2): a refusal the doors keep is recorded FAILED under its own action - a request other than the one pending, another person's withdrawal, a self-approval - naming the period and the act, never a person or a reason")
    void aKeptRefusalIsRecordedAsItself() {
        AccountingPeriodId september = lockedWithAnUnlockPending();
        Instant read = pending(september);
        List<AccountingPeriods.RecordedRefusal> refused = new ArrayList<>();
        for (Runnable act : List.<Runnable>of(
                () -> periods("2026-10-30").approveUnlock(UOW, september, read.plusSeconds(1), controller, "another", correlation()),
                () -> periods("2026-10-30").withdrawUnlock(UOW, september, read, controller, "theirs", correlation()),
                () -> periods("2026-10-30").approveUnlock(UOW, september, read, accountant, "mine", correlation()))) {
            try {
                act.run();
            } catch (AccountingPeriods.RecordedRefusal kept) {
                refused.add(kept);
            }
        }
        assertThat(refused).hasSize(3);
        int before = audit.size();
        refused.forEach(kept -> periods("2026-10-30").recordRefusal(UOW, kept, controller, correlation()));
        assertThat(audit.subList(before, audit.size())).extracting(record -> record.operation().code() + " " + record.outcome() + " " + record.changeSummary().orElseThrow())
                .containsExactly("accounting.RequestStale FAILED period=2026-09, act=period:unlock-approval", "accounting.NotTheProposer FAILED period=2026-09, act=period:unlock-withdrawal",
                        "accounting.SelfApprovalRefused FAILED period=2026-09, act=period:unlock");
        assertThat(audit.subList(before, audit.size())).allSatisfy(record -> {
            assertThat(record.targetId()).isEqualTo(september.value().toString());
            assertThat(record.reason()).isEmpty();
        });
    }

    @Test
    @DisplayName("P14-TSK-024 (d): a second person rejects a pending unlock: the period stays LOCKED under the same lock and pin, the cutoff unmoved, nothing published; the end recorded beside a history row naming the proposer and an audit record with its reason; the month free for a new proposal")
    void aSecondPersonRejectsAPendingUnlock() {
        AccountingPeriodId september = lockedWithAnUnlockPending();
        AccountingPeriodStore.PeriodRow before = store.month(SEPTEMBER).orElseThrow();
        int published = events.size();
        List<String> moves = List.copyOf(cutoffMoves);

        AccountingPeriods.Acted rejected = periods("2026-10-30").rejectUnlock(UOW, september, pending(september), controller, "the accrual was caught up after all", correlation());

        assertThat(rejected.status()).isEqualTo(PeriodStatus.LOCKED);
        assertThat(rejected.unlockPending()).isFalse();
        assertThat(rejected.replayed()).isFalse();
        AccountingPeriodStore.PeriodRow after = store.month(SEPTEMBER).orElseThrow();
        assertThat(after.lockedAt()).as("the same lock instance").isEqualTo(before.lockedAt());
        assertThat(after.pinned()).as("the same pin (INV-ACC-07)").isEqualTo(before.pinned());
        assertThat(after.revision()).isEqualTo(before.revision());
        assertThat(after.unlockPending()).isFalse();
        assertThat(cutoffMoves).as("no cutoff moved (INV-ACC-08)").isEqualTo(moves);
        assertThat(closedThrough).contains(LocalDate.of(2026, 9, 30));
        assertThat(events).as("an end publishes nothing").hasSize(published);
        assertThat(store.unlockEnds()).singleElement().satisfies(end -> {
            assertThat(end.outcome()).isEqualTo(PeriodRequestEnd.REJECTED);
            assertThat(end.proposedBy()).isEqualTo(accountant.id());
            assertThat(end.endedBy()).isEqualTo(controller.id());
            assertThat(end.lockedAt()).isEqualTo(before.lockedAt().orElseThrow());
            assertThat(end.proposedAt()).isEqualTo(before.unlockProposedAt().orElseThrow());
        });
        AccountingPeriodStore.EventRow history = store.history().get(store.history().size() - 1);
        assertThat(history.act()).isEqualTo(AccountingPeriodStore.Act.UNLOCK_REJECTED);
        assertThat(history.from()).contains(PeriodStatus.LOCKED);
        assertThat(history.to()).isEqualTo(PeriodStatus.LOCKED);
        assertThat(history.proposedBy()).contains(accountant.id());
        AuditRecord record = audit.get(audit.size() - 1);
        assertThat(record.operation().code()).isEqualTo("accounting.PeriodUnlockRejected");
        assertThat(record.actor()).isEqualTo(controller);
        assertThat(record.reason()).contains("the accrual was caught up after all");
        assertThat(record.targetId()).isEqualTo(september.value().toString());
        assertThat(record.outcome()).isEqualTo(AuditOutcome.SUCCEEDED);
        assertThat(record.changeSummary().orElseThrow()).contains("period=2026-09").doesNotContain("caught up");

        assertThat(periods("2026-10-30").proposeUnlock(UOW, september, accountant, "another accrual", correlation()).unlockPending()).as("free for a new one").isTrue();
        assertThat(periods("2026-10-30").approveUnlock(UOW, september, pending(september), controller, "reviewed", correlation()).status()).as("which carries its own effect").isEqualTo(PeriodStatus.OPEN);
    }

    @Test
    @DisplayName("P14-TSK-024 (d): four eyes as the acts: the proposer cannot reject their own (SelfApprovalRefused) and withdraws it instead; nobody else withdraws it (NotTheProposer); a person, never the platform; a reason required")
    void aProposerWithdrawsAndNeverRejects() {
        AccountingPeriodId september = lockedWithAnUnlockPending();
        Instant read = pending(september);
        assertThatThrownBy(() -> periods("2026-10-30").rejectUnlock(UOW, september, read, accountant, "mine", correlation()))
                .isInstanceOfSatisfying(AccountingPeriods.SelfApprovalRefused.class, self -> assertThat(self.summary()).contains("act=period:unlock-rejection"));
        assertThatThrownBy(() -> periods("2026-10-30").withdrawUnlock(UOW, september, read, controller, "theirs", correlation()))
                .isInstanceOfSatisfying(AccountingPeriods.NotTheProposer.class, other -> {
                    assertThat(other).hasMessageContaining("only its proposer withdraws");
                    assertThat(other.action()).as("recorded as itself (the fix round's M-2)").isEqualTo(AccountingAuditAction.NOT_THE_PROPOSER);
                    assertThat(other.target()).isEqualTo(september.value().toString());
                    assertThat(other.summary()).isEqualTo("period=2026-09, act=period:unlock-withdrawal");
                });
        assertThatThrownBy(() -> periods("2026-10-30").rejectUnlock(UOW, september, read, Actor.SYSTEM, "the platform", correlation())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> periods("2026-10-30").rejectUnlock(UOW, september, read, controller, " ", correlation())).isInstanceOf(AccountingReasons.ReasonRequired.class);
        assertThat(store.unlockEnds()).as("nothing written by a refusal").isEmpty();
        assertThat(store.month(SEPTEMBER).orElseThrow().unlockPending()).isTrue();

        AccountingPeriods.Acted withdrawn = periods("2026-10-30").withdrawUnlock(UOW, september, read, accountant, "proposed on the wrong month", correlation());

        assertThat(withdrawn.status()).isEqualTo(PeriodStatus.LOCKED);
        assertThat(store.unlockEnds()).singleElement().satisfies(end -> {
            assertThat(end.outcome()).isEqualTo(PeriodRequestEnd.WITHDRAWN);
            assertThat(end.endedBy()).isEqualTo(accountant.id());
        });
        AccountingPeriodStore.EventRow history = store.history().get(store.history().size() - 1);
        assertThat(history.act()).isEqualTo(AccountingPeriodStore.Act.UNLOCK_WITHDRAWN);
        assertThat(history.proposedBy()).as("the proposer named, who is the actor").contains(accountant.id());
        assertThat(audit.get(audit.size() - 1).operation().code()).isEqualTo("accounting.PeriodUnlockWithdrawn");
    }

    @Test
    @DisplayName("P14-TSK-024 (d): the same request ended twice converges for the person who ended it and is refused, by name, to anyone else; the approval after an end is told it was REJECTED, an end after the approval that it was approved - each serialised on the period row, here in sequence (the race is PeriodCloseDatabaseTest's)")
    void anEndAndItsApprovalTellTheLoserByName() {
        AccountingPeriodId september = lockedWithAnUnlockPending();
        Instant read = pending(september);
        periods("2026-10-30").rejectUnlock(UOW, september, read, controller, "not needed", correlation());

        assertThat(periods("2026-10-30").rejectUnlock(UOW, september, read, controller, "not needed", correlation()).replayed()).as("the same person's repeat converges").isTrue();
        assertThat(store.unlockEnds()).as("written once").hasSize(1);
        assertThatThrownBy(() -> periods("2026-10-30").rejectUnlock(UOW, september, read, new Actor("controller-2", ActorType.CUSTOMER), "me too", correlation()))
                .isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("was REJECTED").hasMessageContaining("nothing was changed");
        assertThatThrownBy(() -> periods("2026-10-30").withdrawUnlock(UOW, september, read, accountant, "mine", correlation()))
                .isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("was REJECTED");
        assertThatThrownBy(() -> periods("2026-10-30").approveUnlock(UOW, september, read, controller, "late", correlation()))
                .as("the approval's loser, told by name").isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("was REJECTED");
        assertThat(closedThrough).contains(LocalDate.of(2026, 9, 30));

        periods("2026-10-30").proposeUnlock(UOW, september, accountant, "after all", correlation());
        Instant second = pending(september);
        periods("2026-10-30").approveUnlock(UOW, september, second, controller, "reviewed", correlation());
        assertThatThrownBy(() -> periods("2026-10-30").rejectUnlock(UOW, september, second, new Actor("controller-2", ActorType.CUSTOMER), "too late", correlation()))
                .as("the end's loser, told by name").isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("the unlock named was approved");
        assertThatThrownBy(() -> periods("2026-10-30").withdrawUnlock(UOW, september, second, accountant, "too late", correlation()))
                .isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("the unlock named was approved");
    }

    @Test
    @DisplayName("P14-DOC-001 (P14-TSK-024's re-review (a)): a repeat converges whatever was proposed or ended since - the controller's rejection of the first, repeated after a second was proposed and withdrawn, and again while a third is pending: nothing written, the third still pending; anyone else is told by name")
    void aRepeatConvergesWhateverEndedSince() {
        AccountingPeriodId september = lockedWithAnUnlockPending();
        Instant first = pending(september);
        periods("2026-10-30").rejectUnlock(UOW, september, first, controller, "not needed", correlation());
        periods("2026-10-30").proposeUnlock(UOW, september, accountant, "second", correlation());
        periods("2026-10-30").withdrawUnlock(UOW, september, pending(september), accountant, "proposed in error", correlation());
        int ends = store.unlockEnds().size();
        int history = store.history().size();
        int records = audit.size();

        assertThat(periods("2026-10-30").rejectUnlock(UOW, september, first, controller, "not needed", correlation()).replayed())
                .as("the first's rejection, repeated after the second ended: converged, not 'no unlock is pending'").isTrue();
        assertThatThrownBy(() -> periods("2026-10-30").rejectUnlock(UOW, september, first, new Actor("controller-2", ActorType.CUSTOMER), "me too", correlation()))
                .as("another person, told by name").isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("the unlock named was REJECTED");
        periods("2026-10-30").proposeUnlock(UOW, september, accountant, "third", correlation());
        Instant third = pending(september);
        int proposed = store.history().size();
        int proposedRecords = audit.size();
        assertThat(periods("2026-10-30").rejectUnlock(UOW, september, first, controller, "not needed", correlation()).replayed())
                .as("and while a third is pending: converged, not RequestStale").isTrue();
        assertThat(pending(september)).as("the third still pending, untouched").isEqualTo(third);
        assertThat(store.unlockEnds()).as("no end written by a repeat").hasSize(ends);
        assertThat(store.history()).as("the repeats wrote no history").hasSize(proposed);
        assertThat(audit).as("nor an audit record").hasSize(proposedRecords);
        assertThat(proposed).as("the third's proposal is the only row between").isEqualTo(history + 1);
        assertThat(proposedRecords).isEqualTo(records + 1);
        assertThatThrownBy(() -> periods("2026-10-30").rejectUnlock(UOW, september, first, new Actor("controller-2", ActorType.CUSTOMER), "me too", correlation()))
                .as("another person, while the third is pending: the one read is not the one pending").isInstanceOf(AccountingPeriods.RequestStale.class);
        assertThat(pending(september)).isEqualTo(third);
    }

    @Test
    @DisplayName("P14-TSK-024 (d): an end takes the period row FOR UPDATE - the request lives on it - as the approval does, so the two serialise on that row: the hermetic half; the race on ten connections is PeriodCloseDatabaseTest#anUnlocksRejectionRacingItsApprovalOneWins, owed")
    void anEndTakesThePeriodRow() {
        AccountingPeriodId september = lockedWithAnUnlockPending();
        int before = store.rowLocks();
        periods("2026-10-30").rejectUnlock(UOW, september, pending(september), controller, "not needed", correlation());
        assertThat(store.rowLocks()).as("the period row FOR UPDATE, once").isEqualTo(before + 1);
        periods("2026-10-30").proposeUnlock(UOW, september, accountant, "again", correlation());
        Instant read = pending(september);
        int again = store.rowLocks();
        periods("2026-10-30").withdrawUnlock(UOW, september, read, accountant, "not needed", correlation());
        assertThat(store.rowLocks()).isEqualTo(again + 1);
    }

    @Test
    @DisplayName("P14-TSK-024 (d): an end needs a pending unlock: a LOCKED period with none proposed, an OPEN one never locked, a CLOSED one - each refused by name, nothing written; an end under an earlier lock is not this lock's")
    void anEndNeedsAPendingUnlock() {
        AccountingPeriodId september = open(SEPTEMBER);
        assertThatThrownBy(() -> periods("2026-10-30").rejectUnlock(UOW, september, NOTHING_READ, controller, "none", correlation()))
                .isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("only a LOCKED period's pending unlock is ended");
        periods("2026-10-30").lock(UOW, september, accountant, null, correlation());
        assertThatThrownBy(() -> periods("2026-10-30").rejectUnlock(UOW, september, NOTHING_READ, controller, "none", correlation()))
                .isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("no unlock of the period is pending");
        periods("2026-10-30").proposeUnlock(UOW, september, accountant, "first", correlation());
        Instant first = pending(september);
        periods("2026-10-30").withdrawUnlock(UOW, september, first, accountant, "first withdrawn", correlation());
        periods("2026-10-30").proposeUnlock(UOW, september, accountant, "second", correlation());
        periods("2026-10-30").approveUnlock(UOW, september, pending(september), controller, "reviewed", correlation());
        periods("2026-10-30").lock(UOW, september, accountant, null, correlation());
        assertThat(store.unlockEnds()).as("the first lock's end stands as history").hasSize(1);
        assertThatThrownBy(() -> periods("2026-10-30").withdrawUnlock(UOW, september, first, accountant, "first withdrawn", correlation()))
                .as("an end under an earlier lock is not this lock's: none was proposed under this one").isInstanceOf(AccountingPeriods.PeriodStale.class)
                .hasMessageContaining("no unlock of the period is pending");
        store.closeForTest(SEPTEMBER);
        assertThatThrownBy(() -> periods("2026-10-30").rejectUnlock(UOW, september, NOTHING_READ, controller, "none", correlation()))
                .isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("CLOSED");
        assertThat(store.unlockEnds()).hasSize(1);
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
        store.databaseToday(java.time.LocalDate.parse("2026-10-01"));
        assertThat(periods("2026-10-01").overdueDays(UOW)).isZero();
        store.databaseToday(java.time.LocalDate.parse("2026-10-12"));
        assertThat(periods("2026-10-12").overdueDays(UOW)).isEqualTo(10);
        assertThat(periods("2026-10-01").overdueDays(UOW)).as("the database's date, whatever this instance's clock reads (P14-TSK-020)").isEqualTo(10);
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
                IDS, clock, (unitOfWork, period, actor, correlation) -> 0);
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
