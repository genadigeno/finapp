package com.finapp.risk;

import java.sql.Connection;
import java.util.EnumSet;
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
 * that pulls its permit forward ({@link ProfileHints}). <strong>The abuse readings</strong> (`P13-TSK-014`,
 * {@link PaymentAbuse#party}) - the party's refund and chargeback ratios (its signals over its {@code PARTY} window's
 * committed count, the bucket containing the database's instant) and its BNPL applications within the window - are read
 * when the pinned version carries those kinds; their signals pull the permit forward as every party signal does.
 */
public final class ProfileInputs {

    /**
     * One party's reading: the sealed inputs, and what they were taken from that a retrospective alert names - the
     * account-takeover anchors (`-013`) and the abuse readings' evidence (`-014`).
     */
    public record Read(EvaluationInputs inputs, AccountTakeover.Anchors anchors, PaymentAbuse.PartyRead abuse) {
        public Read {
            Objects.requireNonNull(inputs, "inputs");
            Objects.requireNonNull(anchors, "anchors");
            Objects.requireNonNull(abuse, "abuse");
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

    /**
     * The sealed inputs of {@code partyId}'s {@code MONITORING} evaluation under {@code policy} - what
     * {@link #read(Connection, UUID, Set)} reads, and the abuse readings its rules' windows name ({@link PaymentAbuse#party}:
     * the party's refund and chargeback ratios, its BNPL applications - `P13-TSK-014`), judged at the database's instant.
     */
    public Read read(Connection unitOfWork, UUID partyId, RiskPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        Set<RuleKind> judged = EnumSet.noneOf(RuleKind.class);
        for (RiskRule rule : policy.rules()) {
            judged.add(rule.kind());
        }
        return read(unitOfWork, partyId, judged, policy);
    }

    /**
     * The sealed inputs of {@code partyId}'s {@code MONITORING} evaluation, reading only what {@code judged} needs - the
     * kinds alone, so no abuse reading (its windows are the rules'): {@link #read(Connection, UUID, RiskPolicy)} reads it.
     */
    public Read read(Connection unitOfWork, UUID partyId, Set<RuleKind> judged) {
        return read(unitOfWork, partyId, judged, null);
    }

    private Read read(Connection unitOfWork, UUID partyId, Set<RuleKind> judged, RiskPolicy policy) {
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
        PaymentAbuse.PartyRead abuse = PaymentAbuse.PartyRead.none();
        if (policy != null && judged.stream().anyMatch(PaymentAbuse.KINDS::contains)) {
            abuse = PaymentAbuse.party(unitOfWork, signals, clock, policy, partyId, clock.now(unitOfWork));
            inputs.monitoring(abuse.readings());
        }
        return new Read(inputs.build(), anchors, abuse);
    }
}
