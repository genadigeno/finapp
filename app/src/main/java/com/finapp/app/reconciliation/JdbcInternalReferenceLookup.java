package com.finapp.app.reconciliation;

import com.finapp.merchant.MerchantPayout;
import com.finapp.merchant.MerchantPayoutStore;
import com.finapp.payments.Dispute;
import com.finapp.payments.DisputeStage;
import com.finapp.payments.DisputeStore;
import com.finapp.payments.EndToEndReference;
import com.finapp.payments.PaymentAttempt;
import com.finapp.payments.PaymentAttemptId;
import com.finapp.payments.PaymentAttemptStore;
import com.finapp.payments.ProviderIdempotencyReference;
import com.finapp.payments.ProviderReference;
import com.finapp.payments.RailId;
import com.finapp.payments.Refund;
import com.finapp.payments.RefundStore;
import com.finapp.payments.SchemeExecutionClaim;
import com.finapp.payments.SchemeExecutionClaimStore;
import com.finapp.payments.Withdrawal;
import com.finapp.payments.WithdrawalStore;
import com.finapp.reconciliation.InternalClassification;
import com.finapp.reconciliation.InternalReferenceLookup;
import com.finapp.reconciliation.InternalSubject;
import com.finapp.reconciliation.KeyKind;
import java.sql.Connection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The one implementation of reconciliation's {@link InternalReferenceLookup}
 * (`P8-TSK-010`, ADR-0064: the modules meet only here) — read-only over payments' and
 * merchant's PUBLIC read stores, lock-free, used to TYPE breaks and never to allocate.
 *
 * <p>Per reference: the operation it names and that operation's own state, mapped onto
 * {@link InternalClassification}; across references, the strongest knowledge wins. An
 * instant-rail scheme reference resolves through {@code payments.scheme_execution_claim}
 * to exactly one subject — a claim exists only for a completed execution, so no claim
 * means no completed execution (ADR-0069 §2). {@code ACQUIRER_REF} answers {@code UNKNOWN}
 * here: the ARN's alias resolution is the matcher's key business (`P8-TSK-011`), recorded
 * at design. A store failure propagates — a lookup failure is never read as "unknown".
 */
@RequiredArgsConstructor
public final class JdbcInternalReferenceLookup implements InternalReferenceLookup {

    @NonNull private final PaymentAttemptStore<Connection> attempts;
    @NonNull private final RefundStore<Connection> refunds;
    @NonNull private final DisputeStore<Connection> disputes;
    @NonNull private final WithdrawalStore<Connection> withdrawals;
    @NonNull private final MerchantPayoutStore<Connection> payouts;
    @NonNull private final SchemeExecutionClaimStore<Connection> claims;

    /**
     * The rail a subject's key-scope source settles (`P8-TSK-017`): reconciliation names no rail,
     * so the matcher hands its item's source and the composition reads the rail off the
     * compiled register — the source's settled position, the rail declaring that clearing
     * purpose.
     */
    @NonNull private final RailOfSource railOfSource;

    /**
     * The FX covers, for an FX provider's {@code COVER_REF} (`P9-TSK-011`) - appended last (the
     * Lombok rule).
     */
    @NonNull private final com.finapp.fx.TradeStore covers;

    /** A source's rail, resolved by the composition over its compiled registers. */
    @FunctionalInterface
    public interface RailOfSource {
        Optional<RailId> railOf(Connection unitOfWork, UUID sourceId);
    }

    @Override
    public InternalReference classify(Connection unitOfWork, LookupSubject subject) {
        InternalReference answer = InternalReference.unknown();
        for (Map.Entry<KeyKind, String> reference : subject.references().entrySet()) {
            answer = answer.strongest(
                    resolve(unitOfWork, subject, reference.getKey(), reference.getValue()));
        }
        return answer;
    }

