package com.finapp.fx;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Storage for pricing policy versions (`P9-TSK-007`) - on the caller's unit of work, which owns
 * the transaction. {@code fx V004} holds the machine, the four-eyes rule, the one-ACTIVE and
 * one-PROPOSED partial uniques and the frozen content for every writer beneath this port.
 */
public interface PricingPolicyStore {

    /** A version's decision-relevant columns, as locked or read. */
    record VersionRow(
            PricingPolicyId id,
            int version,
            PricingPolicyStatus status,
            int openQuoteCap,
            String proposedBy,
            Optional<String> decidedBy) {}

    /** A version with its pairs, for the operator's read. */
    record VersionView(VersionRow row, Instant proposedAt, List<PolicyPair> pairs) {}

    int maxVersion(Connection unitOfWork);

    /**
     * Inserts a {@code PROPOSED} version and every pair.
     *
     * @throws PricingPolicyAdministration.ProposalPending when a proposal already awaits a
     *     decision (the one-PROPOSED index) or a racing proposal took the number; the caller's
     *     transaction is then aborted
     */
    void insertProposal(
            Connection unitOfWork,
            PricingPolicyId id,
            int version,
            PricingPolicyProposal proposal,
            String proposedBy,
            Instant at);

    /** The version, {@code FOR UPDATE}. */
    Optional<VersionRow> lock(Connection unitOfWork, PricingPolicyId id);

    /** The {@code ACTIVE} version, {@code FOR UPDATE}, if one exists. */
    Optional<VersionRow> lockActive(Connection unitOfWork);

    /** Moves {@code id} from {@code from} to {@code to}; false when the row was not in {@code from}. */
    boolean decide(
            Connection unitOfWork,
            PricingPolicyId id,
            PricingPolicyStatus from,
            PricingPolicyStatus to,
            String decidedBy,
            String reason,
            Instant at);

    /** Retires the {@code ACTIVE} version {@code id}; false when it was not {@code ACTIVE}. */
    boolean retire(Connection unitOfWork, PricingPolicyId id, Instant at);

    void appendEvent(
            Connection unitOfWork,
            PricingPolicyId id,
            Optional<PricingPolicyStatus> from,
            PricingPolicyStatus to,
            String actorId,
            String reason,
            Instant at);

    /** Versions newest first, at most {@code limit}, each with its pairs. */
    List<VersionView> versions(Connection unitOfWork, int limit);
}
