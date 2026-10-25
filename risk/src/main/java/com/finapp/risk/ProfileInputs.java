package com.finapp.risk;

import java.sql.Connection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * What a profile recompute reads about a party (`P13-TSK-007`; {@code PHASE_13_PLAN.md} section 12.5) - the
 * {@code MONITORING} evaluation's sealed inputs, filled from the sources this build has, on the recompute's unit of
 * work after the head's lock.
 *
 * <p><strong>What is read, and what is honestly {@code ABSENT}.</strong> The subject is the {@link SubjectKind#PARTY};
 * the screening hit is the party's latest clearance through {@link ScreeningClearances} ({@code ABSENT} when never
 * screened). <strong>The account-takeover anchors</strong> (`P13-TSK-013`) - the credential change and the completed
 * recovery, when the pinned version judges them - are the latest such signals over the party's identities
 * ({@link RiskPartyIdentities}), judged at the database's instant ({@link VelocityWindows#now}); a party with no placeable
 * identity leaves them {@code ABSENT}. Every other reading is {@code ABSENT} - "we did not look", recorded on each
 * rule's hit - because its source is another task's to read for a party: `-006`'s velocity windows and the monitoring
 * readings over the party's movements, both `-020`'s, which extends this reader. Only the two anchors among the
 * {@code MONITORING} kinds read a signal ({@link RuleKind}'s scopes); every other signal reaches the profile as the hint
 * that pulls its permit forward ({@link ProfileHints}).
 */
public final class ProfileInputs {

    /** One party's reading: the sealed inputs, and the anchors they were taken from - the retrospective alert's. */
    public record Read(EvaluationInputs inputs, AccountTakeover.Anchors anchors) {
        public Read {
            Objects.requireNonNull(inputs, "inputs");
            Objects.requireNonNull(anchors, "anchors");
        }
    }

    private final ScreeningClearances clearances;
    private final SignalReadings signals;
    private final RiskPartyIdentities identities;
    private final VelocityWindows clock;

    /**
     * @param signals the anchors' signals
     * @param identities the party's identities - the join the anchors are read through
     * @param clock the database's instant the anchors' lookbacks are judged at (and `-020`'s windows)
     */
    public ProfileInputs(ScreeningClearances clearances, SignalReadings signals, RiskPartyIdentities identities,
            VelocityWindows clock) {
        this.clearances = Objects.requireNonNull(clearances, "clearances");
        this.signals = Objects.requireNonNull(signals, "signals");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The sealed inputs of {@code partyId}'s {@code MONITORING} evaluation, reading only what {@code judged} needs. */
    public Read read(Connection unitOfWork, UUID partyId, Set<RuleKind> judged) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(judged, "judged");
        EvaluationInputs.Builder inputs = EvaluationInputs.builder(RiskOperation.MONITORING)
                .subject(SignalSubject.of(SubjectKind.PARTY, partyId));
        clearances.latestFor(unitOfWork, partyId).ifPresent(clearance -> inputs.screeningHit(clearance.hit()));
        AccountTakeover.Anchors anchors = AccountTakeover.Anchors.none();
        if (judged.stream().anyMatch(AtoSequence.ANCHORS::contains)) {
            List<UUID> held = identities.identitiesOf(unitOfWork, partyId);
            if (held.size() > RiskPartyIdentities.BOUND) {
                throw new IllegalStateException("a party's identities are read at most " + RiskPartyIdentities.BOUND);
            }
            if (!held.isEmpty()) {
                anchors = AccountTakeover.Anchors.read(unitOfWork, signals, held, judged);
                anchors.applyTo(inputs, clock.now(unitOfWork));
            }
        }
        return new Read(inputs.build(), anchors);
    }
}
