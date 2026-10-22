package com.finapp.risk;

import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A party's latest screening clearance, as kyc holds it (`P13-TSK-001`; {@code PHASE_13_PLAN.md} sections 3.1, 12.10;
 * ADR-0107 decision 3) - the Screening Result stays kyc's; risk reads a clearance through this port, which {@code app}
 * implements over kyc's screening ports, and records a signal ({@link RuleKind#SCREENING_HIT}). A bounded fact: the
 * screening's identifier, whether it was a hit, when it was decided - never kyc's row. First consumed by `-005`.
 */
@FunctionalInterface
public interface ScreeningClearances {

    /** The party's latest clearance - empty when the party was never screened. */
    Optional<Clearance> latestFor(Connection unitOfWork, UUID partyId);

    /** One clearance's bounded facts. */
    record Clearance(UUID screeningId, boolean hit, Instant decidedAt) {

        public Clearance {
            Objects.requireNonNull(screeningId, "screeningId");
            Objects.requireNonNull(decidedAt, "decidedAt");
        }
    }
}
