package com.finapp.lending;

import com.finapp.creditmath.DueItemId;
import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One automatic collection attempt as recorded (`P11-TSK-019`, {@code lending V015}'s {@code collection_attempt}; ADR-0095
 * point 6, A21, {@code INV-IDEM-02}): per billed item and business date, whatever happened - born once, never changed.
 *
 * @param dueItem the billed item attempted
 * @param attemptDate the business date of the database clock in the pinned zone
 * @param due what the item had due when attempted - zero for {@code NOTHING_DUE}
 * @param collected what was taken from the wallet - positive exactly when money moved
 * @param repayment the {@code AUTO_COLLECTION} repayment that moved it - present exactly when money moved
 */
public record CollectionAttempt(
        UUID id,
        LoanId loan,
        DueItemId dueItem,
        LocalDate attemptDate,
        CollectionOutcome outcome,
        Money due,
        Money collected,
        Optional<UUID> repayment) {

    public CollectionAttempt {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(loan, "loan must not be null");
        Objects.requireNonNull(dueItem, "dueItem must not be null");
        Objects.requireNonNull(attemptDate, "attemptDate must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(due, "due must not be null");
        Objects.requireNonNull(collected, "collected must not be null");
        Objects.requireNonNull(repayment, "repayment must not be null");
        if (!due.currency().equals(collected.currency()) || due.scale() != collected.scale()) {
            throw new IllegalArgumentException("an attempt's figures are in one currency and scale");
        }
        boolean shaped = switch (outcome) {
            case COLLECTED -> due.isPositive() && collected.equals(due);
            case PARTIAL -> collected.isPositive() && collected.compareTo(due) < 0;
            case INSUFFICIENT_FUNDS, WALLET_NOT_POSTABLE -> due.isPositive() && collected.isZero();
            case NOTHING_DUE -> due.isZero() && collected.isZero();
        };
        if (!shaped || outcome.collected() != repayment.isPresent()) {
            throw new IllegalArgumentException("an attempt's outcome, figures and repayment agree (lending V015)");
        }
    }

    /**
     * The requester's key its repayment is born under - {@code <billed item>:<attempt date>}: the attempt's natural key, so
     * {@code repayment UNIQUE (loan_id, kind, request_key)} is a second arbiter of once per item and date.
     */
    public static String requestKey(DueItemId item, LocalDate attemptDate) {
        return item.value() + ":" + attemptDate;
    }

    /** Identifiers and the outcome only - never a figure ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "CollectionAttempt[" + id + ", loan " + loan.value() + ", " + attemptDate + ", " + outcome + "]";
    }
}
