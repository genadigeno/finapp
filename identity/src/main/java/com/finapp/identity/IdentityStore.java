package com.finapp.identity;

import java.util.Optional;

/**
 * Reads identities, in the caller's transaction.
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
}
