package com.finapp.lending;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;

/**
 * A credit line's available limit (`P11-TSK-021`; ADR-0100 point 4, {@code PHASE_11_PLAN.md} section 12.6,
 * {@code INV-LND-14}) - <strong>derived, never stored</strong>: the agreement's limit less the drawn principal, the line's
 * {@code LOAN_PRINCIPAL + LOAN_PRINCIPAL_DUE} folded from journal lines. Zero when the drawn principal is at or above the
 * limit - which only a paydown reversed after a re-draw can cause (`-020`; the exposure read then counts the drawn
 * principal, `-013`) - never negative. Interest and fees never consume it (they are not principal, A4); a principal
 * repayment, due or not yet due, restores it at its commit (it credits one of the two accounts).
 *
 * <p>This type only computes; <em>where</em> the drawn principal is read decides whether the answer may be acted on. The
 * draw ({@link CreditLineDraws}) derives it under the line's row lock (L3), which every principal mover of the line takes
 * first; the customer's read ({@link Loans}) derives it in a snapshot and decides nothing.
 *
 * @param limit the governing agreement version's limit
 * @param drawn the drawn principal - not due and due - in the limit's currency and scale
 */
public record AvailableLimit(Money limit, Money drawn) {

    public AvailableLimit {
        Objects.requireNonNull(limit, "limit must not be null");
        Objects.requireNonNull(drawn, "drawn must not be null");
        if (!limit.isPositive()) {
            throw new IllegalArgumentException("a line's limit is positive");
        }
        if (!limit.currency().equals(drawn.currency()) || limit.scale() != drawn.scale()) {
            throw new IllegalArgumentException("the drawn principal is in the limit's currency and scale");
        }
    }

    /** The limit less the drawn principal, floored at zero. */
    public Money available() {
        Money left = limit.minus(drawn);
        return left.isNegative() ? Money.ofPersisted(0, limit.currency(), limit.scale()) : left;
    }

    /** Whether a draw of {@code amount} fits: a positive amount at most {@link #available()}. */
    public boolean admits(Money amount) {
        Objects.requireNonNull(amount, "amount must not be null");
        return amount.isPositive() && amount.compareTo(available()) <= 0;
    }

    /** Never a figure ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "AvailableLimit[redacted]";
    }
}
