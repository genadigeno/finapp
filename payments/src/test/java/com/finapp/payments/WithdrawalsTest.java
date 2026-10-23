package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finapp.ledger.HoldStatus;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.IdempotencyConflictException;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotencyState;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The withdrawal engine judged by its risk seam, hermetically (`P13-TSK-009`; {@code PHASE_13_PLAN.md} §5, §7.3, §7.4,
 * §14; ADR-0062 §6; {@code INV-BAL-04}, {@code INV-RSK-02}, {@code INV-LIFE-02}, {@code INV-IDEM-01}): the real
 * {@link Withdrawals}, {@link WithdrawalOutcomes}, {@link WithdrawalResolution} and ledger {@code HoldService} over
 * {@link WithdrawalsWorld}'s in-memory stores, a scripted seam and a scripted rail. Each verdict's birth (DISPATCHED and
 * sent; HELD with the hold kept and nothing sent; FAILED(RISK_REFUSED) with the hold released and nothing sent), the
 * seam asked once per withdrawal and never on a replay, the held withdrawal's doors (DONE, ALREADY, REFUSED, the
 * release's takeover) and the expiry leg's claim and outcome on the store's own clock. The concurrency is the owed
 * {@code WithdrawalRiskSeamDatabaseTest}'s and {@code WithdrawalHoldDatabaseTest}'s.
 */
