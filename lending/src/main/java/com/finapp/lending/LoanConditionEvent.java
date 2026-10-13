package com.finapp.lending;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One recorded change of a loan's condition (`P11-TSK-024`, {@code lending V020}'s {@code loan_condition_event}; ADR-0098
 * point 3, {@code INV-LND-12}): born once per (loan, business date, kind), only on a change, INSERT-only.
 *
 * @param kind which aspect changed - the bucket ({@code DELINQUENCY}) or the default flag ({@code DEFAULT})
 * @param state the condition after the change - DPD, bucket, flag - as derived under the loan's lock
 * @param previousBucket for a {@code DELINQUENCY} change, the bucket it changed from (empty = {@code CURRENT}); empty for a
 *     {@code DEFAULT} change, whose previous flag is the negation of {@code state.defaulted()}
 * @param agreementVersion the governing agreement version, whose pinned terms judged it
 */
public record LoanConditionEvent(
        UUID id,
        LoanId loan,
        LocalDate businessDate,
        ConditionKind kind,
        DelinquencyState state,
        Optional<DelinquencyBucket> previousBucket,
        LoanAgreementId agreement,
        int agreementVersion) {

    public LoanConditionEvent {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(loan, "loan must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(state, "state must not be null");
        Objects.requireNonNull(previousBucket, "previousBucket must not be null");
        Objects.requireNonNull(agreement, "agreement must not be null");
        if (kind == ConditionKind.DELINQUENCY && previousBucket.equals(state.bucket())) {
            throw new IllegalArgumentException("a delinquency change names another bucket than the one it changed from");
        }
        if (kind == ConditionKind.DEFAULT && previousBucket.isPresent()) {
            throw new IllegalArgumentException("a default change names no previous bucket");
        }
        if (agreementVersion < 1) {
            throw new IllegalArgumentException("agreement versions are numbered from 1");
        }
    }

    /** {@code CURRENT} or the previous bucket's name - recorded for a {@code DELINQUENCY} change only. */
    public String previousBucketName() {
        return previousBucket.map(Enum::name).orElse("CURRENT");
    }

    @Override
    public String toString() {
        return "LoanConditionEvent[" + loan.value() + ", " + businessDate + ", " + kind + "]";
    }
}
