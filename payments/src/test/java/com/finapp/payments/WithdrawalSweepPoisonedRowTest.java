package com.finapp.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.persistence.SweepBackoff;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.ActorType;
import com.finapp.platform.security.SecurityContext;
import com.finapp.platform.testing.ScriptedListingConnection;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The withdrawal sweep survives a poisoned row (`P16-TSK-017`; {@code OPERATIONS_RUNBOOK.md} section 11.39 (a) and (b)): the REAL
 * {@link WithdrawalResolution} over the withdrawal world, whose store keeps {@code payments.sweep_row_failure}'s rule in memory -
 * one count per backoff window, the base doubled per failure, capped. What PostgreSQL does with the statement is
 * {@code SweepListingStatementsTest}'s shape and the owed database case.
 */
@DisplayName("the withdrawal sweep skips an unreadable row, backs off a failing one, never judges either, and contains its own"
        + " record's failure (P16-TSK-017)")
@SuppressWarnings("try") // Scopes are used for their close side effect (the established idiom).
class WithdrawalSweepPoisonedRowTest {

    private static final Actor PERSON = new Actor(UUID.randomUUID().toString(), ActorType.CUSTOMER);

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

    /** A withdrawal in flight, past the sweep's bounds: its inquiry throws every time it is asked. */
    private WithdrawalId failingEveryTick() {
        Withdrawals.Initiated initiated = world.withdraw("k-poisoned", 50_00);
        assertThat(world.row(initiated.id()).status().isResolvable()).as("a candidate of the sweep").isTrue();
        world.clock.advance(Duration.ofMinutes(15));
        world.inquiryFailure = new IllegalStateException("an answer the adapter cannot read");
        return initiated.id();
    }

    @Test
    @DisplayName("an unreadable row and a row failing every tick: each skipped or contained, recorded once per window, left out"
            + " while its backoff runs, listed again after it - and never judged")
    void eachIsRecordedAndBackedOffAndNeverJudged() {
        WithdrawalId failing = failingEveryTick();
        WithdrawalStatus before = world.row(failing).status();
        UUID corrupt = ScriptedListingConnection.versionFour();
        world.store.unreadable.add(corrupt);

        WithdrawalResolution.SweepResult first = world.resolution.sweep();
        assertThat(first.unreadable()).isEqualTo(1);
        assertThat(first.failedRows()).isEqualTo(1);
        assertThat(world.state.sweepFailures).containsEntry(corrupt, 1).containsEntry(failing.value(), 1);

        WithdrawalResolution.SweepResult inBackoff = world.resolution.sweep();
        assertThat(inBackoff.candidates() + inBackoff.unreadable()).as("both left out while their retry is ahead").isZero();
        assertThat(world.state.sweepFailures).containsEntry(corrupt, 1).containsEntry(failing.value(), 1);

        world.clock.advance(SweepBackoff.BASE.plusSeconds(1));
        WithdrawalResolution.SweepResult due = world.resolution.sweep();
        assertThat(due.unreadable()).isEqualTo(1);
        assertThat(due.failedRows()).isEqualTo(1);
        assertThat(world.state.sweepFailures).containsEntry(corrupt, 2).containsEntry(failing.value(), 2);
        assertThat(world.state.sweepRetries.get(failing.value())).as("the second failure waits twice the base")
                .isEqualTo(world.clock.instant().plus(SweepBackoff.BASE.multipliedBy(2)));

        for (int tick = 0; tick < 8; tick++) {
            world.clock.advance(SweepBackoff.CAP.plusSeconds(1));
            world.resolution.sweep();
        }
        assertThat(world.state.sweepRetries.get(failing.value())).as("capped: a recovered row is listed again within the cap")
                .isEqualTo(world.clock.instant().plus(SweepBackoff.CAP));
        assertThat(world.row(failing).status()).as("never judged: it waits, as before").isEqualTo(before);
    }

    @Test
    @DisplayName("a record that fails is contained: the sweep still ends and reaches every row, as before the record existed")
    void aFailedRecordIsContained() {
        WithdrawalId failing = failingEveryTick();
        world.store.unreadable.add(ScriptedListingConnection.versionFour());
        world.store.recordFailure = new IllegalStateException("the record's table is unreachable");
        WithdrawalResolution.SweepResult result = world.resolution.sweep();
        assertThat(result.unreadable()).isEqualTo(1);
        assertThat(result.failedRows()).isEqualTo(1);
        assertThat(world.state.sweepFailures).isEmpty();
        assertThat(world.resolution.sweep().failedRows()).as("not backed off: tried again on the next tick").isEqualTo(1);
        assertThat(world.row(failing).status().isResolvable()).isTrue();
    }
}
