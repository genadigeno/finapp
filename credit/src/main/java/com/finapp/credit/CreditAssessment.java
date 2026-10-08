package com.finapp.credit;

import com.finapp.sharedkernel.money.CurrencyCode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A decision's three figures, from one snapshot (`P10-TSK-011`; {@code credit V007}; {@code INV-CRD-06},
 * {@code INV-CRD-04}): affordability, exposure and the score, under the snapshot's pinned policy, model and engine
 * versions - born once per snapshot. The score is a figure of the assessment, never a decision.
 *
 * @param snapshotSha256 the snapshot's digest - the inputs' reference
 * @param currency the product's one currency, in which every assessed figure is
 */
public record CreditAssessment(
        CreditAssessmentId id,
        DecisionSnapshotId snapshot,
        UUID decisionRequest,
        byte[] snapshotSha256,
        PinnedVersions versions,
        CurrencyCode currency,
        AffordabilityAssessment.Assessment affordability,
        ExposureAssessment.Assessment exposure,
        int score,
        Instant assessedAt) {

    public CreditAssessment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(decisionRequest, "decisionRequest");
        Objects.requireNonNull(snapshotSha256, "snapshotSha256");
        Objects.requireNonNull(versions, "versions");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(affordability, "affordability");
        Objects.requireNonNull(exposure, "exposure");
        Objects.requireNonNull(assessedAt, "assessedAt");
        snapshotSha256 = snapshotSha256.clone();
    }

    @Override
    public byte[] snapshotSha256() {
        return snapshotSha256.clone();
    }

    /** Whether {@code other}'s figures are these - the same snapshot, versions and three figures. */
    public boolean sameFigures(CreditAssessment other) {
        return snapshot.equals(other.snapshot)
                && versions.equals(other.versions)
                && currency.equals(other.currency)
                && affordability.equals(other.affordability)
                && exposure.equals(other.exposure)
                && score == other.score;
    }

    /** No figure renders. */
    @Override
    public String toString() {
        return "CreditAssessment[" + id + ", snapshot=" + snapshot + "]";
    }
}
