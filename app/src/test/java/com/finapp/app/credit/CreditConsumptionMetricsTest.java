package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.credit.ConsumptionObserver.Outcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The consumption's counter (`P11-TSK-001`): {@code finapp.credit.consumption{outcome}} published from startup for every
 * answer of the port, and counted only once the caller's transaction commits - a rolled-back consumption counts nothing.
 */
@DisplayName("the consumption counter counts after commit, every outcome published from startup (P11-TSK-001)")
class CreditConsumptionMetricsTest {

    @AfterEach
    void noSynchronisationLeaks() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("every outcome's series exists from construction, at zero, and nothing else")
    void publishedFromStartup() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new CreditConsumptionMetrics(registry);
        assertThat(registry.find(CreditConsumptionMetrics.CONSUMPTION).counters()).hasSize(Outcome.values().length);
        for (Outcome outcome : Outcome.values()) {
            assertThat(count(registry, outcome)).as(outcome.name()).isZero();
        }
        assertThat(registry.find(CreditConsumptionMetrics.CONSUMPTION).counters())
                .allSatisfy(counter -> assertThat(counter.getId().getTags()).singleElement()
                        .satisfies(tag -> assertThat(tag.getKey()).isEqualTo("outcome")));
    }

    @Test
    @DisplayName("inside a transaction the count waits for its commit; a rollback counts nothing; outside one, at once")
    void countedAfterCommitOnly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CreditConsumptionMetrics metrics = new CreditConsumptionMetrics(registry);

        TransactionSynchronizationManager.initSynchronization();
        metrics.answered(Outcome.CONSUMED);
        assertThat(count(registry, Outcome.CONSUMED)).as("nothing counted before the commit").isZero();
        List<TransactionSynchronization> rolledBack = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        rolledBack.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(count(registry, Outcome.CONSUMED)).as("a rolled-back consumption counts nothing").isZero();

        TransactionSynchronizationManager.initSynchronization();
        metrics.answered(Outcome.CONSUMED);
        List<TransactionSynchronization> committed = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        committed.forEach(TransactionSynchronization::afterCommit);
        assertThat(count(registry, Outcome.CONSUMED)).as("the committed consumption, once").isEqualTo(1.0);

        metrics.answered(Outcome.LAPSED);
        assertThat(count(registry, Outcome.LAPSED)).as("outside a transaction, at once").isEqualTo(1.0);
        assertThat(count(registry, Outcome.REPLAYED)).as("each outcome its own series").isZero();
    }

    private static double count(SimpleMeterRegistry registry, Outcome outcome) {
        Counter counter = registry.find(CreditConsumptionMetrics.CONSUMPTION)
                .tag("outcome", outcome.name().toLowerCase(Locale.ROOT)).counter();
        assertThat(counter).as("the %s series exists", outcome).isNotNull();
        return counter.count();
    }
}
