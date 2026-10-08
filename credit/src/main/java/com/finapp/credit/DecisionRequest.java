package com.finapp.credit;

import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A customer's credit decision request, as recorded (`P10-TSK-014`, {@code credit V010}; CREDIT_DECISIONING_LIFECYCLES.md
 * section 3.1): one product, the terms applied for, its status - and, from {@code SUBMITTED -> COLLECTING}, the pinned
 * versions (`P10-TSK-015`). Every window is the database's: {@code submittedAt} and {@code expiresAt} are stamped by
 * its trigger.
 *
 * @param closureReason why the platform abandoned it - an {@code ABANDONED} request's alone
 * @param pinned the policy, model and engine versions, once collection began
 */
public record DecisionRequest(
        DecisionRequestId id,
        UUID party,
        CreditProfileId profile,
        Application application,
        DecisionRequestStatus status,
        Optional<ClosureReason> closureReason,
        Optional<PinnedVersions> pinned,
        Instant submittedAt,
        Instant expiresAt,
        String correlation) {

    public DecisionRequest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(closureReason, "closureReason");
        Objects.requireNonNull(pinned, "pinned");
        Objects.requireNonNull(submittedAt, "submittedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(correlation, "correlation");
        if ((status == DecisionRequestStatus.ABANDONED) != closureReason.isPresent()) {
            throw new IllegalArgumentException("an abandoned request says why, and no other does");
        }
    }

    /** No amount and no declared figure renders. */
    @Override
    public String toString() {
        return "DecisionRequest[" + id + ", " + application.product() + ", " + status + "]";
    }

    /**
     * The terms applied for: the amount in the product's one currency at its scale, a term exactly when the product is an
     * instalment product, the declared monthly figures - when given - in that currency and not negative. The product's
     * published bounds are judged at submission ({@link #submitted}), not here, so a request recorded under older bounds
     * still reads back as recorded.
     */
    public record Application(
            CreditProduct product,
            Money requested,
            Optional<Integer> termMonths,
            Optional<Money> declaredMonthlyIncome,
            Optional<Money> declaredMonthlyExpenditure) {

        public Application {
            Objects.requireNonNull(product, "product");
            Objects.requireNonNull(requested, "requested");
            Objects.requireNonNull(termMonths, "termMonths");
            Objects.requireNonNull(declaredMonthlyIncome, "declaredMonthlyIncome");
            Objects.requireNonNull(declaredMonthlyExpenditure, "declaredMonthlyExpenditure");
            if (!inProductCurrency(product, requested) || !requested.isPositive()) {
                throw new AmountOutOfRange("the amount is a positive amount in " + product.currency() + ", at its scale");
            }
            if (product.revolving() == termMonths.isPresent()) {
                throw new AmountOutOfRange(product.revolving() ? "a " + product + " is revolving and has no term"
                        : "a " + product + " names its term in months");
            }
            for (Optional<Money> declared : java.util.List.of(declaredMonthlyIncome, declaredMonthlyExpenditure)) {
                if (declared.isPresent() && (!inProductCurrency(product, declared.get()) || declared.get().isNegative())) {
                    throw new AmountOutOfRange("a declared monthly figure is a non-negative amount in " + product.currency());
                }
            }
        }

        /**
         * The terms as a customer submits them, judged against the product's published bounds as well
         * ({@code INV-CRD-12}): the amount within them, and an instalment product's term within its months. Anything
         * else is {@link AmountOutOfRange} - nothing is clamped or defaulted.
         */
        public static Application submitted(
                CreditProduct product,
                Money requested,
                Optional<Integer> termMonths,
                Optional<Money> declaredMonthlyIncome,
                Optional<Money> declaredMonthlyExpenditure) {
            Application application =
                    new Application(product, requested, termMonths, declaredMonthlyIncome, declaredMonthlyExpenditure);
            if (requested.compareTo(product.minimumAmount()) < 0 || requested.compareTo(product.maximumAmount()) > 0) {
                throw new AmountOutOfRange("the amount is " + product.minimumAmount().toBigDecimal().toPlainString()
                        + " to " + product.maximumAmount().toBigDecimal().toPlainString() + " " + product.currency());
            }
            product.termBounds().ifPresent(bounds -> {
                int term = termMonths.orElseThrow();
                if (term < bounds.minimumMonths() || term > bounds.maximumMonths()) {
                    throw new AmountOutOfRange("the term is " + bounds.minimumMonths() + " to " + bounds.maximumMonths()
                            + " months");
                }
            });
            return application;
        }

        private static boolean inProductCurrency(CreditProduct product, Money amount) {
            return amount.currency().equals(product.currency()) && amount.scale() == product.currency().minorUnits();
        }

        @Override
        public String toString() {
            return "Application[" + product + ", redacted]";
        }
    }

    /** The terms are outside the product's bounds ({@code credit.AmountOutOfRange}); the message names the bound. */
    public static final class AmountOutOfRange extends IllegalArgumentException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public AmountOutOfRange(String bound) {
            super(bound);
        }
    }
}
