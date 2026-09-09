package com.finapp.app.credential;

import com.finapp.identity.Argon2PasswordDeriver;
import com.finapp.identity.AuthenticationThrottle;
import com.finapp.identity.CredentialChange;
import com.finapp.identity.CredentialStore;
import com.finapp.identity.CredentialVerifier;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.identity.SessionRevocation;
import com.finapp.identity.SessionRotation;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wiring for a password change (`P1-TSK-033`).
 *
 * <p>Every collaborator already exists as a bean — {@code CredentialVerifier} and the
 * {@code CredentialStore} from {@code AuthenticationBeans}, {@code SessionRevocation} from
 * {@code SessionBeans}, {@code SessionRotation} and the {@code MfaEnrolmentStore} from
 * {@code MfaBeans}, the throttle, deriver, writers and clock throughout. This task adds no store
 * and no port; it composes what the phase already built, which is why the class is a wiring file
 * and a domain service and nothing else.
 */
@Configuration
class CredentialBeans {

    @Bean
    CredentialChange credentialChange(
            CredentialVerifier credentialVerifier,
            CredentialStore<Connection> credentialStore,
            MfaEnrolmentStore<Connection> mfaEnrolmentStore,
            AuthenticationThrottle authenticationThrottle,
            SessionRevocation sessionRevocation,
            SessionRotation sessionRotation,
            Argon2PasswordDeriver passwordDeriver,
            IdGenerator idGenerator,
            Clock clock,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter) {
        return new CredentialChange(
                credentialVerifier,
                credentialStore,
                mfaEnrolmentStore,
                authenticationThrottle,
                sessionRevocation,
                sessionRotation,
                passwordDeriver,
                idGenerator,
                clock,
                auditWriter,
                outboxWriter);
    }

    /**
     * Its own transaction template, named so the injection is unambiguous.
     *
     * <p>{@code REQUIRES_NEW} and {@code ISOLATION_DEFAULT}, for the reason every sibling template
     * carries: every write on this path is a conditional {@code UPDATE … WHERE} whose row count is
     * the outcome, so there is no read-then-write for a higher isolation level to protect. A fourth
     * template now exists, so an unqualified {@code TransactionTemplate} parameter would be
     * ambiguous and the context would refuse to start — the framework being right ({@code
     * P1-TSK-016}).
     */
    @Bean
    TransactionTemplate credentialChangeTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        return template;
    }
}
