package com.finapp.app.paymentmethods;

import com.finapp.identity.IdentityStore;
import com.finapp.identity.MfaEnrolmentStore;
import com.finapp.paymentmethods.JdbcPaymentMethodStore;
import com.finapp.paymentmethods.PaymentMethodStore;
import com.finapp.paymentmethods.SimulatedTokenisationAdapter;
import com.finapp.paymentmethods.TokenisationProvider;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the payment-method slice (`P5-TSK-005`) — the {@code paymentmethods} module's first
 * beans (`P5-TSK-004`'s store meeting its first composition-root consumer, the licence
 * expiring on schedule).
 */
@Configuration
class PaymentMethodBeans {

    @Bean
    PaymentMethodStore<Connection> paymentMethodStore() {
        return new JdbcPaymentMethodStore();
    }

    /**
     * The simulated tokenisation provider — present only where an endpoint is configured.
     *
     * <p>{@code finapp.paymentmethods.tokenisation.url} has <strong>no default</strong>
     * (ADR-0008 simulates providers; the {@code KycBeans} shape): the only endpoint that
     * exists is whatever a test or a demo stands up. Unlike the kyc adapters, the surface
     * consuming this stays present without it — {@link PaymentMethodService} takes an
     * {@code ObjectProvider} and answers the honest
     * {@code paymentmethods.TokenisationUnavailable} 503, because a contract whose endpoints
     * appear and disappear with a property is not a contract, and detach and list need no
     * provider at all.
     */
    @Bean
    @ConditionalOnProperty("finapp.paymentmethods.tokenisation.url")
    TokenisationProvider tokenisationProvider(
            @Value("${finapp.paymentmethods.tokenisation.url}") java.net.URI url,
            @Value("${finapp.paymentmethods.tokenisation.timeout:PT2S}")
                    java.time.Duration timeout) {
        return new SimulatedTokenisationAdapter(url, timeout);
    }

    /**
     * The payment-method transaction: {@code REQUIRES_NEW} and default isolation, for the
     * {@code BeneficiaryBeans} recorded reasons — the contended write is an insert arbitrated
     * by a unique index, the detach a conditional {@code UPDATE}, and the reads per-decision
     * snapshots.
     */
    @Bean
    TransactionTemplate paymentMethodTransactions(
            PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_DEFAULT);
        return template;
    }

    @Bean
    PaymentMethodService paymentMethodService(
            PaymentMethodStore<Connection> paymentMethodStore,
            ObjectProvider<TokenisationProvider> tokenisationProvider,
            MfaEnrolmentStore<Connection> mfaEnrolmentStore,
            IdentityStore<Connection> identityStore,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator ids,
            Clock clock,
            TransactionTemplate paymentMethodTransactions,
            DataSource dataSource) {
        return new PaymentMethodService(
                paymentMethodStore,
                tokenisationProvider,
                mfaEnrolmentStore,
                identityStore,
                auditWriter,
                outboxWriter,
                ids,
                clock,
                paymentMethodTransactions,
                dataSource);
    }
}
