package com.finapp.lending;

/**
 * What a repayment can pay (`P11-TSK-002`; owner decision L6, ADR-0095, {@code INV-LND-04}).
 *
 * <p>Declared in the default allocation order - fees, then interest, then principal, within the oldest due item first.
 * The order a loan is allocated by is the one its pinned terms version records, applied by its pinned
 * {@link EngineVersion#ALLOCATION_ENGINE_V1}; declaration order is the default, never an implicit rule.
 */
public enum AllocationComponent {

    /** Late and origination fees due. */
    FEES,

    /** Interest billed and due. */
    INTEREST,

    /** Principal billed and due (or, beyond what is due, drawn principal on a line - A26). */
    PRINCIPAL
}
