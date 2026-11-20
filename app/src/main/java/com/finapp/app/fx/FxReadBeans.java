package com.finapp.app.fx;

import com.finapp.fx.JdbcFxCollections;
import com.finapp.fx.RateSnapshotStore;
import com.finapp.fx.TransactionRunner;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The FX operator's reads (`P17-TSK-037`): the reference rates, the availability, the enable requests and the trades. */
@Configuration(proxyBeanMethods = false)
public class FxReadBeans {

    @Bean
    FxReadDesk fxReadDesk(
            RateSnapshotStore<Connection> rateSnapshotStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            TransactionRunner fxTransactionRunner) {
        return new FxReadDesk(
                new JdbcFxCollections(),
                rateSnapshotStore,
                FxProviderBeans.DECLARED.keySet(),
                auditWriter,
                idGenerator,
                clock,
                fxTransactionRunner);
    }

    /** FX's queues of proposals awaiting a decision (`P17-TSK-040`). */
    @Bean
    FxPendingDecisionDesk fxPendingDecisionDesk(TransactionRunner fxTransactionRunner) {
        return new FxPendingDecisionDesk(new JdbcFxCollections(), fxTransactionRunner);
    }
}
