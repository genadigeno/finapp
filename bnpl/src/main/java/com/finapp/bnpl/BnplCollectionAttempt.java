package com.finapp.bnpl;

import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One automatic collection attempt as recorded (`P12-TSK-019`, {@code bnpl V011}'s {@code bnpl_collection_attempt}; A7,
 * {@code INV-IDEM-02}; lending's {@code CollectionAttempt}): per billed instalment and business date, whatever happened - born
 * once, never changed.
 *
 * @param instalment the billed instalment attempted
 * @param attemptDate the business date of the database clock in the pinned zone, read after B4
 * @param due what the instalment had due when attempted - zero for {@code NOTHING_DUE}
 * @param collected what was taken from the wallet - positive exactly when money moved
 * @param agreementVersion the agreement version in force, whose pinned terms governed the attempt
 * @param repayment the {@code AUTO_COLLECTION} repayment that moved it - present exactly when money moved
 */
public record BnplCollectionAttempt(
        UUID id,
        BnplObligationId obligation,
        UUID instalment,
        LocalDate attemptDate,
        CollectionOutcome outcome,
        Money due,
        Money collected,
        int agreementVersion,
        Optional<UUID> repayment,
        CorrelationId correlation) {

    public BnplCollectionAttempt {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(obligation, "obligation must not be null");
        Objects.requireNonNull(instalment, "instalment must not be null");
        Objects.requireNonNull(attemptDate, "attemptDate must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(due, "due must not be null");
        Objects.requireNonNull(collected, "collected must not be null");
        Objects.requireNonNull(repayment, "repayment must not be null");
        Objects.requireNonNull(correlation, "correlation must not be null");
        if (!due.currency().equals(collected.currency()) || due.scale() != collected.scale()) {
            throw new IllegalArgumentException("an attempt's figures are in one currency and scale");
        }
        if (agreementVersion < 1) {
            throw new IllegalArgumentException("an attempt names an agreement version");
        }
        boolean shaped = switch (outcome) {
            case COLLECTED -> due.isPositive() && collected.equals(due);
            case PARTIAL -> collected.isPositive() && collected.compareTo(due) < 0;
            case INSUFFICIENT_FUNDS, WALLET_NOT_POSTABLE -> due.isPositive() && collected.isZero();
            case NOTHING_DUE -> due.isZero() && collected.isZero();
        };
        if (!shaped || outcome.collected() != repayment.isPresent()) {
            throw new IllegalArgumentException("an attempt's outcome, figures and repayment agree (bnpl V011)");
        }
    }

    /**
     * The requester's key its repayment is born under - {@code <instalment>:<attempt date>}: the attempt's natural key, so
     * {@code bnpl_repayment UNIQUE (obligation_id, kind, request_key)} is a second arbiter of once per instalment and date.
     */
    public static String requestKey(UUID instalment, LocalDate attemptDate) {
        return instalment + ":" + attemptDate;
    }

    /** Identifiers and the outcome only - never a figure ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "BnplCollectionAttempt[" + id + ", obligation " + obligation.value() + ", " + attemptDate + ", " + outcome
                + "]";
    }
}
