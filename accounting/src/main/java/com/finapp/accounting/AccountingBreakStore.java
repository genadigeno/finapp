package com.finapp.accounting;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The accounting breaks' store (`P14-TSK-010`; {@code accounting V006}): the break row and its append-only case file.
 * The birth is insert-if-absent on {@code (period, check, subject)} - the row-10 arbiter; every later move a conditional
 * {@code UPDATE} of the row's act columns under {@code V006}'s edge trigger. No delete exists.
 */
public interface AccountingBreakStore {

    /** A case-file act, each on its one edge ({@code break_event_act_is_an_edge}). */
    enum Act {
        RAISED,
        ACKNOWLEDGEMENT_PROPOSED,
        ACKNOWLEDGED,
        RESOLVED
    }

    /** A break to be born. */
    record NewBreak(AccountingBreakId id, YearMonth period, BreakFinding finding, Actor raisedBy, CorrelationId correlation) {

        public NewBreak {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(finding, "finding");
            Objects.requireNonNull(raisedBy, "raisedBy");
            Objects.requireNonNull(correlation, "correlation");
        }
    }

    /** A break as stored - its finding, its status and its acts' who-and-when; never a reason. */
    record BreakRow(
            AccountingBreakId id,
            YearMonth period,
            CloseCheckCode check,
            BreakType type,
            String subject,
            String evidence,
            String judgedBy,
            BreakStatus status,
            Instant raisedAt,
            Optional<String> ackProposedBy,
            Optional<Instant> ackProposedAt,
            Optional<String> disclosure,
            Optional<String> ackApprovedBy,
            Optional<Instant> ackApprovedAt,
            Optional<String> resolvedBy,
            Optional<Instant> resolvedAt,
            Optional<UUID> resolvingEntry) {

        public BreakRow {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(check, "check");
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(evidence, "evidence");
            Objects.requireNonNull(judgedBy, "judgedBy");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(raisedAt, "raisedAt");
            Objects.requireNonNull(ackProposedBy, "ackProposedBy");
            Objects.requireNonNull(ackProposedAt, "ackProposedAt");
            Objects.requireNonNull(disclosure, "disclosure");
            Objects.requireNonNull(ackApprovedBy, "ackApprovedBy");
            Objects.requireNonNull(ackApprovedAt, "ackApprovedAt");
            Objects.requireNonNull(resolvedBy, "resolvedBy");
            Objects.requireNonNull(resolvedAt, "resolvedAt");
            Objects.requireNonNull(resolvingEntry, "resolvingEntry");
        }

        /** True while an acknowledgement is proposed and awaits a second person - only on an {@code OPEN} break. */
        public boolean acknowledgementPending() {
            return status == BreakStatus.OPEN && ackProposedBy.isPresent() && ackApprovedBy.isEmpty();
        }
    }

    /** One case-file row. */
    record EventRow(
            UUID id,
            AccountingBreakId breakId,
            Act act,
            Optional<BreakStatus> from,
            BreakStatus to,
            Actor actor,
            Optional<String> proposedBy,
            Optional<UUID> resolvingEntry,
            Optional<String> reason,
            CorrelationId correlation) {

        public EventRow {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(breakId, "breakId");
            Objects.requireNonNull(act, "act");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(proposedBy, "proposedBy");
            Objects.requireNonNull(resolvingEntry, "resolvingEntry");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(correlation, "correlation");
        }
    }

    /**
     * Births the break unless one already holds its {@code (period, check, subject)} - {@code ON CONFLICT DO NOTHING}: a
     * concurrent raiser waits on the winner's commit and inserts nothing.
     *
     * @return true when this call inserted it
     */
    boolean insertIfAbsent(Connection unitOfWork, NewBreak born);

    Optional<BreakRow> find(Connection unitOfWork, AccountingBreakId id);

    /** The break {@code FOR UPDATE} - every act's first statement. */
    Optional<BreakRow> lock(Connection unitOfWork, AccountingBreakId id);

    /**
     * One keyset page of a period's breaks in id order (a UUIDv7 - birth order), narrowed to {@code status} when named,
     * after {@code after} when given, at most {@code limit}.
     */
    List<BreakRow> list(Connection unitOfWork, YearMonth period, Optional<BreakStatus> status,
            Optional<AccountingBreakId> after, int limit);

    /**
     * The subjects already raised for {@code period} and {@code check}, each with its break's status - what a run counts
     * and never re-raises, a {@code RESOLVED} one reported as a recurrence (the review's I-1).
     */
    Map<String, BreakStatus> held(Connection unitOfWork, YearMonth period, CloseCheckCode check);

    /** How many breaks are not {@code RESOLVED}, per type - {@code finapp.accounting.break.open{type}}. */
    Map<BreakType, Long> openByType(Connection unitOfWork);

    /** Records the acknowledgement proposal - conditional on {@code OPEN} with none pending. */
    boolean proposeAcknowledgement(Connection unitOfWork, AccountingBreakId id, Actor actor, String disclosure);

    /** {@code OPEN -> ACKNOWLEDGED} - conditional on a pending proposal and no approval. */
    boolean approveAcknowledgement(Connection unitOfWork, AccountingBreakId id, Actor actor, String reason);

    /** {@code from -> RESOLVED} by reference to {@code correctingEntry} - conditional on {@code from}. */
    boolean resolve(Connection unitOfWork, AccountingBreakId id, BreakStatus from, Actor actor, UUID correctingEntry,
            String reason);

    void appendEvent(Connection unitOfWork, EventRow row);
}
