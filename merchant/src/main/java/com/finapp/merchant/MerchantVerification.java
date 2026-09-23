package com.finapp.merchant;

import java.util.Optional;
import java.util.UUID;

/**
 * May this party be onboarded as a merchant? (`P6-TSK-003`)
 *
 * <p>The KYB gate: onboarding requires an <strong>organisation</strong> party whose live
 * customer relationship is {@code ACTIVE} — which {@code INV-KYC-05} makes a faithful
 * projection of the KYB decision, exactly as it makes a person's of the KYC decision
 * (Phase 2's {@code CustomerOpenedOpensCase} opens the case whose kind the party dictates,
 * and the decision is what flips the status). This module cannot see {@code party} or
 * {@code kyc}, so the question crosses the boundary through this port (the
 * {@code AccountHolderVerification} shape, verbatim) and the composition root answers it
 * over the party store. Consumed, never recomputed: no read of {@code kyc} anywhere.
 *
 * <p><strong>The answer must be read from authoritative state, per decision, inside the
 * caller's unit of work.</strong> No implementation may cache it (the {@code ConsentGate}
 * rule). And empty deliberately conflates its causes: no such party, a person party, no
 * customer relationship, one still under verification, a terminal one — one answer, because
 * the onboarding refusal must not be an oracle over why ({@code INV-IDN-07}'s reasoning).
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface MerchantVerification<T> {

    /**
     * The identifier of the organisation party's live, <strong>KYB-verified</strong>
     * ({@code ACTIVE}) customer — or empty, causes conflated.
     */
    Optional<UUID> eligibleOrganisation(T unitOfWork, UUID partyId);
}
