package com.finapp.accounting;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The close and reopen proposal rows (`P14-TSK-012`; {@code accounting V008}): {@code period_close} and its nine {@code period_close_check} rows, {@code period_reopen}. Every move a
 * conditional {@code UPDATE} under {@code V008}'s edge triggers, which judge every writer again; no delete exists. The period's own edges are {@link AccountingPeriodStore}'s.
 */
public interface PeriodCloseStore {

    /** Births a proposal {@code PROPOSED} - the period still {@code LOCKED}, its nine checks and its edge to follow in this transaction. */
    void insert(Connection unitOfWork, NewClose close);

    /** Records one check of a proposal: the attempt read, and for a {@code DISCLOSED} one what it disclosed and the person's reason. */
    void insertCheck(Connection unitOfWork, CheckRow check);

    /** The proposal, unlocked. */
    Optional<CloseRow> find(Connection unitOfWork, PeriodCloseId id);

    /** The proposal {@code FOR UPDATE}. */
    Optional<CloseRow> lock(Connection unitOfWork, PeriodCloseId id);

    /** The period's latest proposal, whatever its status, unlocked. */
    Optional<CloseRow> latest(Connection unitOfWork, AccountingPeriodId period);

    /** The proposal's checks, in the checks' order. */
    List<CheckRow> checks(Connection unitOfWork, PeriodCloseId id);

    /** Each check's attempt's evidence, as that attempt froze it - what an approver reads for a {@code PASSED} attempt, whose row holds no copy. */
    java.util.Map<CloseCheckCode, String> attemptEvidence(Connection unitOfWork, PeriodCloseId id);

    /** {@code PROPOSED ->} {@code decision}; {@code false} when no longer {@code PROPOSED}. */
    boolean decide(Connection unitOfWork, PeriodCloseId id, PeriodCloseStatus decision, Actor actor, String reason);

    /** Births a reopen proposal {@code PROPOSED} at the period's revision. */
    void insertReopen(Connection unitOfWork, NewReopen reopen);

    /** The reopen proposal of this revision, unlocked. */
    Optional<ReopenRow> reopenAt(Connection unitOfWork, AccountingPeriodId period, int revision);

    /** The reopen proposal of this revision {@code FOR UPDATE}. */
    Optional<ReopenRow> lockReopenAt(Connection unitOfWork, AccountingPeriodId period, int revision);

    /** {@code PROPOSED -> APPROVED}; {@code false} when no longer {@code PROPOSED}. */
    boolean approveReopen(Connection unitOfWork, PeriodReopenId id, Actor actor, String reason);

    // ------------------------------------------------------------------ rows

    /**
     * A proposal to birth: what the person read.
     *
     * @param lockedAt the lock instance it answers
     * @param pinned the GL mapping version the lock pinned
     * @param rateSet the period's approved valuation rate set
     * @param acknowledged the month's {@code ACKNOWLEDGED} breaks at the proposal
     * @param resolved the month's {@code RESOLVED} breaks at the proposal
     */
    record NewClose(PeriodCloseId id, AccountingPeriodId period, int revision, Instant lockedAt, CloseRunId run, GlMappingVersionId pinned,
            ValuationRateSetId rateSet, long acknowledged, long resolved, Actor proposedBy, String reason, CorrelationId correlation) {

        public NewClose {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(lockedAt, "lockedAt");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(pinned, "pinned");
            Objects.requireNonNull(rateSet, "rateSet");
            Objects.requireNonNull(proposedBy, "proposedBy");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(correlation, "correlation");
        }

        /** The reason never leaves in a text form: a record's generated {@code toString} would carry it into a log line. */
        @Override
        public String toString() {
            return "NewClose[" + id.value() + "]";
        }
    }

