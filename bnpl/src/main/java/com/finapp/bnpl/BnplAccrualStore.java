package com.finapp.bnpl;

import java.sql.Connection;
import java.time.Duration;
import java.util.Optional;

/**
 * Persistence for T-acc (`P12-TSK-017`, {@code bnpl V009}; lending's {@code LoanServicingStore} accrual half): the
 * obligation's latest accrual - the predecessor {@code ACCRUAL_ENGINE_V1} chains a period's next date onto - the row's
 * birth, and the fleet's accrual lag for its gauge. Every read of an obligation is of one the caller holds {@code FOR UPDATE}
 * (B4, {@link BnplServicingStore#lock}) - claimed by the servicing sweep - never a request value.
 */
public interface BnplAccrualStore {

    /** The obligation's latest accrual - empty when nothing has accrued; read under B4. */
    Optional<BnplInterestAccrual> latest(Connection unitOfWork, BnplObligationId obligation);

    /**
     * Births {@code accrual} - {@code bnpl V009}'s trigger re-takes B4 and refuses a date twice, out of order, early, outside
     * an instalment period of the latest schedule version or under another agreement version or engine.
     */
    void insert(Connection unitOfWork, BnplInterestAccrual accrual);

    /**
     * The accrual lag: the time since the end - in its pinned zone, on the database clock - of the oldest elapsed date of an
     * {@code ACTIVE} pay-in-N obligation's accrual span that has not accrued; empty when every such date has.
     */
    Optional<Duration> oldestLag(Connection unitOfWork);
}
