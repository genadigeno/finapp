package com.finapp.payments;

import com.finapp.sharedkernel.correlation.Correlation;
import java.sql.Connection;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * One refund's hinted answer on its locked row (`P15-TSK-036`), for both rails: a card refund's {@code query} and a
 * return's {@code inquireReturn} reach this same judgement, in the provider-neutral verdict.
 */
@Slf4j
final class HintedRefunds {

    private HintedRefunds() {}

    static Refund lock(Connection uow, RefundStore<Connection> refunds, RefundId refund) {
        return refunds.lockForOutcome(uow, refund)
                .orElseThrow(() -> new PaymentsStorageException("a hinted refund vanished"))
                .refund();
    }

    /** {@code verdict} is {@code APPROVED} or {@code DECLINED}: a hint adopts nothing else. */
    static HintedOutcome judge(
            Connection uow,
            PaymentAttemptStore<Connection> attempts,
            PaymentIntentStore<Connection> intents,
            CallbackAppliers appliers,
            Refund locked,
            ProviderAnswer.Verdict verdict,
            Optional<ProviderReference> reference,
            Correlation correlation) {
        if (locked.status() != RefundStatus.DISPATCHED && locked.status() != RefundStatus.UNKNOWN) {
            return late(locked, verdict, reference);
        }
        PaymentAttempt attempt = attempts.findById(uow, locked.attemptId())
                .orElseThrow(() -> new IllegalStateException("a refund row's attempt exists: V004's foreign key holds it"));
        PaymentIntent intent = intents.findById(uow, attempt.intentId())
                .orElseThrow(() -> new IllegalStateException("an attempt row's intent exists: V003's foreign key holds it"));
        PaymentOutcomes.RefundApplied applied = appliers.applyRefund(
                uow, intent.id(), locked, locked.status(), verdict, reference, intent.creditAccount(), correlation);
        return applied.acting() ? HintedOutcome.ADOPTED : HintedOutcome.CONVERGED;
    }

    private static HintedOutcome late(
            Refund locked, ProviderAnswer.Verdict verdict, Optional<ProviderReference> reference) {
        ProviderReference held = locked.providerReference();
        boolean contradicted = verdict == ProviderAnswer.Verdict.APPROVED
                ? held == null || !reference.filter(held::equals).isPresent()
                : held != null;
        if (contradicted) {
            log.warn("The provider's inquiry on refund {} in state {} contradicts the row: answered {}; the row stands and"
                            + " the answer rests as evidence - a break reconciliation must see",
                    locked.id(), locked.status(), verdict);
            return HintedOutcome.CONTRADICTED;
        }
        return HintedOutcome.CONVERGED;
    }
}
