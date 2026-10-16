package com.finapp.lending;

import com.finapp.creditmath.AllocationLine;
import java.util.Objects;
import java.util.UUID;

/**
 * One {@code repayment_allocation} row (`P11-TSK-018`, {@code lending V014}; ADR-0095 points 1 and 3,
 * {@code INV-LND-04}): its identity, its ordinal within the repayment (1..n, the engine's order) and the engine's line -
 * a billed item's component, principal not yet due (a line's paydown, `P11-TSK-023`) or the credit balance. Born with its
 * repayment; never changed (a reversal negates rows, `P11-TSK-020`).
 */
public record RepaymentAllocation(UUID id, int ordinal, AllocationLine line) {

    public RepaymentAllocation {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(line, "line must not be null");
        if (ordinal < 1) {
            throw new IllegalArgumentException("an allocation's ordinal is numbered from 1");
        }
    }

    /** Never the amount. */
    @Override
    public String toString() {
        return "RepaymentAllocation[" + ordinal + ", " + line.target() + "]";
    }
}
