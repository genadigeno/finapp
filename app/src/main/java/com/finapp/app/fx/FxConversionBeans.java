package com.finapp.app.fx;

import com.finapp.accounts.CustomerAccountStore;
import com.finapp.accounts.WalletAccounts;
import com.finapp.fx.CoverDispatchNudge;
import com.finapp.fx.FxAvailability;
import com.finapp.fx.FxConversion;
import com.finapp.fx.JdbcTradeStore;
import com.finapp.fx.QuoteStore;
import com.finapp.fx.TradeStore;
import com.finapp.fx.TransactionRunner;
import com.finapp.fx.ConversionParticipants;
import com.finapp.identity.IdentityStore;
import com.finapp.ledger.AvailableBalance;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcHoldStore;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingService;
import com.finapp.party.PartyStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The wallet conversion's composition (`P9-TSK-009`): the trade store, {@code fx}'s
 * {@link ConversionParticipants} over the party projection and {@code accounts} (the wallet door
 * {@link WalletAccounts}), the conversion over the ledger's chart, available balance and posting,
 * the desk, and the cover nudge - {@link CoverDispatchNudge#NONE} until the sender (`P9-TSK-012`).
 */
@Configuration
public class FxConversionBeans {

    @Bean
    TradeStore tradeStore() {
        return new JdbcTradeStore();
    }

    @Bean
    ConversionParticipants conversionParticipants(
            PartyStore<Connection> partyStore,
            CustomerAccountStore<Connection> customerAccountStore,
            LedgerAccountStore<Connection> ledgerAccountStore,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new PartyConversionParticipants(
                partyStore, customerAccountStore, ledgerAccountStore,
                new WalletAccounts(customerAccountStore, ledgerAccountStore, outboxWriter, idGenerator, clock));
    }

    @Bean
    CoverDispatchNudge coverDispatchNudge() {
        return CoverDispatchNudge.NONE;
    }

    @Bean
    FxConversion fxConversion(
            QuoteStore quoteStore,
            TradeStore tradeStore,
            FxAvailability fxAvailability,
            ConversionParticipants conversionParticipants,
            LedgerAccountStore<Connection> ledgerAccountStore,
            PostingService postingService,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new FxConversion(
                quoteStore, tradeStore, fxAvailability, conversionParticipants, ledgerAccountStore,
                // Stateless stores: a direct instance is the wiring (the TransferBeans precedent).
                new AvailableBalance<>(new JdbcBalanceDerivation(), new JdbcHoldStore()),
                new ChartOfAccounts<>(ledgerAccountStore),
                postingService, auditWriter, outboxWriter, idGenerator, clock);
    }

    @Bean
    FxConversionDesk fxConversionDesk(
            FxConversion fxConversion,
            IdentityStore<Connection> identityStore,
            IdempotentExecutor idempotentExecutor,
            TransactionRunner fxTransactionRunner,
            FxQuoteMetrics fxQuoteMetrics,
            CoverDispatchNudge coverDispatchNudge) {
        return new FxConversionDesk(
                fxConversion, identityStore, idempotentExecutor, fxTransactionRunner, fxQuoteMetrics, coverDispatchNudge);
    }
}
