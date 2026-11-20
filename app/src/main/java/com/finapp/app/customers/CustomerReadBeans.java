package com.finapp.app.customers;

import com.finapp.accounts.CustomerAccountStore;
import com.finapp.app.api.RecordedReads;
import com.finapp.consent.ConsentStore;
import com.finapp.identity.IdentityStore;
import com.finapp.kyc.KycCaseStore;
import com.finapp.party.PartyStore;
import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

/** The operator's customer reads (`P17-TSK-026`), composed from each owning module's store. */
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
}
