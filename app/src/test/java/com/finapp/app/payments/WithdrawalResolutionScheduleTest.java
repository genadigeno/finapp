package com.finapp.app.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.payments.TransactionRunner;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The withdrawal schedule's two legs a tick (`P13-TSK-009`): the inquiry sweep, then the held withdrawal's expiry - each
 * contained, so a failing leg never starves the other and never escapes the tick. The legs' own behaviour is payments'
 * {@code WithdrawalsTest}'s (hermetic) and the owed {@code WithdrawalHoldDatabaseTest}'s; the schedule stays one class
 * in {@code LEASE_PROTECTED_SCHEDULERS} (a leg, not a scheduler).
 */
@DisplayName("the withdrawal schedule: two legs a tick, each contained (P13-TSK-009)")
class WithdrawalResolutionScheduleTest {

    @Test
    @DisplayName("a tick runs the inquiry sweep and then the expiry leg; both failing, the tick still returns")
    void aTickRunsBothLegsContained() {
        List<String> transactions = new ArrayList<>();
        TransactionRunner failing = new TransactionRunner() {
            @Override
            public <R> R inTransaction(Function<Connection, R> work) {
                transactions.add("transaction");
                throw new IllegalStateException("the database is away");
            }
        };
        WithdrawalResolutionSchedule schedule = new WithdrawalResolutionSchedule(
                WithdrawalDoorComposition.resolution(failing), Duration.ofSeconds(30));
        schedule.sweepQuietly();
        assertThat(transactions).as("the sweep's candidate read, then the expiry's first claim").hasSize(2);
        assertThat(schedule.isRunning()).isFalse();
    }
}
