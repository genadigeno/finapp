package com.finapp.risk;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One customer risk rating, born once (`P13-TSK-007`; {@code risk V006}'s {@code customer_risk_profile_version};
 * {@code RISK_LIFECYCLES.md} section 4.9; {@code INV-RSK-10}, {@code INV-RSK-07}, {@code INV-HIST-04}): the party, the
 * version's number, the rating, what set it and why - a {@link ProfileCause#RECOMPUTE} carries its whole
 * {@link Evaluation} (the pinned {@code MONITORING} version, the engine, the signal score and the sealed inputs), a
 * {@link ProfileCause#DISPOSITION} the approved disposition (`-016`) - the ordered reason codes, the flow that wrote it
 * and the database's stamp once stored.
 *
 * <p><strong>Held together by construction</strong>, as the table's {@code CHECK}s hold it: a recompute carries an
 * evaluation and no disposition; a disposition a disposition and no evaluation; {@link RiskRating#PROHIBITED} only by a
 * disposition.
 *
 * @param computedAt the database clock at the insert - empty before the row is stored
 */
public record CustomerRiskProfileVersion(
        UUID partyId,
        int version,
        RiskRating rating,
        ProfileCause cause,
        List<ReasonCode> reasons,
        Optional<Evaluation> evaluation,
        Optional<UUID> dispositionId,
        String correlationId,
        Optional<Instant> computedAt) {

    public CustomerRiskProfileVersion {
        Objects.requireNonNull(partyId, "partyId");
        Objects.requireNonNull(rating, "rating");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(reasons, "reasons");
        Objects.requireNonNull(evaluation, "evaluation");
        Objects.requireNonNull(dispositionId, "dispositionId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(computedAt, "computedAt");
        reasons = List.copyOf(reasons);
        if (version < 1) {
            throw new IllegalArgumentException("a profile version counts from 1");
        }
        if (correlationId.isEmpty() || correlationId.length() > 128) {
            throw new IllegalArgumentException("a correlation is 1 to 128 characters");
        }
        switch (cause) {
            case RECOMPUTE -> {
                if (evaluation.isEmpty() || dispositionId.isPresent()) {
                    throw new IllegalArgumentException("a recompute carries its evaluation and no disposition");
                }
                if (rating.onlyByDisposition()) {
                    // INV-RSK-07: the bands never set it, so a recompute never records it.
                    throw new IllegalArgumentException("a recompute never sets " + rating + ": only a disposition does");
                }
            }
            case DISPOSITION -> {
                if (dispositionId.isEmpty() || evaluation.isPresent()) {
                    throw new IllegalArgumentException("a disposition carries its disposition and no evaluation");
                }
            }
        }
    }

    /**
     * The evaluation a recompute version records - enough to replay its rating ({@code INV-HIST-04}): the pinned
     * {@code MONITORING} version by id and number, the engine, the party's signal score (risk's number, never answered
     * to credit - {@code INV-CRD-04}), and the sealed inputs in {@link CanonicalEvaluation}'s form with their SHA-256.
     */
    public record Evaluation(
            RiskPolicyVersionId policyVersionId,
            int policyVersion,
            RiskEngine engine,
            int signalScore,
            String inputsCanonical,
            String inputsSha256) {

        public Evaluation {
            Objects.requireNonNull(policyVersionId, "policyVersionId");
            Objects.requireNonNull(engine, "engine");
            Objects.requireNonNull(inputsCanonical, "inputsCanonical");
            Objects.requireNonNull(inputsSha256, "inputsSha256");
            if (policyVersion < 1) {
                throw new IllegalArgumentException("a policy version counts from 1");
            }
            if (signalScore < 0 || signalScore > ScoreBand.CEILING) {
                throw new IllegalArgumentException("a signal score is 0 to " + ScoreBand.CEILING);
            }
            if (!inputsSha256.equals(CanonicalEvaluation.sha256(inputsCanonical))) {
                throw new IllegalArgumentException("the inputs' digest is their own");
            }
        }

        /** Never the inputs or the score: they describe a customer ({@code INV-RSK-08}). */
        @Override
        public String toString() {
            return "Evaluation[" + policyVersionId.value() + " v" + policyVersion + ", " + engine + "]";
        }
    }

    /** Identifiers and the version only - never the rating or the reasons ({@code INV-RSK-08}). */
    @Override
    public String toString() {
        return "CustomerRiskProfileVersion[" + partyId + ", v" + version + ", " + cause + "]";
    }
}
