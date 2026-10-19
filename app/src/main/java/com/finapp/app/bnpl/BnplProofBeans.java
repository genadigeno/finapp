package com.finapp.app.bnpl;

import com.finapp.app.credit.ExposureCensus;
import com.finapp.bnpl.BnplBook;
import com.finapp.bnpl.BnplConditionStore;
import com.finapp.bnpl.BnplCorrectionStore;
import com.finapp.bnpl.BnplDisputeStore;
import com.finapp.bnpl.BnplExplanations;
import com.finapp.bnpl.BnplMerchants;
import com.finapp.bnpl.BnplOfferStore;
import com.finapp.bnpl.BnplPayoffStore;
import com.finapp.bnpl.BnplProofReads;
import com.finapp.bnpl.BnplProofs;
import com.finapp.bnpl.BnplPurchaseStore;
import com.finapp.bnpl.BnplRecordStore;
import com.finapp.bnpl.BnplRefundStore;
import com.finapp.bnpl.BnplRepaymentStore;
import com.finapp.bnpl.BnplReplayProof;
import com.finapp.bnpl.BnplSubledgerProof;
import com.finapp.bnpl.BnplTermsStore;
import com.finapp.bnpl.BnplThreeWayProof;
import com.finapp.bnpl.JdbcBnplRecordStore;
import com.finapp.identity.IdentityStore;
import com.finapp.ledger.JdbcLedgerAccountStore;
import com.finapp.ledger.JdbcPositionBreakdown;
import com.finapp.ledger.StatementDerivation;
import com.finapp.ledger.TrialBalance;
import com.finapp.lending.LendingCapitalProof;
import com.finapp.merchant.MerchantPayable;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The BNPL proofs' wiring (`P12-TSK-025`): the record reader over bnpl's stores and the ledger port, the three proofs and
 * their one reading ({@link BnplProofs}), the three-way proof's outside reads ({@link LedgerBnplProofReads} - merchant's
 * drill-down, the ledger's statement and trial balance, lending's capital proof, credit's exposure census), the
 * investigator's doors and the gauges. A configuration of its own, so no common bean method is appended to.
 */
@Configuration(proxyBeanMethods = false)
public class BnplProofBeans {

    @Bean
    BnplRecordStore bnplRecordStore(
            BnplOfferStore bnplOfferStore,
            BnplTermsStore bnplTermsStore,
            BnplPurchaseStore bnplPurchaseStore,
            BnplRepaymentStore bnplRepaymentStore,
            BnplRefundStore bnplRefundStore,
            BnplConditionStore bnplConditionStore,
            BnplPayoffStore bnplPayoffStore,
            BnplDisputeStore bnplDisputeStore,
            BnplCorrectionStore bnplCorrectionStore,
            BnplMerchants bnplMerchants,
            BnplBook bnplBook) {
        return new JdbcBnplRecordStore(bnplOfferStore, bnplTermsStore, bnplPurchaseStore, bnplRepaymentStore,
                bnplRefundStore, bnplConditionStore, bnplPayoffStore, bnplDisputeStore, bnplCorrectionStore, bnplMerchants,
                bnplBook);
    }

    @Bean
    BnplProofReads bnplProofReads(
            StatementDerivation<Connection> statementDerivation,
            LendingCapitalProof lendingCapitalProof) {
        return new LedgerBnplProofReads(new JdbcLedgerAccountStore(),
                new MerchantPayable(new JdbcLedgerAccountStore(), new JdbcPositionBreakdown()), statementDerivation,
                new TrialBalance(), lendingCapitalProof, new ExposureCensus());
    }

    @Bean
    BnplProofs bnplProofs(BnplRecordStore bnplRecordStore, BnplProofReads bnplProofReads, BnplMerchants bnplMerchants) {
        return new BnplProofs(bnplRecordStore, new BnplSubledgerProof(), new BnplReplayProof(),
                new BnplThreeWayProof(bnplProofReads, bnplMerchants));
    }

    /** The investigator's doors; whose party an investigator is read through credit's {@code IdentityActingParty}. */
    @Bean
    BnplExplanations bnplExplanations(
            BnplCorrectionStore bnplCorrectionStore,
            BnplRecordStore bnplRecordStore,
            BnplProofs bnplProofs,
            IdentityStore<Connection> identityStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock) {
        return new BnplExplanations(bnplCorrectionStore, new com.finapp.app.credit.IdentityActingParty(identityStore),
                bnplRecordStore, bnplProofs, new BnplSubledgerProof(), auditWriter, idGenerator, clock);
    }

    @Bean
    BnplInvestigationDesk bnplInvestigationDesk(BnplExplanations bnplExplanations, BnplTransactions bnplTransactions) {
        return new BnplInvestigationDesk(bnplExplanations, bnplTransactions);
    }

    /**
     * {@code finapp.bnpl.threeway.proof{verdict}}, {@code finapp.bnpl.subledger.proof{verdict}} and
     * {@code finapp.bnpl.replay{verdict}}, registered eagerly; read behind {@code finapp.bnpl.proofs.refresh} (15 minutes
     * by default, the reading's cost control).
     */
    @Bean
    BnplProofMetrics bnplProofMetrics(BnplProofs bnplProofs, BnplTransactions bnplTransactions, Clock clock,
            MeterRegistry meterRegistry, @Value("${finapp.bnpl.proofs.refresh:PT15M}") Duration refresh) {
        return new BnplProofMetrics(bnplProofs, bnplTransactions::inSnapshot, clock, meterRegistry, refresh);
    }
}
