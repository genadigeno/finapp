package com.finapp.app.accounting;

import com.finapp.accounting.GlMappingStore;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.ValuationRateReads;
import com.finapp.accounting.ValuationRateSetStore;
import com.finapp.accounting.reporting.TrialBalanceDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the period trial balance (`P14-TSK-009`) - its own configuration beside {@link AccountingBeans} and
 * {@link GeneralLedgerBeans}, so the module's parallel tasks each add theirs without editing one file: the port over the
 * journal and accounting's period and mapping tables, and the {@code TRIAL_BALANCE} definition - the platform's first
 * production {@code ReportDefinition}, which {@code AccountingBeans.reportDefinitions} collects, so
 * {@code POST /v1/operator/accounting/report-runs} serves it.
 */
@Configuration(proxyBeanMethods = false)
public class TrialBalanceBeans {

    /** What the trial balance reads - {@code SELECT} only, on the generation's read-only snapshot. */
    @Bean
    TrialBalanceReads trialBalanceReads(GlMappingStore glMappingStore) {
        return new LedgerTrialBalanceReads(glMappingStore);
    }

    /** What a translated report reads of its pinned set (`P14-TSK-015`) - {@code SELECT} only, on the snapshot. */
    @Bean
    ValuationRateReads valuationRateReads(ValuationRateSetStore valuationRateSetStore) {
        return new PinnedValuationRates(valuationRateSetStore);
    }

    /** {@code TRIAL_BALANCE} version 1 - plain, or translated when a run names its period's approved set. */
    @Bean
    TrialBalanceDefinition trialBalanceDefinition(
            TrialBalanceReads trialBalanceReads, ValuationRateReads valuationRateReads) {
        return new TrialBalanceDefinition(trialBalanceReads, valuationRateReads);
    }
}
