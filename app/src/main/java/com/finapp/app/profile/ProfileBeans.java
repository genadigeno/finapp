package com.finapp.app.profile;

import com.finapp.identity.IdentityStore;
import com.finapp.party.JdbcPartyStore;
import com.finapp.party.PartyStore;
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
 * Wiring for {@code /v1/me} (`P1-TSK-030`).
 *
 * <p>{@code IdentityStore} and {@code AuditWriter} are other slices' beans, injected rather than
 * re-declared. {@code P1-TSK-028} learned that the hard way: it declared its own
 * {@code identityStore} on a javadoc claim that nobody else had, the claim was false, and the
 * framework refused to start naming both definitions.
 */
@Configuration
class ProfileBeans {

    @Bean
    PartyStore<Connection> partyStore() {
        return new JdbcPartyStore();
    }

    /**
     * Its own template, for the reason {@code SessionBeans} records: four already exist, so an
     * unqualified parameter is ambiguous and the context refuses to start — and borrowing another
     * concern's settings would move silently the day that concern changed them.
     *
     * <ul>
     *   <li><strong>{@code REQUIRES_NEW}</strong>. Never a participant in somebody else's
     *       transaction.
     *   <li><strong>{@code ISOLATION_DEFAULT}</strong> ({@code READ COMMITTED}). The rename is one
     *       statement whose row count is the outcome, and the read is two statements that need one
     *       snapshot — which a transaction gives at this level. Neither needs more.
     * </ul>
     */
    @Bean
    TransactionTemplate profileTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        return template;
    }

    @Bean
    ProfileService profileService(
            IdentityStore<Connection> identityStore,
            PartyStore<Connection> partyStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate profileTransactions,
            DataSource dataSource) {
        return new ProfileService(
                identityStore,
                partyStore,
                auditWriter,
                idGenerator,
                clock,
                profileTransactions,
                dataSource);
    }
}
