package com.finapp.reconciliation;

import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The operator reports' reads (`P8-TSK-024`, ADR-0072 §2–§3, §6) — lock-free, reconciliation's
 * own schema only, meant to run inside ONE {@code REPEATABLE READ} transaction the caller holds
 * and audits in. Every amount leaves here as a {@link Money} per ROW, built from the stored
 * ADR-0003 triple; the caller folds — never a SQL {@code SUM} ({@code PositionBreakdown}'s
 * rule). Open-set rows are streamed to a visitor rather than returned as a list, so a report
 * bounded at 100 rows still folds its totals over EVERY row without holding them all. No row
 * carries a counterparty reference, a note, a narrative or a file byte: identifiers, codes,
 * states, dates and amounts only.
 */
public interface ReportReadings {

    /** The open break that owns or names a subject: identity, type and present severity. */
    record OpenBreak(UUID id, BreakType type, Severity severity) {

        public OpenBreak {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(severity, "severity must not be null");
        }
    }

    // ----------------------------------------------------------------- suspense

    /**
     * One suspense item still holding value: its unreleased remainder (amount − released, through
     * {@code Money}), its side — {@code CREDIT} and {@code DEBIT} never net — and its owning
     * break, empty only if the owner row is missing (the {@code suspense.unowned} defect).
     */
    record OpenSuspenseLine(
            UUID id,
            SuspenseOrigin origin,
            SuspenseSide side,
            Money unreleased,
            LocalDate openedOn,
            UUID breakId,
            Optional<OpenBreak> owner) {

        public OpenSuspenseLine {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(origin, "origin must not be null");
            Objects.requireNonNull(side, "side must not be null");
            Objects.requireNonNull(unreleased, "unreleased must not be null");
            Objects.requireNonNull(openedOn, "openedOn must not be null");
            Objects.requireNonNull(breakId, "breakId must not be null");
            Objects.requireNonNull(owner, "owner must not be null");
        }
    }

    /**
     * Every item with an unreleased remainder ({@code OPEN} or {@code PARTIALLY_RELEASED}),
     * oldest {@code opened_on} first, then by id — a total order, so a bound cuts the same rows.
     */
    void eachOpenSuspenseItem(Connection unitOfWork, Consumer<OpenSuspenseLine> visitor);

    // ----------------------------------------------------------------- unexplained value

