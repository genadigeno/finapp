package com.finapp.risk;

import java.sql.Connection;
import java.util.Objects;
import java.util.UUID;

/**
 * What a profile recompute reads about a party (`P13-TSK-007`; {@code PHASE_13_PLAN.md} section 12.5) - the
 * {@code MONITORING} evaluation's sealed inputs, filled from the sources this build has, on the recompute's unit of
 * work after the head's lock.
 *
 * <p><strong>What is read, and what is honestly {@code ABSENT}.</strong> The subject is the {@link SubjectKind#PARTY};
 * the screening hit is the party's latest clearance through {@link ScreeningClearances} ({@code ABSENT} when never
 * screened). Every other reading is {@code ABSENT} - "we did not look", recorded on each rule's hit - because its source
 * is another task's: the velocity windows (`-006`), the monitoring readings over the party's movements (`-020`, which
 * extends this reader). The signals themselves feed no reading here: no rule kind admitted for {@code MONITORING}
 * reads a signal ({@link RuleKind}'s scopes - `DESIGN-007` D10); a signal reaches the profile as the hint that pulls
 * its permit forward ({@link ProfileHints}).
 */
public final class ProfileInputs {

    private final ScreeningClearances clearances;

    public ProfileInputs(ScreeningClearances clearances) {
        this.clearances = Objects.requireNonNull(clearances, "clearances");
    }

    /** The sealed inputs of {@code partyId}'s {@code MONITORING} evaluation. */
    public EvaluationInputs of(Connection unitOfWork, UUID partyId) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(partyId, "partyId");
        EvaluationInputs.Builder inputs = EvaluationInputs.builder(RiskOperation.MONITORING)
                .subject(SignalSubject.of(SubjectKind.PARTY, partyId));
        clearances.latestFor(unitOfWork, partyId).ifPresent(clearance -> inputs.screeningHit(clearance.hit()));
        return inputs.build();
    }
}
