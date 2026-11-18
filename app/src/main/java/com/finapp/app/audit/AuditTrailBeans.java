package com.finapp.app.audit;

import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.JdbcAuditTrail;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Wiring for the audit trail's read (`P17-TSK-017`). */
@Configuration
class AuditTrailBeans {

    /**
     * Its own template, for the reason every module's is its own: {@code REQUIRES_NEW}, so a read and its record commit together and
     * never inside another flow's transaction; {@code READ COMMITTED}, so each page is what was committed when it was read.
     */
    @Bean
    TransactionTemplate auditTrailTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        return template;
    }

    @Bean
    AuditTrailService auditTrailService(
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate auditTrailTransactions,
            DataSource dataSource) {
        return new AuditTrailService(new JdbcAuditTrail(), auditWriter, idGenerator, clock, auditTrailTransactions, dataSource);
    }
}
