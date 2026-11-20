package com.finapp.app.overview;

import com.finapp.party.JdbcCustomerReports;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.transfers.JdbcTransferReports;
import java.sql.Connection;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The operations overview's customers and transfers reports (`P17-TSK-039`). */
@Configuration(proxyBeanMethods = false)
public class OverviewReportBeans {

    @Bean
    OverviewReportDesk overviewReportDesk(
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            PlatformTransactionManager transactionManager,
            DataSource dataSource) {
        return new OverviewReportDesk(
                new JdbcCustomerReports(),
                new JdbcTransferReports(),
                auditWriter,
                idGenerator,
                clock,
                new TransactionTemplate(transactionManager),
                dataSource);
    }
}
