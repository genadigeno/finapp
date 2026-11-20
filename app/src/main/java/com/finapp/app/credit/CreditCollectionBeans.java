package com.finapp.app.credit;

import com.finapp.app.api.RecordedReads;
import com.finapp.credit.CreditCollections;
import com.finapp.credit.CreditPolicyStore;
import com.finapp.credit.JdbcCreditCollections;
import com.finapp.credit.ScorecardStore;
import com.finapp.credit.TransactionRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for credit's §7 collections (`P17-TSK-035`): the read model over credit's tables and the desk the investigator's
 * collection doors serve them through, every serving recorded by {@link RecordedReads}.
 */
@Configuration(proxyBeanMethods = false)
public class CreditCollectionBeans {

    @Bean
    CreditCollections creditCollections() {
        return new JdbcCreditCollections();
    }

    @Bean
    CreditCollectionDesk creditCollectionDesk(
            CreditCollections creditCollections,
            CreditPolicyStore creditPolicyStore,
            ScorecardStore scorecardStore,
            RecordedReads recordedReads,
            TransactionRunner creditTransactionRunner) {
        return new CreditCollectionDesk(creditCollections, creditPolicyStore, scorecardStore, recordedReads,
                creditTransactionRunner);
    }
}
