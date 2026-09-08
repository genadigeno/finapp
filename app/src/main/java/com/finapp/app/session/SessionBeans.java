package com.finapp.app.session;

import com.finapp.identity.JdbcSessionStore;
import com.finapp.identity.SessionPolicy;
import com.finapp.identity.SessionRevocation;
import com.finapp.identity.SessionStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the session endpoints (`P1-TSK-016`).
 *
 * <p>Only what something consumes. {@code P1-TSK-007} deleted two {@code @Bean} methods it had
 * written because nothing used them, and a bean nobody consumes is a false positive waiting to be
 * argued about rather than a seam.
 */
@Configuration
class SessionBeans {

    @Bean
    SessionStore<Connection> sessionStore() {
        return new JdbcSessionStore();
    }

    /**
     * The shipped policy.
     *
     * <p>A bean so that the bound sessions are issued and extended under is one value the whole
     * application shares, rather than {@code SessionPolicy.current()} called at each site — which
     * is how two components come to disagree about how long a session lasts.
     */
    @Bean
    SessionPolicy sessionPolicy() {
        return SessionPolicy.current();
    }

    /**
     * Sessions get their own transaction template rather than borrowing authentication's.
     *
     * <p>Two already exist — {@code authenticationTransactions} and {@code registrationTransactions}
     * — so an unqualified {@code TransactionTemplate} parameter is ambiguous and the context refuses
     * to start. That refusal is the framework being right: reusing another concern's template would
     * couple session behaviour to propagation and isolation settings chosen for a different flow,
     * and it would move silently the day that flow's settings changed.
     *
     * <ul>
     *   <li><strong>{@code REQUIRES_NEW}</strong>. Authentication runs before the handler and its
     *       idle extension must commit whether or not the handler's own work does — a session was
     *       presented and used either way.
     *   <li><strong>{@code ISOLATION_DEFAULT}</strong> ({@code READ COMMITTED}). Every write here is
     *       a conditional {@code UPDATE … WHERE} whose row count is the outcome, so there is no
     *       read-then-write for a higher level to protect.
     * </ul>
     */
    @Bean
    org.springframework.transaction.support.TransactionTemplate sessionTransactions(
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        var template =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        template.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_DEFAULT);
        return template;
    }

    @Bean
    SessionAuthenticationInterceptor sessionAuthenticationInterceptor(
            SessionStore<Connection> sessionStore,
            org.springframework.transaction.support.TransactionTemplate sessionTransactions,
            javax.sql.DataSource dataSource,
            Clock clock,
            SessionPolicy sessionPolicy,
            com.finapp.identity.Authorization authorization) {
        return new SessionAuthenticationInterceptor(
                sessionStore, sessionTransactions, dataSource, clock, sessionPolicy, authorization);
    }

    @Bean
    com.finapp.identity.RoleAssignmentStore<Connection> roleAssignmentStore(IdGenerator idGenerator) {
        return new com.finapp.identity.JdbcRoleAssignmentStore(idGenerator);
    }

    @Bean
    com.finapp.identity.Authorization authorization(
            com.finapp.identity.RoleAssignmentStore<Connection> roleAssignmentStore,
            IdGenerator idGenerator,
            Clock clock,
            AuditWriter<Connection> auditWriter) {
        return new com.finapp.identity.Authorization(
                roleAssignmentStore, idGenerator, clock, auditWriter);
    }

    /**
     * Randomness for session tokens.
     *
     * <p>Declared here rather than shared with {@code mfaRandomness}: {@code PlatformBeans} and
     * {@code MfaBeans} each construct their own, and rewiring proven code to share one would be a
     * refactor with no correctness benefit ({@code EXECUTION_PROTOCOL.md} rule 4). Two
     * {@code SecureRandom} instances are independent and each seeds from the operating system -
     * there is no shared state to get wrong.
     */
    @Bean
    java.security.SecureRandom sessionRandomness() {
        return new java.security.SecureRandom();
    }

    /**
     * Where a first session comes from ({@code P1-TSK-027}).
     *
     * <p>Wired here rather than in {@code AuthenticationBeans} because it is session
     * infrastructure: it takes the {@code sessionStore} and the shared {@code sessionPolicy}
     * declared above, and putting it beside them is what keeps the bound sessions are issued and
     * extended under one value. Authentication consumes it.
     */
    @Bean
    com.finapp.identity.SessionIssue sessionIssue(
            SessionStore<Connection> sessionStore,
            SessionPolicy sessionPolicy,
            IdGenerator idGenerator,
            Clock clock,
            java.security.SecureRandom sessionRandomness) {
        return new com.finapp.identity.SessionIssue(
                sessionStore, sessionPolicy, idGenerator, clock, sessionRandomness);
    }

    @Bean
    SessionRevocation sessionRevocation(
            SessionStore<Connection> sessionStore,
            IdGenerator idGenerator,
            Clock clock,
            AuditWriter<Connection> auditWriter) {
        return new SessionRevocation(sessionStore, idGenerator, clock, auditWriter);
    }
}
