package com.finapp.app.administration;

import com.finapp.identity.Authorization;
import com.finapp.identity.IdentityAdministration;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.SessionRevocation;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wiring for the administrative endpoints (`P1-TSK-028`).
 *
 * <p>{@code Authorization} and {@code SessionRevocation} are {@code SessionBeans}' beans, injected
 * rather than re-declared: a second {@code SessionRevocation} would be a second audit writer for
 * the same operation, and two components disagreeing about how a revocation is recorded is the
 * shape of drift this project closes by having one definition.
 */
@Configuration
class AdministrationBeans {

    /**
     * {@code IdentityStore} is injected, not declared.
     *
     * <p>The first version of this class declared its own, on a javadoc claim that
     * {@code AuthenticationBeans} constructed one inline. <strong>That claim was false</strong> —
     * it has declared the bean since {@code P1-TSK-008} — and the framework refused to start,
     * naming both definitions. Left as a note because it is the shape this repository keeps
     * meeting: a comment asserting something about code the author did not check.
     */
    @Bean
    IdentityAdministration identityAdministration(
            IdGenerator idGenerator,
            Clock clock,
            IdentityStore<Connection> identityStore,
            Authorization authorization,
            SessionRevocation sessionRevocation,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter) {
        return new IdentityAdministration(
                idGenerator,
                clock,
                identityStore,
                authorization,
                sessionRevocation,
                auditWriter,
                outboxWriter);
    }

    /**
     * Its own template, for the reason {@code SessionBeans} records: three already exist, so an
     * unqualified parameter is ambiguous and the context refuses to start — and borrowing another
     * concern's settings would move silently the day that concern changed them.
     *
     * <ul>
     *   <li><strong>{@code REQUIRES_NEW}</strong>. An administrative action is never a participant
     *       in somebody else's transaction; the whole guarantee is that the status change, the
     *       session revocations, the audit record and the event commit together or not at all.
     *   <li><strong>{@code ISOLATION_DEFAULT}</strong> ({@code READ COMMITTED}). There <em>is</em> a
     *       read-then-write here — the identity is loaded and then moved — and it is made safe by
     *       the conditional {@code UPDATE … WHERE status = ?} rather than by an isolation level,
     *       which is the protocol every other contended write in this platform uses (ADR-0014).
     * </ul>
     */
    @Bean
    TransactionTemplate administrationTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        return template;
    }

    @Bean
    IdentityAdministrationService identityAdministrationService(
            IdentityAdministration identityAdministration,
            TransactionTemplate administrationTransactions,
            DataSource dataSource) {
        return new IdentityAdministrationService(
                identityAdministration, administrationTransactions, dataSource);
    }
}
