package com.finapp.app.credit;

import com.finapp.credit.ReservedExposure;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;

/**
 * The party's reserved exposure before the credit decision exists (`P10-TSK-015`; {@code INV-CRD-09}): no decision has
 * been recorded - the deciding transaction and its table are `P10-TSK-016`'s - so no approved amount is reserved, and
 * every party's reserved exposure is zero, deterministically, recorded in the snapshot with this composition's version.
 * `P10-TSK-016`'s implementation over {@code credit_decision} replaces it with a new version; a snapshot frozen under
 * this one replays identically ({@code INV-CRD-01}).
 */
public final class NothingReservedBeforeDecisions implements ReservedExposure<Connection> {

    /** This composition's version. */
    public static final int VERSION = 1;

    @Override
    public int version() {
        return VERSION;
    }

    @Override
    public Money reservedFor(Connection unitOfWork, UUID partyId, CurrencyCode currency) {
        Objects.requireNonNull(partyId, "partyId");
        return Money.zero(Objects.requireNonNull(currency, "currency"));
    }
}
