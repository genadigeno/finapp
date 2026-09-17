package com.finapp.accounts;

import java.util.Optional;
import java.util.UUID;

/**
 * May this party hold accounts — and as which customer? (`P3-TSK-012`)
 *
 * <p>The verification gate: opening requires {@code party.customer.status = ACTIVE}, which
 * {@code INV-KYC-05} makes a faithful projection of the KYC decision — <strong>Phase 2's
 * product, meeting its first consumer</strong>. This module cannot see {@code party}, so the
 * question crosses the boundary through this port (the {@code CaseKindResolver} shape) and the
 * composition root answers it over the party store.
 *
 * <p><strong>The answer must be read from authoritative state, per decision, inside the
 * caller's unit of work.</strong> No implementation may cache it (the {@code ConsentGate}
 * rule): a customer closed on one instance must be refused by every other on its very next
 * decision. And the answer is how the opening flow obtains its customer identifier at all —
 * resolved from the party, never taken from a request (ADR-0031's defect has nothing to act
 * on when there is no identifier to trust).
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface AccountHolderVerification<T> {

    /**
     * The identifier of the party's live, <strong>verified</strong> ({@code ACTIVE}) customer —
     * or empty. Empty deliberately conflates its causes: no customer relationship, a
     * {@code PENDING} one still under verification, and a terminal one whose slot was freed are
     * one answer, because the opening refusal must not be an oracle over why
     * ({@code INV-IDN-07}'s reasoning, one register over).
     */
    Optional<UUID> eligibleCustomer(T unitOfWork, UUID partyId);
}
