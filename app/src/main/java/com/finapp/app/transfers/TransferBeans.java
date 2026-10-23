package com.finapp.app.transfers;

import com.finapp.app.telemetry.TransferMetrics;
import com.finapp.identity.IdentityStore;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.ledger.AvailableBalance;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcHoldStore;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingService;
import com.finapp.party.PartyStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotencyRecordStore;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.transfers.BeneficiaryStore;
import com.finapp.transfers.JdbcTransferStore;
import com.finapp.transfers.TransferExecution;
import com.finapp.transfers.TransferLimitCheck;
import com.finapp.transfers.TransferParticipants;
import com.finapp.transfers.TransferReversal;
import com.finapp.transfers.TransferRiskDecision;
import com.finapp.transfers.TransferStore;
import java.sql.Connection;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the transfer surface (`P4-TSK-008`) — the moment `P4-TSK-005`'s execution command
 * leaves the unconsumed-wiring licence: its first composition-root consumer is this slice.
 */
@Configuration
class TransferBeans {

    @Bean
    TransferStore<Connection> transferStore() {
        return new JdbcTransferStore();
    }

    /**
     * The execution command, composed exactly as its database suite composes it — with two
     * deliberate differences the suite records: the shared beans (participants, posting,
     * executor) rather than private instances, and the real {@code PostingObserver} through the
     * {@code postingService} bean. The two seams are risk's since `P13-TSK-008` —
     * {@code RiskTransferLimitCheck} and {@code RiskTransferRiskDecision}, composed in {@code app}'s
     * risk configuration ({@code RiskTransferBeans}) and injected here by the transfers module's own
     * seam types, so this configuration names no risk class. `P4-TSK-010` hardened the contracts
     * (verdict-returning, in-lock, on the execution's own unit of work), so replacing Phase 4's two
     * {@code PermitAllUntilPhase13} instances was the whole of the wiring change — the class stays in
     * {@code transfers} as Phase 4's record and the database suites' permit-all seam, wired nowhere.
     */
    @Bean
    TransferExecution transferExecution(
            IdempotentExecutor idempotentExecutor,
            TransferParticipants<Connection> transferParticipants,
            LedgerAccountStore<Connection> ledgerAccountStore,
            PostingService postingService,
            TransferStore<Connection> transferStore,
            TransferLimitCheck<Connection> transferLimitCheck,
            TransferRiskDecision<Connection> transferRiskDecision,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator ids,
            Clock clock) {
        return new TransferExecution(
                idempotentExecutor,
                transferParticipants,
                ledgerAccountStore,
                new AvailableBalance<>(new JdbcBalanceDerivation(), new JdbcHoldStore()),
                postingService,
                transferStore,
                transferLimitCheck,
                transferRiskDecision,
                auditWriter,
                outboxWriter,
                ids,
                clock);
    }

    /**
     * The reversal command (`P4-TSK-009`), composed from the shared beans — the ledger's
     * {@code ReversalService} arriving through its own new bean in {@code LedgerBeans}, so the
     * reversal posting is observed and claimed exactly as every other journal write.
     */
    @Bean
    TransferReversal transferReversal(
            TransferStore<Connection> transferStore,
            com.finapp.ledger.ReversalService reversalService,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator ids,
            Clock clock) {
        return new TransferReversal(
                transferStore, reversalService, auditWriter, outboxWriter, ids, clock);
    }

    /**
     * The transfer transaction: {@code REQUIRES_NEW} and default isolation (ADR-0039) — the
     * contended writes are the idempotency claim (unique-constraint arbitrated) and the
     * posting under the source row's {@code FOR UPDATE}, both the command's own.
     */
    @Bean
    TransactionTemplate transferTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        return template;
    }

    /**
     * The door's step-up before the command (`P13-TSK-008`): the advice port — risk's T-advise,
     * composed in {@code RiskTransferBeans} — the command key's records (a retry is never
     * advised), the source resolution (no advice over an account not the caller's) and the
     * enrolments (which step-up words to answer).
     */
    @Bean
    TransferDoorStepUp transferDoorStepUp(
            TransferStepUpAdvice transferStepUpAdvice,
            IdempotencyRecordStore<Connection> idempotencyRecordStore,
            TransferParticipants<Connection> transferParticipants,
            MfaEnrolmentStore<Connection> mfaEnrolmentStore) {
        return new TransferDoorStepUp(
                transferStepUpAdvice,
                idempotencyRecordStore,
                transferParticipants,
                mfaEnrolmentStore);
    }

    @Bean
    TransferService transferService(
            TransferDoorStepUp transferDoorStepUp,
            TransferExecution transferExecution,
            TransferReversal transferReversal,
            TransferStore<Connection> transferStore,
            BeneficiaryStore<Connection> beneficiaryStore,
            IdentityStore<Connection> identityStore,
            PartyStore<Connection> partyStore,
            TransactionTemplate transferTransactions,
            DataSource dataSource,
            TransferMetrics transferMetrics,
            Clock clock) {
        return new TransferService(
                transferDoorStepUp,
                transferExecution,
                transferReversal,
                transferStore,
                beneficiaryStore,
                identityStore,
                partyStore,
                transferTransactions,
                dataSource,
                transferMetrics,
                clock);
    }
}
