package com.finapp.app.accounting;

import com.finapp.accounting.PostingRuleRegister;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wiring for accounting - the module's first beans (`P14-TSK-002`; {@code RiskBeans}' shape): its unit of work and the
 * posting rule register's audited door over {@link PostingRuleRegister#CURRENT}. Later tasks add theirs here.
 */
@Configuration(proxyBeanMethods = false)
public class AccountingBeans {

    /** accounting's unit of work - one transaction, default propagation. */
    @Bean
    AccountingTransactions accountingTransactions(PlatformTransactionManager transactionManager, DataSource dataSource) {
        return new SpringAccountingTransactions(new TransactionTemplate(transactionManager), dataSource);
    }

    @Bean
    PostingRuleDesk postingRuleDesk(
            AccountingTransactions accountingTransactions,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new PostingRuleDesk(PostingRuleRegister.CURRENT, accountingTransactions, auditWriter, idGenerator, clock);
    }
}
