package com.finapp.app.crossborder;

import com.finapp.app.fx.FxQuoteMetrics;
import com.finapp.crossborder.BeneficiaryStore;
import com.finapp.crossborder.CorridorAvailabilityStore;
import com.finapp.crossborder.CorridorPolicyStore;
import com.finapp.crossborder.CounterpartyScreening;
import com.finapp.crossborder.CrossBorderFx;
import com.finapp.crossborder.JdbcOfferStore;
import com.finapp.crossborder.OfferIssuance;
import com.finapp.crossborder.OfferStore;
import com.finapp.crossborder.TransactionRunner;
import com.finapp.fx.QuoteIssuance;
import com.finapp.fx.QuoteLifecycle;
import com.finapp.identity.IdentityStore;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the cross-border offer (`P9-TSK-018`): the offer store, {@link CrossBorderFx} over fx's quote
 * issuance, the offer issuance and the quote door's desk.
 */
@Configuration(proxyBeanMethods = false)
public class CrossBorderOfferBeans {

    @Bean
    OfferStore crossBorderOfferStore() {
        return new JdbcOfferStore();
    }

    @Bean
    CrossBorderFx crossBorderFx(
            QuoteIssuance quoteIssuance,
            QuoteLifecycle quoteLifecycle,
            com.finapp.fx.CrossBorderAcceptance crossBorderAcceptance,
            com.finapp.fx.CoverDispatchNudge coverDispatchNudge) {
        return new FxCrossBorderQuotes(quoteIssuance, quoteLifecycle, crossBorderAcceptance, coverDispatchNudge);
    }

    /** fx's acceptance of a cross-border quote inside the payment's authorization (`P9-TSK-019`). */
    @Bean
    com.finapp.fx.CrossBorderAcceptance crossBorderAcceptance(
            com.finapp.fx.QuoteStore quoteStore,
            com.finapp.fx.TradeStore tradeStore,
            com.finapp.fx.FxAvailability fxAvailability,
            com.finapp.fx.ConversionParticipants conversionParticipants,
            com.finapp.platform.outbox.OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new com.finapp.fx.CrossBorderAcceptance(quoteStore, tradeStore, fxAvailability, conversionParticipants,
                outboxWriter, idGenerator, clock);
    }

    @Bean
    OfferIssuance crossBorderOfferIssuance(
            OfferStore crossBorderOfferStore,
            BeneficiaryStore crossBorderBeneficiaryStore,
            CorridorPolicyStore corridorPolicyStore,
            CorridorAvailabilityStore corridorAvailabilityStore,
            CounterpartyScreening counterpartyScreening,
            CrossBorderFx crossBorderFx,
            IdGenerator idGenerator) {
        return new OfferIssuance(crossBorderOfferStore, crossBorderBeneficiaryStore, corridorPolicyStore,
                corridorAvailabilityStore, counterpartyScreening, crossBorderFx, idGenerator);
    }

    @Bean
    CrossBorderQuoteDesk crossBorderQuoteDesk(
            OfferIssuance crossBorderOfferIssuance,
            IdentityStore<Connection> identityStore,
            IdempotentExecutor idempotentExecutor,
            TransactionRunner crossborderTransactionRunner,
            FxQuoteMetrics fxQuoteMetrics,
            Clock clock) {
        return new CrossBorderQuoteDesk(crossBorderOfferIssuance, identityStore, idempotentExecutor,
                crossborderTransactionRunner, fxQuoteMetrics, clock);
    }
}
