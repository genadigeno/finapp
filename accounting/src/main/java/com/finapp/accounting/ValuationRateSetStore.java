package com.finapp.accounting;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for the valuation rate sets (`P14-TSK-014`, {@code accounting V005}): the set row
 * ({@code valuation_rate_set} - born {@code PROPOSED}, its decision columns the only ones that ever move, by one
 * conditional {@code UPDATE ... WHERE status = 'PROPOSED'}) and its rates ({@code valuation_rate}, born with the set in
 * its capturing transaction, INSERT-only, the set committing only whole).
 *
 * <p>The lock order (the plan's section 8.2): the period row (A1, {@link AccountingPeriodStore}) before the set row
 * {@link #lock FOR UPDATE} (A4).
 */
public interface ValuationRateSetStore {

    /** Births the set {@code PROPOSED} with every one of its rates. */
    void insert(Connection unitOfWork, NewSet set);

    /** The set, unlocked. */
    Optional<SetRow> find(Connection unitOfWork, ValuationRateSetId id);

    /** The set {@code FOR UPDATE}. */
    Optional<SetRow> lock(Connection unitOfWork, ValuationRateSetId id);

    /** The period's {@code APPROVED} set, unlocked - at most one exists. */
    Optional<SetRow> approvedFor(Connection unitOfWork, AccountingPeriodId period);

    /** The set's rates, in their capture order. */
    List<ValuationRate> rates(Connection unitOfWork, ValuationRateSetId id);

    /** {@code PROPOSED -> to}; {@code false} when the set was not {@code PROPOSED}. */
    boolean decide(
            Connection unitOfWork, ValuationRateSetId id, ValuationRateSetStatus to, Actor actor, String reason);

    // ------------------------------------------------------------------ rows

    /** A set to birth: its judged content, its capturer, an optional reason and the correlation. */
    record NewSet(
            ValuationRateSetId id,
            ValuationRateSet content,
            Actor capturedBy,
            Optional<String> reason,
            CorrelationId correlation) {}

    /** A set's row - no reason; the capturer and the decider only for the four-eyes judgement. */
    record SetRow(
            ValuationRateSetId id,
            AccountingPeriodId period,
            YearMonth month,
            Instant asOf,
            int maxAgeHours,
            int rateCount,
            ValuationRateSetStatus status,
            String capturedBy,
            Instant capturedAt,
            Optional<String> decidedBy,
            Optional<Instant> decidedAt) {}
}
