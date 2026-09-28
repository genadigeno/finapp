package com.finapp.app.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.finapp.payments.DisputeResponseStatus;
import com.finapp.payments.PaymentAttemptStatus;
import com.finapp.payments.RailId;
import com.finapp.payments.RefundStatus;
import com.finapp.payments.SimulatedCardPspAdapter;
import com.finapp.payments.SimulatedInstantSchemeAdapter;
import com.finapp.payments.WithdrawalStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The applier-side counting seam (`P7-TSK-015`): a judgement is counted once its transaction
 * COMMITS — never before, never for a rollback — a mid-question status counts nothing, and a meter
 * that fails never reaches the caller of a committed operation. Driven through Spring's own
 * synchronization registry, the mechanism production's transactions fire.
 */
@DisplayName("the committed rail outcomes (P7-TSK-015)")
class CommittedRailOutcomesTest {

    private static final RailId CARD = SimulatedCardPspAdapter.RAIL.id();
    private static final RailId INSTANT = SimulatedInstantSchemeAdapter.RAIL.id();

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("inside a transaction the count waits for the commit - and lands on both the"
            + " legacy and the rail series once it commits")
    void aJudgementCountsOnlyOnceItsTransactionCommits() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CommittedRailOutcomes observer = new CommittedRailOutcomes(new PaymentMeters(registry, "psp"));
        TransactionSynchronizationManager.initSynchronization();

        observer.attemptJudged(CARD, PaymentAttemptStatus.CAPTURED);
        observer.refundJudged(CARD, RefundStatus.COMPLETED);
        observer.withdrawalJudged(INSTANT, WithdrawalStatus.UNKNOWN);
        observer.disputeResponseJudged(CARD, DisputeResponseStatus.SUBMITTED);
        assertThat(attempts(registry, "captured"))
                .as("the transaction has not committed: nothing happened yet")
                .isZero();
        assertThat(rail(registry, CARD, "dispute_response", "submitted")).isZero();

        commit();
        assertThat(attempts(registry, "captured")).isEqualTo(1);
        assertThat(rail(registry, CARD, "payment", "captured")).isEqualTo(1);
        assertThat(rail(registry, CARD, "refund", "completed")).isEqualTo(1);
        assertThat(rail(registry, INSTANT, "withdrawal", "unknown")).isEqualTo(1);
        assertThat(rail(registry, CARD, "dispute_response", "submitted")).isEqualTo(1);
    }

    @Test
    @DisplayName("a rolled-back transaction's judgement never happened: nothing is counted")
    void aRolledBackJudgementCountsNothing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CommittedRailOutcomes observer = new CommittedRailOutcomes(new PaymentMeters(registry, "psp"));
        TransactionSynchronizationManager.initSynchronization();

        observer.attemptJudged(CARD, PaymentAttemptStatus.CAPTURED);
        observer.refundJudged(CARD, RefundStatus.FAILED);
        rollBack();

        assertThat(attempts(registry, "captured")).isZero();
        assertThat(registry.find(PaymentMeters.RAIL_OUTCOME).counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @Test
    @DisplayName("with no transaction to wait for, the count is immediate")
    void withoutATransactionTheCountIsImmediate() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CommittedRailOutcomes observer = new CommittedRailOutcomes(new PaymentMeters(registry, "psp"));

        observer.attemptJudged(INSTANT, PaymentAttemptStatus.EXECUTED);
        assertThat(rail(registry, INSTANT, "payment", "executed")).isEqualTo(1);
    }

    @Test
    @DisplayName("a mid-question status judged nothing: a void redirect, an opened initiation and"
            + " every dispatch count nothing")
    void aMidQuestionStatusCountsNothing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CommittedRailOutcomes observer = new CommittedRailOutcomes(new PaymentMeters(registry, "psp"));

        observer.attemptJudged(CARD, PaymentAttemptStatus.VOID_DISPATCHED);
        observer.attemptJudged(INSTANT, PaymentAttemptStatus.AWAITING_PAYER);
        observer.attemptJudged(CARD, PaymentAttemptStatus.AUTH_DISPATCHED);
        observer.refundJudged(CARD, RefundStatus.DISPATCHED);
        observer.withdrawalJudged(INSTANT, WithdrawalStatus.DISPATCHED);
        observer.disputeResponseJudged(CARD, DisputeResponseStatus.DISPATCHED);

        assertThat(registry.find(PaymentMeters.RAIL_OUTCOME).counters()).isEmpty();
        assertThat(registry.find(PaymentMeters.ATTEMPT).counters())
                .allSatisfy(counter -> assertThat(counter.count()).isZero());
    }

    @Test
    @DisplayName("every honest unknown IS a judgement (INV-LIFE-03): the void's and the execution's"
            + " unknowns count as unknown")
    void everyUnknownIsAJudgement() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CommittedRailOutcomes observer = new CommittedRailOutcomes(new PaymentMeters(registry, "psp"));

        observer.attemptJudged(CARD, PaymentAttemptStatus.VOID_UNKNOWN);
        observer.attemptJudged(INSTANT, PaymentAttemptStatus.EXECUTION_UNKNOWN);
        observer.attemptJudged(CARD, PaymentAttemptStatus.VOIDED);

        assertThat(rail(registry, CARD, "payment", "unknown")).isEqualTo(1);
        assertThat(rail(registry, INSTANT, "payment", "unknown")).isEqualTo(1);
        assertThat(rail(registry, CARD, "payment", "voided")).isEqualTo(1);
    }

    @Test
    @DisplayName("a meter that fails after the commit never reaches the caller: the operation"
            + " committed, and reading its meter's failure as the operation's would be a lie")
    void aFailingMeterNeverPropagates() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CommittedRailOutcomes observer = new CommittedRailOutcomes(new PaymentMeters(registry, "psp"));
        TransactionSynchronizationManager.initSynchronization();

        // A null rail makes the increment itself throw - inside afterCommit.
        observer.attemptJudged(null, PaymentAttemptStatus.CAPTURED);
        assertThatCode(CommittedRailOutcomesTest::commit).doesNotThrowAnyException();
        clearSynchronization();

        assertThatCode(() -> observer.refundJudged(null, RefundStatus.COMPLETED))
                .as("the immediate path swallows too")
                .doesNotThrowAnyException();
    }

    // -----------------------------------------------------------------

    private static void commit() {
        for (TransactionSynchronization synchronization :
                TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
        }
    }

    private static void rollBack() {
        for (TransactionSynchronization synchronization :
                TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
    }

    private static double attempts(SimpleMeterRegistry registry, String outcome) {
        Counter counter = registry.find(PaymentMeters.ATTEMPT).tag("outcome", outcome).counter();
        assertThat(counter).isNotNull();
        return counter.count();
    }

    private static double rail(SimpleMeterRegistry registry, RailId rail, String type, String outcome) {
        Counter counter =
                registry.find(PaymentMeters.RAIL_OUTCOME)
                        .tag("rail", rail.value())
                        .tag("type", type)
                        .tag("outcome", outcome)
                        .counter();
        return counter == null ? 0 : counter.count();
    }
}
