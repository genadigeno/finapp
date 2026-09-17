package com.finapp.transfers;

import java.io.Serial;

/**
 * The destination named at beneficiary creation resolves to no customer wallet — a boundary
 * mistake, refused with nothing written ({@code P4-TSK-006}). A saved destination must point at
 * a product that exists; whether it can <em>post</em> on any later day is deliberately not this
 * refusal's question — postability goes stale by design, and the transfer's own judgement owns
 * it (the accepted race, {@code PHASE_4_PLAN.md} §7).
 */
public final class UnknownBeneficiaryDestinationException extends RuntimeException {

    @Serial private static final long serialVersionUID = 1L;

    public UnknownBeneficiaryDestinationException() {
        super("no such destination product holds a wallet to save as a beneficiary");
    }
}
