package com.finapp.credit;

import java.sql.Connection;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The credit policy's persistence (`P10-TSK-012`, {@code credit V008}). Every method runs on the caller's unit of work;
 * the machine, the frozen rules, the four eyes, the never-approving fallback and the effective period are the
 * database's to hold for every writer - the store only states the domain's intent.
 */
public interface CreditPolicyStore {

    /** A version's machine state, as a decision reads it. */
    record VersionRow(
            CreditPolicyVersionId id,
            CreditProduct product,
            int version,
            CreditPolicyStatus status,
            String proposedBy,
            Optional<String> decidedBy) {}

    /** A version with its policy and its effective period. */
    record PolicyVersion(
            VersionRow row, CreditPolicy policy, Optional<Instant> effectiveFrom, Optional<Instant> effectiveTo) {}

    /** The product's proposal lock: advisory namespace 10 on the product, held to commit. */
    void lockProduct(Connection unitOfWork, CreditProduct product);

    /** Whether a version of the product awaits a decision. */
    boolean proposalPending(Connection unitOfWork, CreditProduct product);

    /** The product's highest version number, 0 for none. */
    int maxVersion(Connection unitOfWork, CreditProduct product);

    /** Writes a version {@code PROPOSED} with every rule, in this one transaction. */
    void insertProposal(
            Connection unitOfWork,
            CreditPolicyVersionId id,
            int version,
            CreditPolicy policy,
            String proposedBy,
            String reason);

    /** Appends a history row. */
    void appendEvent(
            Connection unitOfWork,
            UUID eventId,
            CreditPolicyVersionId id,
            Optional<CreditPolicyStatus> from,
            CreditPolicyStatus to,
            String actorId,
            String reason);

    /** The version, locked {@code FOR UPDATE}. */
    Optional<VersionRow> lock(Connection unitOfWork, CreditPolicyVersionId id);

    /** The product's {@code ACTIVE} version, locked {@code FOR UPDATE}. */
    Optional<VersionRow> lockActive(Connection unitOfWork, CreditProduct product);

    /** {@code ACTIVE -> RETIRED}; false if it was not {@code ACTIVE}. */
    boolean retire(Connection unitOfWork, CreditPolicyVersionId id);

    /**
     * {@code PROPOSED -> to}, naming the decider; empty if it was not {@code PROPOSED}, else the effective start the
     * database stamped (empty inside for a rejection).
     */
    Optional<Optional<Instant>> decide(
            Connection unitOfWork, CreditPolicyVersionId id, CreditPolicyStatus to, String decidedBy, String reason);

    /** A version with its rules, whatever its status. */
    Optional<PolicyVersion> policy(Connection unitOfWork, CreditPolicyVersionId id);

    /** The product's version that was {@code ACTIVE} at {@code instant}, answered from the effective periods. */
    Optional<CreditPolicyVersionId> activeAt(Connection unitOfWork, CreditProduct product, Instant instant);

    /** The product's version that is {@code ACTIVE} now - by status, so no clock but the database's decides. */
    Optional<CreditPolicyVersionId> active(Connection unitOfWork, CreditProduct product);

    /** Each product's {@code ACTIVE} version number; a product with none is absent. */
    Map<CreditProduct, Integer> activeVersions(Connection unitOfWork);

    /**
     * The product's {@code ACTIVE} version {@code FOR SHARE} (`P10-TSK-015`; lock order element (5)) - the pin at
     * {@code SUBMITTED -> COLLECTING}: an activation committing meanwhile waits, so the version pinned is the one in force.
     */
    Optional<CreditPolicyVersionId> shareActive(Connection unitOfWork, CreditProduct product);

    /**
     * The pinned version {@code FOR SHARE} (`P10-TSK-015`; element (5)), whatever its status - read by the freeze and the
     * evaluation even after its retirement ({@code INV-HIST-04}); false when no such version exists.
     */
    boolean sharePinned(Connection unitOfWork, CreditPolicyVersionId id);
}
