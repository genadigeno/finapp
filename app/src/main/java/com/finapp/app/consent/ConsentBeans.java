package com.finapp.app.consent;

import com.finapp.consent.ConsentGate;
import com.finapp.consent.ConsentStore;
import com.finapp.consent.JdbcConsentStore;
import com.finapp.identity.IdentityStore;
import com.finapp.platform.audit.AuditWriter;
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
 * Wiring for {@code /v1/me/consents} (`P2-TSK-018`).
 *
 * <p>The {@code ConsentStore} bean arrives here and not with `P2-TSK-017`, deliberately: the
 * P1-TSK-007 licence — wiring nothing consumes is wiring nobody can review — expired the day
 * this surface became the store's first consumer. `P2-TSK-019`'s gate injects the same bean.
 *
 * <p>{@code IdentityStore} and {@code AuditWriter} are other slices' beans, injected rather
 * than re-declared (the {@code ProfileBeans} lesson).
 */
@Configuration
class ConsentBeans {

    @Bean
    ConsentStore<Connection> consentStore() {
        return new JdbcConsentStore();
    }

    /**
     * The enforcement gate (`P2-TSK-019`): one authoritative read per decision, no state of its
     * own. Its first consumer is the case-opening door, wired in {@code KycBeans} through
     * {@code kyc}'s {@code CaseOpeningConsent} port; `P2-TSK-006`'s endpoint injects this same
     * bean and maps {@code require}'s refusal to its client answer.
     */
    @Bean
    ConsentGate<Connection> consentGate(ConsentStore<Connection> consentStore) {
        return new ConsentGate<>(consentStore);
    }

    /**
     * Its own template, for the reason {@code SessionBeans} records: several already exist, so
     * an unqualified parameter is ambiguous — and borrowing another concern's settings would
     * move silently the day that concern changed them.
     *
     * <ul>
     *   <li><strong>{@code REQUIRES_NEW}</strong>. Never a participant in somebody else's
     *       transaction.
     *   <li><strong>{@code ISOLATION_DEFAULT}</strong> ({@code READ COMMITTED}). The append has
     *       no losing branch and no read-then-act protects an invariant: the grant assessment
     *       and the append share this transaction's snapshot, and a text version racing in by
     *       migration is resolved by the derivation itself, which re-judges every future read
     *       ({@code INV-CNS-04}). Nothing here needs more.
     * </ul>
     */
    @Bean
    TransactionTemplate consentTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        return template;
    }

    @Bean
    ConsentService consentService(
            IdentityStore<Connection> identityStore,
            ConsentStore<Connection> consentStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate consentTransactions,
            DataSource dataSource,
            io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return new ConsentService(
                identityStore,
                consentStore,
                auditWriter,
                idGenerator,
                clock,
                consentTransactions,
                dataSource,
                meterRegistry);
    }
}