    /**
     * One external item holding unexplained value: an {@code UNMATCHED} item's remainder
     * (amount − allocated − parked − offset) inside its grace, or a {@code PARKED} item's parked
     * value — and the worst open break naming it or owning its suspense item, if any.
     */
    record UnexplainedItemLine(
            UUID id,
            UUID sourceId,
            ExternalLineType lineType,
            ExpectationDirection direction,
            ItemStatus status,
            Money value,
            LocalDate businessDate,
            Optional<Instant> graceUntil,
            Optional<OpenBreak> openBreak) {

        public UnexplainedItemLine {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(lineType, "lineType must not be null");
            Objects.requireNonNull(direction, "direction must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(value, "value must not be null");
            Objects.requireNonNull(businessDate, "businessDate must not be null");
            Objects.requireNonNull(graceUntil, "graceUntil must not be null");
            Objects.requireNonNull(openBreak, "openBreak must not be null");
        }
    }

    /**
     * Every {@code UNMATCHED} and {@code PARKED} item, oldest business date first, then by
     * receipt and id — a total order.
     */
    void eachUnexplainedItem(Connection unitOfWork, Consumer<UnexplainedItemLine> visitor);

    /**
     * One expectation still owed: its remainder (amount − allocated − resolved), its own posting
     * date (the age's anchor) and whether the ageing sweep has marked it overdue.
     */
    record OpenExpectationLine(
            UUID id,
            UUID sourceId,
            ExpectationKind kind,
            ExpectationDirection direction,
            Money remainder,
            LocalDate postingDate,
            LocalDate expectedBy,
            Optional<Instant> overdueSince) {

        public OpenExpectationLine {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(direction, "direction must not be null");
            Objects.requireNonNull(remainder, "remainder must not be null");
            Objects.requireNonNull(postingDate, "postingDate must not be null");
            Objects.requireNonNull(expectedBy, "expectedBy must not be null");
            Objects.requireNonNull(overdueSince, "overdueSince must not be null");
        }
    }

    /** Every {@code OPEN} or {@code PARTIALLY_SETTLED} expectation, oldest posting date first. */
    void eachOpenExpectation(Connection unitOfWork, Consumer<OpenExpectationLine> visitor);

    // ----------------------------------------------------------------- breaks

    /**
     * One break as the reports read it: its frozen subject facts (source, value at issue in its
     * own currency, raise), its present type, severity and status, and when it resolved.
     */
    record BreakLine(
            UUID id,
            UUID sourceId,
            BreakType type,
            Severity severity,
            BreakStatus status,
            Money valueAtIssue,
            Instant raisedAt,
            Optional<Instant> resolvedAt) {

        public BreakLine {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(severity, "severity must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(valueAtIssue, "valueAtIssue must not be null");
            Objects.requireNonNull(raisedAt, "raisedAt must not be null");
            Objects.requireNonNull(resolvedAt, "resolvedAt must not be null");
        }
    }

    /** Every break not yet {@code RESOLVED}, oldest raise first. */
    void eachOpenBreak(Connection unitOfWork, Consumer<BreakLine> visitor);

    /**
     * Every break that touches {@code [from, until)}: raised in it, resolved in it, or standing
     * open at its end ({@code raised_at < until} and not resolved before {@code until}) — the
     * caller classifies; the stored {@code raised_at} and {@code resolved_at} are frozen facts,
     * so the as-of answer is exact.
     */
    void eachBreakAround(
            Connection unitOfWork, Instant from, Instant until, Consumer<BreakLine> visitor);

    // ----------------------------------------------------------------- a business date

    /**
     * One item of a business date's runs, its four stored figures as {@code Money}: the line's
     * amount and what was allocated, parked and offset against it.
     */
    record DayItemLine(
            UUID sourceId,
            ItemStatus status,
            Money amount,
            Money allocated,
            Money parked,
            Money offset) {

        public DayItemLine {
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(allocated, "allocated must not be null");
            Objects.requireNonNull(parked, "parked must not be null");
            Objects.requireNonNull(offset, "offset must not be null");
        }
    }

    /** Every item of the runs whose {@code business_date} is {@code businessDate}. */
    void eachItemOfBusinessDate(
            Connection unitOfWork, LocalDate businessDate, Consumer<DayItemLine> visitor);

    /**
     * Accepted batches per source for {@code businessDate}: their {@code BATCH} runs, one born
     * in each acceptance's own transaction — a later repudiation leaves the run standing, so
     * this counts acceptances, its items saying {@code REPUDIATED}.
     */
    Map<UUID, Long> acceptedBatchesOfBusinessDate(Connection unitOfWork, LocalDate businessDate);

    /**
     * One resolution APPROVED in a period: its source (the break's, or for a batch-subject
     * repudiation its batch's run's — empty only if neither row answers), its kind, its proposed
     * amount and whether it posted (a journal entry recorded).
     */
    record ApprovedResolutionLine(
            Optional<UUID> sourceId, ResolutionKind kind, Money proposed, boolean posted) {

        public ApprovedResolutionLine {
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(proposed, "proposed must not be null");
        }
    }

    /** Every resolution APPROVED in {@code [from, until)}. */
    List<ApprovedResolutionLine> resolutionsApprovedBetween(
            Connection unitOfWork, Instant from, Instant until);

    // ----------------------------------------------------------------- provider costs

    /** One approved batch repudiation and its reversal entry (`P8-TSK-023`). */
    record RepudiationPosting(UUID resolutionId, UUID settlementBatchId, UUID journalEntryId) {

        public RepudiationPosting {
            Objects.requireNonNull(resolutionId, "resolutionId must not be null");
            Objects.requireNonNull(settlementBatchId, "settlementBatchId must not be null");
            Objects.requireNonNull(journalEntryId, "journalEntryId must not be null");
        }
    }

    /**
     * Every APPROVED {@code REPUDIATE_BATCH} resolution with a reversal entry, decided in
     * {@code [from, until)} — the reversal is dated by the approval's UTC day, so the caller
     * reads its posting date from the ledger to place it in a month.
     */
    List<RepudiationPosting> repudiationsApprovedBetween(
            Connection unitOfWork, Instant from, Instant until);

    /**
     * One fee line's expected fee as its latest {@code CHECK} decision recorded it under the
     * pinned schedule, in the item's currency, with the item's direction ({@code OUTBOUND} a
     * charge, {@code INBOUND} a rebate).
     */
    record ExpectedFeeLine(UUID settlementBatchId, ExpectationDirection direction, Money expected) {

        public ExpectedFeeLine {
            Objects.requireNonNull(settlementBatchId, "settlementBatchId must not be null");
            Objects.requireNonNull(direction, "direction must not be null");
            Objects.requireNonNull(expected, "expected must not be null");
        }
    }

    /** The expected fees the fee check recorded for the items of these settlement batches. */
    List<ExpectedFeeLine> expectedFeesOf(
            Connection unitOfWork, Collection<UUID> settlementBatchIds);
}
