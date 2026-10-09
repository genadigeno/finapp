package com.finapp.app.credit;

import static org.assertj.core.api.Assertions.assertThat;

import com.finapp.credit.CreditDataObserver.Outcome;
import com.finapp.credit.CreditSourceKind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Credit data collection's meters (`P10-TSK-006`, `P10-TSK-020`): an answer is counted only once the transaction that
 * records it commits - a rolled-back answer counts nothing - and every declared source has its series, with every
 * outcome, from startup.
 */
@DisplayName("credit data collection's meters count after commit, published from startup (P10-TSK-020)")
class CreditDataMetricsTest {

    @AfterEach
    void noSynchronisationLeaks() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("inside a transaction the count waits for its commit; a rollback counts nothing")
    void countedAfterCommitOnly() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CreditDataMetrics metrics = new CreditDataMetrics(registry, List.of(new UnconfiguredBureau()));

        TransactionSynchronizationManager.initSynchronization();
        metrics.answered(CreditSourceKind.BUREAU, UnconfiguredBureau.CODE, Outcome.UNAVAILABLE);
        assertThat(unavailable(registry)).as("nothing counted before the commit").isZero();
        List<TransactionSynchronization> rolledBack = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        rolledBack.forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        assertThat(unavailable(registry)).as("a rolled-back answer counts nothing").isZero();

        TransactionSynchronizationManager.initSynchronization();
        metrics.answered(CreditSourceKind.BUREAU, UnconfiguredBureau.CODE, Outcome.UNAVAILABLE);
        List<TransactionSynchronization> committed = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        committed.forEach(TransactionSynchronization::afterCommit);
        assertThat(unavailable(registry)).as("the committed answer, once").isEqualTo(1.0);

        metrics.answered(CreditSourceKind.BUREAU, UnconfiguredBureau.CODE, Outcome.UNAVAILABLE);
        assertThat(unavailable(registry)).as("outside a transaction, at once").isEqualTo(2.0);
    }

    @Test
    @DisplayName("every declared source's series, every outcome, from startup; the latency timed per call")
    void publishedFromStartup() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CreditDataMetrics metrics =
                new CreditDataMetrics(registry, List.of(new UnconfiguredBureau(), new UnconfiguredFinancialData()));
        for (Outcome outcome : Outcome.values()) {
            String tag = outcome.name().toLowerCase(Locale.ROOT);
            assertThat(registry.get(CreditDataMetrics.REQUEST).tags("source_kind", "bureau", "provider",
                    UnconfiguredBureau.CODE, "outcome", tag).counter().count()).isZero();
            assertThat(registry.get(CreditDataMetrics.REQUEST).tags("source_kind", "financial_data", "provider",
                    UnconfiguredFinancialData.CODE, "outcome", tag).counter().count()).isZero();
        }
        metrics.called(CreditSourceKind.FINANCIAL_DATA, UnconfiguredFinancialData.CODE, Duration.ofMillis(40));
        assertThat(registry.get(CreditDataMetrics.LATENCY).tags("source_kind", "financial_data").timer().count())
                .isEqualTo(1);
    }

    private static double unavailable(SimpleMeterRegistry registry) {
        return registry.get(CreditDataMetrics.REQUEST)
                .tags("source_kind", "bureau", "provider", UnconfiguredBureau.CODE, "outcome", "unavailable")
                .counter().count();
    }
}
