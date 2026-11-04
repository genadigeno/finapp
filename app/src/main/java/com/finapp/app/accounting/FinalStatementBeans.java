package com.finapp.app.accounting;

import com.finapp.accounting.JdbcPeriodPins;
import com.finapp.accounting.ReportRuns;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wiring for a controller's read of a FINAL statement (`P14-TSK-022`) - its own configuration beside {@link StatementBeans}, so the module's parallel tasks each add theirs without editing one file. */
@Configuration(proxyBeanMethods = false)
public class FinalStatementBeans {

    @Bean
    FinalStatementDesk finalStatementDesk(ReportRuns reportRuns, ReportSnapshots reportSnapshots, AccountingTransactions accountingTransactions, AuditWriter<Connection> auditWriter, AccountingFlowScope accountingFlowScope, IdGenerator idGenerator, Clock clock) {
        return new FinalStatementDesk(reportRuns, new JdbcPeriodPins(), reportSnapshots, accountingTransactions, auditWriter, accountingFlowScope, idGenerator, clock);
    }
}
