package com.finapp.reconciliation;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The person's resolution machine's persistence (`P8-TSK-015`, ADR-0071): the resolution row
 * and its history, the subject rows it disposes of — each read LOCKED in the Phase 8 order,
 * after the break — and the conditional writes the machine's arbiters are.
 */
public interface ResolutionStore {

    /** One resolution as stored. */
    record ResolutionRow(
            UUID id,
            UUID breakId,
            ResolutionKind kind,
            ResolutionStatus status,
            ResolutionReasonCode reasonCode,
            boolean fourEyes,
            long proposedAmountMinor,
            String currency,
            int scale,
            long residualVersion,
            Optional<UUID> targetAccountId,
            Optional<UUID> offsetItemId,
            Optional<UUID> chosenExpectationId,
            UUID ruleSetId,
            Optional<UUID> adjustmentProposalId,
            Optional<UUID> journalEntryId,
            String proposedBy,
            Instant proposedAt,
            Optional<String> decidedBy) {

        public Money proposedAmount() {
            return Money.ofPersisted(proposedAmountMinor, CurrencyCode.of(currency), scale);
        }
    }

    /** A new proposal — or a born-approved acknowledgement — exactly as `V007` stores it. */
    record NewResolution(
            UUID id,
            UUID breakId,
            ResolutionKind kind,
            ResolutionStatus status,
            ResolutionReasonCode reasonCode,
            String narrative,
            boolean fourEyes,
            Money proposedAmount,
            long residualVersion,
            Optional<UUID> targetAccountId,
            Optional<UUID> offsetItemId,
            Optional<UUID> chosenExpectationId,
            UUID ruleSetId,
            Optional<UUID> adjustmentProposalId,
            Actor proposedBy,
            Instant at,
            CorrelationId correlation) {}

    /**
     * @throws OneLiveProposal when the break already carries a {@code PROPOSED} resolution —
     *     the partial unique refused it for this writer as for any
     */
    void insert(Connection unitOfWork, NewResolution resolution);

    void appendEvent(
            Connection unitOfWork,
            UUID resolutionId,
            Optional<ResolutionStatus> from,
            ResolutionStatus to,
            Actor actor,
            Optional<String> reason,
            Instant at,
            CorrelationId correlation);

    /** Lock-free: only the frozen break id is used from it, to take the locks in order. */
    Optional<ResolutionRow> byId(Connection unitOfWork, UUID resolutionId);

    Optional<ResolutionRow> lockById(Connection unitOfWork, UUID resolutionId);

    /** The break's live proposal, locked — the evidence leg's withdrawal target. */
    Optional<ResolutionRow> lockProposedOf(Connection unitOfWork, UUID breakId);

    /**
     * The conditional {@code PROPOSED → to}; false when another writer decided first. An
     * approval names what it produced — the entry, and a manual match's decision and park —
     * once (`V007`'s trigger).
     */
    boolean decide(
            Connection unitOfWork,
            UUID resolutionId,
            ResolutionStatus to,
            Actor decidedBy,
            Instant at,
            Optional<UUID> journalEntryId,
            Optional<UUID> decisionId,
            Optional<UUID> parkId);

    // ------------------------------------------------------------------ the break

    /** The break's conditional status move ({@code V004}'s edge trigger beneath). */
    boolean moveBreak(
            Connection unitOfWork, UUID breakId, BreakStatus from, BreakStatus to, Instant at);

    /**
     * The break's terminal edge from any open state, with its history row naming the
     * resolution — false when it was already {@code RESOLVED}.
     */
    boolean resolveBreak(
            Connection unitOfWork,
            UUID breakId,
            UUID resolutionId,
            ResolutionKind kind,
            Actor actor,
            Instant at,
            CorrelationId correlation);

    // ------------------------------------------------------------------ the subject

    /** An expectation's open remainder, locked. */
    record ExpectationHolding(
            UUID expectationId,
            ExpectationDirection direction,
            ExpectationStatus status,
            long remainderMinor,
            String currency,
            int scale,
            UUID positionAccountId) {}

    Optional<ExpectationHolding> lockExpectation(Connection unitOfWork, UUID expectationId);

    /** Another open break answering for the same expectation's remainder. */
    record RemainderSibling(UUID breakId, BreakStatus status) {}

    /**
     * The OTHER open breaks answering for the expectation's remainder — {@code AMOUNT_MISMATCH}
     * (a remittance's {@code SETTLEMENT_MISMATCH}, `P8-TSK-016`) and {@code MISSING_EXTERNAL}
     * on it, both valued at what is left — locked in id order. A partial allocation raises the
     * first and ageing the second, so both can stand; the disposal of the remainder explains
     * both. They share the expectation's source, so the command's one advisory covers them.
     */
    List<RemainderSibling> lockRemainderSiblings(
            Connection unitOfWork, UUID expectationId, UUID exceptBreakId);

    /** One suspense item, locked. */
    record SuspenseHolding(
            UUID suspenseItemId,
            UUID breakId,
            Optional<UUID> externalItemId,
            SuspenseSide side,
            SuspenseItemStatus status,
            long unreleasedMinor,
            String currency,
            int scale,
            UUID positionAccountId,
            LocalDate openedOn) {}

    /** The break's OWN open suspense items, locked in id order. */
    List<SuspenseHolding> lockOpenSuspenseOf(Connection unitOfWork, UUID breakId);

    Optional<SuspenseHolding> lockSuspenseItem(Connection unitOfWork, UUID suspenseItemId);

    /** The suspense item's owning break — frozen, so read before any lock. */
    Optional<UUID> breakOfSuspenseItem(Connection unitOfWork, UUID suspenseItemId);

    /**
     * Whether the item is at least {@code gain_min_age_days} old under the pinned rule set —
     * judged in SQL on the DATABASE clock against the stored {@code opened_on} (ADR-0070 §4).
     */
    boolean gainEligible(Connection unitOfWork, UUID suspenseItemId);

    /**
     * The expectation's whole remainder taken into {@code resolved_minor}:
     * {@code RESOLVED_BY_ADJUSTMENT}, with its {@code RESOLVED} history row naming the
     * resolution — conditional on the remainder the approval derived.
     */
    boolean resolveExpectation(
            Connection unitOfWork,
            UUID expectationId,
            long amountMinor,
            UUID resolutionId,
            Actor actor,
            Instant at,
            CorrelationId correlation);

    /**
     * The candidate expectations the item's stored decisions snapshotted, each with the key
     * that reached it — MANUAL_MATCH's closed choice (ADR-0068 §9).
     */
    Map<UUID, KeyKind> storedCandidatesOf(Connection unitOfWork, UUID externalItemId);

    /** The reported item's direction (frozen at ingestion). */
    Optional<ExpectationDirection> itemDirection(Connection unitOfWork, UUID externalItemId);

    /** The item's run — a manual decision's own run. */
    Optional<UUID> runOfItem(Connection unitOfWork, UUID externalItemId);

    /** The partial unique refused a second live proposal on the break. */
    final class OneLiveProposal extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public OneLiveProposal(Throwable cause) {
            super("the break already carries a live resolution proposal", cause);
        }
    }
}
