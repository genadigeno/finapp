package com.finapp.identity;

import java.util.Optional;

/**
 * Reads identities and moves their status, in the caller's transaction.
 *
 * <p>The read side {@code P1-TSK-006} never needed: registration writes an identity and nothing has
 * had cause to load one until now. It is separate from {@code IdentityRegistration} because that
 * class is a use case and this is a lookup, and folding a query into a command is how a command
 * acquires a second reason to change.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface IdentityStore<T> {

    /**
     * The identity that logs in with this identifier, if there is one.
     *
     * <p>At most one, ever: {@code identity_login_identifier_is_unique} is a <strong>total</strong>
     * index, so a retired login never frees its name and this lookup can never be ambiguous about
     * which person an identifier meant.
     *
     * <p><strong>Returns identities in every status</strong>, not only those that can authenticate.
     * Filtering here would be convenient and wrong: the caller must do the same amount of work for
     * a suspended identity as for an active one, and a query that returned nothing for a suspended
     * account would let the caller skip that work without noticing ({@code INV-IDN-07}).
     */
    Optional<Identity> findByLoginIdentifier(T unitOfWork, LoginIdentifier loginIdentifier);

    /**
     * The identity with this identifier, if there is one.
     *
     * <p>Added by {@code P1-TSK-026}'s successor, {@code P1-TSK-028}: an administrator names the
     * subject by identifier, so this is the first lookup on this table whose argument comes
     * <strong>from a request</strong> rather than from a login attempt. Nothing about the query
     * makes that safe, and nothing here pretends to - what makes it safe is a permission at the
     * boundary and a not-self rule in the domain, which is why {@code OwnershipIsScopedTest}
     * classifies it {@code ADMINISTERED} rather than squeezing it into a class that would read as
     * a proof.
     *
     * <p>Returns identities in <strong>every</strong> status, for {@link #findByLoginIdentifier}'s
     * reason and one more: an administrator must be able to see that an identity is already
     * suspended, and a query that hid it would report "no such identity" for somebody who plainly
     * exists.
     */
    Optional<Identity> findById(T unitOfWork, IdentityId id);

    /**
     * Moves an identity to a new status, only if it is still in the one it was read at.
     *
     * <p><strong>Conditional on the previous status, and the row count is the outcome.</strong> The
     * aggregate has already refused an illegal transition ({@code INV-LIFE-02}); what this adds is
     * the arbitration between two administrators acting at once, which no aggregate can perform
     * because each sees only its own copy (ADR-0014). Ten instances suspending the same identity
     * produce one transition and one audit record; nine are told they lost.
     *
     * @return whether this call performed the transition
     */
    boolean moveStatus(T unitOfWork, IdentityId id, IdentityStatus from, Identity to);
}
