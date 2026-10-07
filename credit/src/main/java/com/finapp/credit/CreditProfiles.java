package com.finapp.credit;

import java.util.Optional;
import java.util.UUID;

/**
 * The credit profiles (`P10-TSK-004`; PHASE_10_PLAN.md section 7, {@code INV-CRD-09}).
 *
 * <p>Both operations run on the <em>caller's</em> unit of work, so the profile and the act that
 * needs it share one transaction.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface CreditProfiles<T> {

    /**
     * The party's profile, born if absent - every caller, on every instance, gets the same one.
     *
     * <p>One {@code INSERT ... ON CONFLICT (party_id) DO NOTHING}: a racing caller's insert waits
     * for the winner's commit and then reads the winner's row, so ten concurrent ensurers leave
     * one row and return one id. A lost response is harmless - the retry reads the same row.
     * Requires {@code READ COMMITTED}, the platform's isolation.
     */
    CreditProfile ensure(T unitOfWork, UUID partyId);

    /**
     * The party's profile locked {@code FOR UPDATE} - lock-order element (1), taken by deciding
     * transactions only, and then always first ({@code DISTRIBUTED_EXECUTION.md} §3). A second
     * locker waits until the first commits or rolls back. Empty when the party has no profile.
     */
    Optional<CreditProfile> lockForDecision(T unitOfWork, UUID partyId);
}
