package com.finapp.bnpl;

import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One recorded change of a BNPL obligation's condition (`P12-TSK-020`, {@code bnpl V013}'s {@code bnpl_condition_event};
 * {@code PHASE_12_PLAN.md} section 12.8; lending's {@code LoanConditionEvent}): born once per (obligation, business date,
 * kind), only on a change, INSERT-only - the append-only history ({@code INV-HIST-01}).
 *
 * @param kind which aspect changed - the bucket ({@code DELINQUENCY}) or the default flag ({@code DEFAULT})
 * @param state the condition after the change - DPD, bucket, flag - as derived under the obligation's lock (B4)
 * @param previousBucket for a {@code DELINQUENCY} change, the bucket it changed from (empty = {@code CURRENT}); empty for a
 *     {@code DEFAULT} change, whose previous flag is the negation of {@code state.defaulted()}
 * @param agreementVersion the agreement version in force, whose pinned terms judged it
 */
public record BnplConditionEvent(
        UUID id,
        BnplObligationId obligation,
        LocalDate businessDate,
        BnplConditionKind kind,
        BnplDelinquencyState state,
        Optional<BnplDelinquencyBucket> previousBucket,
        BnplAgreementId agreement,
        int agreementVersion,
        CorrelationId correlation) {

    public BnplConditionEvent {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(obligation, "obligation must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(previousBucket, "previousBucket must not be null");
        Objects.requireNonNull(agreement, "agreement must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        if (kind == BnplConditionKind.DELINQUENCY && previousBucket.equals(state.bucket())) {
            throw new IllegalArgumentException("a delinquency change names another bucket than the one it changed from");
        }
        if (kind == BnplConditionKind.DEFAULT && previousBucket.isPresent()) {
            throw new IllegalArgumentException("a default change names no previous bucket");
        }
        if (agreementVersion < 1) {
            throw new IllegalArgumentException("agreement versions are numbered from 1");
        }
    }

    /** {@code CURRENT} or the previous bucket's name - recorded for a {@code DELINQUENCY} change only. */
    public String previousBucketName() {
        return BnplDelinquencyBucket.nameOf(previousBucket);
    }

    /** Identifiers, the date and the kind only - never the DPD. */
    @Override
    public String toString() {
        return "BnplConditionEvent[" + obligation.value() + ", " + businessDate + ", " + kind + "]";
    }
}
