package com.finapp.app.accounts;

import com.finapp.accounts.AccountClosing;
import com.finapp.accounts.AccountHolderVerification;
import com.finapp.accounts.AccountOpening;
import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.JdbcCustomerAccountStore;
import com.finapp.identity.IdentityStore;
import com.finapp.ledger.BalanceDerivation;
import com.finapp.ledger.BalanceDisplay;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcBalanceDisplay;
import com.finapp.ledger.JdbcHoldStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JdbcStatementDerivation;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.StatementDerivation;
import com.finapp.party.PartyStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
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
 * Wires the accounts slice (`P3-TSK-013`) — the first wiring of `P3-TSK-012`'s opening and the
 * ledger's first beans in the composition root.
 *
 * <p>The audit writer, outbox writer and idempotent executor are {@code RegistrationBeans}'
 * beans, injected rather than re-declared — one executor means one retention and lease policy,
 * and two would be two answers to "how long does a completed command answer retries?".
 */
@Configuration
class AccountsBeans {

    @Bean
    CustomerAccountStore<Connection> customerAccountStore() {
        return new JdbcCustomerAccountStore();
    }

    @Bean
    LedgerAccountStore<Connection> ledgerAccountStore() {
        return new JdbcLedgerAccountStore();
    }

    @Bean
    BalanceDisplay<Connection> balanceDisplay() {
        return new JdbcBalanceDisplay();
    }

    @Bean
    BalanceDerivation<Connection> balanceDerivation() {
        return new JdbcBalanceDerivation();
    }

    /**
     * `P3-TSK-018`'s statement: derived from postings through the DERIVATION — never the
     * display projection — because a statement is evidence-shaped and its figures must
     * reconcile to the journal lines that compose them ({@code INV-ACC-02}).
     */
    @Bean
    StatementDerivation<Connection> statementDerivation(
            BalanceDerivation<Connection> balanceDerivation) {
        return new JdbcStatementDerivation(balanceDerivation);
    }

    /**
     * `P3-TSK-014`'s close: the zero-balance check is a financial decision, so it takes the
     * DERIVATION — never {@code balanceDisplay} — inside the account lock ({@code INV-BAL-05}).
     */
    @Bean
    AccountClosing accountClosing(
            CustomerAccountStore<Connection> customerAccountStore,
            LedgerAccountStore<Connection> ledgerAccountStore,
            BalanceDerivation<Connection> balanceDerivation,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator ids,
            Clock clock,
            com.finapp.payments.PaymentIntentStore<Connection> paymentIntentStore,
            com.finapp.payments.DisputeStore<Connection> disputeStore) {
        return new AccountClosing(
                customerAccountStore,
                ledgerAccountStore,
                balanceDerivation,
                // The close judges standing reservations from the authoritative hold rows
                // (P3-TSK-015); the store is stateless, so a direct instance is the wiring.
                new JdbcHoldStore(),
                // THE PAYMENTS IN FLIGHT (the Phase 6 -> 7 transition): accounts cannot see
                // payments, so the composition root answers the port over the intent store -
                // and, since P7-TSK-013, over the disputes too: a chargeback whose share was
                // posted to the account and that the network may still RETURN would credit
                // it back on a win, and a closed account refuses that credit (V007). So the
                // account stays open until the dispute can no longer be won.
                (unitOfWork, account) ->
                        paymentIntentStore.anyInFlightCrediting(unitOfWork, account)
                                || disputeStore.anyRestorableTo(unitOfWork, account),
                auditWriter,
                outboxWriter,
                ids,
                clock);
    }

    @Bean
    AccountHolderVerification<Connection> accountHolderVerification(
            PartyStore<Connection> partyStore) {
        return new VerifiedAccountHolder(partyStore);
    }

    @Bean
    AccountOpening accountOpening(
            CustomerAccountStore<Connection> customerAccountStore,
            LedgerAccountStore<Connection> ledgerAccountStore,
            AccountHolderVerification<Connection> accountHolderVerification,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator ids,
            Clock clock) {
        return new AccountOpening(
                customerAccountStore,
                ledgerAccountStore,
                accountHolderVerification,
                auditWriter,
                outboxWriter,
                ids,
                clock);
    }

    /**
     * The account transaction: {@code REQUIRES_NEW} and default isolation, for
     * {@code RegistrationBeans}' recorded reasons — every contended write here is an insert
     * arbitrated by a unique index, and the reads are per-decision snapshots.
     */
    @Bean
    TransactionTemplate accountTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        return template;
    }

    @Bean
    AccountService accountService(
            AccountOpening accountOpening,
            AccountClosing accountClosing,
            CustomerAccountStore<Connection> customerAccountStore,
            BalanceDisplay<Connection> balanceDisplay,
            StatementDerivation<Connection> statementDerivation,
            IdentityStore<Connection> identityStore,
            PartyStore<Connection> partyStore,
            IdempotentExecutor idempotentExecutor,
            TransactionTemplate accountTransactions,
            DataSource dataSource,
            com.finapp.app.telemetry.AccountMetrics accountMetrics) {
        return new AccountService(
                accountOpening,
                accountClosing,
                customerAccountStore,
                balanceDisplay,
                statementDerivation,
                identityStore,
                partyStore,
                idempotentExecutor,
                accountTransactions,
                dataSource,
                accountMetrics);
    }
}
