package com.finapp.app.risk;

import com.finapp.app.transfers.TransferStepUpAdvice;
import com.finapp.identity.IdentityStore;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.risk.RiskEvaluation;
import com.finapp.transfers.TransferLimitCheck;
import com.finapp.transfers.TransferRiskDecision;
import java.sql.Connection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Transfers judged by risk (`P13-TSK-008`; {@code PHASE_13_PLAN.md} sections 7.3, 12.1, 12.3; ADR-0108): the two seams
 * transfers declares - {@link RiskTransferLimitCheck} and {@link RiskTransferRiskDecision}, one
 * {@link TransferRiskJudgement} each over the one {@link RiskEvaluation} - and the door's step-up advice
 * ({@link RiskTransferStepUpAdvice}). Exposed by the consumers' own types ({@code TransferLimitCheck},
 * {@code TransferRiskDecision}, {@code TransferStepUpAdvice}), the credit seam's precedent ({@code RiskProfileBeans}), so
 * transfers' composition and its customer door name no risk class ({@code NoCustomerSurfaceReadsRiskTest}).
 */
@Configuration(proxyBeanMethods = false)
public class RiskTransferBeans {

    /** Transfers' limit seam: {@code REFUSE} when a limit rule decided the refusal ({@code FAILED(LIMIT_REFUSED)}). */
    @Bean
    TransferLimitCheck<Connection> transferLimitCheck(RiskEvaluation riskEvaluation,
            IdentityStore<Connection> identityStore, LedgerAccountStore<Connection> ledgerAccountStore) {
        return new RiskTransferLimitCheck(judgement(riskEvaluation, identityStore, ledgerAccountStore));
    }

    /** Transfers' risk seam: {@code REFUSE} unless the assessment permits ({@code FAILED(RISK_REFUSED)}). */
    @Bean
    TransferRiskDecision<Connection> transferRiskDecision(RiskEvaluation riskEvaluation,
            IdentityStore<Connection> identityStore, LedgerAccountStore<Connection> ledgerAccountStore) {
        return new RiskTransferRiskDecision(judgement(riskEvaluation, identityStore, ledgerAccountStore));
    }

    /** The transfer door's advice before the command: T-advise, nothing written. */
    @Bean
    TransferStepUpAdvice transferStepUpAdvice(RiskEvaluation riskEvaluation) {
        return new RiskTransferStepUpAdvice(riskEvaluation);
    }

    private static TransferRiskJudgement judgement(RiskEvaluation riskEvaluation,
            IdentityStore<Connection> identityStore, LedgerAccountStore<Connection> ledgerAccountStore) {
        return new TransferRiskJudgement(riskEvaluation, TransferSubjects.over(identityStore, ledgerAccountStore));
    }
}
