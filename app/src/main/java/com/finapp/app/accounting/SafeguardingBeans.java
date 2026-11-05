package com.finapp.app.accounting;

import com.finapp.accounting.CounterpartyPositionReads;
import com.finapp.accounting.LendingFundedAsOfCutoff;
import com.finapp.accounting.TrialBalanceReads;
import com.finapp.accounting.reporting.SafeguardingPositionDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the reference safeguarding return (`P14-TSK-019`; ADR-0118 decision 4) - its own configuration beside {@link OperationalReportBeans}, so the module's parallel tasks each add theirs without editing one file: the definition, which {@code AccountingBeans.reportDefinitions} collects, and the port it reads what the trial balance's reads cannot say through, {@code SELECT} only, on the run's snapshot. Its obligation enters through the four-eyes doors, with no migration and no
 * seed: {@code OPERATIONS_RUNBOOK.md} section 10.6 holds the configuration.
 */
@Configuration(proxyBeanMethods = false)
public class SafeguardingBeans {

    @Bean
    LendingFundedAsOfCutoff lendingFundedAsOfCutoff() {
        return new LendingFundedAsOfCutoffReads();
    }

    @Bean
    SafeguardingPositionDefinition safeguardingPositionDefinition(TrialBalanceReads trialBalanceReads, CounterpartyPositionReads counterpartyPositionReads, LendingFundedAsOfCutoff lendingFundedAsOfCutoff) {
        return new SafeguardingPositionDefinition(trialBalanceReads, counterpartyPositionReads, lendingFundedAsOfCutoff);
    }
}
