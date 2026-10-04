package com.finapp.ledger;

import java.util.List;
import java.util.Optional;

/**
 * Reads the ledger's counterparty registry (`P9-TSK-010`, ADR-0078 section 2). Read-only by
 * design: a counterparty is registered by the migration that admits it, with SELECT and INSERT
 * grants only, and nothing at runtime mints one - so this port has no write.
 *
 * @param <T> the unit of work
 */
public interface CounterpartyStore<T> {

    /** The registered counterparty with this code, if any. */
    Optional<Counterparty> findByCode(T unitOfWork, String code);

    /** Every registered counterparty, in code order. */
    List<Counterparty> all(T unitOfWork);
}
