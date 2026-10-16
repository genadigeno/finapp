package com.finapp.creditmath;

/**
 * The order in which a repayment reaches a loan's due items (`P11-TSK-005`; owner decision L6, ADR-0095) - the outer
 * half of the allocation rule, the {@link AllocationComponent} order within each item being the inner half. Both are
 * fields of the terms version, pinned on the agreement and applied by its pinned allocation engine.
 *
 * <p><strong>Only implemented members.</strong> {@code OLDEST_DUE_FIRST} alone, what {@link
 * EngineVersion#ALLOCATION_ENGINE_V1} implements.
 */
public enum AllocationSequence {

    /** The oldest due item is paid first; within it, the components in the terms' order. */
    OLDEST_DUE_FIRST
}
