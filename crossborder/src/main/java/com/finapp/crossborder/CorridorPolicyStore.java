package com.finapp.crossborder;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Storage for corridor policy versions (`P9-TSK-015`) - on the caller's unit of work, which owns the
 * transaction. {@code crossborder V002} holds the machine, the four-eyes rule, the one-ACTIVE and
 * one-PROPOSED partial uniques and the frozen content for every writer beneath this port.
 */
public interface CorridorPolicyStore {

    /** A version's decision-relevant columns, as locked or read. */
    record VersionRow(
            CorridorPolicyId id,
            int version,
            CorridorPolicyStatus status,
            String proposedBy,
            Optional<String> decidedBy) {}

    /** A version with its corridors. */
    record VersionView(VersionRow row, Instant proposedAt, List<CorridorTerms> corridors) {}

    int maxVersion(Connection unitOfWork);

    /**
     * Inserts a {@code PROPOSED} version and every corridor.
     *
     * @throws CorridorPolicyAdministration.ProposalPending when a proposal already awaits a decision
     *     or a racing proposal took the number; the caller's transaction is then aborted
     */
    void insertProposal(
            Connection unitOfWork,
            CorridorPolicyId id,
            int version,
            CorridorPolicyProposal proposal,
            String proposedBy,
            Instant at);

    /** The version, {@code FOR UPDATE}. */
    Optional<VersionRow> lock(Connection unitOfWork, CorridorPolicyId id);

    /** The {@code ACTIVE} version, {@code FOR UPDATE}, if one exists. */
    Optional<VersionRow> lockActive(Connection unitOfWork);

    /** Moves {@code id} from {@code from} to {@code to}; false when the row was not in {@code from}. */
    boolean decide(
            Connection unitOfWork,
            CorridorPolicyId id,
            CorridorPolicyStatus from,
            CorridorPolicyStatus to,
            String decidedBy,
            String reason,
            Instant at);

    /** Retires the {@code ACTIVE} version {@code id}; false when it was not {@code ACTIVE}. */
    boolean retire(Connection unitOfWork, CorridorPolicyId id, Instant at);

    void appendEvent(
            Connection unitOfWork,
            CorridorPolicyId id,
            Optional<CorridorPolicyStatus> from,
            CorridorPolicyStatus to,
            String actorId,
            String reason,
            Instant at);

    /** Versions newest first, at most {@code limit}, each with its corridors. */
    List<VersionView> versions(Connection unitOfWork, int limit);

    /** The {@code ACTIVE} version with its corridors, if one exists - read, not locked. */
    Optional<VersionView> active(Connection unitOfWork);

    /** The version {@code id} with its corridors. */
    Optional<VersionView> version(Connection unitOfWork, CorridorPolicyId id);
}
