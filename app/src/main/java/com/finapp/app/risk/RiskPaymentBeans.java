package com.finapp.app.risk;

import com.finapp.app.checkout.CheckoutStepUpAdvice;
import com.finapp.ledger.LedgerAccountStore;
import com.finapp.payments.HeldPayments;
import com.finapp.payments.PaymentRiskDecision;
import com.finapp.risk.HeldPaymentActions;
import com.finapp.risk.RiskEvaluation;
import java.sql.Connection;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Checkout payments judged by risk (`P13-TSK-010`; {@code PHASE_13_PLAN.md} sections 7.3, 12.1, 12.3; ADR-0108): payments'
 * seam ({@link RiskPaymentRiskDecision}), the checkout confirmation door's step-up advice
 * ({@link RiskCheckoutStepUpAdvice}) and risk's {@link HeldPaymentActions} port over payments' keyed doors on a held card
 * payment ({@link PaymentsHeldPaymentActions}). Exposed by the consumers' own types ({@code PaymentRiskDecision},
 * {@code CheckoutStepUpAdvice}) - the withdrawal's precedent ({@code RiskWithdrawalBeans}) - so payments' composition and
 * the customer's checkout door name no risk class ({@code NoCustomerSurfaceReadsRiskTest}).
 */
@Configuration(proxyBeanMethods = false)
public class RiskPaymentBeans {

    /** Payments' checkout payment seam: PERMIT, HOLD (a card, with the pinned hold validity) or REFUSE. */
    @Bean
    PaymentRiskDecision<Connection> paymentRiskDecision(RiskEvaluation riskEvaluation,
            LedgerAccountStore<Connection> ledgerAccountStore) {
        return new RiskPaymentRiskDecision(riskEvaluation, RiskPaymentRiskDecision.Accounts.over(ledgerAccountStore));
    }

    /** The checkout confirmation door's advice before the command: T-advise, nothing written. */
    @Bean
    CheckoutStepUpAdvice checkoutStepUpAdvice(RiskEvaluation riskEvaluation) {
        return new RiskCheckoutStepUpAdvice(riskEvaluation);
    }

    /** Payments' held-payment doors as risk's action port - for `-012` and `-017`, which are its callers. */
    @Bean
    HeldPaymentActions heldPaymentActions(ObjectProvider<HeldPayments> heldPayments) {
        return new PaymentsHeldPaymentActions(heldPayments::getIfAvailable);
    }
}
