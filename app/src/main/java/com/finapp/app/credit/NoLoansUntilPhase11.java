package com.finapp.app.credit;

import com.finapp.credit.PlatformCreditExposure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 10's platform credit exposure (`P10-TSK-010`; ADR-0088 section 6): the platform has extended no credit - there
 * are no loans until Phase 11 - so every party's outstanding platform credit is zero, deterministically, and the
 * snapshot records it with this composition's version. Phase 11's loans replace it with a new version; a decision frozen
 * under this one replays identically ({@code INV-CRD-01}).
 */
public final class NoLoansUntilPhase11 implements PlatformCreditExposure<Connection> {

    /** This composition's version. */
    public static final int VERSION = 1;

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public Money outstandingFor(Connection unitOfWork, UUID partyId, CurrencyCode currency) {
        Objects.requireNonNull(partyId, "partyId");
        return Money.zero(Objects.requireNonNull(currency, "currency"));
    }
}