    private InternalReference resolve(
            Connection unitOfWork, LookupSubject subject, KeyKind kind, String value) {
        return switch (kind) {
            case PSP_CAPTURE_REF ->
                    attempts.findByCaptureProviderReference(
                                    unitOfWork, new ProviderReference(value))
                            .map(this::ofAttempt)
                            .orElseGet(InternalReference::unknown);
            case CARD_ATTEMPT -> byAttemptId(unitOfWork, value);
            case PSP_REFUND_REF, OUR_REF -> byOperationReference(unitOfWork, value);
            case DISPUTE_CB_REF, DISPUTE_REV_REF, DISPUTE_FEE_REF ->
                    disputes.findByProviderReference(unitOfWork, new ProviderReference(value))
                            .map(this::ofDispute)
                            .orElseGet(InternalReference::unknown);
            case SCHEME_REF -> bySchemeReference(unitOfWork, subject, value);
            case END_TO_END_REF -> byEndToEndReference(unitOfWork, value);
            case PAYOUT_PROVIDER_REF ->
                    payouts.findByProviderReference(unitOfWork, value)
                            .map(this::ofPayout)
                            .orElseGet(InternalReference::unknown);
            // The platform's cover reference (`P9-TSK-011`, PHASE_9_PLAN.md 12.9.2): a cover in
            // flight is MISSING_INTERNAL's case, a reference we never minted UNKNOWN_EXTERNAL's.
            case COVER_REF ->
                    covers.coverByClientReference(unitOfWork, value)
                            .map(this::ofCover)
                            .orElseGet(InternalReference::unknown);
            // The ARN's alias resolution is the matcher's (`P8-TSK-011`); a remittance
            // reference names evidence, not an operation; the FX provider's trade reference is
            // an alias recorded for the trace, never an operation key.
            case ACQUIRER_REF, REMITTANCE_REF, FX_TRADE_REF -> InternalReference.unknown();
        };
    }

    private InternalReference byAttemptId(Connection unitOfWork, String value) {
        UUID id;
        try {
            id = UUID.fromString(value);
        } catch (IllegalArgumentException notAnId) {
            return InternalReference.unknown();
        }
        return attempts.findById(unitOfWork, PaymentAttemptId.of(id))
                .map(this::ofAttempt)
                .orElseGet(InternalReference::unknown);
    }

    /**
     * A reference the platform minted and handed the provider: a refund's, a payout's, or - since
     * the Phase 8 -> 9 transition (IDEM-2's card residual) - a card attempt's authorization or
     * capture reference. A capture whose answer was lost holds no provider reference, so its
     * PSP line's {@code PSP_CAPTURE_REF} names nothing; the line's {@code OUR_REF} still names
     * the attempt, which answers {@code IN_FLIGHT} - the line is {@code MISSING_INTERNAL}, never
     * {@code UNKNOWN_EXTERNAL}, and a person's transfer of its parked value waits for the
     * attempt's own conclusion (ADR-0071 §2). Each kind's references are unique and minted in
     * its own shape, so at most one hit answers.
     */
    private InternalReference byOperationReference(Connection unitOfWork, String value) {
        Optional<Refund> refund =
                refunds.findByOperationReference(
                        unitOfWork, new ProviderIdempotencyReference(value));
        if (refund.isPresent()) {
            return ofRefund(refund.get());
        }
        Optional<MerchantPayout> payout = payouts.findByReference(unitOfWork, value);
        if (payout.isPresent()) {
            return ofPayout(payout.get());
        }
        return attempts.findByOperationReference(
                        unitOfWork, new ProviderIdempotencyReference(value))
                .map(this::ofAttempt)
                .orElseGet(InternalReference::unknown);
    }

    private InternalReference byEndToEndReference(Connection unitOfWork, String value) {
        Optional<PaymentAttempt> attempt =
                attempts.findByEndToEndReference(unitOfWork, new EndToEndReference(value));
        if (attempt.isPresent()) {
            return ofAttempt(attempt.get());
        }
        return withdrawals
                .findByEndToEndReference(unitOfWork, new EndToEndReference(value))
                .map(this::ofWithdrawal)
                .orElseGet(InternalReference::unknown);
    }

    private InternalReference bySchemeReference(
            Connection unitOfWork, LookupSubject subject, String value) {
        Optional<RailId> rail =
                subject.rail()
                        .map(RailId::new)
                        .or(() -> subject.scopeSourceId()
                                .flatMap(source -> railOfSource.railOf(unitOfWork, source)));
        if (rail.isEmpty()) {
            return InternalReference.unknown();
        }
        return claims.findByExecution(unitOfWork, rail.get(), new ProviderReference(value))
                .map(this::ofClaim)
                .orElseGet(InternalReference::unknown);
    }

    // ------------------------------------------------------------- the state mappings
    //
    // Pure and package-private, so JdbcInternalReferenceLookupTest proves every state
    // each store can answer maps as the backlog names (the gate's find): a capture
    // UNKNOWN is known-and-not-completed, a VOIDED or FAILED attempt is terminal, a
    // dispute LOST or ACCEPTED is terminal while a charged-back stage is the applied
    // fact, and everything dispatched or unknown is in flight - never "unknown".

