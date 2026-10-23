package com.finapp.app.risk;

import com.finapp.app.payments.WithdrawalStepUpAdvice;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.payments.WithdrawalRiskDecision;
import com.finapp.payments.Withdrawals;
import com.finapp.risk.HeldWithdrawalActions;
import com.finapp.risk.RiskEvaluation;
import java.sql.Connection;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Withdrawals judged by risk (`P13-TSK-009`; {@code PHASE_13_PLAN.md} sections 7.3, 12.1, 12.3; ADR-0108): payments' seam
 * ({@link RiskWithdrawalRiskDecision}), the withdrawal door's step-up advice ({@link RiskWithdrawalStepUpAdvice}) and
 * risk's {@link HeldWithdrawalActions} port over payments' keyed doors ({@link PaymentsHeldWithdrawalActions}). Exposed
 * by the consumers' own types ({@code WithdrawalRiskDecision}, {@code WithdrawalStepUpAdvice}) - the transfer seams'
 * precedent ({@code RiskTransferBeans}) - so payments' composition and the customer's withdrawal door name no risk class
 * ({@code NoCustomerSurfaceReadsRiskTest}).
 */
@Configuration(proxyBeanMethods = false)
public class RiskWithdrawalBeans {

    /** Payments' withdrawal seam: PERMIT, HOLD (with the pinned hold validity) or REFUSE. */
    @Bean
    WithdrawalRiskDecision<Connection> withdrawalRiskDecision(RiskEvaluation riskEvaluation,
            LedgerAccountStore<Connection> ledgerAccountStore) {
        return new RiskWithdrawalRiskDecision(riskEvaluation,
                RiskWithdrawalRiskDecision.WalletOwner.over(ledgerAccountStore));
    }

    /** The withdrawal door's advice before the command: T-advise, nothing written. */
    @Bean
    WithdrawalStepUpAdvice withdrawalStepUpAdvice(RiskEvaluation riskEvaluation) {
        return new RiskWithdrawalStepUpAdvice(riskEvaluation);
    }

    /** Payments' held-withdrawal doors as risk's action port - for `-012` and `-017`, which are its callers. */
    @Bean
    HeldWithdrawalActions heldWithdrawalActions(ObjectProvider<Withdrawals> withdrawals) {
        return new PaymentsHeldWithdrawalActions(withdrawals::getIfAvailable);
    }
}
