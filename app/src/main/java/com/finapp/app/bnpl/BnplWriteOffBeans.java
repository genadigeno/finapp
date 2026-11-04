package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplBook;
import com.finapp.bnpl.BnplConditionStore;
import com.finapp.bnpl.BnplCorrectionStore;
import com.finapp.bnpl.BnplDisputeStore;
import com.finapp.bnpl.BnplPurchaseStore;
import com.finapp.bnpl.BnplServicingStore;
import com.finapp.bnpl.BnplTermsStore;
import com.finapp.bnpl.BnplWriteOffStore;
import com.finapp.bnpl.BnplWriteOffs;
import com.finapp.bnpl.JdbcBnplWriteOffStore;
import com.finapp.identity.IdentityStore;
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
 * A BNPL write-off's wiring (`P14-TSK-021`, owner decision C6 (A)): the store, the machine, the meters and the desk - a configuration
 * of its own, so parallel tasks append to no common bean method. The headroom and the proofs need nothing wired: they read the entry.
 */
@Configuration(proxyBeanMethods = false)
public class BnplWriteOffBeans {

    @Bean
    BnplWriteOffStore bnplWriteOffStore() {
        return new JdbcBnplWriteOffStore();
    }

    @Bean
    BnplWriteOffs bnplWriteOffs(
            BnplCorrectionStore bnplCorrectionStore,
            BnplServicingStore bnplServicingStore,
            BnplPurchaseStore bnplPurchaseStore,
            BnplWriteOffStore bnplWriteOffStore,
            BnplConditionStore bnplConditionStore,
            BnplTermsStore bnplTermsStore,
            BnplDisputeStore bnplDisputeStore,
            BnplBook bnplBook,
            IdentityStore<Connection> identityStore,
            OutboxWriter<Connection> outboxWriter,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new BnplWriteOffs(bnplCorrectionStore, bnplServicingStore, bnplPurchaseStore, bnplWriteOffStore, bnplConditionStore,
                bnplTermsStore, bnplDisputeStore, bnplBook, new com.finapp.app.credit.IdentityActingParty(identityStore), outboxWriter, auditWriter, idGenerator, clock);
    }

    @Bean
    BnplWriteOffMetrics bnplWriteOffMetrics(MeterRegistry meterRegistry) {
        return new BnplWriteOffMetrics(meterRegistry);
    }

    @Bean
    BnplWriteOffDesk bnplWriteOffDesk(
            BnplWriteOffs bnplWriteOffs,
            IdempotentExecutor idempotentExecutor,
            BnplTransactions bnplTransactions,
            BnplWriteOffMetrics bnplWriteOffMetrics) {
        return new BnplWriteOffDesk(bnplWriteOffs, idempotentExecutor, bnplTransactions, bnplWriteOffMetrics);
    }
}
