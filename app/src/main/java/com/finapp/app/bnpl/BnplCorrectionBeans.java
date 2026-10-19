package com.finapp.app.bnpl;

import com.finapp.bnpl.BnplBook;
import com.finapp.bnpl.BnplCapital;
import com.finapp.bnpl.BnplClosures;
import com.finapp.bnpl.BnplCorrectionStore;
import com.finapp.bnpl.BnplCreditDecisions;
import com.finapp.bnpl.BnplPurchaseStore;
import com.finapp.bnpl.BnplRepaymentReversals;
import com.finapp.bnpl.BnplServicingStore;
import com.finapp.bnpl.BnplWaivers;
import com.finapp.bnpl.BnplWallets;
import com.finapp.bnpl.JdbcBnplCorrectionStore;
import com.finapp.identity.IdentityStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The BNPL servicing corrections' wiring (`P12-TSK-024`): the store, T-rev ({@link BnplRepaymentReversals}) and T-wvr
 * ({@link BnplWaivers}) - whose party an operator is through credit's {@code IdentityActingParty}, one read for credit,
 * lending and bnpl - the meters and the desk. A configuration of its own, so parallel tasks append to no common bean method.
 */
@Configuration(proxyBeanMethods = false)
public class BnplCorrectionBeans {

    @Bean
    BnplCorrectionStore bnplCorrectionStore() {
        return new JdbcBnplCorrectionStore();
    }

    /** T-rev - the four-eyes reversal under L0, B4, B6, L6, (P) and L8, posting F8 through the ledger's ReversalService. */
    @Bean
    BnplRepaymentReversals bnplRepaymentReversals(
            BnplCorrectionStore bnplCorrectionStore,
            BnplServicingStore bnplServicingStore,
            BnplPurchaseStore bnplPurchaseStore,
            BnplCreditDecisions bnplCreditDecisions,
            BnplCapital bnplCapital,
            BnplBook bnplBook,
            IdentityStore<Connection> identityStore,
            OutboxWriter<Connection> outboxWriter,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new BnplRepaymentReversals(bnplCorrectionStore, bnplServicingStore, bnplPurchaseStore, bnplCreditDecisions,
                bnplCapital, bnplBook, new com.finapp.app.credit.IdentityActingParty(identityStore), outboxWriter,
                auditWriter, idGenerator, clock);
    }

    /** T-wvr - the four-eyes fee waiver under B4, B6, (P) beside a credit balance and L8, posting F7. */
    @Bean
    BnplWaivers bnplWaivers(
            BnplCorrectionStore bnplCorrectionStore,
            BnplServicingStore bnplServicingStore,
            BnplPurchaseStore bnplPurchaseStore,
            BnplWallets bnplWallets,
            BnplBook bnplBook,
            BnplClosures bnplClosures,
            IdentityStore<Connection> identityStore,
            OutboxWriter<Connection> outboxWriter,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new BnplWaivers(bnplCorrectionStore, bnplServicingStore, bnplPurchaseStore, bnplWallets, bnplBook,
                bnplClosures, new com.finapp.app.credit.IdentityActingParty(identityStore), outboxWriter, auditWriter,
                idGenerator, clock);
    }

    /** {@code finapp.bnpl.reversal{outcome}} and {@code finapp.bnpl.waiver{outcome}} - every series eager. */
    @Bean
    BnplCorrectionMetrics bnplCorrectionMetrics(MeterRegistry meterRegistry) {
        return new BnplCorrectionMetrics(meterRegistry);
    }

    @Bean
    BnplCorrectionDesk bnplCorrectionDesk(
            BnplRepaymentReversals bnplRepaymentReversals,
            BnplWaivers bnplWaivers,
            IdempotentExecutor idempotentExecutor,
            BnplTransactions bnplTransactions,
            BnplCorrectionMetrics bnplCorrectionMetrics) {
        return new BnplCorrectionDesk(bnplRepaymentReversals, bnplWaivers, idempotentExecutor, bnplTransactions,
                bnplCorrectionMetrics);
    }
}
