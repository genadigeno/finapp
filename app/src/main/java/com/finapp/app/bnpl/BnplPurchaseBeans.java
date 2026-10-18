package com.finapp.app.bnpl;

import com.finapp.accounts.CustomerAccountStore;
import com.finapp.bnpl.BnplAcceptances;
import com.finapp.bnpl.BnplAccounts;
import com.finapp.bnpl.BnplBook;
import com.finapp.bnpl.BnplCapital;
import com.finapp.bnpl.BnplCheckoutSessions;
import com.finapp.bnpl.BnplCreditDecisions;
import com.finapp.bnpl.BnplMerchants;
import com.finapp.bnpl.BnplObligations;
import com.finapp.bnpl.BnplOfferStore;
import com.finapp.bnpl.BnplPartyStanding;
import com.finapp.bnpl.BnplPurchaseStore;
import com.finapp.bnpl.BnplTermsStore;
import com.finapp.bnpl.BnplWallets;
import com.finapp.bnpl.JdbcBnplPurchaseStore;
import com.finapp.bnpl.LedgerBnplBook;
import com.finapp.identity.IdentityStore;
import com.finapp.ledger.AvailableBalance;
import com.finapp.ledger.BalanceDerivation;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.JdbcHoldStore;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.ledger.PostingService;
import com.finapp.party.PartyStore;
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
 * The BNPL purchase's wiring (`P12-TSK-013`): the store, the wallet and ledger seams, the acceptance (T-pur), the owner's
 * agreement reads, the meter and the desk. bnpl's ports are each {@code app}'s composition over the owning module's
 * published API - credit's lock and consumption ({@code CreditBnplDecisions}), checkout's session
 * ({@code CheckoutBnplSessions}), merchant's standing, payable and fee pin ({@code MerchantBnplMerchants}), lending's
 * capital ({@code LendingBnplCapital}), accounts' wallet ({@link AccountsBnplWallets}) and the ledger
 * ({@link LedgerBnplBook}).
 */
@Configuration(proxyBeanMethods = false)
public class BnplPurchaseBeans {

    @Bean
    BnplPurchaseStore bnplPurchaseStore() {
        return new JdbcBnplPurchaseStore();
    }

    /** The customer's existing wallet - accounts' one wallet, never opened (B6). */
    @Bean
    BnplWallets bnplWallets(
            PartyStore<Connection> partyStore,
            CustomerAccountStore<Connection> customerAccountStore,
            LedgerAccountStore<Connection> ledgerAccountStore) {
        return new AccountsBnplWallets(partyStore, customerAccountStore, ledgerAccountStore);
    }

    /** The ledger as bnpl's money-moving acts command and read it - the obligation's opener, the chart, the posting. */
    @Bean
    BnplBook bnplBook(
            LedgerAccountStore<Connection> ledgerAccountStore,
            BalanceDerivation<Connection> balanceDerivation,
            PostingService postingService,
            IdGenerator idGenerator,
            Clock clock) {
        return new LedgerBnplBook(new BnplAccounts(ledgerAccountStore, idGenerator, clock), ledgerAccountStore,
                new ChartOfAccounts<>(ledgerAccountStore),
                new AvailableBalance<>(balanceDerivation, new JdbcHoldStore()), balanceDerivation, postingService);
    }

    /** T-pur - the customer's acceptance, the purchase, the merchant financing and the obligation in one transaction. */
    @Bean
    BnplAcceptances bnplAcceptances(
            BnplOfferStore bnplOfferStore,
            BnplTermsStore bnplTermsStore,
            BnplPurchaseStore bnplPurchaseStore,
            BnplCreditDecisions bnplCreditDecisions,
            BnplPartyStanding bnplPartyStanding,
            BnplCheckoutSessions bnplCheckoutSessions,
            BnplMerchants bnplMerchants,
            BnplCapital bnplCapital,
            BnplWallets bnplWallets,
            BnplBook bnplBook,
            OutboxWriter<Connection> outboxWriter,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new BnplAcceptances(bnplOfferStore, bnplTermsStore, bnplPurchaseStore, bnplCreditDecisions,
                bnplPartyStanding, bnplCheckoutSessions, bnplMerchants, bnplCapital, bnplWallets, bnplBook, outboxWriter,
                auditWriter, idGenerator, clock);
    }

    /** The customer's agreements - the list, the read with its derived balances, the schedule. */
    @Bean
    BnplObligations bnplObligations(BnplPurchaseStore bnplPurchaseStore, BnplBook bnplBook) {
        return new BnplObligations(bnplPurchaseStore, bnplBook);
    }

    /** {@code finapp.bnpl.purchase{product, plan, outcome}} - every series eager. */
    @Bean
    BnplPurchaseMetrics bnplPurchaseMetrics(MeterRegistry meterRegistry) {
        return new BnplPurchaseMetrics(meterRegistry);
    }

    @Bean
    BnplAgreementDesk bnplAgreementDesk(
            BnplAcceptances bnplAcceptances,
            BnplObligations bnplObligations,
            IdentityStore<Connection> identityStore,
            IdempotentExecutor idempotentExecutor,
            BnplTransactions bnplTransactions,
            BnplPurchaseMetrics bnplPurchaseMetrics) {
        return new BnplAgreementDesk(bnplAcceptances, bnplObligations, identityStore, idempotentExecutor,
                bnplTransactions, bnplPurchaseMetrics);
    }
}
