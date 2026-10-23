package com.finapp.risk;

import java.sql.Connection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The reads of the risk policy versions, and <strong>the pin</strong> (`P13-TSK-002`; ADR-0109 decision 6;
 * {@code PHASE_13_PLAN.md} section 7.2 (R1); {@code INV-RSK-05}). T-eval (`-004`) pins the operation's {@code ACTIVE}
 * version through {@link #active} on the caller's connection, inside the caller's transaction, after the caller's
 * locks: the row {@code FOR SHARE} to the caller's commit, so an activation waits for it and the evaluation judges
 * under the old version or the new, never one retiring under it - <strong>and never neither</strong>.
 *
 * <p><strong>The A3-I1 rule</strong> (the Phase 12 → 13 gate, repaired in bnpl and lending, applied here forward from
 * the first version). Under {@code READ COMMITTED} a {@code FOR SHARE} that waited on an activation's lock re-checks
 * only the row it waited on: it finds the predecessor {@code RETIRED} and never sees the successor, born after its
 * snapshot - "neither". So when the first read is empty the pin takes advisory namespace {@code 13} in SHARED mode
 * ({@link RiskPolicyStore#lockOperationShared}) and reads once more: every administration act holds the namespace
 * exclusively to its commit, so once the shared lock is granted no activation is in flight and the successor, if one
 * committed, is in the new statement's snapshot. A retirement commits only beside its successor, so an empty second
 * read is genuine - no version of the operation is {@code ACTIVE}, and the caller falls to its no-policy fail-safe
 * (ADR-0108). The common path takes nothing of the administration's; the shared lock is held only by a pin that found
 * none, which holds no policy row an administration act waits on - one-way, no cycle.
 */
@RequiredArgsConstructor
public final class RiskPolicies {

    @NonNull private final RiskPolicyStore store;

    /** No policy version of the operation is {@code ACTIVE} ({@code 409 risk.PolicyNotActive} at the door). */
    public static final class PolicyNotActive extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PolicyNotActive(RiskOperation operation) {
            super("no risk policy version is active for " + operation.name());
        }
    }

    /**
     * The pin: the operation's {@code ACTIVE} version, its row held {@code FOR SHARE} to the caller's commit; empty only
     * when, after the shared re-read, no version of the operation is {@code ACTIVE} - the caller's no-policy fail-safe,
     * recorded as a decision, never a silent default.
     */
    public Optional<RiskPolicyVersion> active(Connection unitOfWork, RiskOperation operation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(operation, "operation");
        Optional<RiskPolicyVersionId> pinned = store.shareActiveOnce(unitOfWork, operation);
        if (pinned.isEmpty()) {
            store.lockOperationShared(unitOfWork, operation);
            pinned = store.shareActiveOnce(unitOfWork, operation);
        }
        return pinned.map(id -> store.version(unitOfWork, id)
                .orElseThrow(() -> new IllegalStateException("a pinned ACTIVE version has its content")));
    }

    /**
     * The operation's version {@code ACTIVE} now, by status, unlocked - the door's read; never an evaluation's.
     *
     * @throws PolicyNotActive when none is active
     */
    public RiskPolicyVersion inForce(Connection unitOfWork, RiskOperation operation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(operation, "operation");
        RiskPolicyVersionId id = store.active(unitOfWork, operation).orElseThrow(() -> new PolicyNotActive(operation));
        return store.version(unitOfWork, id).orElseThrow(() -> new PolicyNotActive(operation));
    }

    /**
     * One version by id, in any state - what the second person reads before deciding a proposal.
     *
     * @throws RiskPolicyAdministration.PolicyNotFound when no version has this id
     */
    public RiskPolicyVersion version(Connection unitOfWork, RiskPolicyVersionId id) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        return store.version(unitOfWork, id).orElseThrow(RiskPolicyAdministration.PolicyNotFound::new);
    }

    /** Every operation's {@code ACTIVE} version number - for the gauge. */
    public Map<RiskOperation, Integer> activeVersions(Connection unitOfWork) {
        return store.activeVersions(Objects.requireNonNull(unitOfWork, "unitOfWork"));
    }
}
