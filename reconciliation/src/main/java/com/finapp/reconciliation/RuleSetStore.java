package com.finapp.reconciliation;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The rule set administration's persistence (`P8-TSK-022`, ADR-0068 §8, `V012`): the version
 * row and its members, its history, and the conditional status moves the machine's arbiters
 * are. `V012`'s every-writer triggers stand beneath every write here — the version is born
 * {@code PROPOSED} and undecided, its content frozen from then on, its members admitted only in
 * its own transaction, its status moved only along {@code PROPOSED → ACTIVE | REJECTED} and
 * {@code ACTIVE → RETIRED} — and its partial uniques keep one {@code ACTIVE} and one
 * {@code PROPOSED} version per source for any writer.
 *
 * <p>Every method runs in the caller's unit of work; none opens, commits or wraps a savepoint.
 */
public interface RuleSetStore {

    /** One version row as stored — its identity, machine facts and dating magnitudes. */
    record VersionRow(
            UUID id,
            UUID sourceId,
            int version,
            RuleSetStatus status,
            String proposedBy,
            Optional<String> decidedBy,
            Optional<Instant> decidedAt,
            String reason,
            Instant createdAt,
            int fundingLagDays,
            int gainMinAgeDays,
            LocalDate effectiveFrom) {

        public VersionRow {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(proposedBy, "proposedBy must not be null");
            Objects.requireNonNull(decidedBy, "decidedBy must not be null; use Optional.empty()");
            Objects.requireNonNull(decidedAt, "decidedAt must not be null; use Optional.empty()");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(createdAt, "createdAt must not be null");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
        }
    }

    /**
     * One version with its whole content read back: lags in kind order, rules by priority,
     * tolerances by comparison and currency, fee schedules by line type and currency, and the
     * thresholds by currency code.
     */
    record VersionView(
            VersionRow row,
            Map<ExpectationKind, Integer> lagDays,
            List<RuleSetProposal.Rule> rules,
            List<RuleSetProposal.Tolerance> tolerances,
            List<RuleSetProposal.FeeTerms> feeSchedules,
            Map<CurrencyCode, Long> severityThresholds) {

        public VersionView {
            Objects.requireNonNull(row, "row must not be null");
            lagDays = RuleSetProposal.orderedLags(lagDays);
            rules = List.copyOf(rules);
            tolerances = List.copyOf(tolerances);
            feeSchedules = List.copyOf(feeSchedules);
            severityThresholds = RuleSetProposal.orderedThresholds(severityThresholds);
        }
    }

    /** The source's highest version number; {@code 0} when the source has none. */
    int maxVersion(Connection unitOfWork, UUID sourceId);

    /**
     * The source's {@code ACTIVE} version, read WITHOUT a lock — a proposal's advisory reading
     * of what it succeeds; the approval re-judges against the locked predecessor.
     */
    Optional<VersionRow> active(Connection unitOfWork, UUID sourceId);

    /** The version, {@code FOR UPDATE} — the decision commands' first lock. */
    Optional<VersionRow> lock(Connection unitOfWork, UUID ruleSetId);

    /**
     * The source's {@code ACTIVE} version, {@code FOR UPDATE} — the predecessor an activation
     * retires, taken after the proposal's own row lock.
     */
    Optional<VersionRow> lockActive(Connection unitOfWork, UUID sourceId);

    /** The expectation kinds the version holds a dating lag for. */
    Set<ExpectationKind> lagKinds(Connection unitOfWork, UUID ruleSetId);

    /**
     * Writes the version {@code PROPOSED} and undecided, with every member row, at the TOP
     * level of the caller's transaction: `V012`'s member trigger admits a member only while its
     * parent's {@code xmin} is the transaction's own id, so a savepoint around the parent would
     * refuse every member — fail-closed.
     *
     * @throws RuleSetAdministration.RuleSetProposalPending when a unique refused the version
     *     row — {@code rule_set_one_proposed} (a proposal already awaits a decision for the
     *     source) or {@code rule_set_version_once} (a racing proposal took the number). The
     *     caller's transaction is then aborted and must roll back.
     */
    void insertProposal(
            Connection unitOfWork,
            UUID id,
            int version,
            RuleSetProposal proposal,
            LocalDate effectiveFrom,
            Actor proposedBy,
            Instant at,
            CorrelationId correlation);

    /**
     * The conditional {@code from → to}; false when the version no longer stands at
     * {@code from}. A decision ({@code PROPOSED → ACTIVE | REJECTED}) names its person and
     * instant together, so {@code decidedBy} is required there; a retirement
     * ({@code ACTIVE → RETIRED}) keeps the activation's decision — `V012`'s trigger — so
     * {@code decidedBy} must be empty and {@code at} is not stored (the retirement's instant is
     * its history row's).
     *
     * @throws IllegalArgumentException for an edge outside the machine, or a
     *     {@code decidedBy} that does not fit the edge
     */
    boolean move(
            Connection unitOfWork,
            UUID id,
            RuleSetStatus from,
            RuleSetStatus to,
            Optional<Actor> decidedBy,
            Instant at);

    /** Appends one history row; {@code from} is empty exactly for the birth. */
    void appendEvent(
            Connection unitOfWork,
            UUID id,
            Optional<RuleSetStatus> from,
            RuleSetStatus to,
            Actor actor,
            String reason,
            Instant at,
            CorrelationId correlation);

    /** The source's versions, newest version first, at most {@code limit}, content included. */
    List<VersionView> versions(Connection unitOfWork, UUID sourceId, int limit);
}
