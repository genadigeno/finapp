package com.finapp.app.risk;

import com.finapp.identity.IdentityStore;
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
 * The risk investigator's held card payments (`P17-TSK-032`): wired here, in risk's own configuration, so no customer or merchant
 * surface's reach meets a risk type ({@code NoCustomerSurfaceReadsRiskTest}). Whose party a reader is reads through credit's
 * {@code IdentityActingParty}, the one read for credit, lending, bnpl and risk.
 */
@Configuration(proxyBeanMethods = false)
public class HeldPaymentBeans {

    @Bean
    HeldPaymentReadDesk heldPaymentReadDesk(
            IdentityStore<Connection> identityStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            PlatformTransactionManager transactionManager,
            DataSource dataSource) {
        return new HeldPaymentReadDesk(
                new com.finapp.payments.JdbcPaymentCollections(),
                new com.finapp.app.credit.IdentityActingParty(identityStore),
                auditWriter,
                idGenerator,
                clock,
                new TransactionTemplate(transactionManager),
                dataSource);
    }
}
