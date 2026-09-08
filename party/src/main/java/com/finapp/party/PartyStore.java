package com.finapp.party;

import java.util.Optional;

/**
 * Reads a Party and changes its display name, in the caller's transaction (`P1-TSK-030`).
 *
 * <p>The read side {@code P1-TSK-006} never needed: registration writes a Party and nothing had
 * cause to load one until {@code GET /v1/me}.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface PartyStore<T> {

    /**
     * The Party with this identifier, if there is one.
     *
     * <p><strong>The identifier never comes from a request.</strong> Neither {@code /v1/me} endpoint
     * takes a path variable, a query parameter or a body field naming a party — it is resolved from
     * the proven session's Identity, which holds it by value (ADR-0029). ADR-0031's defect is
     * <em>trusting an identifier out of the request</em>, and here there is none to trust.
     */
    Optional<Party> findById(T unitOfWork, PartyId id);

    /**
     * Changes the display name, and reports what it replaced.
     *
     * <p><strong>One statement, and that is what makes the audit record true.</strong> A read
     * followed by an update would record a "before" that another transaction may already have
     * replaced; here the previous value is captured in the same snapshot as the write.
     *
     * <p>A rename to the value already held changes nothing and returns
     * {@link Optional#empty()} — so the caller writes no audit record for it. An entry reading
     * <em>"changed from Ada to Ada"</em> is noise, and worse, it would let anybody pad the trail at
     * will.
     *
     * @return the name that was replaced, or empty if nothing changed
     */
    Optional<PartyName> rename(T unitOfWork, PartyId id, PartyName newName);
}