    /** A proposal as stored - the proposer's reason read only to tell a repeat from another act, and never served. */
    record CloseRow(PeriodCloseId id, AccountingPeriodId period, YearMonth month, int revision, Instant lockedAt, CloseRunId run,
            GlMappingVersionId pinned, ValuationRateSetId rateSet, long acknowledged, long resolved, PeriodCloseStatus status, String proposedBy,
            Instant proposedAt, String proposalReason, Optional<String> decidedBy, Optional<Instant> decidedAt, CorrelationId correlation) {

        public CloseRow {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(month, "month");
            Objects.requireNonNull(lockedAt, "lockedAt");
            Objects.requireNonNull(run, "run");
            Objects.requireNonNull(pinned, "pinned");
            Objects.requireNonNull(rateSet, "rateSet");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(proposedBy, "proposedBy");
            Objects.requireNonNull(proposedAt, "proposedAt");
            Objects.requireNonNull(proposalReason, "proposalReason");
            Objects.requireNonNull(decidedBy, "decidedBy");
            Objects.requireNonNull(decidedAt, "decidedAt");
            Objects.requireNonNull(correlation, "correlation");
        }

        /** {@link #proposalReason} never leaves in a text form: a record's generated {@code toString} would carry it into a log line. */
        @Override
        public String toString() {
            return "CloseRow[" + id.value() + ", " + month + ", " + status + "]";
        }
    }

    /**
     * One check of a proposal.
     *
     * @param attemptId the frozen attempt row read
     * @param evidence a copy of the attempt's evidence, exactly when {@code DISCLOSED}
     * @param disclosureReason the person's reason, exactly when {@code DISCLOSED}
     */
    record CheckRow(PeriodCloseId close, CloseCheckCode check, CloseCheckId attemptId, int attempt, CloseCheckStatus status, String judgedBy,
            Optional<String> evidence, Optional<String> disclosureReason) {

        public CheckRow {
            Objects.requireNonNull(close, "close");
            Objects.requireNonNull(check, "check");
            Objects.requireNonNull(attemptId, "attemptId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(judgedBy, "judgedBy");
            Objects.requireNonNull(evidence, "evidence");
            Objects.requireNonNull(disclosureReason, "disclosureReason");
            if (!status.readyForClose()) {
                throw new IllegalArgumentException("a close is proposed on PASSED and DISCLOSED attempts alone");
            }
            if ((status == CloseCheckStatus.DISCLOSED) != disclosureReason.isPresent()
                    || (status == CloseCheckStatus.DISCLOSED) != evidence.isPresent()) {
                throw new IllegalArgumentException("a DISCLOSED attempt carries a reason and what it disclosed; a PASSED one neither");
            }
        }

        /** A reason is CONFIDENTIAL and never leaves in a text form: a record's generated {@code toString} would carry it into a log line. */
        @Override
        public String toString() {
            return "CheckRow[" + check + "#" + attempt + ", " + status + "]";
        }
    }

    /** A reopen proposal to birth. */
    record NewReopen(PeriodReopenId id, AccountingPeriodId period, int revision, Actor proposedBy, String reason, CorrelationId correlation) {

        public NewReopen {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(proposedBy, "proposedBy");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(correlation, "correlation");
        }

        /** The reason never leaves in a text form: a record's generated {@code toString} would carry it into a log line. */
        @Override
        public String toString() {
            return "NewReopen[" + id.value() + ", revision=" + revision + "]";
        }
    }

    /** A reopen proposal as stored - the proposer's reason read only to tell a repeat from another act, and never served. */
    record ReopenRow(PeriodReopenId id, AccountingPeriodId period, int revision, PeriodReopenStatus status, String proposedBy, Instant proposedAt,
            String proposalReason, Optional<String> approvedBy, Optional<Instant> approvedAt) {

        public ReopenRow {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(proposedBy, "proposedBy");
            Objects.requireNonNull(proposedAt, "proposedAt");
            Objects.requireNonNull(proposalReason, "proposalReason");
            Objects.requireNonNull(approvedBy, "approvedBy");
            Objects.requireNonNull(approvedAt, "approvedAt");
        }

        /** {@link #proposalReason} never leaves in a text form. */
        @Override
        public String toString() {
            return "ReopenRow[" + id.value() + ", revision=" + revision + ", " + status + "]";
        }
    }
}
