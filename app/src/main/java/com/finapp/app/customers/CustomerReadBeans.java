package com.finapp.app.customers;

import com.finapp.accounts.CustomerAccountStore;
import com.finapp.app.api.RecordedReads;
import com.finapp.consent.ConsentStore;
import com.finapp.identity.IdentityStore;
import com.finapp.kyc.KycCaseStore;
import com.finapp.ledger.BalanceDisplay;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.party.PartyStore;
import com.finapp.transfers.TransferStore;
import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

/** The operator's customer reads (`P17-TSK-026`) and account and transfer reads (`P17-TSK-027`), from each owner's store. */
@Configuration(proxyBeanMethods = false)
public class CustomerReadBeans {

    @Bean
    CustomerReadDesk customerReadDesk(
            PartyStore<Connection> partyStore,
            IdentityStore<Connection> identityStore,
            KycCaseStore<Connection> kycCaseStore,
            CustomerAccountStore<Connection> customerAccountStore,
            ConsentStore<Connection> consentStore,
            RecordedReads recordedReads,
            TransactionTemplate operatorReadTransactions,
            DataSource dataSource) {
        return new CustomerReadDesk(partyStore, identityStore, kycCaseStore, customerAccountStore, consentStore, recordedReads,
                operatorReadTransactions, dataSource);
    }

    @Bean
    AccountTransferReadDesk accountTransferReadDesk(
            PartyStore<Connection> partyStore,
            CustomerAccountStore<Connection> customerAccountStore,
            LedgerAccountStore<Connection> ledgerAccountStore,
            BalanceDisplay<Connection> balanceDisplay,
            TransferStore<Connection> transferStore,
            RecordedReads recordedReads,
            TransactionTemplate operatorReadTransactions,
            DataSource dataSource) {
        return new AccountTransferReadDesk(partyStore, customerAccountStore, ledgerAccountStore, balanceDisplay,
                new com.finapp.ledger.JdbcLedgerCollections(), transferStore, recordedReads, operatorReadTransactions,
                dataSource);
    }
}