@DisplayName("the withdrawal engine and its risk seam (P13-TSK-009)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class WithdrawalsTest {

    private static final Actor PERSON = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);
    private static final Actor OPERATOR = new Actor(UUID.randomUUID().toString(), ActorType.EMPLOYEE);

    private WithdrawalsWorld world;
    private SecurityContext.Scope actor;
    private CorrelationContext.Scope correlation;

    @BeforeEach
    void enter() {
        world = new WithdrawalsWorld();
        actor = SecurityContext.enter(PERSON);
        correlation = CorrelationContext.enter(Correlation.startingWith(CorrelationId.generate(world.ids)));
    }

    @AfterEach
    void leave() {
        correlation.close();
        actor.close();
    }

    private Withdrawal held(String key) {
        world.verdict = WithdrawalVerdict.hold(Duration.ofHours(72));
        Withdrawals.Initiated initiated = world.withdraw(key, 2_600_00);
        assertThat(initiated.status()).isEqualTo(WithdrawalStatus.HELD);
        return world.row(initiated.id());
    }

    private Withdrawals.Held asOperator(java.util.function.Supplier<Withdrawals.Held> door) {
        try (SecurityContext.Scope operator = SecurityContext.enter(OPERATOR)) {
            return door.get();
        }
    }

    // ------------------------------------------------------------------ each verdict's birth

    @Test
    @DisplayName("PERMIT: born DISPATCHED as before Phase 13 - the hold kept for the wire, sent once")
    void permitDispatches() {
        Withdrawals.Initiated initiated = world.withdraw("k-permit", 50_00);
        // The scripted rail answers INDETERMINATE: the withdrawal is UNKNOWN with the hold standing.
        assertThat(initiated.status()).isEqualTo(WithdrawalStatus.UNKNOWN);
        assertThat(world.sends).hasSize(1);
        assertThat(world.holdOf(initiated.id()).status()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(world.row(initiated.id()).held()).isEmpty();
        assertThat(world.eventTypes()).contains(WithdrawalOutcomes.INITIATED_EVENT_TYPE);
        assertThat(world.auditCodes()).contains(PaymentsAuditAction.WITHDRAWAL_DISPATCHED.code());
    }

    @Test
    @DisplayName("HOLD: born HELD - the hold KEPT, nothing sent, the window from the verdict on the store's clock")
    void holdKeepsTheHold() {
        world.verdict = WithdrawalVerdict.hold(Duration.ofHours(72));
        Withdrawals.Initiated initiated = world.withdraw("k-hold", 2_600_00);
        Withdrawal row = world.row(initiated.id());
        assertThat(initiated.status()).isEqualTo(WithdrawalStatus.HELD);
        assertThat(initiated.failureReason()).isEmpty();
        assertThat(world.sends).as("a held withdrawal is never sent").isEmpty();
        assertThat(world.holdOf(initiated.id()).status()).as("INV-BAL-04: the hold kept").isEqualTo(HoldStatus.ACTIVE);
        assertThat(world.activeHolds()).isEqualTo(1);
        assertThat(row.held()).hasValueSatisfying(window -> {
            assertThat(window.at()).isEqualTo(world.clock.instant());
            assertThat(window.validity()).isEqualTo(Duration.ofHours(72));
        });
        assertThat(world.eventTypes()).contains(WithdrawalOutcomes.HELD_EVENT_TYPE)
                .doesNotContain(WithdrawalOutcomes.INITIATED_EVENT_TYPE);
        assertThat(world.auditCodes()).contains(PaymentsAuditAction.WITHDRAWAL_HELD.code())
                .doesNotContain(PaymentsAuditAction.WITHDRAWAL_DISPATCHED.code());
        assertThat(world.state.decisions).as("routed at birth, as every withdrawal").hasSize(1);
    }

    @Test
    @DisplayName("REFUSE: born FAILED(RISK_REFUSED) - the hold released in the same transaction, nothing sent")
    void refuseReleasesTheHold() {
        world.verdict = WithdrawalVerdict.refuse();
        Withdrawals.Initiated initiated = world.withdraw("k-refuse", 50_00);
        assertThat(initiated.status()).isEqualTo(WithdrawalStatus.FAILED);
        assertThat(initiated.failureReason()).contains(WithdrawalFailureReason.RISK_REFUSED);
        assertThat(world.sends).isEmpty();
        assertThat(world.holdOf(initiated.id()).status()).as("INV-BAL-04: released").isEqualTo(HoldStatus.RELEASED);
        assertThat(world.activeHolds()).isZero();
        assertThat(world.row(initiated.id()).held()).isEmpty();
        assertThat(world.eventTypes()).contains(WithdrawalOutcomes.FAILED_EVENT_TYPE);
        assertThat(world.state.audit).anySatisfy(record -> {
            assertThat(record.operation()).isEqualTo(PaymentsAuditAction.WITHDRAWAL_REFUSED);
            assertThat(record.actor()).isEqualTo(PERSON);
            assertThat(record.outcome()).isEqualTo(com.finapp.platform.audit.AuditOutcome.FAILED);
        });
    }

    @Test
    @DisplayName("the seam is asked once, in-lock after the hold, under the withdrawal's own id - and never on a"
            + " replay of the key")
    void theSeamIsAskedOncePerWithdrawal() {
        world.verdict = WithdrawalVerdict.hold(Duration.ofHours(1));
        Withdrawals.Initiated first = world.withdraw("k-once", 2_600_00);
        Withdrawals.Initiated replay = world.withdraw("k-once", 2_600_00);
        assertThat(world.judged).hasSize(1);
        WithdrawalRiskDecision.Judged judged = world.judged.get(0);
        assertThat(judged.id()).isEqualTo(first.id());
        assertThat(judged.partyId()).isEqualTo(world.party);
        assertThat(judged.walletAccountId()).isEqualTo(world.wallet.id());
        assertThat(judged.toString()).doesNotContain("2600").doesNotContain(world.party.toString());
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.status()).as("the key replays the judged status").isEqualTo(WithdrawalStatus.HELD);
        assertThat(world.state.withdrawals).hasSize(1);
    }

    @Test
    @DisplayName("an unfunded withdrawal is never judged: the hold refuses first, and the transaction writes"
            + " nothing")
    void anUnfundedWithdrawalIsNeverJudged() {
        world.settled = com.finapp.sharedkernel.money.Money.ofMinorUnits(10_00, WithdrawalsWorld.EUR);
        assertThatThrownBy(() -> world.withdraw("k-poor", 50_00)).isInstanceOf(WithdrawalUnfundedException.class);
        assertThat(world.judged).isEmpty();
        assertThat(world.state.withdrawals).isEmpty();
        assertThat(world.state.holds).isEmpty();
    }

    @Test
    @DisplayName("a seam failure aborts the dispatch whole: no row, no hold, the key unburned")
    void aSeamFailureAbortsTheDispatch() {
        world.seamFailure = new IllegalStateException("the seal could not be written");
        assertThatThrownBy(() -> world.withdraw("k-abort", 50_00)).isInstanceOf(IllegalStateException.class);
        assertThat(world.state.withdrawals).isEmpty();
        assertThat(world.state.holds).isEmpty();
        assertThat(world.state.claims).isEmpty();
        assertThat(world.sends).isEmpty();
    }

    @Test
    @DisplayName("the instruction carries out a credit decision and is never judged")
    void theInstructionIsNotJudged() {
        world.seamFailure = new IllegalStateException("an instruction is never judged");
        com.finapp.ledger.Hold adopted;
        try (SecurityContext.Scope platform = SecurityContext.enter(Actor.SYSTEM)) {
            adopted = world.holdService.place(WithdrawalsWorld.CONNECTION, world.wallet.id(),
                    com.finapp.sharedkernel.money.Money.ofMinorUnits(40_00, WithdrawalsWorld.EUR));
            Withdrawals.Initiated initiated = world.engine.withdrawOnInstruction(
                    new Withdrawals.Instruction("lending:" + UUID.randomUUID(),
                            com.finapp.sharedkernel.money.Money.ofMinorUnits(40_00, WithdrawalsWorld.EUR),
                            adopted.id()),
                    uow -> world.resolved());
            assertThat(initiated.status()).isEqualTo(WithdrawalStatus.UNKNOWN);
        }
        assertThat(world.judged).isEmpty();
        assertThat(world.sends).hasSize(1);
    }

    // ------------------------------------------------------------------ the held withdrawal's doors

    @Test
    @DisplayName("release: HELD -> DISPATCHED under its key, sent once, the release audited as the caller's"
            + " act with its reason code")
    void theReleaseDispatches() {
        Withdrawal held = held("k-r");
        world.clock.advance(Duration.ofMinutes(1));
        Withdrawals.Held released = asOperator(() ->
                world.engine.releaseHeld("risk.action:r1", held.id(), "OVERRIDE_APPROVED"));
        assertThat(released.outcome()).isEqualTo(Withdrawals.HeldOutcome.DONE);
        assertThat(released.status()).contains(WithdrawalStatus.UNKNOWN);
        assertThat(world.sends).hasSize(1);
        Withdrawal row = world.row(held.id());
        assertThat(row.heldResolutionKey()).contains("risk.action:r1");
        assertThat(row.lastDispatchedAt()).as("the first real send permit").isAfter(held.lastDispatchedAt());
        assertThat(row.held()).isEqualTo(held.held());
        assertThat(world.holdOf(held.id()).status()).as("the dispatched withdrawal's hold stands")
                .isEqualTo(HoldStatus.ACTIVE);
        assertThat(world.eventTypes()).contains(WithdrawalOutcomes.RELEASED_EVENT_TYPE);
        assertThat(world.state.audit).anySatisfy(record -> {
            assertThat(record.operation()).isEqualTo(PaymentsAuditAction.WITHDRAWAL_RELEASED);
            assertThat(record.actor()).isEqualTo(OPERATOR);
            assertThat(record.reason()).contains("OVERRIDE_APPROVED");
        });
        assertThat(world.state.claims.get(new IdempotencyKey(Withdrawals.RELEASE_SCOPE, "risk.action:r1"))
                .state()).isEqualTo(IdempotencyState.COMPLETED);
    }

    @Test
    @DisplayName("ten releases of one held withdrawal dispatch once: one DONE, nine ALREADY, one send; the same"
            + " key replays DONE")
    void tenReleasesDispatchOnce() {
        Withdrawal held = held("k-ten");
        int done = 0;
        int already = 0;
        for (int i = 0; i < 10; i++) {
            String key = "risk.action:ten-" + i;
            Withdrawals.Held answer = asOperator(() -> world.engine.releaseHeld(key, held.id(), "OVERRIDE_APPROVED"));
            if (answer.outcome() == Withdrawals.HeldOutcome.DONE) {
                done++;
            } else if (answer.outcome() == Withdrawals.HeldOutcome.ALREADY) {
                already++;
            }
        }
        assertThat(done).isEqualTo(1);
        assertThat(already).isEqualTo(9);
        assertThat(world.sends).hasSize(1);
        Withdrawals.Held replay = asOperator(() ->
                world.engine.releaseHeld("risk.action:ten-0", held.id(), "OVERRIDE_APPROVED"));
        assertThat(replay.outcome()).isEqualTo(Withdrawals.HeldOutcome.DONE);
        assertThat(replay.replayed()).isTrue();
        assertThat(world.sends).hasSize(1);
        assertThat(world.state.history).containsOnlyOnce(held.id() + ":HELD->DISPATCHED");
    }

    @Test
    @DisplayName("deny: HELD -> FAILED(RISK_REFUSED) and the hold released in one transaction; a re-driven key"
            + " replays; a second key reads ALREADY")
    void theDenialReleasesTheHold() {
        Withdrawal held = held("k-d");
        Withdrawals.Held denied = asOperator(() ->
                world.engine.denyHeld("risk.action:d1", held.id(), "OVERRIDE_REJECTED"));
        assertThat(denied.outcome()).isEqualTo(Withdrawals.HeldOutcome.DONE);
        assertThat(denied.status()).contains(WithdrawalStatus.FAILED);
        Withdrawal row = world.row(held.id());
        assertThat(row.failureReason()).contains(WithdrawalFailureReason.RISK_REFUSED);
        assertThat(row.failureDetail()).isEmpty();
        assertThat(row.heldResolutionKey()).contains("risk.action:d1");
        assertThat(world.holdOf(held.id()).status()).isEqualTo(HoldStatus.RELEASED);
        assertThat(world.sends).isEmpty();
        assertThat(world.auditCodes()).contains(PaymentsAuditAction.WITHDRAWAL_DENIED.code());

        Withdrawals.Held replay = asOperator(() ->
                world.engine.denyHeld("risk.action:d1", held.id(), "OVERRIDE_REJECTED"));
        assertThat(replay.outcome()).isEqualTo(Withdrawals.HeldOutcome.DONE);
        assertThat(replay.replayed()).isTrue();
        Withdrawals.Held second = asOperator(() ->
                world.engine.denyHeld("risk.action:d2", held.id(), "OVERRIDE_REJECTED"));
        assertThat(second.outcome()).isEqualTo(Withdrawals.HeldOutcome.ALREADY);
        Withdrawals.Held release = asOperator(() ->
                world.engine.releaseHeld("risk.action:d3", held.id(), "OVERRIDE_APPROVED"));
        assertThat(release.outcome()).as("a denied withdrawal is never released").isEqualTo(
                Withdrawals.HeldOutcome.ALREADY);
        assertThat(world.sends).isEmpty();
    }

    @Test
    @DisplayName("an unknown withdrawal is REFUSED; a key reused for another withdrawal is the conflict")
    void unknownAndReusedKeys() {
        Withdrawal held = held("k-u");
        assertThat(asOperator(() -> world.engine.releaseHeld("risk.action:u", WithdrawalId.next(world.ids),
                "OVERRIDE_APPROVED")).outcome()).isEqualTo(Withdrawals.HeldOutcome.REFUSED);
        assertThatThrownBy(() -> asOperator(() ->
                world.engine.releaseHeld("risk.action:u", held.id(), "OVERRIDE_APPROVED")))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(world.row(held.id()).status()).isEqualTo(WithdrawalStatus.HELD);
    }

    @Test
    @DisplayName("the doors take a reason code, never a note, and only from the platform or an operator")
    void theDoorsPreconditions() {
        Withdrawal held = held("k-p");
        assertThatThrownBy(() -> asOperator(() ->
                world.engine.releaseHeld("risk.action:p", held.id(), "the customer called us")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> world.engine.denyHeld("risk.action:p", held.id(), "OVERRIDE_REJECTED"))
                .as("a customer never releases or denies a held withdrawal")
                .isInstanceOf(IllegalStateException.class);
        assertThat(world.row(held.id()).status()).isEqualTo(WithdrawalStatus.HELD);
    }

    @Test
    @DisplayName("the release's takeover: a key whose flight was lost after its Tx1 re-sends behind a renewed"
            + " permit; another key reads ALREADY")
    void theReleaseTakeover() {
        Withdrawal held = held("k-t");
        // The flight is lost on the wire: Tx1 committed the release, the claim stays IN_PROGRESS.
        world.answer = null;
        assertThatThrownBy(() -> asOperator(() ->
                world.engine.releaseHeld("risk.action:t", held.id(), "OVERRIDE_APPROVED")))
                .isInstanceOf(RuntimeException.class);
        assertThat(world.row(held.id()).status()).isEqualTo(WithdrawalStatus.DISPATCHED);
        assertThat(world.state.claims.get(new IdempotencyKey(Withdrawals.RELEASE_SCOPE, "risk.action:t"))
                .state()).isEqualTo(IdempotencyState.IN_PROGRESS);
        assertThat(asOperator(() -> world.engine.releaseHeld("risk.action:other", held.id(), "OVERRIDE_APPROVED"))
                .outcome()).isEqualTo(Withdrawals.HeldOutcome.ALREADY);

        world.answer = PushAnswer.indeterminate();
        world.clock.advance(Duration.ofMinutes(6)); // past the claim's lease
        Withdrawals.Held retaken = asOperator(() ->
                world.engine.releaseHeld("risk.action:t", held.id(), "OVERRIDE_APPROVED"));
        assertThat(retaken.outcome()).isEqualTo(Withdrawals.HeldOutcome.DONE);
        assertThat(world.sends).as("the lost send and the takeover's re-send of OUR reference").hasSize(2);
        assertThat(world.sends.get(1).reference()).isEqualTo(world.sends.get(0).reference());
    }

    // ------------------------------------------------------------------ the expiry leg (WithdrawalResolutionSchedule's)

    @Test
    @DisplayName("past held_until on the store's clock: FAILED(RISK_REFUSED, HOLD_EXPIRED), the hold released,"
            + " once, as the platform")
    void theExpiryReleasesTheHoldOnce() {
        Withdrawal held = held("k-e");
        assertThat(world.resolution.expireHolds().expired()).as("not yet due").isZero();
        world.clock.advance(Duration.ofHours(72));
        WithdrawalResolution.ExpiryResult result = world.resolution.expireHolds();
        assertThat(result.expired()).isEqualTo(1);
        Withdrawal row = world.row(held.id());
        assertThat(row.status()).isEqualTo(WithdrawalStatus.FAILED);
        assertThat(row.failureReason()).contains(WithdrawalFailureReason.RISK_REFUSED);
        assertThat(row.failureDetail()).contains(WithdrawalFailureDetail.HOLD_EXPIRED);
        assertThat(row.heldResolutionKey()).isEmpty();
        assertThat(world.holdOf(held.id()).status()).isEqualTo(HoldStatus.RELEASED);
        assertThat(world.state.audit).anySatisfy(record -> {
            assertThat(record.operation()).isEqualTo(PaymentsAuditAction.WITHDRAWAL_HOLD_EXPIRED);
            assertThat(record.actor()).isEqualTo(Actor.SYSTEM);
        });
        assertThat(world.resolution.expireHolds().expired()).as("once").isZero();
        assertThat(world.sends).isEmpty();
    }

    @Test
    @DisplayName("a release racing the expiry leaves exactly one outcome: the release won, the expiry finds"
            + " nothing; the expiry won, the release reads ALREADY")
    void releaseAndExpiryLeaveExactlyOne() {
        Withdrawal first = held("k-x1");
        Withdrawal second = held("k-x2");
        // The release first, within the validity: the expiry claims only HELD rows.
        assertThat(asOperator(() -> world.engine.releaseHeld("risk.action:x1", first.id(), "OVERRIDE_APPROVED"))
                .outcome()).isEqualTo(Withdrawals.HeldOutcome.DONE);
        world.clock.advance(Duration.ofHours(73));
        assertThat(world.resolution.expireHolds().expired()).as("only the second").isEqualTo(1);
        assertThat(world.row(first.id()).status()).isNotEqualTo(WithdrawalStatus.FAILED);
        // The expiry first: the release reads ALREADY and sends nothing.
        assertThat(asOperator(() -> world.engine.releaseHeld("risk.action:x2", second.id(), "OVERRIDE_APPROVED"))
                .outcome()).isEqualTo(Withdrawals.HeldOutcome.ALREADY);
        assertThat(world.row(second.id()).failureDetail()).contains(WithdrawalFailureDetail.HOLD_EXPIRED);
        assertThat(world.sends).as("the first's release only").hasSize(1);
    }

    @Test
    @DisplayName("a release past held_until, before the sweep, is REFUSED with nothing written - the expiry is the"
            + " policy's outcome, and the leg then fails it")
    void aLateReleaseIsRefused() {
        Withdrawal held = held("k-late");
        world.clock.advance(Duration.ofHours(72));
        Withdrawals.Held late = asOperator(() ->
                world.engine.releaseHeld("risk.action:late", held.id(), "OVERRIDE_APPROVED"));
        assertThat(late.outcome()).isEqualTo(Withdrawals.HeldOutcome.REFUSED);
        assertThat(late.status()).contains(WithdrawalStatus.HELD);
        assertThat(world.row(held.id()).status()).isEqualTo(WithdrawalStatus.HELD);
        assertThat(world.sends).isEmpty();
        assertThat(world.resolution.expireHolds().expired()).isEqualTo(1);
        assertThat(world.row(held.id()).failureDetail()).contains(WithdrawalFailureDetail.HOLD_EXPIRED);
    }

    @Test
    @DisplayName("one row's failure never starves the rest: it is passed over for the pass")
    void aFailingRowIsPassedOver() {
        Withdrawal poisoned = held("k-poison");
        Withdrawal healthy = held("k-healthy");
        // The poisoned row's hold was released by another hand - an invariant broken, loud.
        world.state.holds.put(poisoned.holdId(), world.state.holds.get(poisoned.holdId()).release(world.clock));
        world.clock.advance(Duration.ofHours(73));
        WithdrawalResolution.ExpiryResult result = world.resolution.expireHolds();
        assertThat(result.expired()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(world.row(healthy.id()).status()).isEqualTo(WithdrawalStatus.FAILED);
        assertThat(world.row(poisoned.id()).status()).isEqualTo(WithdrawalStatus.HELD);
    }
}
