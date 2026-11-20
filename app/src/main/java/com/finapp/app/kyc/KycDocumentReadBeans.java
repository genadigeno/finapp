package com.finapp.app.kyc;

import com.finapp.app.api.RecordedReads;
import com.finapp.kyc.DocumentAccess;
import com.finapp.kyc.DocumentStore;
import com.finapp.kyc.KycCaseStore;
import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The KYC reviewer's document reads (`P17-TSK-125`). One snapshot per answer: {@code operatorReadTransactions}, the operator
 * reads' own template ({@code REPEATABLE READ}, {@code REQUIRES_NEW}) - chosen by this parameter's name, as every
 * {@code TransactionTemplate} here is.
 */
@Configuration(proxyBeanMethods = false)
public class KycDocumentReadBeans {

    @Bean
    KycDocumentReadDesk kycDocumentReadDesk(
            DocumentStore<Connection> documentStore,
            DocumentAccess documentAccess,
            KycCaseStore<Connection> kycCaseStore,
            RecordedReads recordedReads,
            TransactionTemplate operatorReadTransactions,
            DataSource dataSource) {
        return new KycDocumentReadDesk(documentStore, documentAccess, kycCaseStore, recordedReads, operatorReadTransactions,
                dataSource);
    }
}
