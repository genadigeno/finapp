package com.finapp.lending;

/**
 * How an origination fee is collected (`P11-TSK-005`; assumption A17, ADR-0092 section 2) - a field of the terms
 * version, pinned on the agreement.
 *
 * <p><strong>Only implemented members.</strong> {@code DEDUCTED} alone: the fee is withheld from the disbursement and
 * recognised upfront in the operational ledger (effective-interest amortisation is Phase 14's). Financing the fee into
 * principal would be a new member together with the schedule engine version that amortises it.
 */
public enum OriginationFeeTreatment {

    /** Withheld from the disbursed principal; the borrower owes the full principal. */
    DEDUCTED
}
