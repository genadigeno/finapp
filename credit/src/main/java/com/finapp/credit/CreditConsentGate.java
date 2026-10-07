package com.finapp.credit;

import java.util.UUID;

/**
 * Whether a current lawful basis exists to collect a source kind's credit data for a party
 * ({@code P10-TSK-002}; ADR-0084 section 3, ADR-0085 section 4, {@code INV-CRD-03}).
 *
 * <p><strong>A port, because consent is not credit's.</strong> The lawful basis is the
 * {@code consent} module's recorded fact, and credit has no build edge to it (ADR-0084): credit
 * asks this question by {@link CreditSourceKind} and never learns which consent purpose answers
 * it. {@code app} implements it over the consent gate and owns the kind-to-purpose mapping -
 * one purpose per kind, so a basis for one kind never admits the other.
 *
 * <p><strong>Authoritative, every time.</strong> An implementation reads the basis on the
 * <em>caller's</em> unit of work, so the answer and the act it permits share one snapshot, and
 * holds no state of its own: a withdrawal committed on any instance refuses the very next acting
 * transaction on every instance ({@code INV-CNS-03}). A caller asks when it opens a data request,
 * at every retry, when it records the answer, at the freeze and in the deciding transaction -
 * never once and remembered.
 *
 * <p>Absence, withdrawal and a grant lapsed by a re-consent-demanding text are one {@code false}
 * ({@code INV-CNS-01}): credit cannot tell the causes apart, by design.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface CreditConsentGate<T> {

    /**
     * Whether a current basis exists for this party to collect this kind's data - read now, on
     * this unit of work.
     */
    boolean permits(T unitOfWork, UUID partyId, CreditSourceKind kind);
}
