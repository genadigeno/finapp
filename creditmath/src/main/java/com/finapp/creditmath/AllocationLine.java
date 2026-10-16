package com.finapp.creditmath;

import com.finapp.sharedkernel.money.Money;
import java.util.Objects;
import java.util.Optional;

/**
 * One part of an allocated payment - what it paid, of what (`P11-TSK-008`; ADR-0095 point 1, {@code INV-LND-04}).
 *
 * <p>Every line names a billed item and one of its components, or one of the two overflow targets; its amount is
 * positive (a component the payment does not reach has no line). The lines of one allocation sum to the payment
 * exactly. They are what {@code repayment_allocation} records per item and component, and the journal entry's credit
 * lines aggregate them per account ({@code P11-TSK-018}): a {@link Target#DUE_ITEM} line credits the due account of its
 * component ({@code LOAN_FEES_DUE}, {@code LOAN_INTEREST_DUE}, {@code LOAN_PRINCIPAL_DUE}), a
 * {@link Target#NOT_DUE_PRINCIPAL} line {@code LOAN_PRINCIPAL}, a {@link Target#CREDIT_BALANCE} line
 * {@code LOAN_CREDIT_BALANCE}.
 *
 * @param target what the line pays
 * @param item the billed item - present exactly for {@link Target#DUE_ITEM}
 * @param component the item's component - present exactly for {@link Target#DUE_ITEM}
 * @param amount positive
 */
public record AllocationLine(Target target, Optional<DueItemId> item, Optional<AllocationComponent> component, Money amount) {

    /** What a line pays. */
    public enum Target {

        /** A component of a billed item - an instalment's or a statement's fees, interest or principal still due. */
        DUE_ITEM,

        /** Principal not yet due: a line's drawn principal paid down (A26), or a loan's prepayment ({@code P11-TSK-028}). */
        NOT_DUE_PRINCIPAL,

        /** Beyond every receivable the treatment reaches: held as the loan's credit balance (L6, A26). */
        CREDIT_BALANCE
    }

    public AllocationLine {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(component, "component");
        Objects.requireNonNull(amount, "amount");
        if ((target == Target.DUE_ITEM) != item.isPresent() || item.isPresent() != component.isPresent()) {
            throw new IllegalArgumentException("a line names a billed item and its component exactly when it pays one");
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("a line's amount is positive");
        }
    }

    /** A line paying {@code amount} of {@code item}'s {@code component}. */
    public static AllocationLine due(DueItemId item, AllocationComponent component, Money amount) {
        return new AllocationLine(Target.DUE_ITEM, Optional.of(item), Optional.of(component), amount);
    }

    /** A line paying {@code amount} of principal not yet due. */
    public static AllocationLine notDuePrincipal(Money amount) {
        return new AllocationLine(Target.NOT_DUE_PRINCIPAL, Optional.empty(), Optional.empty(), amount);
    }

    /** A line holding {@code amount} as credit. */
    public static AllocationLine creditBalance(Money amount) {
        return new AllocationLine(Target.CREDIT_BALANCE, Optional.empty(), Optional.empty(), amount);
    }
}
