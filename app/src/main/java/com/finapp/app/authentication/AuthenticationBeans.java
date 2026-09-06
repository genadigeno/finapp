package com.finapp.app.authentication;

import com.finapp.identity.Argon2PasswordDeriver;
import com.finapp.identity.CredentialStore;
import com.finapp.identity.CredentialVerifier;
import com.finapp.identity.DerivationParameters;
import com.finapp.identity.IdentityAuthentication;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.JdbcCredentialStore;
import com.finapp.identity.JdbcIdentityStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the authentication slice (`P1-TSK-010`).
 *
 * <p>{@code CredentialVerifier}, {@code JdbcCredentialStore} and {@code JdbcIdentityStore} have
 * existed since {@code P1-TSK-007} and {@code P1-TSK-008} and nothing constructed them - they were
 * proven by tests that built them directly, and {@code P1-TSK-007} deliberately deleted the two
 * {@code @Bean} methods it had written because nothing consumed them. This is where they become
 * part of a running application.
 */
@Configuration
class AuthenticationBeans {

    @Bean
    IdentityStore<Connection> identityStore() {
        return new JdbcIdentityStore();
    }

    @Bean
    CredentialStore<Connection> credentialStore() {
        return new JdbcCredentialStore();
    }

    /**
     * The password deriver, at current policy.
     *
     * <p><strong>Constructed once</strong>, and that matters more than it looks: the verifier
     * derives a throwaway password in its own constructor to hold the dummy derivation that keeps
     * an absent identity as expensive as a present one. Building a verifier per request would pay
     * ~46 ms and ~19 MiB for that, on every request, before doing any work.
     *
     * <p>Parameters come from {@link DerivationParameters#current()} rather than from configuration.
     * ADR-0032 records why: a work factor that is a deployment setting is one that cannot be raised,
     * because nothing then records what any existing credential used. It is recorded per credential
     * instead, and this constant is the policy new ones are written at.
     */
    @Bean
    Argon2PasswordDeriver passwordDeriver() {
        return new Argon2PasswordDeriver(DerivationParameters.current());
    }

    @Bean
    CredentialVerifier credentialVerifier(
            IdentityStore<Connection> identityStore,
            CredentialStore<Connection> credentialStore,
            Argon2PasswordDeriver passwordDeriver,
            IdGenerator ids,
            Clock clock) {
        return new CredentialVerifier(identityStore, credentialStore, passwordDeriver, ids, clock);
    }

    @Bean
    IdentityAuthentication identityAuthentication(
            IdGenerator ids,
            Clock clock,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter) {
        return new IdentityAuthentication(ids, clock, auditWriter, outboxWriter);
    }

    /**
     * The authentication transaction.
     *
     * <p>Its own template, declaring the two properties a reader will look for:
     *
     * <ul>
     *   <li><strong>{@code PROPAGATION_REQUIRES_NEW}</strong> - authentication is never a
     *       participant in somebody else's transaction. Joining one would put its commit under a
     *       caller's control, and the audit record of a failed attempt must commit whatever else
     *       is happening.
     *   <li><strong>{@code ISOLATION_DEFAULT}</strong> ({@code READ COMMITTED}). Unlike
     *       registration this flow <em>does</em> read then write - the credential is read and may
     *       be superseded - and a higher level would still buy nothing, because the write is
     *       conditional and its row count is the outcome (`P1-TSK-008`).
     * </ul>
     */
    @Bean
    TransactionTemplate authenticationTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        return template;
    }

    @Bean
    AuthenticationService authenticationService(
            CredentialVerifier credentialVerifier,
            IdentityAuthentication identityAuthentication,
            TransactionTemplate authenticationTransactions,
            DataSource dataSource,
            MeterRegistry meters) {
        return new AuthenticationService(
                credentialVerifier,
                identityAuthentication,
                authenticationTransactions,
                dataSource,
                meters);
    }
}