    static InternalClassification classifyAttempt(
            com.finapp.payments.PaymentAttemptStatus status) {
        return switch (status) {
            case CAPTURED, EXECUTED -> InternalClassification.COMPLETED;
            case VOIDED, FAILED -> InternalClassification.TERMINAL;
            default -> InternalClassification.IN_FLIGHT;
        };
    }

    static InternalClassification classifyRefund(com.finapp.payments.RefundStatus status) {
        return switch (status) {
            case COMPLETED -> InternalClassification.COMPLETED;
            case FAILED -> InternalClassification.TERMINAL;
            default -> InternalClassification.IN_FLIGHT;
        };
    }

    static InternalClassification classifyWithdrawal(
            com.finapp.payments.WithdrawalStatus status) {
        return switch (status) {
            case COMPLETED -> InternalClassification.COMPLETED;
            case FAILED -> InternalClassification.TERMINAL;
            default -> InternalClassification.IN_FLIGHT;
        };
    }

    static InternalClassification classifyPayout(
            com.finapp.merchant.MerchantPayoutStatus status) {
        return switch (status) {
            case COMPLETED -> InternalClassification.COMPLETED;
            case FAILED -> InternalClassification.TERMINAL;
            default -> InternalClassification.IN_FLIGHT;
        };
    }

    /** A cover: executed is completed, rejected or voided terminal, dispatched or unknown in flight. */
    static InternalClassification classifyCover(com.finapp.fx.CoverStatus status) {
        return switch (status) {
            case EXECUTED -> InternalClassification.COMPLETED;
            case REJECTED, VOIDED -> InternalClassification.TERMINAL;
            case DISPATCHED, UNKNOWN -> InternalClassification.IN_FLIGHT;
        };
    }

    static InternalClassification classifyDispute(DisputeStage stage) {
        if (stage == DisputeStage.LOST || stage == DisputeStage.ACCEPTED) {
            return InternalClassification.TERMINAL;
        }
        return stage.isChargedBack()
                ? InternalClassification.COMPLETED
                : InternalClassification.IN_FLIGHT;
    }

    private InternalReference ofAttempt(PaymentAttempt attempt) {
        return new InternalReference(
                classifyAttempt(attempt.status()),
                Optional.of(attempt.id().value().toString()),
                Optional.of(attempt.status().name()),
                Optional.of(InternalSubject.PAYMENT_ATTEMPT));
    }

    private InternalReference ofRefund(Refund refund) {
        return new InternalReference(
                classifyRefund(refund.status()),
                Optional.of(refund.id().value().toString()),
                Optional.of(refund.status().name()),
                Optional.of(InternalSubject.REFUND));
    }

    private InternalReference ofWithdrawal(Withdrawal withdrawal) {
        return new InternalReference(
                classifyWithdrawal(withdrawal.status()),
                Optional.of(withdrawal.id().value().toString()),
                Optional.of(withdrawal.status().name()),
                Optional.of(InternalSubject.WITHDRAWAL));
    }

    private InternalReference ofCover(com.finapp.fx.TradeStore.CoverByReference cover) {
        return new InternalReference(
                classifyCover(cover.status()),
                Optional.of(cover.coverId().toString()),
                Optional.of(cover.status().name()),
                Optional.of(InternalSubject.COVER));
    }

    private InternalReference ofPayout(MerchantPayout payout) {
        return new InternalReference(
                classifyPayout(payout.status()),
                Optional.of(payout.id().value().toString()),
                Optional.of(payout.status().name()),
                Optional.of(InternalSubject.PAYOUT));
    }

    private InternalReference ofDispute(Dispute dispute) {
        return new InternalReference(
                classifyDispute(dispute.stage()),
                Optional.of(dispute.id().value().toString()),
                Optional.of(dispute.stage().name()),
                Optional.of(InternalSubject.DISPUTE));
    }

    private InternalReference ofClaim(SchemeExecutionClaim claim) {
        // A claim is taken only when the execution completed (ADR-0069 section 2).
        return new InternalReference(
                InternalClassification.COMPLETED,
                Optional.of(claim.subjectId().toString()),
                Optional.of(claim.subject().name()),
                Optional.of(subjectOf(claim.subject())));
    }

    /** A claim's subject in reconciliation's own vocabulary. */
    static InternalSubject subjectOf(SchemeExecutionClaim.Subject subject) {
        return switch (subject) {
            case PAY_IN -> InternalSubject.PAYMENT_ATTEMPT;
            case WITHDRAWAL -> InternalSubject.WITHDRAWAL;
            case RETURN -> InternalSubject.REFUND;
            case UNMATCHED -> InternalSubject.PARKING;
        };
    }
}
