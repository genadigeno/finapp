package com.finapp.risk;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Storage for the sealed assessments (`P13-TSK-004`; {@code risk V007}; ADR-0033) - a port whose one write is the
 * born-once insert, every other method a read, all on the caller's unit of work. Nothing here updates or deletes: the
 * tables refuse both for every writer.
 */
public interface RiskAssessmentStore {

    /** The assessment sealed for {@code commandRef} of {@code operation}, if one is - no lock. */
    Optional<RiskAssessment> find(Connection unitOfWork, RiskOperation operation, CommandRef commandRef);

    /** The assessment {@code id}, if one exists - no lock. */
    Optional<RiskAssessment> read(Connection unitOfWork, RiskAssessmentId id);

    /**
     * Inserts {@code assessment} whole - the head {@code ON CONFLICT (operation, command_ref) DO NOTHING}, then its
     * inputs, hits and reasons. False when another transaction sealed the same command first: nothing was written.
     */
    boolean insert(Connection unitOfWork, RiskAssessment assessment);

    /**
     * The movement of every assessment {@link VelocityOutcome#COUNTED} at or after {@code from}, at its cut instant -
     * the velocity proof's evidence ({@link AssessmentVelocityEvidence}); the head's columns only, no lock.
     */
    List<VelocityEvidence.Counted> countedSince(Connection unitOfWork, Instant from);
}
