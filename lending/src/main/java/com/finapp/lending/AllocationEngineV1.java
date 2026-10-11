package com.finapp.lending;

import com.finapp.lending.DueState.DueItem;
import com.finapp.sharedkernel.money.Money;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@code ALLOCATION_ENGINE_V1}: the split of money received over what a loan account owes (`P11-TSK-008`; L6, A26,
 * ADR-0095, {@code PHASE_11_PLAN.md} section 12.5; {@code INV-LND-04}, {@code INV-MON-01}).
 *
 * <p><strong>The rule.</strong> (1) Billed items - instalments or statements - oldest due date first; within each, the
 * components in the agreement's pinned order (the default {@code FEES -> INTEREST -> PRINCIPAL}), each paid up to what
 * it still has due. (2) The remainder by the pinned overpayment treatment: {@code HOLD_AS_CREDIT} (the instalment loan)
 * holds it all as credit - an overpayment never reduces a loan's principal, prepayment being its own command;
 * {@code PAY_DOWN_PRINCIPAL} (the line) pays down principal not yet due, restoring the limit, and holds as credit only
 * what exceeds every receivable it reaches (A26).
 *
 * <p><strong>The three uses.</strong> A repayment - the customer's, auto-collection's, a payoff's - is
 * {@link #allocate}. The credit-balance application at billing (rule 6, {@code P11-TSK-017}) is {@link #allocate} too,
 * "through the same engine", with the credit balance as the amount: the lines on billed items and on principal are
 * what it applies, and a {@link AllocationLine.Target#CREDIT_BALANCE} line is what stays held. A partial prepayment
 * ({@code P11-TSK-028}, if built) is {@link #allocatePrepayment}: one line to principal not yet due, refused while any
 * billed item has something due.
 *
 * <p><strong>Pure, so correct on any number of instances.</strong> No state, no clock, no I/O: the lines are a function
 * of the amount, the due state and the rules alone - the same on every JVM, whatever its charset, locale, zone or hash
 * codes ({@code AllocationEngineV1PropertiesTest}). Correctness under concurrency is the caller's: the due state is
 * derived and the allocation posted under the account's lock (L3), so two racing payments each allocate against what
 * the other left ({@code P11-TSK-018}, scenario 3).
 *
 * <p><strong>Asserted before it returns</strong> (ADR-0095 point 1): the lines sum to the amount exactly, and no line
 * exceeds what its component - or principal not yet due - had due. A breach is a defect of this engine, never a
 * refusal of its input; it throws {@link IllegalStateException} and nothing is posted.
 *
 * <p><strong>Exact.</strong> Money only - integer minor units, compared and subtracted, never divided or rounded:
 * nothing here can create or lose a minor unit ({@code INV-MON-01}).
 *
 * <p><strong>A version is never edited</strong> ({@code INV-LND-09}): a different rule is a new terms version naming an
 * order this engine implements, or a new engine version beside this one.
 */
public final class AllocationEngineV1 {

    /** The version this class implements - the one an agreement's rules must pin. */
    public static final EngineVersion VERSION = EngineVersion.ALLOCATION_ENGINE_V1;

    /** Oldest due date first: a total order, since a due state holds one item per due date. */
    private static final Comparator<DueItem> OLDEST_DUE_FIRST = Comparator.comparing(DueItem::dueDate);

    private AllocationEngineV1() {}

    /**
     * Allocates {@code amount} received over {@code dueState} by {@code rules}.
     *
     * @return the lines, billed items oldest first and each item's components in the pinned order, then the
     *     remainder's lines; empty for a zero amount
     * @throws AllocationRefused a negative amount, another currency or scale, or rules this engine does not implement
     */
    public static List<AllocationLine> allocate(Money amount, DueState dueState, AllocationRules rules) {
        judge(amount, dueState, rules);
        List<AllocationLine> lines = new ArrayList<>();
        Money remaining = amount;
        for (DueItem item : dueState.items().stream().sorted(OLDEST_DUE_FIRST).toList()) {
            for (AllocationComponent component : rules.order()) {
                Money paid = least(remaining, item.due(component));
                if (paid.isPositive()) {
                    lines.add(AllocationLine.due(item.id(), component, paid));
                    remaining = remaining.minus(paid);
                }
            }
        }
        if (rules.overpaymentTreatment() == OverpaymentTreatment.PAY_DOWN_PRINCIPAL) {
            Money paidDown = least(remaining, dueState.notDuePrincipal());
            if (paidDown.isPositive()) {
                lines.add(AllocationLine.notDuePrincipal(paidDown));
                remaining = remaining.minus(paidDown);
            }
        }
        if (remaining.isPositive()) {
            lines.add(AllocationLine.creditBalance(remaining));
        }
        return verified(amount, dueState, lines);
    }

    /**
     * Allocates a partial prepayment of {@code amount} to principal not yet due - an instalment loan's own command
     * ({@code P11-TSK-028}), arrears first.
     *
     * @return one {@link AllocationLine.Target#NOT_DUE_PRINCIPAL} line; empty for a zero amount
     * @throws AllocationRefused as {@link #allocate}; and while any billed item has something due
     *     ({@link AllocationRefused.Reason#ARREARS_OUTSTANDING}), for amounts beyond the principal not yet due
     *     ({@link AllocationRefused.Reason#PREPAYMENT_EXCEEDS_PRINCIPAL}), or under a line's treatment
     *     ({@link AllocationRefused.Reason#PREPAYMENT_NOT_APPLICABLE} - a line's payment already pays principal down)
     */
    public static List<AllocationLine> allocatePrepayment(Money amount, DueState dueState, AllocationRules rules) {
        judge(amount, dueState, rules);
        if (rules.overpaymentTreatment() != OverpaymentTreatment.HOLD_AS_CREDIT) {
            throw new AllocationRefused(AllocationRefused.Reason.PREPAYMENT_NOT_APPLICABLE,
                    "a prepayment is an instalment loan's; a line's payment pays principal down by its treatment");
        }
        if (dueState.anythingBilledDue()) {
            throw new AllocationRefused(AllocationRefused.Reason.ARREARS_OUTSTANDING,
                    "a prepayment waits until nothing billed is due");
        }
        if (amount.compareTo(dueState.notDuePrincipal()) > 0) {
            throw new AllocationRefused(AllocationRefused.Reason.PREPAYMENT_EXCEEDS_PRINCIPAL,
                    "a prepayment pays at most the principal not yet due");
        }
        List<AllocationLine> lines = amount.isPositive() ? List.of(AllocationLine.notDuePrincipal(amount)) : List.of();
        return verified(amount, dueState, lines);
    }

    // ------------------------------------------------------------------ the input judged

    private static void judge(Money amount, DueState dueState, AllocationRules rules) {
        // A missing argument is the caller's defect, not a refusal of a value: no reason fits it.
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(dueState, "dueState");
        Objects.requireNonNull(rules, "rules");
        if (rules.engine() != VERSION) {
            throw new AllocationRefused(AllocationRefused.Reason.UNSUPPORTED_ENGINE,
                    "the rules pin an allocation engine version this class does not implement");
        }
        if (rules.sequence() != AllocationSequence.OLDEST_DUE_FIRST) {
            throw new AllocationRefused(AllocationRefused.Reason.MALFORMED_RULES,
                    "the rules name a sequence this engine version does not implement");
        }
        if (!amount.currency().equals(dueState.currency()) || amount.scale() != dueState.notDuePrincipal().scale()) {
            throw new AllocationRefused(AllocationRefused.Reason.CURRENCY_MISMATCH,
                    "the amount is in the due state's currency and scale");
        }
        if (amount.isNegative()) {
            throw new AllocationRefused(AllocationRefused.Reason.NEGATIVE_AMOUNT, "an allocated amount is zero or positive");
        }
    }

    // ------------------------------------------------------------------ the assertion

    /**
     * The lines, once they conserve and none exceeds its due ({@code INV-LND-04}); otherwise this engine is wrong and
     * nothing may be posted from it.
     */
    private static List<AllocationLine> verified(Money amount, DueState dueState, List<AllocationLine> lines) {
        Money total = Money.ofPersisted(0, amount.currency(), amount.scale());
        Set<String> paid = new HashSet<>();
        for (AllocationLine line : lines) {
            total = total.plus(line.amount());
            if (!paid.add(line.target() + "/" + line.item().map(DueItemId::value) + "/" + line.component())) {
                throw new IllegalStateException("ALLOCATION_ENGINE_V1 defect: two lines pay one component");
            }
            Money due = switch (line.target()) {
                case DUE_ITEM -> dueOf(dueState, line);
                case NOT_DUE_PRINCIPAL -> dueState.notDuePrincipal();
                case CREDIT_BALANCE -> line.amount();
            };
            if (line.amount().compareTo(due) > 0) {
                throw new IllegalStateException("ALLOCATION_ENGINE_V1 defect: a line exceeds what its component had due");
            }
        }
        if (!total.equals(amount)) {
            throw new IllegalStateException("ALLOCATION_ENGINE_V1 defect: the lines do not sum to the amount");
        }
        return List.copyOf(lines);
    }

    /** What the line's item had due of its component. */
    private static Money dueOf(DueState dueState, AllocationLine line) {
        DueItemId id = line.item().orElseThrow();
        AllocationComponent component = line.component().orElseThrow();
        for (DueItem item : dueState.items()) {
            if (item.id().equals(id)) {
                return item.due(component);
            }
        }
        throw new IllegalStateException("ALLOCATION_ENGINE_V1 defect: a line names an item the due state does not hold");
    }

    private static Money least(Money a, Money b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    // ------------------------------------------------------------------ the refusal

    /**
     * An allocation refused whole: its input is not one this engine version allocates. Typed by {@link Reason}; the
     * detail names the defect, never an amount.
     */
    public static final class AllocationRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        /** Why. */
        public enum Reason {
            /** The amount is below zero. */
            NEGATIVE_AMOUNT,
            /** The amount or a due figure is in another currency or scale. */
            CURRENCY_MISMATCH,
            /** The rules are incomplete, name an order omitting or repeating a component, or a sequence not implemented. */
            MALFORMED_RULES,
            /** The rules pin an allocation engine version other than this class's. */
            UNSUPPORTED_ENGINE,
            /** The due state holds a negative figure, a gap, or two items with one identity or one due date. */
            MALFORMED_DUE_STATE,
            /** A prepayment while a billed item still has something due (arrears first). */
            ARREARS_OUTSTANDING,
            /** A prepayment beyond the principal not yet due. */
            PREPAYMENT_EXCEEDS_PRINCIPAL,
            /** A prepayment under the line's treatment. */
            PREPAYMENT_NOT_APPLICABLE
        }

        private final Reason reason;

        public AllocationRefused(Reason reason, String detail) {
            super(Objects.requireNonNull(reason, "reason") + ": " + detail);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }
}
