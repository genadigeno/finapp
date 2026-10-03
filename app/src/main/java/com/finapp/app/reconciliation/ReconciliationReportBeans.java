package com.finapp.app.reconciliation;

import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.reconciliation.JdbcReportReadings;
import com.finapp.reconciliation.ReportReadings;
import com.finapp.settlement.SettlementBatchStore;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.settlement.SettlementSources;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The operator reports' composition (`P8-TSK-024`, ADR-0072 §2): reconciliation's report reads
 * and the service that composes them, the position proof's suspense verdicts, and settlement's
 * and the ledger's read APIs into the suspense, unmatched, summary and provider-costs reports.
 * Parameter names select the bean where a type has several ({@code reconciliationTransactions},
 * {@code ledgerAccountStore}).
 */
@Configuration
public class ReconciliationReportBeans {

    /** Reconciliation's report reads — lock-free, streamed, amounts per row as {@code Money}. */
    @Bean
    ReportReadings reconciliationReportReadings() {
        return new JdbcReportReadings();
    }

    /** The four reports, each one {@code REPEATABLE READ} snapshot audited in itself. */
    @Bean
    ReconciliationReports reconciliationReports(
            ReportReadings reconciliationReportReadings,
            PositionProof positionProof,
            SettlementBatchStore<Connection> settlementBatchStore,
            SettlementFileStore<Connection> settlementFileStore,
            SettlementSources settlementSources,
            JournalEntryStore<Connection> journalEntryStore,
            LedgerAccountStore<Connection> ledgerAccountStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate reconciliationTransactions,
            DataSource dataSource) {
        return new ReconciliationReports(
                reconciliationReportReadings,
                positionProof,
                settlementBatchStore,
                settlementFileStore,
                settlementSources,
                journalEntryStore,
                ledgerAccountStore,
                auditWriter,
                idGenerator,
                clock,
                reconciliationTransactions,
                dataSource);
    }
}
