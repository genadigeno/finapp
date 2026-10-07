package com.finapp.credit;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.util.UUID;

/**
 * The party's reserved exposure (`P10-TSK-008` declares it; `P10-TSK-010` pins its meaning in
 * {@code ReservedExposureContract}; `P10-TSK-016` implements it over {@code credit_decision}; PHASE_10_PLAN.md section
 * 12.4, {@code INV-CRD-09}): the approved amount of the party's decisions still valid on the database clock and not
 * consumed - read on the caller's unit of work, under the profile's lock when the caller decides.
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface ReservedExposure<T> {

    /** The implementation's version, recorded in the snapshot's provenance. */
    int version();

    /** The party's reserved exposure in {@code currency} - zero when nothing is reserved. */
    Money reservedFor(T unitOfWork, UUID partyId, CurrencyCode currency);
}
