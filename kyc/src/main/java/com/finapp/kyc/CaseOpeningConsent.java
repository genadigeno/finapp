package com.finapp.kyc;

import java.util.UUID;

/**
 * Answers whether opening this customer's case has a lawful basis (`P2-TSK-019`,
 * {@code INV-CNS-01}).
 *
 * <p>A port, because the answer is the {@code consent} module's — a current, purpose-scoped
 * grant for the party behind the customer — and this module cannot see {@code consent}
 * (isolation both directions, `P2-TSK-003`). The {@code app} composition root implements it
 * over the consent gate, exactly as {@link CaseKindResolver} carries {@code party}'s fact
 * across the same boundary.
 *
 * <p><strong>Asked per decision, on the caller's unit of work</strong> — never cached, because
 * a withdrawal must block the capability on every instance from the transaction that recorded
 * it ({@code INV-CNS-03}). The implementation's read and the case-opening write share one
 * snapshot.
 *
 * @param <T> the transactional unit of work — a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface CaseOpeningConsent<T> {

    /**
     * Whether a current basis exists to open (and thereby process) this customer's
     * verification.
     *
     * <p>{@code false} for absence, withdrawal and a grant the current text has lapsed alike —
     * the causes are indistinguishable here on purpose, and a refused opening is a quiet
     * domain outcome, not an error: the case opens when the person grants and acts.
     */
    boolean permitsOpening(T unitOfWork, UUID customerId);
}
