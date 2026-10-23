package com.finapp.risk;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the risk policy versions (`P13-TSK-002`, {@code risk V002}): the machine row
 * ({@code risk_policy_version}, its decision columns the only ones that move), the content born with it
 * ({@code risk_policy}, {@code risk_rule}, {@code risk_rule_parameter}, {@code risk_policy_band}, INSERT-only) and the
 * history ({@code risk_policy_version_event}, append-only).
 *
 * <p>The administration's lock order (A0 to A2, {@code RISK_LIFECYCLES.md} section 3): the operation's advisory lock
 * (namespace {@code 13}), then the version row {@code FOR UPDATE}, then the operation's {@code ACTIVE} row
 * {@code FOR UPDATE}. A pinning reader - T-eval at (R1) - takes the {@code ACTIVE} row {@code FOR SHARE} and nothing of
 * the administration's, except, when that first read finds no row, A0 in SHARED mode for one more read
 * ({@link RiskPolicies#active} composes the two: the Phase 12 → 13 gate's A3-I1 rule).
 */
public interface RiskPolicyStore {

    /**
     * A0: the operation's administration lock - advisory namespace {@code 13} on {@code hashtext(operation)}, blocking,
     * transaction-scoped - taken FIRST by every proposal and decision.
     */
    void lockOperation(Connection unitOfWork, RiskOperation operation);

    /**
     * A0 in SHARED mode - taken only by a pin whose first {@code FOR SHARE} read found no {@code ACTIVE} version: every
     * administration act holds A0 exclusively to its commit, so once this is granted no activation is in flight and the
     * statement after it reads every committed one.
     */
    void lockOperationShared(Connection unitOfWork, RiskOperation operation);

    /** The operation's highest version number, 0 for none - read under A0. */
    int maxVersion(Connection unitOfWork, RiskOperation operation);

    /** Births the version {@code PROPOSED}, its content and its history row; the database stamps the time. */
    void insertProposal(Connection unitOfWork, NewProposal proposal);

    /** The version's machine row, unlocked - to learn its (frozen) operation before A0. */
    Optional<VersionRow> find(Connection unitOfWork, RiskPolicyVersionId id);

    /** A1: the version's machine row {@code FOR UPDATE}. */
    Optional<VersionRow> lock(Connection unitOfWork, RiskPolicyVersionId id);

    /** A2: the operation's {@code ACTIVE} row {@code FOR UPDATE} - waits for every evaluation pinning it {@code FOR SHARE}. */
    Optional<VersionRow> lockActive(Connection unitOfWork, RiskOperation operation);

    /** Retires an {@code ACTIVE} version; {@code false} when it was not {@code ACTIVE}. */
    boolean retire(Connection unitOfWork, RiskPolicyVersionId id);

    /**
     * Decides a {@code PROPOSED} version - {@code ACTIVE} or {@code REJECTED} - conditional on {@code PROPOSED}. Returns
     * the database's stamps, or empty when the version was no longer {@code PROPOSED}: nothing written.
     */
    Optional<DecisionStamps> decide(
            Connection unitOfWork, RiskPolicyVersionId id, RiskPolicyVersionStatus status, Actor actor, String reason);

    /** Appends one history row; the database stamps the time. */
    void appendEvent(Connection unitOfWork, HistoryRow row);

    /** The version with its whole content, any status. */
    Optional<RiskPolicyVersion> version(Connection unitOfWork, RiskPolicyVersionId id);

    /** The version of {@code operation} {@code ACTIVE} now, by status - an unlocked read, the door's. */
    Optional<RiskPolicyVersionId> active(Connection unitOfWork, RiskOperation operation);

    /**
     * ONE read of the {@code ACTIVE} version of {@code operation}, its row held {@code FOR SHARE} to the caller's commit -
     * the pin's statement ((R1)). {@link RiskPolicies#active} reads once more under {@link #lockOperationShared} when
     * this is empty; a caller that reads once and answers "none" has rebuilt the A3-I1 defect.
     */
    Optional<RiskPolicyVersionId> shareActiveOnce(Connection unitOfWork, RiskOperation operation);

    /** Every operation's {@code ACTIVE} version number - for the gauge. */
    Map<RiskOperation, Integer> activeVersions(Connection unitOfWork);

    // ------------------------------------------------------------------ rows

    /** A proposal at birth. */
    record NewProposal(
            RiskPolicyVersionId id,
            int version,
            RiskPolicy policy,
            Actor proposer,
            String reason,
            CorrelationId correlation,
            UUID eventId) {

        public NewProposal {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(policy, "policy must not be null");
            Objects.requireNonNull(proposer, "proposer must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            Objects.requireNonNull(eventId, "eventId must not be null");
            if (version < 1) {
                throw new IllegalArgumentException("a policy version is numbered from 1");
            }
        }

        @Override
        public String toString() {
            return "NewProposal[" + id.value() + ", " + policy.operation() + " v" + version + "]";
        }
    }

    /** One edge of the history. */
    record HistoryRow(
            UUID eventId,
            RiskPolicyVersionId version,
            RiskOperation operation,
            Optional<RiskPolicyVersionStatus> from,
            RiskPolicyVersionStatus to,
            Actor actor,
            String reason,
            CorrelationId correlation) {

        public HistoryRow {
            Objects.requireNonNull(eventId, "eventId must not be null");
            Objects.requireNonNull(version, "version must not be null");
            Objects.requireNonNull(operation, "operation must not be null");
            Objects.requireNonNull(from, "from must not be null");
            Objects.requireNonNull(to, "to must not be null");
            Objects.requireNonNull(actor, "actor must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }

        @Override
        public String toString() {
            return "HistoryRow[" + version.value() + ", " + from.map(Enum::name).orElse("-") + " -> " + to + "]";
        }
    }

    /** The database's stamps on a decision: when, and - for an activation - the effective start. */
    record DecisionStamps(Instant decidedAt, Optional<Instant> effectiveFrom) {

        public DecisionStamps {
            Objects.requireNonNull(decidedAt, "decidedAt must not be null");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
        }
    }

    /**
     * A version's machine row: who, which state, which engine name, and its effective period. No rule, no amount. The
     * engine is held as its stored name so a version written for an engine this build no longer holds is read and
     * refused at activation (ADR-0109 decision 8), not made unreadable.
     */
    record VersionRow(
            RiskPolicyVersionId id,
            RiskOperation operation,
            int version,
            String engine,
            RiskPolicyVersionStatus status,
            String proposedBy,
            Instant proposedAt,
            Optional<String> decidedBy,
            Optional<Instant> decidedAt,
            Optional<Instant> effectiveFrom,
            Optional<Instant> effectiveTo) {

        public VersionRow {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(operation, "operation must not be null");
            Objects.requireNonNull(engine, "engine must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(proposedBy, "proposedBy must not be null");
            Objects.requireNonNull(proposedAt, "proposedAt must not be null");
            Objects.requireNonNull(decidedBy, "decidedBy must not be null");
            Objects.requireNonNull(decidedAt, "decidedAt must not be null");
            Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
            Objects.requireNonNull(effectiveTo, "effectiveTo must not be null");
        }

        @Override
        public String toString() {
            return "VersionRow[" + id.value() + ", " + operation + " v" + version + ", " + status + "]";
        }
    }
}
