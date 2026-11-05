package com.finapp.app.accounting;

import com.finapp.accounting.AccountingBreakStore;
import com.finapp.accounting.AccountingBreaks;
import com.finapp.accounting.AccountingChecks;
import com.finapp.accounting.GlPins;
import com.finapp.accounting.JdbcAccountingBreakStore;
import com.finapp.accounting.JournalReads;
import com.finapp.accounting.JournalShapes;
import com.finapp.accounting.PostingRuleRegister;
import com.finapp.accounting.SourceProofs;
import com.finapp.accounting.reporting.GeneralLedger;
import com.finapp.app.reconciliation.PositionProof;
import com.finapp.bnpl.BnplProofs;
import com.finapp.fx.FxBooksProof;
import com.finapp.lending.LendingCapitalProof;
import com.finapp.lending.LendingProofs;
import com.finapp.ledger.JdbcBalanceDerivation;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JdbcPositionBreakdown;
import com.finapp.ledger.ProjectionVerification;
import com.finapp.merchant.MerchantPayable;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the accounting breaks and their three checks (`P14-TSK-010`) - its own configuration beside
 * {@link AccountingBeans} and {@link GeneralLedgerBeans}, so the module's parallel tasks each add theirs without editing
 * one file: the break store and machine, the journal-shape port, the product proofs' port, the checks' callable for the
 * close run (`P14-TSK-011` invokes it; nothing here schedules), the doors and the open-breaks gauge.
 */
@Configuration(proxyBeanMethods = false)
public class AccountingBreakBeans {

    /** A check's statement timeout unless configured ({@code finapp.accounting.checks.statement-timeout}). */
    static final String CHECKS_STATEMENT_TIMEOUT = "PT30S";

    @Bean
    AccountingBreakStore accountingBreakStore() {
        return new JdbcAccountingBreakStore();
    }

    @Bean
    AccountingBreaks accountingBreaks(
            AccountingBreakStore accountingBreakStore,
            JournalReads journalReads,
            AuditWriter<Connection> auditWriter,
            OutboxWriter<Connection> outboxWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new AccountingBreaks(accountingBreakStore, journalReads, auditWriter, outboxWriter, idGenerator, clock);
    }

    /** accounting's port over the journal's entries and their key namespaces - {@code SELECT} only. */
    @Bean
    JournalShapes journalShapes() {
        return new LedgerJournalShapes();
    }

    /** accounting's port over the nine product proofs, each read as its module built it. */
    @Bean
    SourceProofs sourceProofs(
            LendingProofs lendingProofs,
            LendingCapitalProof lendingCapitalProof,
            BnplProofs bnplProofs,
            FxBooksProof fxBooksProof,
            PositionProof positionProof) {
        return new ProductSourceProofs(lendingProofs::prove, lendingCapitalProof::prove, bnplProofs::prove,
                fxBooksProof::prove, positionProof::sweep,
                ProductSourceProofs.merchantPayables(
                        new MerchantPayable(new JdbcLedgerAccountStore(), new JdbcPositionBreakdown())),
                ProductSourceProofs.projections(new ProjectionVerification(new JdbcBalanceDerivation())));
    }

    /**
     * The three break-raising checks - the callable the close run invokes per claimed check. Each judge reads on its own
     * verified {@code REPEATABLE READ READ ONLY} snapshot ({@link GlSnapshots}, private to this bean so the GL doors'
     * bound stays theirs), every statement bounded by {@code finapp.accounting.checks.statement-timeout} (30 s by
     * default); every raise its own transaction.
     */
    @Bean
    AccountingChecks accountingChecks(
            GeneralLedger generalLedger,
            GlPins glPins,
            JournalShapes journalShapes,
            SourceProofs sourceProofs,
            AccountingBreaks accountingBreaks,
            AccountingTransactions accountingTransactions,
            DataSource dataSource,
            @Value("${finapp.accounting.checks.statement-timeout:" + CHECKS_STATEMENT_TIMEOUT + "}")
                    Duration statementTimeout) {
        GlSnapshots snapshots = new GlSnapshots(dataSource::getConnection, statementTimeout);
        return new AccountingChecks(generalLedger, glPins, journalShapes, sourceProofs, PostingRuleRegister.CURRENT,
                accountingBreaks, snapshots::read, accountingTransactions::inTransaction);
    }

    @Bean
    AccountingBreakDesk accountingBreakDesk(
            AccountingBreaks accountingBreaks,
            IdempotentExecutor idempotentExecutor,
            AccountingTransactions accountingTransactions,
            AccountingFlowScope accountingFlowScope,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new AccountingBreakDesk(accountingBreaks, idempotentExecutor, accountingTransactions, accountingFlowScope,
                auditWriter, idGenerator, clock);
    }

    /** {@code finapp.accounting.break.open{type}} - read from the database, cached 15 s. */
    @Bean
    AccountingBreakMetrics accountingBreakMetrics(AccountingBreaks accountingBreaks,
            @org.springframework.beans.factory.annotation.Qualifier(AccountingGaugeBeans.SNAPSHOTS) GlSnapshots snapshots, Clock clock, MeterRegistry meterRegistry) {
        return new AccountingBreakMetrics(accountingBreaks, snapshots, clock, meterRegistry);
    }
}
