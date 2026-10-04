package com.finapp.app.fx;

import com.finapp.fx.AvailabilityStore;
import com.finapp.fx.FxAvailability;
import com.finapp.fx.JdbcAvailabilityStore;
import com.finapp.fx.JdbcPricingPolicyStore;
import com.finapp.fx.PricingPolicyAdministration;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.fx.TransactionRunner;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The FX controller's composition (`P9-TSK-007`): the pricing policy and availability stores, the
 * two administrations - the policy's naming only the providers this build declares
 * ({@link FxProviderBeans#DECLARED}) - and the desk behind {@link FxAdministrationController}.
 */
@Configuration
public class FxAdministrationBeans {

    @Bean
    PricingPolicyStore pricingPolicyStore(IdGenerator idGenerator) {
        return new JdbcPricingPolicyStore(idGenerator);
    }

    @Bean
    AvailabilityStore availabilityStore(IdGenerator idGenerator) {
        return new JdbcAvailabilityStore(idGenerator);
    }

    @Bean
    PricingPolicyAdministration pricingPolicyAdministration(
            PricingPolicyStore pricingPolicyStore,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator) {
        return new PricingPolicyAdministration(
                pricingPolicyStore, auditWriter, outboxWriter, idGenerator,
                FxProviderBeans.DECLARED.keySet());
    }

    @Bean
    FxAvailability fxAvailability(
            AvailabilityStore availabilityStore,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator) {
        return new FxAvailability(availabilityStore, auditWriter, outboxWriter, idGenerator);
    }

    @Bean
    FxAdministrationDesk fxAdministrationDesk(
            PricingPolicyAdministration pricingPolicyAdministration,
            FxAvailability fxAvailability,
            IdempotentExecutor idempotentExecutor,
            TransactionRunner fxTransactionRunner,
            Clock clock) {
        return new FxAdministrationDesk(
                pricingPolicyAdministration, fxAvailability, idempotentExecutor,
                fxTransactionRunner, clock, FxProviderBeans.DECLARED.keySet());
    }
}
