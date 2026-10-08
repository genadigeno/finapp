package com.finapp.credit;

import java.sql.Connection;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The scorecard model's persistence (`P10-TSK-011`, {@code credit V006}). Every method runs on the caller's unit of
 * work; the machine, the frozen bands, the four eyes and the effective period are the database's to hold for every
 * writer - the store only states the domain's intent.
 */
public interface ScorecardStore {

    /** A version's machine state, as a decision reads it. */
    record VersionRow(
            ScorecardModelVersionId id,
            ScorecardFamily family,
            int version,
            ScorecardStatus status,
            String proposedBy,
            Optional<String> decidedBy) {}

    /** A version with its table and its effective period. */
    record ModelVersion(
            VersionRow row, Scorecard scorecard, Optional<Instant> effectiveFrom, Optional<Instant> effectiveTo) {}

    /** The family's proposal lock: advisory namespace 10 on the family, held to commit. */
    void lockFamily(Connection unitOfWork, ScorecardFamily family);

    /** Whether a version of the family awaits a decision. */
    boolean proposalPending(Connection unitOfWork, ScorecardFamily family);

    /** The family's highest version number, 0 for none. */
    int maxVersion(Connection unitOfWork, ScorecardFamily family);

    /** Writes a version {@code PROPOSED} with every band, in this one transaction. */
    void insertProposal(
            Connection unitOfWork,
            ScorecardModelVersionId id,
            ScorecardFamily family,
            int version,
            Scorecard scorecard,
            String proposedBy,
            String reason);

    /** Appends a history row. */
    void appendEvent(
            Connection unitOfWork,
            UUID eventId,
            ScorecardModelVersionId id,
            Optional<ScorecardStatus> from,
            ScorecardStatus to,
            String actorId,
            String reason);

    /** The version, locked {@code FOR UPDATE}. */
    Optional<VersionRow> lock(Connection unitOfWork, ScorecardModelVersionId id);

    /** The family's {@code ACTIVE} version, locked {@code FOR UPDATE}. */
    Optional<VersionRow> lockActive(Connection unitOfWork, ScorecardFamily family);

    /** {@code ACTIVE -> RETIRED}; false if it was not {@code ACTIVE}. */
    boolean retire(Connection unitOfWork, ScorecardModelVersionId id);

    /**
     * {@code PROPOSED -> to}, naming the decider; empty if it was not {@code PROPOSED}, else the effective start the
     * database stamped (empty inside for a rejection).
     */
    Optional<Optional<Instant>> decide(
            Connection unitOfWork, ScorecardModelVersionId id, ScorecardStatus to, String decidedBy, String reason);

    /** A version with its bands, whatever its status. */
    Optional<ModelVersion> model(Connection unitOfWork, ScorecardModelVersionId id);

    /** The family's version that was {@code ACTIVE} at {@code instant}, answered from the effective periods. */
    Optional<ScorecardModelVersionId> activeAt(Connection unitOfWork, ScorecardFamily family, Instant instant);
}
