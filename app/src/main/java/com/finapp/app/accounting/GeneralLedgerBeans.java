package com.finapp.app.accounting;

import com.finapp.accounting.AccountingPeriodStore;
import com.finapp.accounting.GlMappingGap;
import com.finapp.accounting.GlMappingStore;
import com.finapp.accounting.GlPins;
import com.finapp.accounting.JdbcPeriodPins;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.LedgerChart;
import com.finapp.accounting.reporting.GeneralLedger;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the GL read model (`P14-TSK-007`) - its own configuration beside {@link AccountingBeans}, so the module's
 * parallel tasks each add theirs without editing one file: the journal port over the ledger's tables, the fold, the
 * version resolution, the read-only snapshot, the audited door and the unmapped gauge's reading.
 */
@Configuration(proxyBeanMethods = false)
public class GeneralLedgerBeans {

    /** accounting's port over the ledger's journal - {@code SELECT} only, on the caller's snapshot. */
    @Bean
    JournalReads journalReads() {
        return new LedgerJournalReads();
    }

    @Bean
    GeneralLedger generalLedger(JournalReads journalReads) {
        return new GeneralLedger(journalReads);
    }

    /** The version a month is read under: its period's pin, else the version in force. */
    @Bean
    GlPins glPins(GlMappingStore glMappingStore) {
        return new GlPins(glMappingStore, new JdbcPeriodPins());
    }

    /** One verified {@code REPEATABLE READ READ ONLY} snapshot per GL read, on a connection of its own. */
    @Bean
    GlSnapshots glSnapshots(DataSource dataSource) {
        return new GlSnapshots(dataSource::getConnection);
    }

    /** The reading behind {@code finapp.accounting.gl.unmapped} - read on a {@link GlSnapshots} snapshot. */
    @Bean
    GlMappingGap glMappingGap(
            GlMappingStore glMappingStore,
            LedgerChart ledgerChart,
            AccountingPeriodStore accountingPeriodStore,
            JournalReads journalReads) {
        return new GlMappingGap(glMappingStore, ledgerChart, accountingPeriodStore, journalReads);
    }

    @Bean
    GeneralLedgerDesk generalLedgerDesk(
            GeneralLedger generalLedger,
            GlPins glPins,
            GlSnapshots glSnapshots,
            AccountingTransactions accountingTransactions,
            AuditWriter<Connection> auditWriter,
            AccountingFlowScope accountingFlowScope,
            IdGenerator idGenerator,
            Clock clock) {
        return new GeneralLedgerDesk(generalLedger, glPins, glSnapshots, accountingTransactions, auditWriter,
                accountingFlowScope, idGenerator, clock);
    }
}
