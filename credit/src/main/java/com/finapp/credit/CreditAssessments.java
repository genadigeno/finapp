package com.finapp.credit;

import com.finapp.platform.outbox.EventPayload;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.event.EventEnvelope;
import com.finapp.sharedkernel.event.EventId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Objects;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Assesses a snapshot, once (`P10-TSK-011`; {@code INV-CRD-06}, {@code INV-CRD-07}, {@code INV-CRD-04},
 * {@code INV-HIST-04}): affordability, exposure and the score, each from the snapshot alone, recorded in one
 * {@code credit_assessment} row that is born once per snapshot.
 *
 * <p><strong>The model is the snapshot's.</strong> The scorecard is the version the snapshot pinned, read by its id
 * whatever has been activated since - so an activation racing an assessment changes nothing it scores; a version that
 * was never active scores nothing. The engine version is the snapshot's too, and this build holds version 1 alone. The
 * terms - the stress rate, the payment ratio, the minimum disposable, the maximum exposure - are the pinned policy's,
 * handed in by the evaluation (`P10-TSK-013`).
 *
 * <p><strong>Ten assessors, one row.</strong> {@code INSERT ... ON CONFLICT (snapshot_id) DO NOTHING}, then read: every
 * caller answers with the one stored assessment, and only the writer publishes {@code credit.CreditAssessmentCreated}.
 * A second computation that disagrees with the stored one is a defect, never a silent replacement.
 */
@RequiredArgsConstructor
public final class CreditAssessments {

    /** The event the one writer publishes - never a figure. */
    static final String CREATED_EVENT = "credit.CreditAssessmentCreated";

    static final String AGGREGATE_TYPE = "credit_assessment";

    /** The engine versions this build can assess under. */
    static final int ENGINE_VERSION = 1;

    @NonNull private final CreditAssessmentStore assessments;
    @NonNull private final ScorecardStore scorecards;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** The pinned policy's terms the figures are judged against. */
    public record Terms(AffordabilityAssessment.Parameters affordability, Money maximumExposure) {
        public Terms {
            Objects.requireNonNull(affordability, "affordability");
            Objects.requireNonNull(maximumExposure, "maximumExposure");
        }

        /**
         * The terms {@code policy} states (`P10-TSK-013`): its stress rate and payment ratio from basis points, its
         * minimum disposable income and maximum exposure as written - the pinned policy's, handed to the assessment.
         */
        public static Terms of(CreditPolicy policy) {
            Objects.requireNonNull(policy, "policy");
            return new Terms(
                    new AffordabilityAssessment.Parameters(
                            BigDecimal.valueOf(policy.assessmentRateBps(), 4),
                            BigDecimal.valueOf(policy.minimumPaymentRatioBps(), 4),
                            policy.minimumDisposable()),
                    policy.maximumExposure());
        }
    }

    /** The outcome: the stored assessment, and whether this call wrote it. */
    public record Assessed(CreditAssessment assessment, boolean replayed) {}

    /** Assesses {@code snapshot} under {@code terms}, in the caller's unit of work. */
    public Assessed assess(Connection unitOfWork, DecisionSnapshot snapshot, Terms terms, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(terms, "terms");
        Objects.requireNonNull(correlation, "correlation");
        if (!snapshot.verifies()) {
            throw new IllegalStateException("the snapshot's text does not verify against its hash (INV-CRD-07)");
        }
        SnapshotContent content = snapshot.content();
        PinnedVersions versions = content.versions();
        if (versions.engineVersion() != ENGINE_VERSION
                || AffordabilityAssessment.ENGINE_VERSION != ENGINE_VERSION
                || ExposureAssessment.ENGINE_VERSION != ENGINE_VERSION) {
            throw new IllegalStateException("the snapshot pins engine version " + versions.engineVersion()
                    + ", which this build does not hold");
        }
        ScorecardStore.ModelVersion model = scorecards.model(unitOfWork, ScorecardModelVersionId.of(versions.modelVersion()))
                .orElseThrow(() -> new IllegalStateException("the snapshot pins a scorecard version that does not exist"));
        if (model.effectiveFrom().isEmpty()) {
            throw new IllegalStateException("the snapshot pins a scorecard version that was never active");
        }
        CreditAssessment computed = new CreditAssessment(
                CreditAssessmentId.next(ids),
                snapshot.id(),
                content.decisionRequest(),
                snapshot.sha256(),
                versions,
                content.product().currency(),
                AffordabilityAssessment.assess(content, terms.affordability()),
                ExposureAssessment.assess(content, terms.maximumExposure()),
                model.scorecard().score(content),
                clock.instant());
        boolean written = assessments.insert(unitOfWork, computed);
        CreditAssessment stored = assessments.bySnapshot(unitOfWork, snapshot.id())
                .orElseThrow(() -> new IllegalStateException("an assessment is readable once written"));
        if (!stored.sameFigures(computed)) {
            throw new IllegalStateException(
                    "the snapshot's stored assessment disagrees with this computation: an assessment is born once (INV-CRD-06)");
        }
        if (written) {
            outbox.write(
                    unitOfWork,
                    new EventEnvelope(
                            EventId.next(ids),
                            CREATED_EVENT,
                            1,
                            EventEnvelope.CURRENT_SCHEMA_VERSION,
                            stored.id(),
                            AGGREGATE_TYPE,
                            clock.instant(),
                            ScorecardAdministration.PRODUCER,
                            correlation,
                            CausationId.of(correlation.value())),
                    EventPayload.of()
                            .with("decisionRequestId", stored.decisionRequest().toString())
                            .with("snapshotSha256", HexFormat.of().formatHex(stored.snapshotSha256()))
                            .with("modelVersionId", versions.modelVersion().toString())
                            .toBytes(),
                    EventPayload.MEDIA_TYPE);
        }
        return new Assessed(stored, !written);
    }
}
