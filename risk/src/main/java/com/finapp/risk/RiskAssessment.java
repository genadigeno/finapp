package com.finapp.risk;

import com.finapp.sharedkernel.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One sealed risk assessment (`P13-TSK-004`; {@code risk V007}; {@code RISK_LIFECYCLES.md} section 4.2;
 * {@code INV-RSK-01}, {@code INV-RSK-04}) - born {@code DECIDED} by T-eval in the judged command's transaction and never
 * changed: the command, the movement's subjects, the judged amount, the pinned policy version and engine, the
 * verdict (score, decision, every rule's hit, the ordered reasons, whether the fail-safe applied and why), the
 * challenge's answer, what the windows did with the movement and when, the sealed inputs with their digest and the
 * verdict's digest. A value: the store writes it whole and reads it whole.
 *
 * <p><strong>Held together by construction</strong>, as {@code risk V007}'s {@code CHECK}s hold every writer: a fail-safe
 * carries its reason and its cause together, and names a policy version unless none could be pinned; a challenge's
 * answer exactly for a {@code CHALLENGE}; a refused movement never counted; the digests the canonical texts' own.
 */
public record RiskAssessment(
        RiskAssessmentId id,
        RiskOperation operation,
        CommandRef commandRef,
        Subjects subjects,
        Money amount,
        Optional<Pin> pin,
        RiskEngine engine,
        Score score,
        RiskDecision decision,
        Optional<UnavailableCause> unavailable,
        Optional<Boolean> challengeSatisfied,
        VelocityOutcome velocity,
        Optional<Instant> cutAt,
        String inputsCanonical,
        String inputsSha256,
        String verdictSha256,
        List<RuleHit> hits,
        List<ReasonCode> reasons,
        long latencyMicros,
        String correlationId,
        String causationId,
        Optional<Instant> assessedAt) {

    /** The pinned policy version, by id and number. */
    public record Pin(RiskPolicyVersionId id, int version) {
        public Pin {
            Objects.requireNonNull(id, "id");
            if (version < 1) {
                throw new IllegalArgumentException("a policy version is numbered from 1");
            }
        }
    }

    /** The movement's subjects - exactly the windows' (`RiskEvaluationRequest#movement`). */
    public record Subjects(UUID party, Optional<UUID> identity, Optional<UUID> sourceAccount,
            Optional<RiskEvaluationRequest.Destination> destination, Optional<DeviceHash> device,
            Optional<UUID> merchant) {
        public Subjects {
            Objects.requireNonNull(party, "party");
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(sourceAccount, "sourceAccount");
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(device, "device");
            Objects.requireNonNull(merchant, "merchant");
        }

        /** {@code request}'s subjects. */
        public static Subjects of(RiskEvaluationRequest request) {
            return new Subjects(request.party(), request.identity(), request.sourceAccount(), request.destination(),
                    request.device(), request.merchant());
        }

        /** The velocity movement of {@code amount} these subjects make. */
        public VelocityMovement movement(Money amount) {
            VelocityMovement.Builder movement = VelocityMovement.of(amount).party(party);
            sourceAccount.ifPresent(movement::sourceAccount);
            destination.ifPresent(to -> {
                if (to.kind() == RiskEvaluationRequest.DestinationKind.ACCOUNT) {
                    movement.destinationAccount(to.id());
                } else {
                    movement.destinationBeneficiary(to.id());
                }
            });
            device.ifPresent(movement::device);
            merchant.ifPresent(movement::merchant);
            return movement.build();
        }

        @Override
        public String toString() {
            return "Subjects";
        }
    }

    public RiskAssessment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(commandRef, "commandRef");
        Objects.requireNonNull(subjects, "subjects");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(pin, "pin");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(score, "score");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(unavailable, "unavailable");
        Objects.requireNonNull(challengeSatisfied, "challengeSatisfied");
        Objects.requireNonNull(velocity, "velocity");
        Objects.requireNonNull(cutAt, "cutAt");
        Objects.requireNonNull(inputsCanonical, "inputsCanonical");
        Objects.requireNonNull(inputsSha256, "inputsSha256");
        Objects.requireNonNull(verdictSha256, "verdictSha256");
        hits = List.copyOf(Objects.requireNonNull(hits, "hits"));
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(causationId, "causationId");
        Objects.requireNonNull(assessedAt, "assessedAt");
        if (!operation.judgesAMovement() || !decision.isAdmittedBy(operation)) {
            throw new IllegalArgumentException(operation + " is judged, and admits the sealed decision");
        }
        if (pin.isPresent() == unavailable.map(UnavailableCause::unpinned).orElse(false)) {
            throw new IllegalArgumentException("a policy version is pinned unless none could be");
        }
        if (unavailable.isPresent() && (score.value() != 0 || !hits.isEmpty() || reasons.size() != 1
                || reasons.get(0) != unavailable.get().reason())) {
            throw new IllegalArgumentException("a fail-safe evaluated no rule and carries exactly its reason");
        }
        if (challengeSatisfied.isPresent() != (decision == RiskDecision.CHALLENGE)) {
            throw new IllegalArgumentException("a challenge's answer is a CHALLENGE's alone");
        }
        boolean permits = VelocityWindows.permits(decision, challengeSatisfied.orElse(false));
        if ((velocity == VelocityOutcome.NOT_PERMITTED) == permits) {
            throw new IllegalArgumentException("a refused movement is never counted; a permitted one is, or says not");
        }
        if (velocity == VelocityOutcome.COUNTED && cutAt.isEmpty()) {
            throw new IllegalArgumentException("a counted movement names its cut");
        }
        if (latencyMicros < 0) {
            throw new IllegalArgumentException("a latency is never negative");
        }
        if (!CanonicalEvaluation.sha256(inputsCanonical).equals(inputsSha256)) {
            throw new IllegalArgumentException("the inputs' digest is their own");
        }
    }

    /** The fail-safe's reason, when the fail-safe answered. */
    public Optional<ReasonCode> failSafeReason() {
        return unavailable.map(UnavailableCause::reason);
    }

    /** True when the decision lets the movement proceed - {@code ALLOW}, or a satisfied {@code CHALLENGE}. */
    public boolean permits() {
        return VelocityWindows.permits(decision, challengeSatisfied.orElse(false));
    }

    /** The verdict these rows are - what {@link #verdictSha256} seals. */
    public Verdict verdict() {
        return new Verdict(operation, engine, hits, score, Optional.of(decision), Optional.empty(), reasons,
                unavailable.isPresent());
    }

    /** True when the rows still re-render to the sealed verdict's digest, and the inputs to theirs. */
    public boolean sealHolds() {
        try {
            return CanonicalEvaluation.sha256(CanonicalEvaluation.render(verdict())).equals(verdictSha256)
                    && CanonicalEvaluation.sha256(inputsCanonical).equals(inputsSha256);
        } catch (IllegalArgumentException incoherent) {
            return false;
        }
    }

    /** The velocity movement this assessment judged. */
    public VelocityMovement movement() {
        return subjects.movement(amount);
    }

    @Override
    public String toString() {
        // Identifiers and codes only: the subjects, the amount and the inputs are CONFIDENTIAL (INV-RSK-08).
        return "RiskAssessment[" + id.value() + ", " + operation + ", " + decision
                + unavailable.map(cause -> ", fail-safe " + cause).orElse("") + "]";
    }
}
