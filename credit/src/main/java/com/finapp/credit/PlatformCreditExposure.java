package com.finapp.credit;

import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.util.UUID;

/**
 * The platform's outstanding credit to a party (`P10-TSK-010`; ADR-0088 section 6, ADR-0084): declared in credit,
 * implemented in {@code app}. Phase 10's composition answers zero for every party - there are no loans - and the
 * snapshot records the answer with the port's version as its provenance, so a decision pinned to that version replays
 * identically once Phase 11's loans change the composition and its version ({@code INV-CRD-01}).
 *
 * @param <T> the transactional unit of work - a JDBC {@code Connection}, fixed by ADR-0033
 */
public interface PlatformCreditExposure<T> {

    /** The implementation's version, recorded in the snapshot's provenance. */
    int version();

    /** The party's outstanding platform credit in {@code currency} - zero when there is none. */
    Money outstandingFor(T unitOfWork, UUID partyId, CurrencyCode currency);
}
