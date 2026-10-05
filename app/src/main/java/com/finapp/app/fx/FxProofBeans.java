package com.finapp.app.fx;

import com.finapp.fx.FxBooksProof;
import com.finapp.fx.FxPlanVerification;
import com.finapp.fx.FxProofStore;
import com.finapp.fx.FxProvenanceStore;
import com.finapp.fx.JdbcFxProofStore;
import com.finapp.fx.JdbcFxProvenanceStore;
import com.finapp.fx.PricingPolicyStore;
import com.finapp.fx.QuoteStore;
import com.finapp.fx.TransactionRunner;
import com.finapp.ledger.BalanceDerivation;
import com.finapp.ledger.ChartOfAccounts;
import com.finapp.ledger.JournalEntryStore;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The FX proofs and the investigator's door (`P9-TSK-013`; PHASE_9_PLAN.md section 12.9.4): the
 * books proof and the plan replay over fx's rows and ledger's own reads, their verdict gauges, and
 * the audited provenance read.
 */
@Configuration
public class FxProofBeans {

    @Bean
    FxProofStore fxProofStore() {
        return new JdbcFxProofStore();
    }

    @Bean
    FxBooksProof fxBooksProof(
            FxProofStore fxProofStore,
            LedgerAccountStore<Connection> ledgerAccountStore,
            BalanceDerivation<Connection> balanceDerivation) {
        return new FxBooksProof(fxProofStore, new ChartOfAccounts<>(ledgerAccountStore), balanceDerivation);
    }

    @Bean
    FxPlanVerification fxPlanVerification(
            FxProofStore fxProofStore,
            QuoteStore quoteStore,
            PricingPolicyStore pricingPolicyStore,
            JournalEntryStore<Connection> journalEntryStore,
            LedgerAccountStore<Connection> ledgerAccountStore) {
        return new FxPlanVerification(fxProofStore, quoteStore, pricingPolicyStore, journalEntryStore,
                new ChartOfAccounts<>(ledgerAccountStore));
    }

    @Bean
    FxProofMetrics fxProofMetrics(
            FxBooksProof fxBooksProof,
            FxPlanVerification fxPlanVerification,
            DataSource dataSource,
            Clock clock,
            MeterRegistry meterRegistry) {
        return new FxProofMetrics(fxBooksProof, fxPlanVerification, dataSource::getConnection, clock, meterRegistry);
    }

    @Bean
    FxProvenanceStore fxProvenanceStore() {
        return new JdbcFxProvenanceStore();
    }

    @Bean
    FxProvenanceDesk fxProvenanceDesk(
            FxProvenanceStore fxProvenanceStore,
            AuditWriter<Connection> auditWriter,
            TransactionRunner fxTransactionRunner,
            IdGenerator idGenerator,
            Clock clock) {
        return new FxProvenanceDesk(fxProvenanceStore, auditWriter, fxTransactionRunner, idGenerator, clock);
    }
}
