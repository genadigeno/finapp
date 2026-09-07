package com.finapp.app.recovery;

import com.finapp.identity.ContactChannelService;
import com.finapp.identity.ContactChannelStore;
import com.finapp.identity.CredentialStore;
import com.finapp.identity.JdbcContactChannelStore;
import com.finapp.identity.JdbcRecoveryRequestStore;
import com.finapp.identity.PasswordDeriver;
import com.finapp.identity.RecoveryRequestStore;
import com.finapp.identity.RecoveryService;
import com.finapp.identity.SessionStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wiring for recovery and contact channels (`P1-TSK-023`).
 *
 * <p>The composition root, as every other module's beans are: {@code identity} declares ports and
 * knows nothing about Spring, and {@code app} is the only place that assembles them.
 */
@Configuration
public class RecoveryBeans {

    /**
     * A named template, because three others already exist.
     *
     * <p>Matched by parameter name rather than by type — the {@code NoUniqueBeanDefinitionException}
     * a fourth unnamed {@code TransactionTemplate} would cause is the framework being right, and
     * naming it states which boundary this is.
     */
    @Bean
    TransactionTemplate recoveryTransactions(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    /**
     * Randomness for recovery and verification tokens.
     *
     * <p>Its own {@code SecureRandom} rather than a shared one, so a future change to how MFA seeds
     * its generator cannot silently change how recovery tokens are drawn. They are independent
     * security decisions and the wiring says so.
     */
    @Bean
    SecureRandom recoveryRandomness() {
        return new SecureRandom();
    }

    @Bean
    ContactChannelStore<Connection> contactChannelStore() {
        return new JdbcContactChannelStore();
    }

    @Bean
    RecoveryRequestStore<Connection> recoveryRequestStore(IdGenerator idGenerator) {
        return new JdbcRecoveryRequestStore(idGenerator);
    }

    @Bean
    ContactChannelService contactChannelService(
            ContactChannelStore<Connection> contactChannelStore,
            IdGenerator idGenerator,
            Clock clock,
            SecureRandom recoveryRandomness,
            AuditWriter<Connection> auditWriter) {
        return new ContactChannelService(
                contactChannelStore, idGenerator, clock, recoveryRandomness, auditWriter);
    }

    @Bean
    RecoveryService recoveryService(
            RecoveryRequestStore<Connection> recoveryRequestStore,
            CredentialStore<Connection> credentialStore,
            SessionStore<Connection> sessionStore,
            PasswordDeriver passwordDeriver,
            IdGenerator idGenerator,
            Clock clock,
            SecureRandom recoveryRandomness,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter) {
        return new RecoveryService(
                recoveryRequestStore,
                credentialStore,
                sessionStore,
                passwordDeriver,
                idGenerator,
                clock,
                recoveryRandomness,
                auditWriter,
                outboxWriter);
    }
}
