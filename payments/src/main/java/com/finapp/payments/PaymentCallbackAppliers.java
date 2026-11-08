package com.finapp.payments;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** The production {@link CallbackAppliers}: straight delegation to the components the sweeps use (`P15-TSK-036`). */
@RequiredArgsConstructor
public final class PaymentCallbackAppliers implements CallbackAppliers {

    @NonNull private final PaymentOutcomes outcomes;
    @NonNull private final PaymentClearing clearing;
    @NonNull private final DisputeNotifications disputes;
    @NonNull private final UnmatchedConfirmations unmatched;
    @NonNull private final WithdrawalOutcomes withdrawals;

    @Override
    public PaymentOutcomes.Applied applyAuthorization(
            Connection uow, PaymentIntentId intent, PaymentAttemptId attempt, PaymentAttemptStatus from,
            ProviderAnswer.Verdict verdict, Optional<ProviderReference> reference, Money promised, Correlation correlation) {
        return outcomes.applyAuthorization(uow, intent, attempt, from, verdict, reference, promised, correlation);
    }

    @Override
    public PaymentOutcomes.Applied applyCapture(
            Connection uow, PaymentIntentId intent, PaymentAttemptId attempt, PaymentAttemptStatus from,
            ProviderAnswer.Verdict verdict, Optional<ProviderReference> reference, LedgerAccountId wallet, Money amount,
            Correlation correlation) {
        return outcomes.applyCapture(uow, intent, attempt, from, verdict, reference, wallet, amount, correlation);
    }

    @Override
    public PaymentOutcomes.Applied applyVoid(
            Connection uow, PaymentIntentId intent, PaymentAttemptId attempt, PaymentAttemptStatus from,
            ProviderAnswer.Verdict verdict, Optional<ProviderReference> reference, Correlation correlation) {
        return outcomes.applyVoid(uow, intent, attempt, from, verdict, reference, correlation);
    }

    @Override
    public PaymentOutcomes.RefundApplied applyRefund(
            Connection uow, PaymentIntentId intent, Refund refund, RefundStatus from, ProviderAnswer.Verdict verdict,
            Optional<ProviderReference> reference, LedgerAccountId wallet, Correlation correlation) {
        return outcomes.applyRefund(uow, intent, refund, from, verdict, reference, wallet, correlation);
    }

    @Override
    public PaymentOutcomes.Applied applyExecution(
            Connection uow, PaymentIntentId intent, PaymentAttemptId attempt, PaymentAttemptStatus from,
            PushInquiryAnswer.Verdict verdict, Optional<ProviderReference> scheme, Optional<String> cycle,
            LedgerAccountId credit, Money amount, Optional<Money> executed, Correlation correlation) {
        return outcomes.applyExecution(
                uow, intent, attempt, from, verdict, scheme, cycle, credit, amount, executed, correlation);
    }

    @Override
    public WithdrawalOutcomes.Applied applyWithdrawal(
            Connection uow, Withdrawal locked, PushInquiryAnswer answer, Instant neverReceivedBound, Correlation correlation) {
        return withdrawals.applyInquiryAnswer(uow, locked, answer, neverReceivedBound, correlation);
    }

    @Override
    public PaymentClearing.Outcome recordClearing(
            Connection uow, PaymentAttempt attempt, ProviderReference acquirer, ProviderReference network,
            Correlation correlation) {
        return clearing.record(uow, attempt, acquirer, network, correlation);
    }

    @Override
    public DisputeNotifications.Outcome applyDispute(
            Connection uow, PaymentAttempt attempt, DisputeNotice notice, Correlation correlation) {
        return disputes.apply(uow, attempt, notice, correlation);
    }

    @Override
    public boolean canPark(Connection uow, RailId rail, CurrencyCode currency) {
        return unmatched.canPark(uow, rail, currency);
    }

    @Override
    public UnmatchedConfirmations.Parked park(Connection uow, UnmatchedConfirmations.Parking parking) {
        return unmatched.park(uow, parking);
    }
}
