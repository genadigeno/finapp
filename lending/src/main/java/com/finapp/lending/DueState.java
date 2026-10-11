package com.finapp.lending;

import com.finapp.lending.AllocationEngineV1.AllocationRefused;
import com.finapp.lending.AllocationEngineV1.AllocationRefused.Reason;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What one loan account owes at the instant a payment is allocated - the allocation engine's input (`P11-TSK-008`;
 * ADR-0095 point 1, {@code PHASE_11_PLAN.md} section 12.5, {@code INV-LND-04}).
 *
 * <p><strong>Derived by the caller, under the account's lock.</strong> The figures are what each billed item still has
 * due - billed less allocated, per component - and the principal not yet due, read from postings and rows while the
 * {@code loan} row is held {@code FOR UPDATE} (L3, {@code P11-TSK-017}/{@code -018}), never from a projection
 * (ADR-0041). This type only holds them and refuses a shape no derivation can produce: a negative figure (a due balance
 * below zero is {@code INV-LND-04} already broken), two items with one identity or one due date (oldest-first must be
 * a total order, never an input order), or two currencies or scales.
 *
 * <p>The items are kept in the order given; the engine orders them itself, so the allocation is a function of the set.
 *
 * @param items the billed items - instalments (loan) or statements (line) - with what each still has due
 * @param notDuePrincipal the principal outstanding and not yet billed ({@code LOAN_PRINCIPAL}): drawn principal on a
 *     line, the unbilled schedule on a loan - zero or positive, in the currency and scale of every item
 */
public record DueState(List<DueItem> items, Money notDuePrincipal) {

    public DueState {
        if (items == null || notDuePrincipal == null) {
            throw new AllocationRefused(Reason.MALFORMED_DUE_STATE, "a due state names its items and its not-due principal");
        }
        if (notDuePrincipal.isNegative()) {
            throw new AllocationRefused(Reason.MALFORMED_DUE_STATE, "the principal not yet due is zero or positive");
        }
        Set<DueItemId> ids = new HashSet<>();
        Set<LocalDate> dates = new HashSet<>();
        for (DueItem item : items) {
            if (item == null) {
                throw new AllocationRefused(Reason.MALFORMED_DUE_STATE, "a due state's items have no gap");
            }
            if (!item.fees().currency().equals(notDuePrincipal.currency()) || item.fees().scale() != notDuePrincipal.scale()) {
                throw new AllocationRefused(Reason.CURRENCY_MISMATCH, "every figure of a due state is in one currency and scale");
            }
            if (!ids.add(item.id())) {
                throw new AllocationRefused(Reason.MALFORMED_DUE_STATE, "a billed item appears once");
            }
            if (!dates.add(item.dueDate())) {
                throw new AllocationRefused(Reason.MALFORMED_DUE_STATE, "one billed item falls due on a date");
            }
        }
        items = List.copyOf(items);
    }

    /** Nothing billed: only principal not yet due (a loan before its first billing, a line between statements). */
    public static DueState nothingBilled(Money notDuePrincipal) {
        return new DueState(List.of(), notDuePrincipal);
    }

    /** The currency every figure is in. */
    public CurrencyCode currency() {
        return notDuePrincipal.currency();
    }

    /** Whether any billed item still has something due - arrears, for a prepayment. */
    public boolean anythingBilledDue() {
        for (DueItem item : items) {
            if (!item.total().isZero()) {
                return true;
            }
        }
        return false;
    }

    /**
     * One billed item and what it still has due, per component (billed less allocated; a late fee assessed on the item
     * counts among its fees).
     *
     * @param id the billing or statement
     * @param dueDate the date it fell due - the order of the outer half of the rule
     * @param fees fees still due, zero or positive
     * @param interest interest still due, zero or positive
     * @param principal principal still due, zero or positive
     */
    public record DueItem(DueItemId id, LocalDate dueDate, Money fees, Money interest, Money principal) {

        public DueItem {
            if (id == null || dueDate == null || fees == null || interest == null || principal == null) {
                throw new AllocationRefused(Reason.MALFORMED_DUE_STATE, "a billed item names its identity, due date and figures");
            }
            if (!fees.currency().equals(interest.currency()) || !fees.currency().equals(principal.currency())
                    || fees.scale() != interest.scale() || fees.scale() != principal.scale()) {
                throw new AllocationRefused(Reason.CURRENCY_MISMATCH, "a billed item's figures are in one currency and scale");
            }
            if (fees.isNegative() || interest.isNegative() || principal.isNegative()) {
                throw new AllocationRefused(Reason.MALFORMED_DUE_STATE, "what a billed item has due is zero or positive");
            }
        }

        /** What the item has due of {@code component}. */
        public Money due(AllocationComponent component) {
            return switch (component) {
                case FEES -> fees;
                case INTEREST -> interest;
                case PRINCIPAL -> principal;
            };
        }

        /** Everything the item still has due. */
        public Money total() {
            return fees.plus(interest).plus(principal);
        }
    }
}
