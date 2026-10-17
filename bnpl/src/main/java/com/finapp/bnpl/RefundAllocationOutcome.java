package com.finapp.bnpl;

import java.util.Objects;

/**
 * What the refund allocation engine answers (`P12-TSK-009`; ADR-0105): an {@link Allocated} split, or a {@link Refused}
 * refund with its typed reason - the caller decides what a refusal means at its door ({@code 422
 * bnpl.RefundExceedsPurchase} at the refund doors, `P12-TSK-021`), and nothing in between is an exception. The
 * {@link BnplScheduleOutcome} shape.
 */
public sealed interface RefundAllocationOutcome {

    /** The split, preserving value by construction ({@link RefundAllocation}). */
    record Allocated(RefundAllocation allocation) implements RefundAllocationOutcome {
        public Allocated {
            Objects.requireNonNull(allocation, "allocation");
        }
    }

    /** No split: the reason, and a detail naming the defect - never an amount. */
    record Refused(RefundAllocationRefusal reason, String detail) implements RefundAllocationOutcome {
        public Refused {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(detail, "detail");
        }
    }
}
