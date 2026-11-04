package com.finapp.app.lending;

import com.finapp.identity.IdentityStore;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingService;
import com.finapp.ledger.PurposeBalances;
import com.finapp.lending.InstalmentBillingStore;
import com.finapp.lending.JdbcLoanWriteOffStore;
import com.finapp.lending.LoanClosures;
import com.finapp.lending.LoanConditionStore;
import com.finapp.lending.LoanStore;
import com.finapp.lending.LoanTermsStore;
import com.finapp.lending.LoanWriteOffStore;
import com.finapp.lending.LoanWriteOffs;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A loan write-off's wiring (`P14-TSK-021`, owner decision C6 (A)): the store, the machine, the meters and the desk - a configuration
 * of its own, so parallel tasks append to no common bean method. The headroom and the proofs need nothing wired: they read the entry.
 */
@Configuration(proxyBeanMethods = false)
public class LoanWriteOffBeans {

    @Bean
    LoanWriteOffStore loanWriteOffStore() {
        return new JdbcLoanWriteOffStore();
    }

    @Bean
    LoanWriteOffs loanWriteOffs(
            LoanStore loanStore,
            LoanWriteOffStore loanWriteOffStore,
            LoanConditionStore loanConditionStore,
            LoanTermsStore loanTermsStore,
            InstalmentBillingStore instalmentBillingStore,
            LedgerAccountStore<Connection> ledgerAccountStore,
            PurposeBalances<Connection> purposeBalances,
            LoanClosures loanClosures,
            PostingService postingService,
            IdentityStore<Connection> identityStore,
            OutboxWriter<Connection> outboxWriter,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new LoanWriteOffs(loanStore, loanWriteOffStore, loanConditionStore, loanTermsStore, instalmentBillingStore,
                ledgerAccountStore, purposeBalances, loanClosures, postingService,
                new com.finapp.app.credit.IdentityActingParty(identityStore), outboxWriter, auditWriter,
                idGenerator, clock);
    }

    @Bean
    LoanWriteOffMetrics loanWriteOffMetrics(MeterRegistry meterRegistry) {
        return new LoanWriteOffMetrics(meterRegistry);
    }

    @Bean
    LoanWriteOffDesk loanWriteOffDesk(
            LoanWriteOffs loanWriteOffs,
            IdempotentExecutor idempotentExecutor,
            LendingTransactions lendingTransactions,
            LoanWriteOffMetrics loanWriteOffMetrics) {
        return new LoanWriteOffDesk(loanWriteOffs, idempotentExecutor, lendingTransactions, loanWriteOffMetrics);
    }
}
