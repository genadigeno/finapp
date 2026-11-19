package com.finapp.app.api;

import com.finapp.platform.audit.AuditWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** The one recorder of the operator's reads of customers (`P17-TSK-025`) and their snapshot (`P17-TSK-026`). */
@Configuration(proxyBeanMethods = false)
public class RecordedReadBeans {

    @Bean
    RecordedReads recordedReads(AuditWriter<Connection> auditWriter, IdGenerator idGenerator, Clock clock) {
        return new RecordedReads(auditWriter, idGenerator, clock);
    }

    /**
     * One snapshot per operator read (`P17-TSK-026`): {@code REPEATABLE READ}, so every statement composing one answer - the
     * profile's party, identities and latest case; a page and the existence check before it - sees one instant while every
     * replica writes; a transaction of its own ({@code REQUIRES_NEW}) whose only write is the answer's record, an insert, which
     * no concurrent writer can make fail serialization.
     */
    @Bean
    TransactionTemplate operatorReadTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return template;
    }
}
