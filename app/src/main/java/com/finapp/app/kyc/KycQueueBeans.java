package com.finapp.app.kyc;

import com.finapp.app.api.RecordedReads;
import com.finapp.kyc.JdbcKycInquiries;
import com.finapp.kyc.KycCaseStore;
import com.finapp.kyc.KycInquiries;
import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

/** The reviewers' queues (`P17-TSK-029`): kyc's read port and the desk over it, on kyc's own transactions. */
@Configuration(proxyBeanMethods = false)
public class KycQueueBeans {

    @Bean
    KycInquiries kycInquiries() {
        return new JdbcKycInquiries();
    }

    @Bean
    KycQueueDesk kycQueueDesk(
            KycInquiries kycInquiries,
            KycCaseStore<Connection> kycCaseStore,
            RecordedReads recordedReads,
            TransactionTemplate kycTransactions,
            DataSource dataSource) {
        return new KycQueueDesk(kycInquiries, kycCaseStore, recordedReads, kycTransactions, dataSource);
    }
}
