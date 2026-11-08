package com.finapp.payments;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;

/**
 * What a callback-hinted inquiry may reach (`P15-TSK-036`, ADR-0083 decision 1): the same appliers the resolution sweeps
 * use, one seam, so the channel a verdict arrived by cannot change the judgement it gets. Each method runs inside the
 * caller's transaction and platform scope. {@link PaymentCallbackAppliers} is the production binding; a hermetic suite
 * records through this seam the verdict a door handed on, which is the assertion the forged-callback tests make.
 */
public interface CallbackAppliers {

    PaymentOutcomes.Applied applyAuthorization(
            Connection uow, PaymentIntentId intent, PaymentAttemptId attempt, PaymentAttemptStatus from,
            ProviderAnswer.Verdict verdict, Optional<ProviderReference> reference, Money promised, Correlation correlation);

    PaymentOutcomes.Applied applyCapture(
            Connection uow, PaymentIntentId intent, PaymentAttemptId attempt, PaymentAttemptStatus from,
            ProviderAnswer.Verdict verdict, Optional<ProviderReference> reference, LedgerAccountId wallet, Money amount,
            Correlation correlation);

    PaymentOutcomes.Applied applyVoid(
            Connection uow, PaymentIntentId intent, PaymentAttemptId attempt, PaymentAttemptStatus from,
            ProviderAnswer.Verdict verdict, Optional<ProviderReference> reference, Correlation correlation);

    PaymentOutcomes.RefundApplied applyRefund(
            Connection uow, PaymentIntentId intent, Refund refund, RefundStatus from, ProviderAnswer.Verdict verdict,
            Optional<ProviderReference> reference, LedgerAccountId wallet, Correlation correlation);

    PaymentOutcomes.Applied applyExecution(
            Connection uow, PaymentIntentId intent, PaymentAttemptId attempt, PaymentAttemptStatus from,
            PushInquiryAnswer.Verdict verdict, Optional<ProviderReference> scheme, Optional<String> cycle,
            LedgerAccountId credit, Money amount, Optional<Money> executed, Correlation correlation);

    WithdrawalOutcomes.Applied applyWithdrawal(
            Connection uow, Withdrawal locked, PushInquiryAnswer answer, Instant neverReceivedBound, Correlation correlation);

    PaymentClearing.Outcome recordClearing(
            Connection uow, PaymentAttempt attempt, ProviderReference acquirer, ProviderReference network,
            Correlation correlation);

    DisputeNotifications.Outcome applyDispute(
            Connection uow, PaymentAttempt attempt, DisputeNotice notice, Correlation correlation);

    boolean canPark(Connection uow, RailId rail, CurrencyCode currency);

    UnmatchedConfirmations.Parked park(Connection uow, UnmatchedConfirmations.Parking parking);
}
