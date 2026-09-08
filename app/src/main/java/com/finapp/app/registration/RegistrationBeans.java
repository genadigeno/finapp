package com.finapp.app.registration;

import com.finapp.identity.CredentialStore;
import com.finapp.identity.IdentityRegistration;
import com.finapp.identity.PasswordDeriver;
import com.finapp.party.PartyRegistration;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.audit.JdbcAuditWriter;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.JdbcIdempotencyRecordStore;
import com.finapp.platform.outbox.JdbcOutboxWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the registration slice, and with it the Phase 0 kernel's first production users
 * (`P1-TSK-006`).
 *
 * <p>The idempotency store, the audit writer and the outbox writer have existed since Phase 0 and
 * nothing constructed them: they were proven by tests that built them directly. This is where they
 * become part of a running application.
 */
@Configuration
class RegistrationBeans {

    /**
     * How long a completed record answers retries.
     *
     * <p>{@code DATA_MIGRATIONS.md} §8: too short costs money, too long costs storage. Registration
     * is the mild case in both directions - a retry after expiry is refused by the unique login
     * index rather than creating a second person, so the second mechanism catches what the first no
     * longer covers. Twenty-four hours comfortably exceeds any client retry window.
     */
    private static final Duration RETENTION = Duration.ofHours(24);

    /**
     * How long a claim may be held before another instance may take it over.
     *
     * <p>Must exceed the longest a registration can legitimately take. Three inserts, two audit
     * records and three outbox rows take milliseconds; a minute is generous by three orders of
     * magnitude, which is the right direction to be wrong in - taking over a claim that is still
     * running is how one request becomes two effects. Measured by the <strong>database's</strong>
     * clock, so it absorbs command duration and not clock skew between instances (ADR-0014).
     */
    private static final Duration LEASE = Duration.ofMinutes(1);

    @Bean
    IdempotencyRecordStore<Connection> idempotencyRecordStore() {
        return new JdbcIdempotencyRecordStore();
    }

    @Bean
    AuditWriter<Connection> auditWriter() {
        return new JdbcAuditWriter();
    }

    @Bean
    OutboxWriter<Connection> outboxWriter() {
        return new JdbcOutboxWriter();
    }

    @Bean
    IdempotentExecutor idempotentExecutor(
            IdempotencyRecordStore<Connection> store, Clock clock) {
        return new IdempotentExecutor(store, clock, RETENTION, LEASE);
    }

    @Bean
    PartyRegistration partyRegistration(
            IdGenerator ids,
            Clock clock,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter) {
        return new PartyRegistration(ids, clock, auditWriter, outboxWriter);
    }

    /**
     * {@code credentialStore} and {@code passwordDeriver} are {@code AuthenticationBeans}' beans,
     * injected rather than re-declared: a second {@code Argon2PasswordDeriver} would be a second
     * set of cost factors, and {@code INV-IDN-02}'s whole point is that a credential records the
     * parameters that produced it - two derivers means two answers to "what is current policy?"
     * and an upgrade campaign that converges on neither.
     */
    @Bean
    IdentityRegistration identityRegistration(
            IdGenerator ids,
            Clock clock,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            CredentialStore<Connection> credentialStore,
            PasswordDeriver passwordDeriver) {
        return new IdentityRegistration(
                ids, clock, auditWriter, outboxWriter, credentialStore, passwordDeriver);
    }

    /**
     * The registration transaction.
     *
     * <p>Its own template rather than the framework default, so the two properties that matter are
     * declared where a reader will look for them:
     *
     * <ul>
     *   <li><strong>{@code PROPAGATION_REQUIRES_NEW}</strong> - registration is never a participant
     *       in somebody else's transaction. Joining one would put its commit under a caller's
     *       control, and the whole guarantee is that these writes commit together and alone.
     *   <li><strong>{@code ISOLATION_DEFAULT}</strong>, which is {@code READ COMMITTED}. Deliberate:
     *       there is no read-then-write anywhere in the flow, so there is nothing for a higher level
     *       to protect. Both contended writes are inserts arbitrated by unique indexes, which are
     *       enforced at every isolation level.
     * </ul>
     */
    @Bean
    TransactionTemplate registrationTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        return template;
    }

    @Bean
    RegistrationService registrationService(
            IdempotentExecutor executor,
            PartyRegistration partyRegistration,
            IdentityRegistration identityRegistration,
            TransactionTemplate registrationTransactions,
            DataSource dataSource,
            MeterRegistry meters) {
        return new RegistrationService(
                executor,
                partyRegistration,
                identityRegistration,
                registrationTransactions,
                dataSource,
                meters);
    }
}
