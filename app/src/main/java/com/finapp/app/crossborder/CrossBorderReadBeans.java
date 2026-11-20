package com.finapp.app.crossborder;

import com.finapp.crossborder.CorridorPolicyStore;
import com.finapp.crossborder.JdbcCrossBorderCollections;
import com.finapp.crossborder.TransactionRunner;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The cross-border operator's reads (`P17-TSK-037`): the corridors, the corridor enable requests and the payments. */
@Configuration(proxyBeanMethods = false)
public class CrossBorderReadBeans {

    @Bean
    CrossBorderReadDesk crossBorderReadDesk(
            CorridorPolicyStore corridorPolicyStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            TransactionRunner crossborderTransactionRunner) {
        return new CrossBorderReadDesk(
                new JdbcCrossBorderCollections(),
                corridorPolicyStore,
                auditWriter,
                idGenerator,
                clock,
                crossborderTransactionRunner);
    }

    /** Cross-border's queues of proposals awaiting a decision (`P17-TSK-040`). */
    @Bean
    CrossBorderPendingDecisionDesk crossBorderPendingDecisionDesk(TransactionRunner crossborderTransactionRunner) {
        return new CrossBorderPendingDecisionDesk(new JdbcCrossBorderCollections(), crossborderTransactionRunner);
    }
}
