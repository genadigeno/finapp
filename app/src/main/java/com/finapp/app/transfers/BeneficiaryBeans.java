package com.finapp.app.transfers;

import com.finapp.accounts.CustomerAccountStore;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.party.PartyStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.transfers.BeneficiaryCreation;
import com.finapp.transfers.BeneficiaryStore;
import com.finapp.transfers.JdbcBeneficiaryStore;
import com.finapp.transfers.TransferParticipants;
import java.sql.Connection;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the beneficiary slice (`P4-TSK-007`) — the {@code transfers} module's first beans, and
 * the first wiring of `P4-TSK-005`'s {@link JdbcTransferParticipants} (the execution command
 * itself stays unbeaned until its surface, `P4-TSK-008` — the unconsumed-wiring licence).
 */
@Configuration
class BeneficiaryBeans {

    /**
     * The resolution port over the party, accounts and ledger stores — `P4-TSK-005`'s
     * composition-root half, wired here by its first consuming surface.
     */
    @Bean
    TransferParticipants<Connection> transferParticipants(
            PartyStore<Connection> partyStore,
            CustomerAccountStore<Connection> customerAccountStore,
            LedgerAccountStore<Connection> ledgerAccountStore) {
        return new JdbcTransferParticipants(
                partyStore, customerAccountStore, ledgerAccountStore);
    }

    @Bean
    BeneficiaryStore<Connection> beneficiaryStore() {
        return new JdbcBeneficiaryStore();
    }

    @Bean
    BeneficiaryCreation<Connection> beneficiaryCreation(
            TransferParticipants<Connection> transferParticipants,
            BeneficiaryStore<Connection> beneficiaryStore,
            IdGenerator ids,
            Clock clock) {
        return new BeneficiaryCreation<>(transferParticipants, beneficiaryStore, ids, clock);
    }

    /**
     * The beneficiary transaction: {@code REQUIRES_NEW} and default isolation, for
     * {@code RegistrationBeans}' recorded reasons — the contended write is an insert arbitrated
     * by a unique index, the removal a conditional {@code UPDATE}, and the reads per-decision
     * snapshots.
     */
    @Bean
    TransactionTemplate beneficiaryTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        return template;
    }

    @Bean
    BeneficiaryService beneficiaryService(
            BeneficiaryCreation<Connection> beneficiaryCreation,
            BeneficiaryStore<Connection> beneficiaryStore,
            MfaEnrolmentStore<Connection> mfaEnrolmentStore,
            IdentityStore<Connection> identityStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator ids,
            Clock clock,
            TransactionTemplate beneficiaryTransactions,
            DataSource dataSource) {
        return new BeneficiaryService(
                beneficiaryCreation,
                beneficiaryStore,
                mfaEnrolmentStore,
                identityStore,
                auditWriter,
                ids,
                clock,
                beneficiaryTransactions,
                dataSource);
    }
}
