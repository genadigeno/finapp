package com.finapp.app.accounts;

import com.finapp.accounts.AccountRestrictionStore;
import com.finapp.accounts.AccountRestrictions;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.JdbcAccountRestrictionStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** An agreement's restriction by four eyes (`P17-TSK-028`, ADR-0153): accounts' machine and app's keyed door over it. */
@Configuration(proxyBeanMethods = false)
public class AccountRestrictionBeans {

    @Bean
    AccountRestrictionStore<Connection> accountRestrictionStore() {
        return new JdbcAccountRestrictionStore();
    }

    @Bean
    AccountRestrictions accountRestrictions(
            CustomerAccountStore<Connection> customerAccountStore,
            AccountRestrictionStore<Connection> accountRestrictionStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new AccountRestrictions(customerAccountStore, accountRestrictionStore, auditWriter, idGenerator, clock);
    }

    /** Each act in a transaction of its own, the platform's default isolation: the agreement's row lock is the control. */
    @Bean
    AccountRestrictionDesk accountRestrictionDesk(
            AccountRestrictions accountRestrictions,
            IdempotentExecutor idempotentExecutor,
            PlatformTransactionManager transactionManager,
            DataSource dataSource,
            com.finapp.app.api.RecordedReads recordedReads) {
        return new AccountRestrictionDesk(accountRestrictions, idempotentExecutor, new TransactionTemplate(transactionManager),
                dataSource, recordedReads);
    }
}
