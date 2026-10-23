package com.finapp.risk;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A party's customer risk profile head (`P13-TSK-007`; {@code risk V006}'s {@code customer_risk_profile};
 * {@code RISK_LIFECYCLES.md} section 4.9; {@code INV-RSK-10}): the newest version's number and rating - version 0 and
 * no rating while the party is enrolled but not yet assessed - and the recompute's permit. The head always says what
 * its newest version says: the schema's trigger moves the two together, one step at a time.
 *
 * @param partyId the party
 * @param currentVersion the newest version's number; 0 when none exists
 * @param rating the newest version's rating; empty exactly when {@code currentVersion} is 0
 * @param nextRecomputeAt the permit - the sweep claims the head when this instant has come (the database's clock)
 * @param recomputedAt the database clock at the last evaluation; empty before the first
 */
public record CustomerRiskProfile(
        UUID partyId, int currentVersion, Optional<RiskRating> rating, Instant nextRecomputeAt,
        Optional<Instant> recomputedAt) {

    public CustomerRiskProfile {
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(rating, "rating");
        Objects.requireNonNull(nextRecomputeAt, "nextRecomputeAt");
        Objects.requireNonNull(recomputedAt, "recomputedAt");
        if (currentVersion < 0) {
            throw new IllegalArgumentException("a profile's version counts from 0");
        }
        if ((currentVersion == 0) != rating.isEmpty()) {
            throw new IllegalArgumentException("a profile is rated exactly when it has a version");
        }
    }

    /** Whether risk has assessed the party - a version exists. */
    public boolean assessed() {
        return currentVersion > 0;
    }

    /** Whether the party is {@link RiskRating#PROHIBITED} - which only a disposition sets or lifts. */
    public boolean prohibited() {
        return rating.filter(RiskRating::onlyByDisposition).isPresent();
    }

    /** Identifiers and the version only - never the rating ({@code INV-RSK-08}). */
    @Override
    public String toString() {
        return "CustomerRiskProfile[" + partyId + ", v" + currentVersion + "]";
    }
}
