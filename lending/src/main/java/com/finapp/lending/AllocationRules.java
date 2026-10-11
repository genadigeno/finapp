package com.finapp.lending;

import com.finapp.lending.AllocationEngineV1.AllocationRefused;
import com.finapp.lending.AllocationEngineV1.AllocationRefused.Reason;
import java.util.EnumSet;
import java.util.List;

/**
 * The allocation rule an agreement pins - which engine version, the outer sequence, the component order within an item
 * and what happens to the remainder (`P11-TSK-008`; L6, A26, ADR-0095 point 2, ADR-0092).
 *
 * <p><strong>Data, never code.</strong> The rule is a field set of the terms version, copied onto the agreement and read
 * back from it: {@link #of(LoanTerms)} takes it from terms, and the agreement's canonical terms ({@code P11-TSK-011})
 * will rebuild it through this constructor. So the constructor judges it again - an order naming every component
 * exactly once, an allocation engine's version - and refuses a malformed one ({@link Reason#MALFORMED_RULES}) rather
 * than letting the engine guess.
 *
 * @param engine the allocation engine version the agreement pins
 * @param sequence the order over billed items - {@code OLDEST_DUE_FIRST}
 * @param order every {@link AllocationComponent} exactly once, in the order an item's components are paid
 * @param overpaymentTreatment what the remainder beyond every billed item does
 */
public record AllocationRules(
        EngineVersion engine,
        AllocationSequence sequence,
        List<AllocationComponent> order,
        OverpaymentTreatment overpaymentTreatment) {

    public AllocationRules {
        if (engine == null || sequence == null || order == null || overpaymentTreatment == null) {
            throw new AllocationRefused(Reason.MALFORMED_RULES, "the rules name an engine, a sequence, an order and a treatment");
        }
        if (engine.engine() != LendingEngine.ALLOCATION) {
            throw new AllocationRefused(Reason.MALFORMED_RULES, "the rules name a version of the allocation engine");
        }
        EnumSet<AllocationComponent> named = EnumSet.noneOf(AllocationComponent.class);
        for (AllocationComponent component : order) {
            // An immutable list's contains(null) throws, so the gap is looked for element by element.
            if (component == null || !named.add(component)) {
                throw new AllocationRefused(Reason.MALFORMED_RULES, "the order names every component exactly once");
            }
        }
        if (!named.equals(EnumSet.allOf(AllocationComponent.class))) {
            throw new AllocationRefused(Reason.MALFORMED_RULES, "the order names every component exactly once");
        }
        order = List.copyOf(order);
    }

    /** The rule the terms carry: their allocation engine, sequence, order and their kind's overpayment treatment. */
    public static AllocationRules of(LoanTerms terms) {
        return new AllocationRules(terms.allocationEngine(), terms.allocationSequence(), terms.allocationOrder(),
                terms.overpaymentTreatment());
    }
}
