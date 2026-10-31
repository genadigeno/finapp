package com.finapp.app.accounting;

import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.JdbcValuationRateSetStore;
import com.finapp.accounting.ValuationRateSetStore;
import com.finapp.accounting.ValuationRateSets;
import com.finapp.accounting.ValuationRates;
import com.finapp.accounting.ValuationStaleness;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for accounting's valuation rate sets (`P14-TSK-014`) - a configuration of its own beside {@code AccountingBeans},
 * whose unit of work, flow scope and period store it reuses: the {@link ValuationRates} port over fx's reference
 * snapshots, the staleness bound, the store, the domain and its desk.
 */
@Configuration(proxyBeanMethods = false)
public class AccountingValuationBeans {

    /** accounting's port over fx's reference snapshots - {@code fx.rate_snapshot} only, SELECT only. */
    @Bean
    ValuationRates valuationRates() {
        return FxReferenceValuationRates.reference();
    }

    /** How old a reference observation may be before the period's end - configuration (default 96 h, 1 to 336). */
    @Bean
    ValuationStaleness valuationStaleness(
            @Value("${finapp.accounting.valuation.max-age-hours:" + ValuationStaleness.DEFAULT_HOURS + "}") int hours) {
        return new ValuationStaleness(hours);
    }

    @Bean
    ValuationRateSetStore valuationRateSetStore() {
        return new JdbcValuationRateSetStore();
    }

    @Bean
    ValuationRateSets valuationRateSets(
            ValuationRateSetStore valuationRateSetStore,
            AccountingPeriodStore accountingPeriodStore,
            ValuationRates valuationRates,
            ValuationStaleness valuationStaleness,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new ValuationRateSets(valuationRateSetStore, accountingPeriodStore, valuationRates, valuationStaleness,
                auditWriter, idGenerator, clock);
    }

    @Bean
    ValuationRateSetDesk valuationRateSetDesk(
            ValuationRateSets valuationRateSets,
            IdempotentExecutor idempotentExecutor,
            AccountingTransactions accountingTransactions,
            AccountingFlowScope accountingFlowScope) {
        return new ValuationRateSetDesk(valuationRateSets, idempotentExecutor, accountingTransactions,
                accountingFlowScope);
    }
}
