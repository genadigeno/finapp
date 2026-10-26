package com.finapp.risk;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Where customer risk profiles rest (`P13-TSK-007`; {@code risk V006}) - every operation on the caller's unit of work
 * (ADR-0033). The head is born unassessed, claimed and locked at the Phase 13 lock order's R8, moved only beside a new
 * version, and its permit re-stamped on the database clock; the versions are {@code INSERT}-only. No delete exists in
 * the contract, the grant or the triggers.
 */
public interface CustomerRiskProfileStore {

    /**
     * T-sig's hint: the party's head born unassessed and due now, or its permit pulled to now if later - one
     * {@code INSERT ... ON CONFLICT DO UPDATE} that <strong>waits</strong> on a recompute holding the head (never skips
     * it), so a recompute that read its inputs before the signal committed is followed by a permit due again.
     */
    void pullForward(Connection unitOfWork, UUID partyId);

    /**
     * The sweep's claim: one head whose permit has come on {@code clock_timestamp()}, oldest first,
     * {@code FOR UPDATE SKIP LOCKED} - a head another sweeper holds is skipped, never waited on.
     */
    Optional<CustomerRiskProfile> claimDue(Connection unitOfWork);

    /** The party's head, born unassessed if missing, then locked {@code FOR UPDATE} - waiting on a holder (R8). */
    CustomerRiskProfile lockOrCreate(Connection unitOfWork, UUID partyId);

    /** The party's head, no lock - the seam's, an evaluation's and the door's read. */
    Optional<CustomerRiskProfile> read(Connection unitOfWork, UUID partyId);

    /** A version, born once - {@code PRIMARY KEY (party_id, version)} and the in-sequence trigger arbitrate. */
    void insertVersion(Connection unitOfWork, CustomerRiskProfileVersion version);

    /**
     * The head moved from {@code fromVersion} to {@code toVersion} with {@code rating}, beside that version, stamped
     * recomputed and its permit re-stamped {@code permitDelay} after the database's clock - conditional on
     * {@code fromVersion} (the caller holds the lock; any other row count is a defect, thrown).
     */
    void advance(Connection unitOfWork, UUID partyId, int fromVersion, int toVersion, RiskRating rating,
            Duration permitDelay);

    /**
     * The permit re-stamped {@code permitDelay} after the database's clock with nothing else moved; {@code evaluated}
     * stamps {@code recomputed_at} too (an unchanged rating), otherwise not (no policy to judge with, or a prohibited
     * party the sweep never re-rates).
     */
    void restamp(Connection unitOfWork, UUID partyId, Duration permitDelay, boolean evaluated);

    /** Every version of the party's profile, oldest first. */
    List<CustomerRiskProfileVersion> versions(Connection unitOfWork, UUID partyId);

    /**
     * The version {@code dispositionId} wrote, if any (`P13-TSK-016`) - at most one, by
     * {@code customer_risk_profile_version_one_per_disposition}; the prohibition's idempotency under the head's lock.
     */
    Optional<CustomerRiskProfileVersion> versionOfDisposition(Connection unitOfWork, UUID dispositionId);

    /**
     * The heads by their current rating (`P13-TSK-024`; {@code PHASE_13_PLAN.md} section 15's
     * {@code finapp.risk.profile.rating{bucket}}): how many parties stand at each {@link RiskRating}, and how many are
     * born and never rated ({@code rating IS NULL} - no {@code MONITORING} version has judged them yet). One grouped count
     * on the caller's read-only snapshot, no lock; counts only - never a party.
     */
    Ratings ratings(Connection unitOfWork);

    /** The heads per rating, and the unrated ones. */
    record Ratings(Map<RiskRating, Long> rated, long unrated) {
        public Ratings {
            rated = Map.copyOf(Objects.requireNonNull(rated, "rated"));
            if (unrated < 0 || rated.values().stream().anyMatch(count -> count < 0)) {
                throw new IllegalArgumentException("a rating count counts from zero");
            }
        }

        /** The parties at {@code rating}, 0 for none. */
        public long at(RiskRating rating) {
            return rated.getOrDefault(Objects.requireNonNull(rating, "rating"), 0L);
        }
    }
}
