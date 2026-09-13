package com.finapp.consent;

import java.util.Objects;
import java.util.UUID;

/**
 * The enforcement gate (`P2-TSK-019`, ADR-0037): a consent-gated capability proceeds only when a
 * current, purpose-scoped grant exists.
 *
 * <h2>One authoritative read per decision, and nothing else — that IS the design</h2>
 *
 * <p>Every question goes to {@link ConsentStore#hasCurrentBasis} on the <strong>caller's</strong>
 * unit of work, so the decision and the act it authorises share one snapshot. The gate holds no
 * state of its own — no field, no memo, no cache — because {@code INV-CNS-03} says withdrawal is
 * immediate on every instance, and a process-local copy of a consent answer turns that into
 * <em>"immediate once every instance's copy has expired"</em>, which is not immediacy
 * ({@code INV-IDN-03}'s reasoning, applied to lawful basis). {@code NoProcessLocalConsentStateTest}
 * fails the build on the shape somebody would actually write.
 *
 * <h2>Absence is refusal, and the gate cannot tell the causes apart on purpose</h2>
 *
 * <p>No history, a latest withdrawal, and a grant superseded by a text version demanding
 * re-consent are one {@code false} ({@code INV-CNS-01}, {@code INV-CNS-04}): the derivation
 * answers, and the gate adds no vocabulary of its own — a gate that distinguished the causes
 * would hand every consumer the distinction the query surface deliberately withholds.
 *
 * <h2>Neither authentication nor authorization ever substitutes</h2>
 *
 * <p>The gate takes a party and a purpose — never a session, never a role ({@code INV-IDN-04}).
 * A caller that has proven who is asking has proven nothing about whether processing is lawful.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public final class ConsentGate<T> {

    private final ConsentStore<T> consents;

    public ConsentGate(ConsentStore<T> consents) {
        this.consents = Objects.requireNonNull(consents, "consents must not be null");
    }

    /**
     * Whether a current basis exists for this party and purpose — read from authoritative state,
     * now, on this unit of work.
     *
     * <p>For callers whose refusal is a quiet branch rather than an error: the case-opening
     * consumer skips, because a consumer has nobody to tell.
     */
    public boolean permits(T unitOfWork, UUID partyId, ConsentPurpose purpose) {
        Objects.requireNonNull(partyId, "partyId must not be null");
        Objects.requireNonNull(purpose, "purpose must not be null");
        return consents.hasCurrentBasis(unitOfWork, partyId, purpose);
    }

    /**
     * Refuses loudly when no current basis exists — the form an HTTP surface maps to a client
     * refusal (`P2-TSK-006`).
     *
     * <p>The exception names the purpose and nothing more: which of the three causes produced
     * the refusal is exactly what {@code INV-CNS-01} keeps indistinguishable.
     */
    public void require(T unitOfWork, UUID partyId, ConsentPurpose purpose) {
        if (!permits(unitOfWork, partyId, purpose)) {
            throw new ConsentNotGrantedException(purpose);
        }
    }
}
