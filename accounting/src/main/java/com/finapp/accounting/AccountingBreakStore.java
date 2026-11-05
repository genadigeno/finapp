package com.finapp.accounting;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The accounting breaks' store (`P14-TSK-010`; {@code accounting V006}, {@code V008}): the break row and its append-only case
 * file. The birth is insert-if-absent on {@code (period, check, subject, generation)} - the row-10 arbiter; every later move a
 * conditional {@code UPDATE} of the row's act columns under the edge trigger. No delete exists.
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
    record NewBreak(AccountingBreakId id, YearMonth period, BreakFinding finding, int generation, Actor raisedBy,
            CorrelationId correlation) {

        public NewBreak {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(period, "period");
            Objects.requireNonNull(finding, "finding");
            Objects.requireNonNull(raisedBy, "raisedBy");
            Objects.requireNonNull(correlation, "correlation");
            if (generation < 1) {
                throw new IllegalArgumentException("a break's generation starts at 1");
            }
        }

        /** A subject's first break. */
        public NewBreak(AccountingBreakId id, YearMonth period, BreakFinding finding, Actor raisedBy, CorrelationId correlation) {
            this(id, period, finding, 1, raisedBy, correlation);
        }
    }

    /** A break as stored - its finding, its status and its acts' who-and-when; never a reason. */
    record BreakRow(
            AccountingBreakId id,
            YearMonth period,
            CloseCheckCode check,
            BreakType type,
            String subject,
            int generation,
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
     * Births the break unless one already holds its {@code (period, check, subject, generation)} - {@code ON CONFLICT DO
     * NOTHING}: a concurrent raiser waits on the winner's commit and inserts nothing.
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
     * The subjects already raised for {@code period} and {@code check}, each with its <strong>latest generation</strong>'s
     * status, evidence and number - what a run compares a finding with (ADR-0117's ruling, `P14-TSK-012`).
     */
    Map<String, Held> held(Connection unitOfWork, YearMonth period, CloseCheckCode check);

    /**
     * What a raised subject's latest break holds: its status, the evidence it was raised with - never rewritten - and its
     * generation. <strong>One rule for a finding a person decided on</strong> (the owner's ruling, ADR-0117): an {@code OPEN}
     * break awaits a decision, so nothing is compared with it. A decided one - {@code ACKNOWLEDGED}, or {@code RESOLVED} by its
     * correcting entry - holds a finding unchanged from its evidence, which counts as disclosed; a finding that differs is
     * {@link #changedBy changed}, a new break at {@link #nextGeneration()}.
     *
     * <p><strong>What decides "unchanged"</strong>: exact equality of {@link BreakFinding#evidence()}, which every producer
     * renders canonically (no clock, a fixed member order, sorted lists, the cut at {@value BreakFinding#MAX_EVIDENCE} deterministic) and
     * the column stores verbatim. The limits: evidence holds no amounts (accepted by the owner) and a difference past the cut is not seen.
     */
    record Held(BreakStatus status, String evidence, int generation) {

        public Held {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(evidence, "evidence");
        }

        /** A subject's first break. */
        public Held(BreakStatus status, String evidence) {
            this(status, evidence, 1);
        }

        /** True when a person has decided on this break: acknowledged it, or resolved it by its correcting entry. */
        boolean decided() {
            return status != BreakStatus.OPEN;
        }

        /** True when {@code finding} is not what a person decided on - decided, and now reading differently. */
        boolean changedBy(BreakFinding finding) {
            return decided() && !evidence.equals(finding.evidence());
        }

        /** The generation a changed finding is raised at. */
        int nextGeneration() {
            return generation + 1;
        }
    }

    /** How many breaks are not {@code RESOLVED}, per type - {@code finapp.accounting.break.open{type}}. */
    Map<BreakType, Long> openByType(Connection unitOfWork);

    /**
     * How many breaks are {@code OPEN}, raised at least {@code bound} ago on the database's clock, per type - {@code finapp.accounting.break.aged{type}} (`P14-TSK-020`; `P14-TSK-012`'s item 2).
     * {@code OPEN} alone: an {@code ACKNOWLEDGED} generation, superseded or not, was decided by two people and can stand for good, so it never asks anyone anything.
     */
    Map<BreakType, Long> agedOpenByType(Connection unitOfWork, Duration bound);

    /** How many breaks of {@code period} per status - a status with none absent ({@code OPEN_ACCOUNTING_BREAKS}, `P14-TSK-011`). */
    Map<BreakStatus, Long> countByStatus(Connection unitOfWork, YearMonth period);

    /** Records the acknowledgement proposal - conditional on {@code OPEN} with none pending. */
    boolean proposeAcknowledgement(Connection unitOfWork, AccountingBreakId id, Actor actor, String disclosure);

    /** {@code OPEN -> ACKNOWLEDGED} - conditional on a pending proposal and no approval. */
    boolean approveAcknowledgement(Connection unitOfWork, AccountingBreakId id, Actor actor, String reason);

    /** {@code from -> RESOLVED} by reference to {@code correctingEntry} - conditional on {@code from}. */
    boolean resolve(Connection unitOfWork, AccountingBreakId id, BreakStatus from, Actor actor, UUID correctingEntry,
            String reason);

    void appendEvent(Connection unitOfWork, EventRow row);
}
