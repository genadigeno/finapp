package com.finapp.accounting;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the prior-period adjustments (`P14-TSK-013`, {@code accounting V009}): one row per adjustment, frozen but for its one decision, each edge a
 * conditional {@code UPDATE ... WHERE status = 'PROPOSED'}.
 *
 * <p>The lock order (the plan's section 8.2, A4, after the period rows): an approval takes every live adjustment of the corrected month {@link #lockCorrectionsOf
 * in one statement, in id order}, so two approvals of one month serialise and neither can post unaware of the other; a rejection takes {@link #lock its own row}. No
 * transaction takes either after namespace {@code 14}.
 */
public interface PriorPeriodAdjustmentStore {

    /** Births the row {@code PROPOSED}; the database stamps the time. */
    void insert(Connection unitOfWork, NewAdjustment adjustment);

    /** The row, unlocked. */
    Optional<Row> find(Connection unitOfWork, PriorPeriodAdjustmentId id);

    /** The row {@code FOR UPDATE}. */
    Optional<Row> lock(Connection unitOfWork, PriorPeriodAdjustmentId id);

    /** Every adjustment of {@code corrected} not {@code REJECTED}, {@code FOR UPDATE}, in id order - one statement. */
    List<Row> lockCorrectionsOf(Connection unitOfWork, AccountingPeriodId corrected);

    /** Every adjustment of {@code corrected}, whatever its status and revision, oldest first. Unlocked. */
    List<Row> ofCorrected(Connection unitOfWork, AccountingPeriodId corrected);

    /** The oldest {@code limit} still {@code PROPOSED}. Unlocked. */
    List<Row> pending(Connection unitOfWork, int limit);

    /** {@code PROPOSED -> POSTED}, naming the entry; {@code false} when it was no longer {@code PROPOSED}. */
    boolean markPosted(Connection unitOfWork, PriorPeriodAdjustmentId id, UUID entry, Actor actor, String reason);

    /** {@code PROPOSED -> REJECTED}; {@code false} when it was no longer {@code PROPOSED}. */
    boolean markRejected(Connection unitOfWork, PriorPeriodAdjustmentId id, Actor actor, String reason);

    // ------------------------------------------------------------------ rows

    /** A proposal to birth. */
    record NewAdjustment(
            PriorPeriodAdjustmentId id,
            AccountingPeriodId corrected,
            int correctedRevision,
            AccountingPeriodId target,
            LocalDate postingDate,
            UUID proposal,
            Actor proposedBy,
            String reason,
            CorrelationId correlation) {

        public NewAdjustment {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(corrected, "corrected");
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(postingDate, "postingDate");
            Objects.requireNonNull(proposal, "proposal");
            Objects.requireNonNull(proposedBy, "proposedBy");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(correlation, "correlation");
        }

        /** The reason stays out of a log line: a record's generated {@code toString} would carry it. */
        @Override
        public String toString() {
            return "NewAdjustment[" + id + "]";
        }
    }

    /** One adjustment, its two periods' months joined. */
    record Row(
            PriorPeriodAdjustmentId id,
            AccountingPeriodId correctedPeriod,
            YearMonth correctedMonth,
            int correctedRevision,
            AccountingPeriodId targetPeriod,
            YearMonth targetMonth,
            LocalDate postingDate,
            UUID proposal,
            Optional<UUID> entry,
            PriorPeriodAdjustmentStatus status,
            String proposedBy,
            Instant proposedAt,
            String proposalReason,
            Optional<String> decidedBy,
            Optional<Instant> decidedAt,
            CorrelationId correlation) {

        /** The reason stays out of a log line: a record's generated {@code toString} would carry it. */
        @Override
        public String toString() {
            return "Row[" + id + ", " + correctedMonth + "#" + correctedRevision + " -> " + postingDate + ", " + status + "]";
        }
    }
}
