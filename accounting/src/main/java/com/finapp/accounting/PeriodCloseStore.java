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
 * conditional {@code UPDATE} under {@code V008}'s edge triggers, which judge every writer again; no delete exists. The period's own edges are {@link AccountingPeriodStore}'s. A reopen ends
 * without its effect too (`P14-TSK-024`, {@code accounting V011}): at most one {@code PROPOSED} per period and one {@code APPROVED} per revision, so a revision can hold several ended ones.
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

    /**
     * The period's latest proposal, whatever its status, unlocked - ranked by the rows, never by a stamp a stepped clock can reorder (`P15-TSK-033`, PI-013): the
     * {@code PROPOSED} one when one stands, else the highest revision's, its {@code APPROVED} one first; a stamp orders only one revision's rejected proposals.
     */
    Optional<CloseRow> latest(Connection unitOfWork, AccountingPeriodId period);

    /** The proposal's checks, in the checks' order. */
    List<CheckRow> checks(Connection unitOfWork, PeriodCloseId id);

    /**
     * Each frozen check's attempt as that attempt concluded, read from its own {@code close_check} row (`P14-TSK-024`, `P14-TSK-012`'s (j)): its evidence - what an approver reads for a {@code
     * PASSED} attempt, whose frozen row holds no copy - and when it concluded, on the database's clock as stored. One entry per frozen check.
     */
    java.util.Map<CloseCheckCode, FrozenAttempt> frozenAttempts(Connection unitOfWork, PeriodCloseId id);

    /** {@code PROPOSED ->} {@code decision}; {@code false} when no longer {@code PROPOSED}. */
    boolean decide(Connection unitOfWork, PeriodCloseId id, PeriodCloseStatus decision, Actor actor, String reason);

    /** Births a reopen proposal {@code PROPOSED} at the period's revision. */
    void insertReopen(Connection unitOfWork, NewReopen reopen);

    /** The period's pending reopen - at most one ({@code period_reopen_one_pending}) - unlocked. */
    Optional<ReopenRow> pendingReopen(Connection unitOfWork, AccountingPeriodId period);

    /** The period's pending reopen {@code FOR UPDATE}. */
    Optional<ReopenRow> lockPendingReopen(Connection unitOfWork, AccountingPeriodId period);

    /** The reopen approved at this revision - at most one ({@code period_reopen_one_approved}) - unlocked. */
    Optional<ReopenRow> approvedReopenAt(Connection unitOfWork, AccountingPeriodId period, int revision);

    /**
     * The period's reopen {@code id}, when it ended without its effect ({@code REJECTED} or {@code WITHDRAWN}), unlocked - an ended reopen is terminal; empty when it did not, or is not the period's
     * (`P14-DOC-001`, `P14-TSK-024`'s re-review (a): the request named, never "the latest end").
     */
    Optional<ReopenRow> endedReopen(Connection unitOfWork, AccountingPeriodId period, PeriodReopenId id);

    /** {@code PROPOSED -> APPROVED}; {@code false} when no longer {@code PROPOSED}. */
    boolean approveReopen(Connection unitOfWork, PeriodReopenId id, Actor actor, String reason);

    /** {@code PROPOSED -> REJECTED | WITHDRAWN} (`P14-TSK-024`); {@code false} when no longer {@code PROPOSED}. {@code accounting V011} holds the four eyes. */
    boolean endReopen(Connection unitOfWork, PeriodReopenId id, PeriodRequestEnd end, Actor actor, String reason);

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

    /**
     * A frozen check's attempt as it concluded (`P14-TSK-024`).
     *
     * @param evidence the attempt's evidence, {@code CONFIDENTIAL}, never logged
     * @param concludedAt when the attempt's conclusion was written, the database's {@code clock_timestamp()} as {@code close_check.concluded_at} stores it: what it judged was read before then
     */
    record FrozenAttempt(String evidence, Instant concludedAt) {

        public FrozenAttempt {
            Objects.requireNonNull(evidence, "evidence");
            Objects.requireNonNull(concludedAt, "concludedAt");
        }

        /** The evidence is {@code CONFIDENTIAL}: a record's generated {@code toString} would carry it into a log line. */
        @Override
        public String toString() {
            return "FrozenAttempt[" + concludedAt + "]";
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

    /** A reopen proposal as stored - the proposer's reason read only to tell a repeat from another act, and never served; no end's reason is read at all. */
    record ReopenRow(PeriodReopenId id, AccountingPeriodId period, int revision, PeriodReopenStatus status, String proposedBy, Instant proposedAt,
            String proposalReason, Optional<String> approvedBy, Optional<Instant> approvedAt, Optional<String> endedBy, Optional<Instant> endedAt) {

        public ReopenRow {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(proposedBy, "proposedBy");
            Objects.requireNonNull(proposedAt, "proposedAt");
            Objects.requireNonNull(proposalReason, "proposalReason");
            Objects.requireNonNull(approvedBy, "approvedBy");
            Objects.requireNonNull(approvedAt, "approvedAt");
            Objects.requireNonNull(endedBy, "endedBy");
            Objects.requireNonNull(endedAt, "endedAt");
        }

        /** {@link #proposalReason} never leaves in a text form. */
        @Override
        public String toString() {
            return "ReopenRow[" + id.value() + ", revision=" + revision + ", " + status + "]";
        }
    }
}
