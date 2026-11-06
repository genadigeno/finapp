package com.finapp.app.accounting;

import static com.finapp.app.accounting.AccountingPeriodFixture.IDS;
import static com.finapp.app.accounting.AccountingPeriodFixture.JANUARY;
import static com.finapp.app.accounting.AccountingPeriodFixture.correlation;
import static com.finapp.app.accounting.AccountingPeriodFixture.id;
import static com.finapp.app.accounting.AccountingPeriodFixture.inScope;
import static com.finapp.app.accounting.AccountingPeriodFixture.periods;
import static com.finapp.app.accounting.AccountingPeriodFixture.person;
import static com.finapp.app.accounting.AccountingPeriodFixture.row;
import static com.finapp.app.accounting.PeriodCloseFixture.ACCOUNTANT;
import static com.finapp.app.accounting.PeriodCloseFixture.BREAKS;
import static com.finapp.app.accounting.PeriodCloseFixture.CLOSES;
import static com.finapp.app.accounting.PeriodCloseFixture.CONTROLLER;
import static com.finapp.app.accounting.PeriodCloseFixture.MACHINE;
import static com.finapp.app.accounting.PeriodCloseFixture.REASON;
import static com.finapp.app.accounting.PeriodCloseFixture.RUNS;
import static com.finapp.app.accounting.PeriodCloseFixture.RUN_STORE;
import static com.finapp.app.accounting.PeriodCloseFixture.count;
import static com.finapp.app.accounting.PeriodCloseFixture.proposed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.accounting.AccountingBreakId;
import com.finapp.accounting.AccountingBreakStore;
import com.finapp.accounting.AccountingPeriodId;
import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.AccountingPeriods;
import com.finapp.accounting.AccountingReasons;
import com.finapp.accounting.BreakFinding;
import com.finapp.accounting.BreakStatus;
import com.finapp.accounting.BreakType;
import com.finapp.accounting.CloseCheckCode;
import com.finapp.accounting.CloseCheckId;
import com.finapp.accounting.CloseRunId;
import com.finapp.accounting.CloseRunStore;
import com.finapp.accounting.PeriodCloseId;
import com.finapp.accounting.PeriodCloseStatus;
import com.finapp.accounting.PeriodCloseStore;
import com.finapp.accounting.PeriodCloses;
import com.finapp.accounting.PeriodStatus;
import com.finapp.accounting.SourceCompleteness;
import com.finapp.platform.security.Actor;
import com.finapp.platform.testing.database.DatabaseRoles;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The four-eyes close against a live PostgreSQL (`P14-TSK-012`; {@code PHASE_14_PLAN.md} sections 6, 8.3 rows 3 and 4, 13.5, 14.2; {@code INV-ACC-09}, {@code INV-AUD-04}) - the entry's three named cases and what the design needs: ten approvers, the proposer refused by the domain and by
 * every {@code CHECK} for the owner itself, each refusal of an incomplete or moved run, the frozen record, a break's generation and the key's migration, and every edge the trigger refuses to a raw writer. {@code accounting V008} executes here, and nowhere before the phase exit.
 * <strong>Written and owed</strong> (owner decision C5: the database tiers are skipped per task).
 *
 * <p><strong>A database of its own</strong> ({@code own-container}): it closes January 1999, a shared fixture no other suite's January could then lock, and activates a GL mapping from it.
 *
 * <p><strong>An unlock's end</strong> (`P14-TSK-024`, {@code accounting V011}): {@link #aPendingUnlockEndsWithoutItsEffect} and {@link #anUnlocksRejectionRacingItsApprovalOneWins}. JUnit runs this class in its default order, by the hash of each name: the race second, after
 * {@code anIncompleteRunCannotBeProposed}, the fix round's {@link #anApprovalNamesTheUnlockItRead} sixth, after {@code aReasonIsBoundToTheAttemptRead}, and the end tenth, before {@code whatMovedRefusesTheApproval}. None depends on it: each locks January afresh, counts its own rows as a difference from what stood before it, and leaves January {@code LOCKED} or
 * {@code OPEN}, which {@link #januaryIsOpen} returns to {@code OPEN} through the machine's own doors, a pending unlock approved and none proposed and approved. An end row is never deleted, so an earlier lock's stands; each case reads only its own lock's.
 */
@Tag("database")
@Tag("own-container") // its own JVM and database: ownContainerDatabaseTest, the javadoc says why
@DisplayName("the four-eyes close against the database (P14-TSK-012)")
class PeriodCloseDatabaseTest {

    private static final int APPROVERS = 10;
    /** A month no case here locks: a break row holds a month, not a period, and a break left of January would change what every later close of it discloses. */
    private static final YearMonth QUIET = YearMonth.of(1999, 6);

    @BeforeEach
    void januaryIsOpen() {
        AccountingPeriodFixture.openJanuaryAndFebruary();
        PeriodCloseFixture.mappingInForce();
        PeriodCloseFixture.everyPeriodOpen();
    }

    // ------------------------------------------------------------------ row 3

    @Test
    @DisplayName("contention row 3: ten approvers on ten connections, one close: one edge, one history row, one event, one audit record; the other nine told it is stale")
    void twoApprovalsOneClose() throws Exception {
        PeriodCloses.Acted acted = proposed(JANUARY);
        AccountingPeriodId january = acted.id();
        PeriodCloseId proposal = acted.proposal().orElseThrow();
        long events = count("SELECT count(*) FROM platform.outbox_event WHERE event_type = '" + PeriodCloses.CLOSED_EVENT + "' AND aggregate_id = '" + january.value() + "'");
        long records = count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.PeriodClosed' AND target_id = '" + january.value() + "'");
        long history = count("SELECT count(*) FROM accounting.period_event WHERE act = 'CLOSED' AND period_id = '" + january.value() + "'");
        ExecutorService pool = Executors.newFixedThreadPool(APPROVERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> racing = new ArrayList<>();
        try {
            for (int approver = 0; approver < APPROVERS; approver++) {
                racing.add(pool.submit(() -> {
                    start.await();
                    Actor person = person();
                    try {
                        return inScope(person, uow -> MACHINE.approve(uow, january, proposal, person, REASON, correlation()));
                    } catch (AccountingPeriods.PeriodStale stale) {
                        return stale;
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> outcome : racing) {
                outcomes.add(outcome.get(2, TimeUnit.MINUTES));
            }
            assertThat(outcomes.stream().filter(PeriodCloses.Acted.class::isInstance)).hasSize(1);
            assertThat(outcomes.stream().filter(AccountingPeriods.PeriodStale.class::isInstance)).hasSize(APPROVERS - 1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(row(JANUARY).status()).isEqualTo(PeriodStatus.CLOSED);
        assertThat(count("SELECT count(*) FROM platform.outbox_event WHERE event_type = '" + PeriodCloses.CLOSED_EVENT + "' AND aggregate_id = '" + january.value() + "'")).isEqualTo(events + 1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.PeriodClosed' AND target_id = '" + january.value() + "'")).isEqualTo(records + 1);
        assertThat(count("SELECT count(*) FROM accounting.period_event WHERE act = 'CLOSED' AND period_id = '" + january.value() + "'")).isEqualTo(history + 1);
        assertThat(row(JANUARY).lockedAt()).as("the lock stands through CLOSED: ADR-0115").isPresent();
        assertThat(row(JANUARY).pinned()).isPresent();
    }

    // ------------------------------------------------------------------ an unlock's end (P14-TSK-024)

    @Test
    @DisplayName("P14-TSK-024 (d): a pending unlock ends without its effect: the proposer's rejection refused by the domain, the end row's CHECK and the history's; a raw clearing with no end, an end left beside the proposal, and an end of nothing refused for the owner itself; a second person's rejection leaves the lock, its pin, the revision and the cutoff, one end, one history row and one audit record; the repeat converges; the month free, and the proposer alone withdraws")
    void aPendingUnlockEndsWithoutItsEffect() throws Exception {
        AccountingPeriodId january = PeriodCloseFixture.locked(JANUARY);
        String period = january.value().toString();
        AccountingPeriodStore.PeriodRow locked = row(JANUARY);
        Optional<java.time.LocalDate> cut = AccountingPeriodFixture.closedThrough();
        Actor proposer = person();
        inScope(proposer, uow -> periods().proposeUnlock(uow, january, proposer, "a missed accrual", correlation()));
        Instant read = pendingUnlock(january);

        assertThatThrownBy(() -> inScope(proposer, uow -> periods().rejectUnlock(uow, january, read, proposer, "mine", correlation()))).isInstanceOf(AccountingPeriods.SelfApprovalRefused.class);
        refused(rawEnd(period, "'REJECTED', p.unlock_proposed_by"), "period_unlock_end_four_eyes");
        refused("INSERT INTO accounting.period_event (id, period_id, act, from_status, to_status, revision, actor, actor_type, proposed_by, reason, occurred_at, correlation_id) VALUES ('"
                + IDS.next() + "', '" + period + "', 'UNLOCK_REJECTED', 'LOCKED', 'LOCKED', 1, 'same', 'CUSTOMER', 'same', 'raw', clock_timestamp(), 'raw')", "period_event_four_eyes");
        refused("UPDATE accounting.accounting_period SET unlock_proposed_by = NULL, unlock_proposed_by_type = NULL, unlock_proposed_at = NULL, unlock_proposal_reason = NULL WHERE id = '" + period + "'",
                "only beside the record of its end");
        refused(rawEnd(period, "'REJECTED', 'raw-other'"), "committed beside a period still holding");
        assertThat(row(JANUARY).unlockPending()).as("every refusal left the request standing").isTrue();

        long ends = count("SELECT count(*) FROM accounting.period_unlock_end WHERE period_id = '" + period + "'");
        long history = count("SELECT count(*) FROM accounting.period_event WHERE act = 'UNLOCK_REJECTED' AND period_id = '" + period + "'");
        long records = count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.PeriodUnlockRejected' AND target_id = '" + period + "'");
        Actor rejecter = person();

        AccountingPeriods.Acted rejected = inScope(rejecter, uow -> periods().rejectUnlock(uow, january, read, rejecter, "not needed after all", correlation()));

        assertThat(rejected.status()).isEqualTo(PeriodStatus.LOCKED);
        AccountingPeriodStore.PeriodRow after = row(JANUARY);
        assertThat(after.status()).isEqualTo(PeriodStatus.LOCKED);
        assertThat(after.lockedAt()).as("the same lock instance").isEqualTo(locked.lockedAt());
        assertThat(after.pinned()).as("the same pin (INV-ACC-07)").isEqualTo(locked.pinned());
        assertThat(after.revision()).isEqualTo(locked.revision());
        assertThat(after.unlockPending()).isFalse();
        assertThat(AccountingPeriodFixture.closedThrough()).as("no cutoff moved (INV-ACC-08)").isEqualTo(cut);
        assertThat(count("SELECT count(*) FROM accounting.period_unlock_end WHERE period_id = '" + period + "' AND outcome = 'REJECTED' AND ended_by = '" + rejecter.id() + "' AND proposed_by = '"
                + proposer.id() + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM accounting.period_unlock_end WHERE period_id = '" + period + "'")).isEqualTo(ends + 1);
        assertThat(count("SELECT count(*) FROM accounting.period_event WHERE act = 'UNLOCK_REJECTED' AND period_id = '" + period + "' AND proposed_by = '" + proposer.id() + "'")).isPositive();
        assertThat(count("SELECT count(*) FROM accounting.period_event WHERE act = 'UNLOCK_REJECTED' AND period_id = '" + period + "'")).isEqualTo(history + 1);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.PeriodUnlockRejected' AND target_id = '" + period + "'")).isEqualTo(records + 1);

        assertThat(inScope(rejecter, uow -> periods().rejectUnlock(uow, january, read, rejecter, "not needed after all", correlation())).replayed()).as("the same person's repeat converges").isTrue();
        assertThat(count("SELECT count(*) FROM accounting.period_unlock_end WHERE period_id = '" + period + "'")).as("written once").isEqualTo(ends + 1);
        refused(rawEnd(period, "'REJECTED', 'raw-other'"), "names the pending unlock");
        assertThatThrownBy(() -> inScope(CONTROLLER, uow -> periods().approveUnlock(uow, january, read, CONTROLLER, "late", correlation()))).isInstanceOf(AccountingPeriods.PeriodStale.class)
                .hasMessageContaining("was REJECTED");

        Actor again = person();
        inScope(again, uow -> periods().proposeUnlock(uow, january, again, "another accrual", correlation()));
        Instant second = pendingUnlock(january);
        assertThatThrownBy(() -> inScope(proposer, uow -> periods().withdrawUnlock(uow, january, second, proposer, "not mine", correlation()))).isInstanceOf(AccountingPeriods.NotTheProposer.class);
        refused(rawEnd(period, "'WITHDRAWN', 'raw-other'"), "period_unlock_end_four_eyes");
        assertThat(inScope(again, uow -> periods().withdrawUnlock(uow, january, second, again, "the wrong month", correlation())).status()).isEqualTo(PeriodStatus.LOCKED);
        assertThat(count("SELECT count(*) FROM accounting.period_unlock_end WHERE period_id = '" + period + "' AND outcome = 'WITHDRAWN' AND ended_by = '" + again.id() + "'")).isEqualTo(1);
        assertThat(row(JANUARY).lockedAt()).isEqualTo(locked.lockedAt());
        refused("UPDATE accounting.period_unlock_end SET end_reason = 'edited' WHERE period_id = '" + period + "'", "append-only");
    }

    @Test
    @DisplayName("P14-TSK-024's fix round (M-1): an approval names the unlock it read - withdrawn and another proposed by someone else, the approval, the rejection and the withdrawal naming the first are refused RequestStale, nothing written; the second still pending, approved by name, the cutoff back")
    void anApprovalNamesTheUnlockItRead() throws Exception {
        AccountingPeriodId january = PeriodCloseFixture.locked(JANUARY);
        String period = january.value().toString();
        Optional<java.time.LocalDate> cut = AccountingPeriodFixture.closedThrough();
        Actor alice = person();
        inScope(alice, uow -> periods().proposeUnlock(uow, january, alice, "the wrong month", correlation()));
        Instant first = pendingUnlock(january);
        inScope(alice, uow -> periods().withdrawUnlock(uow, january, first, alice, "the wrong month", correlation()));
        Actor dave = person();
        inScope(dave, uow -> periods().proposeUnlock(uow, january, dave, "a missed accrual", correlation()));
        Instant second = pendingUnlock(january);
        assertThat(second).as("the database's clock under the row never repeats an instant").isNotEqualTo(first);
        long ends = count("SELECT count(*) FROM accounting.period_unlock_end WHERE period_id = '" + period + "'");
        Actor carol = person();

        assertThatThrownBy(() -> inScope(carol, uow -> periods().approveUnlock(uow, january, first, carol, "the one I read", correlation())))
                .isInstanceOf(AccountingPeriods.RequestStale.class).hasMessageContaining("is not the one pending");
        assertThatThrownBy(() -> inScope(carol, uow -> periods().rejectUnlock(uow, january, first, carol, "the one I read", correlation()))).isInstanceOf(AccountingPeriods.RequestStale.class);
        assertThatThrownBy(() -> inScope(dave, uow -> periods().withdrawUnlock(uow, january, first, dave, "the one I read", correlation()))).isInstanceOf(AccountingPeriods.RequestStale.class);
        assertThat(row(JANUARY).status()).isEqualTo(PeriodStatus.LOCKED);
        assertThat(row(JANUARY).unlockProposedAt()).as("the second still pending").contains(second);
        assertThat(AccountingPeriodFixture.closedThrough()).as("no cutoff moved").isEqualTo(cut);
        assertThat(count("SELECT count(*) FROM accounting.period_unlock_end WHERE period_id = '" + period + "'")).as("nothing ended").isEqualTo(ends);

        assertThat(inScope(carol, uow -> periods().approveUnlock(uow, january, second, carol, "the one I read", correlation())).status()).isEqualTo(PeriodStatus.OPEN);
    }

    @Test
    @DisplayName("P14-DOC-001 (P14-TSK-024's re-review (a)): Carol's rejection of the first unlock, repeated after Dave proposed and withdrew a second, converges - the named end read by period_unlock_end_once's key, its instant bound and read back exactly - and again while a third is pending; nothing written, the third still pending")
    void aRepeatConvergesWhateverEndedSince() throws Exception {
        AccountingPeriodId january = PeriodCloseFixture.locked(JANUARY);
        String period = january.value().toString();
        Actor alice = person();
        inScope(alice, uow -> periods().proposeUnlock(uow, january, alice, "a missed accrual", correlation()));
        Instant first = pendingUnlock(january);
        Actor carol = person();
        inScope(carol, uow -> periods().rejectUnlock(uow, january, first, carol, "not needed", correlation()));
        Actor dave = person();
        inScope(dave, uow -> periods().proposeUnlock(uow, january, dave, "another accrual", correlation()));
        Instant second = pendingUnlock(january);
        inScope(dave, uow -> periods().withdrawUnlock(uow, january, second, dave, "proposed in error", correlation()));
        long ends = count("SELECT count(*) FROM accounting.period_unlock_end WHERE period_id = '" + period + "'");
        long records = count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.PeriodUnlockRejected' AND target_id = '" + period + "'");

        assertThat(inScope(carol, uow -> periods().rejectUnlock(uow, january, first, carol, "not needed", correlation())).replayed())
                .as("converged, not 409 'no unlock of the period is pending'").isTrue();
        inScope(alice, uow -> periods().proposeUnlock(uow, january, alice, "a third", correlation()));
        Instant third = pendingUnlock(january);
        assertThat(inScope(carol, uow -> periods().rejectUnlock(uow, january, first, carol, "not needed", correlation())).replayed())
                .as("converged, not 409 RequestStale").isTrue();
        assertThat(row(JANUARY).unlockProposedAt()).as("the third still pending").contains(third);
        assertThat(count("SELECT count(*) FROM accounting.period_unlock_end WHERE period_id = '" + period + "'")).as("no end written").isEqualTo(ends);
        assertThat(count("SELECT count(*) FROM platform.audit_record WHERE operation = 'accounting.PeriodUnlockRejected' AND target_id = '" + period + "'"))
                .as("no record of a repeat").isEqualTo(records);
        assertThatThrownBy(() -> inScope(dave, uow -> periods().rejectUnlock(uow, january, first, dave, "me too", correlation())))
                .as("another person: the one read is not the one pending").isInstanceOf(AccountingPeriods.RequestStale.class);
        inScope(dave, uow -> periods().rejectUnlock(uow, january, third, dave, "not this one either", correlation()));
    }

    @Test
    @DisplayName("P14-TSK-024 (d), the race: five approvers and five rejecters of one pending unlock on ten connections, released together: exactly one commits; every other is refused by name - approved, or REJECTED - and the period is the winner's alone: OPEN with the cutoff back and no end, or LOCKED with one end and the cutoff where it stood")
    void anUnlocksRejectionRacingItsApprovalOneWins() throws Exception {
        AccountingPeriodId january = PeriodCloseFixture.locked(JANUARY);
        String period = january.value().toString();
        Optional<java.time.LocalDate> cut = AccountingPeriodFixture.closedThrough();
        Actor proposer = person();
        inScope(proposer, uow -> periods().proposeUnlock(uow, january, proposer, "a missed accrual", correlation()));
        Instant read = pendingUnlock(january);
        long ends = count("SELECT count(*) FROM accounting.period_unlock_end WHERE period_id = '" + period + "'");
        ExecutorService pool = Executors.newFixedThreadPool(APPROVERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Object>> approvals = new ArrayList<>();
        List<Future<Object>> rejections = new ArrayList<>();
        try {
            for (int each = 0; each < APPROVERS / 2; each++) {
                approvals.add(pool.submit(() -> {
                    start.await();
                    Actor approver = person();
                    while (true) {
                        try {
                            return inScope(approver, uow -> periods().approveUnlock(uow, january, read, approver, "reviewed", correlation()));
                        } catch (AccountingPeriods.PeriodStale stale) {
                            return stale;
                        } catch (com.finapp.accounting.PostingCutoff.CutoffBusy busy) {
                            Thread.onSpinWait();
                        }
                    }
                }));
                rejections.add(pool.submit(() -> {
                    start.await();
                    Actor rejecter = person();
                    try {
                        return inScope(rejecter, uow -> periods().rejectUnlock(uow, january, read, rejecter, "not needed", correlation()));
                    } catch (AccountingPeriods.PeriodStale stale) {
                        return stale;
                    }
                }));
            }
            start.countDown();
            List<Object> approved = new ArrayList<>();
            for (Future<Object> outcome : approvals) {
                approved.add(outcome.get(2, TimeUnit.MINUTES));
            }
            List<Object> ended = new ArrayList<>();
            for (Future<Object> outcome : rejections) {
                ended.add(outcome.get(2, TimeUnit.MINUTES));
            }
            long approvedOnce = approved.stream().filter(AccountingPeriods.Acted.class::isInstance).count();
            long endedOnce = ended.stream().filter(AccountingPeriods.Acted.class::isInstance).count();
            assertThat(approvedOnce + endedOnce).as("exactly one act commits, never both").isEqualTo(1);
            String winner = approvedOnce == 1 ? "the unlock named was approved" : "was REJECTED";
            assertThat(ended.stream().filter(AccountingPeriods.PeriodStale.class::isInstance).map(Object::toString)).as("each rejecter that lost is told by name")
                    .allMatch(message -> approvedOnce == 1 ? message.contains(winner) : message.contains("was REJECTED"));
            if (approvedOnce == 1) {
                assertThat(row(JANUARY).status()).isEqualTo(PeriodStatus.OPEN);
                assertThat(count("SELECT count(*) FROM accounting.period_unlock_end WHERE period_id = '" + period + "'")).as("no end beside an approval").isEqualTo(ends);
            } else {
                assertThat(approved.stream().map(Object::toString)).as("each approver that lost is told it was rejected").allMatch(message -> message.contains("was REJECTED"));
                assertThat(row(JANUARY).status()).isEqualTo(PeriodStatus.LOCKED);
                assertThat(AccountingPeriodFixture.closedThrough()).isEqualTo(cut);
                assertThat(count("SELECT count(*) FROM accounting.period_unlock_end WHERE period_id = '" + period + "'")).isEqualTo(ends + 1);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** An unlock's end as a raw writer would insert it, copied from the period row like the store's: {@code outcomeAndEnder} the outcome and the ender's id, as SQL. */
    private static String rawEnd(String period, String outcomeAndEnder) {
        return "INSERT INTO accounting.period_unlock_end (id, period_id, period_revision, period_locked_at, proposed_by, proposed_by_type, proposed_at, outcome, ended_by, ended_by_type, ended_at,"
                + " end_reason, correlation_id) SELECT '" + IDS.next() + "', p.id, p.revision, p.locked_at, p.unlock_proposed_by, p.unlock_proposed_by_type, p.unlock_proposed_at, " + outcomeAndEnder
                + ", 'CUSTOMER', clock_timestamp(), 'raw', 'raw' FROM accounting.accounting_period p WHERE p.id = '" + period + "'";
    }

    // ------------------------------------------------------------------ row 4

    @Test
    @DisplayName("contention row 4, the entry's probe: the proposer cannot approve - the domain refuses; a raw approval by the proposer is refused by the row's CHECK and the history's, for the owner itself")
    void theProposerCannotApprove() throws Exception {
        PeriodCloses.Acted acted = proposed(JANUARY);
        PeriodCloseId proposal = acted.proposal().orElseThrow();
        assertThatThrownBy(() -> inScope(ACCOUNTANT, uow -> MACHINE.approve(uow, acted.id(), proposal, ACCOUNTANT, REASON, correlation())))
                .isInstanceOf(AccountingPeriods.SelfApprovalRefused.class);
        assertThat(row(JANUARY).status()).isEqualTo(PeriodStatus.CLOSE_PROPOSED);

        refused("UPDATE accounting.period_close SET status = 'APPROVED', decided_by = proposed_by, decided_by_type = 'CUSTOMER', decision_reason = 'raw' WHERE id = '" + proposal.value() + "'",
                "period_close_four_eyes");
        refused("INSERT INTO accounting.period_event (id, period_id, act, from_status, to_status, revision, actor, actor_type, proposed_by, reason, occurred_at, correlation_id) VALUES ('"
                + IDS.next() + "', '" + acted.id().value() + "', 'CLOSED', 'CLOSE_PROPOSED', 'CLOSED', 1, 'same', 'CUSTOMER', 'same', 'raw', clock_timestamp(), 'raw')", "period_event_four_eyes");
        refused("UPDATE accounting.period_close SET status = 'REJECTED', decided_by = 'platform', decided_by_type = 'SYSTEM', decision_reason = 'raw' WHERE id = '" + proposal.value() + "'",
                "period_close_persons");
    }

    // ------------------------------------------------------------------ readiness

    @Test
    @DisplayName("an incomplete run cannot be proposed: no run yet - the owner's (3) - a check FAILED, a break OPEN; and the edge is refused for every writer with no proposal under the lock")
    void anIncompleteRunCannotBeProposed() throws Exception {
        AccountingPeriodId january = PeriodCloseFixture.locked(JANUARY);
        assertThatThrownBy(() -> inScope(ACCOUNTANT, uow -> MACHINE.propose(uow, january, CloseRunId.next(IDS), List.of(), ACCOUNTANT, REASON, correlation())))
                .isInstanceOf(PeriodCloses.CloseNotReady.class).hasMessageContaining("no close run yet");

        CloseRunId run = PeriodCloseFixture.started(january);
        PeriodCloseFixture.concludeAll(Set.of(), Set.of(CloseCheckCode.PROJECTION_VERIFIED));
        assertThatThrownBy(() -> inScope(ACCOUNTANT, uow -> MACHINE.propose(uow, january, run, List.of(), ACCOUNTANT, REASON, correlation())))
                .isInstanceOf(PeriodCloses.CloseNotReady.class).hasMessageContaining("PROJECTION_VERIFIED");
        assertThat(row(JANUARY).status()).isEqualTo(PeriodStatus.LOCKED);

        refused("UPDATE accounting.accounting_period SET status = 'CLOSE_PROPOSED' WHERE id = '" + january.value() + "'", "by a proposal under its present lock");
        refused("UPDATE accounting.accounting_period SET status = 'CLOSED' WHERE id = '" + january.value() + "'", "not an edge");
        refused("INSERT INTO accounting.period_close (id, period_id, period_revision, period_locked_at, close_run_id, pinned_mapping_version_id, valuation_rate_set_id, breaks_acknowledged,"
                + " breaks_resolved, status, proposed_by, proposed_by_type, proposed_at, proposal_reason, correlation_id)"
                + " SELECT '" + IDS.next() + "', p.id, p.revision + 1, p.locked_at, '" + run.value() + "', p.pinned_mapping_version_id, (SELECT s.id FROM accounting.valuation_rate_set s WHERE s.period_id = p.id AND s.status = 'APPROVED'),"
                + " 0, 0, 'PROPOSED', 'raw', 'CUSTOMER', clock_timestamp(), 'raw', 'raw' FROM accounting.accounting_period p WHERE p.id = '" + january.value() + "'", "under its present lock");
    }

    @Test
    @DisplayName("a reason per DISCLOSED attempt, bound to the attempt read: none refused; a re-run landing while it was written refused as stale; the frozen row holds the attempt's evidence and the reason, for every writer INSERT-only")
    void aReasonIsBoundToTheAttemptRead() throws Exception {
        CloseRunId run = PeriodCloseFixture.ready(JANUARY, CloseCheckCode.RECONCILIATION_DISCLOSURE);
        AccountingPeriodId january = id(JANUARY);
        assertThatThrownBy(() -> inScope(ACCOUNTANT, uow -> MACHINE.propose(uow, january, run, List.of(), ACCOUNTANT, REASON, correlation()))).isInstanceOf(AccountingReasons.ReasonRequired.class);

        PeriodCloses.Disclosure read = new PeriodCloses.Disclosure(CloseCheckCode.RECONCILIATION_DISCLOSURE, 1, "two suspense items");
        inScope(ACCOUNTANT, uow -> RUNS.rerun(uow, january, CloseCheckCode.RECONCILIATION_DISCLOSURE, ACCOUNTANT, "a batch arrived", correlation()));
        PeriodCloseFixture.concludeAll(Set.of(CloseCheckCode.RECONCILIATION_DISCLOSURE), Set.of());
        assertThatThrownBy(() -> inScope(ACCOUNTANT, uow -> MACHINE.propose(uow, january, run, List.of(read), ACCOUNTANT, REASON, correlation()))).isInstanceOf(PeriodCloses.CloseProposalStale.class);

        PeriodCloses.Disclosure latest = new PeriodCloses.Disclosure(CloseCheckCode.RECONCILIATION_DISCLOSURE, 2, "two suspense items, read again");
        PeriodCloses.Acted acted = inScope(ACCOUNTANT, uow -> MACHINE.propose(uow, january, run, List.of(latest), ACCOUNTANT, REASON, correlation()));
        PeriodCloseId proposal = acted.proposal().orElseThrow();
        List<PeriodCloseStore.CheckRow> frozen = inScope(Actor.SYSTEM, uow -> CLOSES.checks(uow, proposal));
        assertThat(frozen).hasSize(CloseCheckCode.values().length);
        PeriodCloseStore.CheckRow disclosed = frozen.stream().filter(row -> row.check() == CloseCheckCode.RECONCILIATION_DISCLOSURE).findFirst().orElseThrow();
        assertThat(disclosed.attempt()).isEqualTo(2);
        assertThat(disclosed.disclosureReason()).contains("two suspense items, read again");
        assertThat(disclosed.evidence()).isPresent();

        refused("UPDATE accounting.period_close_check SET disclosure_reason = 'rewritten' WHERE close_id = '" + proposal.value() + "'", "append-only");
        refused("DELETE FROM accounting.period_close_check WHERE close_id = '" + proposal.value() + "'", "append-only");
        refused("DELETE FROM accounting.period_close WHERE id = '" + proposal.value() + "'", "never deleted");
        refused("UPDATE accounting.period_close SET proposal_reason = 'rewritten' WHERE id = '" + proposal.value() + "'", "frozen");
        try (Connection application = DatabaseRoles.application(); Statement statement = application.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE accounting.period_close_check SET disclosure_reason = 'rewritten'")).isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
    }

    @Test
    @DisplayName("what the approver approves: a later attempt, or a break raised, since the proposal refuses the approval; the trigger refuses a raw approval too; the way on is rejection and a new proposal")
    void whatMovedRefusesTheApproval() throws Exception {
        PeriodCloses.Acted acted = proposed(JANUARY);
        AccountingPeriodId january = acted.id();
        PeriodCloseId proposal = acted.proposal().orElseThrow();
        CloseRunId run = acted.run().orElseThrow();
        assertThat(inScope(CONTROLLER, uow -> MACHINE.proposalOf(uow, january)).orElseThrow().stillStands()).isTrue();
        assertThatThrownBy(() -> inScope(ACCOUNTANT, uow -> RUNS.rerun(uow, january, CloseCheckCode.RULE_CONFORMANCE, ACCOUNTANT, "again", correlation()))).as("no re-run while proposed")
                .isInstanceOf(AccountingPeriods.PeriodStale.class);

        inScope(Actor.SYSTEM, uow -> {
            RUN_STORE.insertAttempt(uow, new CloseRunStore.NewAttempt(CloseCheckId.next(IDS), run, CloseCheckCode.OPEN_ACCOUNTING_BREAKS, 2, Actor.SYSTEM, Optional.empty(), correlation()));
            return null;
        });
        assertThat(inScope(CONTROLLER, uow -> MACHINE.proposalOf(uow, january)).orElseThrow().stillStands()).isFalse();
        assertThatThrownBy(() -> inScope(CONTROLLER, uow -> MACHINE.approve(uow, january, proposal, CONTROLLER, REASON, correlation()))).isInstanceOf(PeriodCloses.CloseProposalStale.class);
        refused("UPDATE accounting.period_close SET status = 'APPROVED', decided_by = 'raw-approver', decided_by_type = 'CUSTOMER', decision_reason = 'raw' WHERE id = '" + proposal.value() + "'",
                "latest");

        PeriodCloses.Acted rejected = inScope(CONTROLLER, uow -> MACHINE.reject(uow, january, proposal, CONTROLLER, "the attempts moved", correlation()));
        assertThat(rejected.status()).isEqualTo(PeriodStatus.LOCKED);
        PeriodCloseFixture.concludeAll(Set.of(), Set.of());
        PeriodCloses.Acted again = inScope(ACCOUNTANT, uow -> MACHINE.propose(uow, january, run, List.of(), ACCOUNTANT, REASON, correlation()));
        assertThat(again.proposal()).isNotEqualTo(Optional.of(proposal));

        inScope(Actor.SYSTEM, uow -> BREAKS.insertIfAbsent(uow, new AccountingBreakStore.NewBreak(AccountingBreakId.next(IDS), JANUARY, finding("s-" + IDS.next(), "fails=[A]"), Actor.SYSTEM, correlation())));
        assertThatThrownBy(() -> inScope(CONTROLLER, uow -> MACHINE.approve(uow, january, again.proposal().orElseThrow(), CONTROLLER, REASON, correlation()))).as("a break raised since")
                .isInstanceOf(PeriodCloses.CloseProposalStale.class);
        refused("UPDATE accounting.period_close SET status = 'APPROVED', decided_by = 'raw-approver', decided_by_type = 'CUSTOMER', decision_reason = 'raw' WHERE id = '" + again.proposal().orElseThrow().value() + "'",
                "no break of its month OPEN");
        // RESOLVED leaves January's OPEN_ACCOUNTING_BREAKS PASSED, so the later cases' closes disclose nothing of it; an ACKNOWLEDGED break would ask each for a reason.
        AccountingBreakStore.BreakRow raised = inScope(Actor.SYSTEM, uow -> BREAKS.list(uow, JANUARY, Optional.of(BreakStatus.OPEN), Optional.empty(), 1)).get(0);
        inScope(CONTROLLER, uow -> BREAKS.resolve(uow, raised.id(), BreakStatus.OPEN, CONTROLLER, IDS.next(), "corrected"));
    }

    @Test
    @DisplayName("a rejection returns to the very lock and run: the proposal kept, proposable again; a pending unlock refuses a proposal and the machine's rows are unmoved")
    void aRejectionReturnsToTheLock() throws Exception {
        PeriodCloses.Acted acted = proposed(JANUARY);
        AccountingPeriodId january = acted.id();
        var lockedAt = row(JANUARY).lockedAt();
        inScope(ACCOUNTANT, uow -> MACHINE.reject(uow, january, acted.proposal().orElseThrow(), ACCOUNTANT, "my own mistake", correlation()));
        assertThat(row(JANUARY).status()).isEqualTo(PeriodStatus.LOCKED);
        assertThat(row(JANUARY).lockedAt()).isEqualTo(lockedAt);
        assertThat(PeriodCloseFixture.proposalStatus(january)).isEqualTo(PeriodCloseStatus.REJECTED);

        Actor proposer = person();
        inScope(proposer, uow -> periods().proposeUnlock(uow, january, proposer, "a mistake was found", correlation()));
        assertThatThrownBy(() -> inScope(ACCOUNTANT, uow -> MACHINE.propose(uow, january, acted.run().orElseThrow(), List.of(), ACCOUNTANT, REASON, correlation())))
                .isInstanceOf(AccountingPeriods.PeriodStale.class).hasMessageContaining("unlock");
        refused("INSERT INTO accounting.period_close (id, period_id, period_revision, period_locked_at, close_run_id, pinned_mapping_version_id, valuation_rate_set_id, breaks_acknowledged,"
                + " breaks_resolved, status, proposed_by, proposed_by_type, proposed_at, proposal_reason, correlation_id)"
                + " SELECT '" + IDS.next() + "', p.id, p.revision, p.locked_at, '" + acted.run().orElseThrow().value() + "', p.pinned_mapping_version_id, (SELECT s.id FROM accounting.valuation_rate_set s WHERE s.period_id = p.id AND s.status = 'APPROVED'),"
                + " 0, 0, 'PROPOSED', 'raw', 'CUSTOMER', clock_timestamp(), 'raw', 'raw' FROM accounting.accounting_period p WHERE p.id = '" + january.value() + "'", "no unlock pending");
    }

    @Test
    @DisplayName("period.open.age falls on close; the stalled gauge's statement reads the machine: CLOSE_PROPOSED and CLOSED not stalled, a rejection's LOCKED has its run")
    void theMetersReadTheMachine() throws Exception {
        AccountingPeriods read = periods();
        long before = inScope(Actor.SYSTEM, read::overdueDays);
        assertThat(before).isPositive();

        PeriodCloses.Acted acted = proposed(JANUARY);
        assertThat(inScope(Actor.SYSTEM, read::overdueDays)).as("January is not closed yet").isGreaterThanOrEqualTo(before);
        long whileProposed = inScope(Actor.SYSTEM, uow -> RUN_STORE.stalledLocks(uow, Duration.ZERO));
        assertThat(whileProposed).isZero();

        inScope(CONTROLLER, uow -> MACHINE.reject(uow, acted.id(), acted.proposal().orElseThrow(), CONTROLLER, "not yet", correlation()));
        long whileRejected = inScope(Actor.SYSTEM, uow -> RUN_STORE.stalledLocks(uow, Duration.ZERO));
        assertThat(whileRejected).as("its run answers its lock").isZero();

        PeriodCloses.Acted again = inScope(ACCOUNTANT, uow -> MACHINE.propose(uow, acted.id(), acted.run().orElseThrow(), List.of(), ACCOUNTANT, REASON, correlation()));
        inScope(CONTROLLER, uow -> MACHINE.approve(uow, again.id(), again.proposal().orElseThrow(), CONTROLLER, REASON, correlation()));
        assertThat(inScope(Actor.SYSTEM, read::overdueDays)).as("the oldest unclosed is February now, its grace long past: not January's age").isLessThan(before);
    }

    // ------------------------------------------------------------------ a break's generation

    @Test
    @DisplayName("the key holds rows already in the table: generation 1 by the default for a writer that names none; one per generation; a later one only over a decided one reading differently; a loser of the race writes nothing and meets no exception; the finding frozen")
    void theGenerationInTheKey() throws Exception {
        String subject = "LOAN_SUBLEDGER:gen-" + IDS.next();
        AccountingBreakId first = AccountingBreakId.next(IDS);
        raw("INSERT INTO accounting.accounting_break (id, period_start, check_code, break_type, subject_ref, evidence, judged_by, status, raised_at, raised_by, raised_by_type, correlation_id) VALUES ('"
                + first.value() + "', '1999-06-01', 'SOURCE_COMPLETENESS', 'SOURCE_DISCREPANCY', '" + subject + "', 'fails=[A]', 'source-proofs:v1', 'OPEN', clock_timestamp(), 'platform', 'SYSTEM', 'raw')");
        assertThat(count("SELECT generation FROM accounting.accounting_break WHERE id = '" + first.value() + "'")).as("a row as the table held it before V008").isEqualTo(1);

        boolean born = inScope(Actor.SYSTEM, uow -> BREAKS.insertIfAbsent(uow, new AccountingBreakStore.NewBreak(AccountingBreakId.next(IDS), QUIET, finding(subject, "fails=[A]"), Actor.SYSTEM, correlation())));
        assertThat(born).as("the same generation: the key, unchanged in effect").isFalse();
        // Raw, as the owner: the store's failure path keeps the SQLSTATE and deliberately drops the driver's text (DatabaseFailure), and it is this trigger's refusal being proven.
        refused(laterGeneration(subject, 2, "fails=[B]"), "a later generation");

        inScope(ACCOUNTANT, uow -> BREAKS.proposeAcknowledgement(uow, first, ACCOUNTANT, "disclosed"));
        inScope(CONTROLLER, uow -> BREAKS.approveAcknowledgement(uow, first, CONTROLLER, "agreed"));
        assertThat(inScope(Actor.SYSTEM, uow -> BREAKS.held(uow, QUIET, CloseCheckCode.SOURCE_COMPLETENESS)).get(subject).generation()).isEqualTo(1);
        refused(laterGeneration(subject, 2, "fails=[A]"), "a later generation");
        refused(laterGeneration(subject, 3, "fails=[B]"), "a later generation");

        ExecutorService pool = Executors.newFixedThreadPool(APPROVERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> racing = new ArrayList<>();
        try {
            for (int worker = 0; worker < APPROVERS; worker++) {
                racing.add(pool.submit(() -> {
                    start.await();
                    return inScope(Actor.SYSTEM, uow -> BREAKS.insertIfAbsent(uow, new AccountingBreakStore.NewBreak(AccountingBreakId.next(IDS), QUIET, finding(subject, "fails=[B]"), 2, Actor.SYSTEM, correlation())));
                }));
            }
            start.countDown();
            int births = 0;
            for (Future<Boolean> outcome : racing) {
                births += outcome.get(2, TimeUnit.MINUTES) ? 1 : 0;
            }
            assertThat(births).as("ten workers, one generation").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        Map<String, AccountingBreakStore.Held> held = inScope(Actor.SYSTEM, uow -> BREAKS.held(uow, QUIET, CloseCheckCode.SOURCE_COMPLETENESS));
        assertThat(held.get(subject).generation()).as("the latest is what a finding is compared with").isEqualTo(2);
        assertThat(held.get(subject).evidence()).isEqualTo("fails=[B]");
        assertThat(held.get(subject).status()).isEqualTo(BreakStatus.OPEN);
        refused("UPDATE accounting.accounting_break SET generation = 9 WHERE id = '" + first.value() + "'", "frozen");
        refused("INSERT INTO accounting.accounting_break (id, period_start, check_code, break_type, subject_ref, generation, evidence, judged_by, status, raised_at, raised_by, raised_by_type, correlation_id) VALUES ('"
                + IDS.next() + "', '1999-06-01', 'SOURCE_COMPLETENESS', 'SOURCE_DISCREPANCY', 'z', 0, 'e', 'source-proofs:v1', 'OPEN', clock_timestamp(), 'platform', 'SYSTEM', 'raw')", "accounting_break_generation_positive");
    }

    // ------------------------------------------------------------------ plumbing

    private static BreakFinding finding(String subject, String evidence) {
        return new BreakFinding(CloseCheckCode.SOURCE_COMPLETENESS, BreakType.SOURCE_DISCREPANCY, subject, evidence, SourceCompleteness.JUDGED_BY);
    }

    /** A break of {@code subject} at {@code generation}, as a raw writer would insert it. */
    private static String laterGeneration(String subject, int generation, String evidence) {
        return "INSERT INTO accounting.accounting_break (id, period_start, check_code, break_type, subject_ref, generation, evidence, judged_by, status, raised_at, raised_by, raised_by_type, correlation_id) VALUES ('"
                + IDS.next() + "', '1999-06-01', 'SOURCE_COMPLETENESS', 'SOURCE_DISCREPANCY', '" + subject + "', " + generation + ", '" + evidence + "', 'source-proofs:v1', 'OPEN', clock_timestamp(), 'platform', 'SYSTEM', 'raw')";
    }

    /** When the pending unlock was proposed, read as a person reads it before acting (`P14-TSK-024`). */
    private static Instant pendingUnlock(AccountingPeriodId period) {
        return inScope(Actor.SYSTEM, uow -> periods().pendingUnlock(uow, period)).orElseThrow();
    }

    private static void raw(String sql) throws SQLException {
        try (Connection owner = DatabaseRoles.migrator(); Statement statement = owner.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    /** {@code sql} as the owner itself - the trigger and the {@code CHECK}s bind every writer - refused, its message naming {@code words}. */
    private static void refused(String sql, String words) throws SQLException {
        try (Connection owner = DatabaseRoles.migrator(); Statement statement = owner.createStatement()) {
            assertThatThrownBy(() -> statement.executeUpdate(sql)).isInstanceOf(SQLException.class).hasMessageContaining(words);
        }
    }
}
