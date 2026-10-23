package com.finapp.app.risk;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.AssessmentReplay;
import com.finapp.risk.ReasonCode;
import com.finapp.risk.RiskAssessment;
import com.finapp.risk.RiskAssessmentId;
import com.finapp.risk.RiskAssessments;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskErrorCode;
import com.finapp.risk.RiskReasons;
import com.finapp.risk.RiskSpans;
import com.finapp.risk.RuleHit;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The analyst's risk assessment doors (`P13-TSK-004`; {@code PHASE_13_PLAN.md} sections 9, 11; behind
 * {@code RISK_INVESTIGATE}; bnpl's {@code BnplInvestigationDesk} and `-007`'s {@code RiskProfileDesk} shape).
 *
 * <ul>
 *   <li><strong>The read</strong> - one transaction: the self-dealing judgement, the assessment whole, and
 *       {@code risk.AssessmentRead} committed with it before the answer, so nothing is served unrecorded. A read, so no
 *       idempotency key: a repeated read is a second serving, recorded again.</li>
 *   <li><strong>The replay</strong> - a blank or instrument-shaped reason refused ({@code 422 risk.ReasonRequired}) before
 *       anything is read; the replay on one read-only {@code REPEATABLE READ} snapshot; then {@code risk.Replayed}
 *       committed in a transaction of its own - only then is the verdict answered. It writes nothing else.</li>
 * </ul>
 *
 * <p>An analyst of the assessment's own party - or one the platform cannot place in a party - is {@code 403
 * risk.SelfDealingRefused}, the attempt recorded {@code FAILED} in a transaction of its own; an unknown assessment, or a
 * malformed id, is the uniform {@code 404 risk.NotFound}. The answer carries the sealed inputs, the hits, the score and
 * the reasons - an operator's surface only, never a customer's ({@code INV-RSK-08}); none of it is logged.
 */
public final class RiskAssessmentDesk {

    private final RiskAssessments assessments;
    private final RiskTransactions transactions;
    private final RiskFlowScope flow;

    public RiskAssessmentDesk(RiskAssessments assessments, RiskTransactions transactions, RiskFlowScope flow) {
        this.assessments = Objects.requireNonNull(assessments, "assessments");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.flow = Objects.requireNonNull(flow, "flow");
    }

    /**
     * One sealed assessment: the command and the movement's subjects, the judged amount (a decimal string in its
     * currency), the pinned policy version and engine, the verdict - score, decision, every rule's hit, the ordered
     * reason codes, the fail-safe's reason and cause - the challenge's answer, what the windows did and when, the sealed
     * inputs (CanonicalEvaluation format 1) with their digest, the verdict's digest and whether the rows still render to
     * it. Instants are ISO-8601 UTC.
     */
    public record RiskAssessmentView(String assessmentId, String operation, String commandRef, String partyId,
            String identityId, String sourceAccountId, String destinationKind, String destinationId, String deviceHash,
            String merchantId, String amount, String currency, String policyVersionId, Integer policyVersion,
            String engine, int score, String decision, String failSafeReason, String unavailableCause,
            Boolean challengeSatisfied, String velocityOutcome, String velocityCutAt, int canonicalFormat,
            String inputsCanonical, String inputsSha256, String verdictSha256, boolean sealHolds,
            List<RiskRuleHitView> hits, List<String> reasonCodes, long latencyMicros, String correlationId,
            String assessedAt) {}

    /** One rule of the pinned version as the assessment judged it - the reading it judged, never a threshold. */
    public record RiskRuleHitView(int ordinal, String kind, String outcome, int contribution, String hardDecision,
            String judged) {}

    /** A replay's verdict: what diverged, by kind, and the first rule that did - never a figure. */
    public record RiskReplayView(String assessmentId, String verdict, List<String> diverged, Integer firstDivergedRule) {}

    /** {@code rawId}'s assessment, read and recorded. */
    public RiskAssessmentView read(String rawId) {
        RiskAssessmentId id = assessmentId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        RiskAssessment assessment;
        try {
            assessment = flow.door(RiskSpans.EXPLAIN, () -> transactions.inTransaction(
                    unitOfWork -> assessments.read(unitOfWork, id, actor, correlation)));
        } catch (RiskAssessments.SelfDealingRefused refused) {
            throw refused(id, refused, actor, correlation);
        } catch (RiskAssessments.AssessmentNotFound none) {
            throw notFound();
        }
        return view(assessment);
    }

    /** {@code rawId}'s assessment replayed with {@code reason}, the verdict answered once recorded. */
    public RiskReplayView replay(String rawId, String reason) {
        RiskAssessmentId id = assessmentId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        String reasoned;
        try {
            reasoned = RiskAssessments.reasoned(reason);
        } catch (RiskReasons.ReasonRequired missing) {
            throw new ApiException(RiskErrorCode.REASON_REQUIRED, "A risk assessment replay without a usable reason",
                    "a replay is reasoned: 1 to " + RiskReasons.BOUND + " characters, no card or account number.");
        }
        AssessmentReplay.Replay replay;
        try {
            replay = flow.door(RiskSpans.EXPLAIN, () -> transactions.inSnapshot(
                    snapshot -> assessments.replay(snapshot, id, actor)));
        } catch (RiskAssessments.SelfDealingRefused refused) {
            throw refused(id, refused, actor, correlation);
        } catch (RiskAssessments.AssessmentNotFound none) {
            throw notFound();
        }
        // Recorded before it is answered, in a transaction of its own: a replay nobody recorded was never served.
        transactions.inTransaction(unitOfWork -> {
            assessments.recordReplay(unitOfWork, replay, reasoned, actor, correlation);
            return null;
        });
        return new RiskReplayView(replay.assessment().value().toString(), replay.verdict().name(),
                replay.diverged().stream().map(Enum::name).toList(),
                replay.firstDivergedRule().isPresent() ? replay.firstDivergedRule().getAsInt() : null);
    }

    private ApiException refused(RiskAssessmentId id, RiskAssessments.SelfDealingRefused refused, Actor actor,
            CorrelationId correlation) {
        // The act was abandoned; the attempt is recorded FAILED in a transaction of its own, so it survives.
        transactions.inTransaction(unitOfWork -> {
            assessments.recordRefusal(unitOfWork, id, refused, actor, correlation);
            return null;
        });
        return new ApiException(RiskErrorCode.SELF_DEALING_REFUSED, "A risk assessment read was refused",
                "a holder never reads a risk assessment of their own party; nothing was served.");
    }

    static RiskAssessmentView view(RiskAssessment assessment) {
        RiskAssessment.Subjects subjects = assessment.subjects();
        return new RiskAssessmentView(
                assessment.id().value().toString(),
                assessment.operation().name(),
                assessment.commandRef().value(),
                subjects.party().toString(),
                subjects.identity().map(UUID::toString).orElse(null),
                subjects.sourceAccount().map(UUID::toString).orElse(null),
                subjects.destination().map(to -> to.kind().name()).orElse(null),
                subjects.destination().map(to -> to.id().toString()).orElse(null),
                subjects.device().map(device -> device.value()).orElse(null),
                subjects.merchant().map(UUID::toString).orElse(null),
                assessment.amount().toBigDecimal().toPlainString(),
                assessment.amount().currency().code(),
                assessment.pin().map(pin -> pin.id().value().toString()).orElse(null),
                assessment.pin().map(RiskAssessment.Pin::version).orElse(null),
                assessment.engine().name(),
                assessment.score().value(),
                assessment.decision().name(),
                assessment.failSafeReason().map(ReasonCode::name).orElse(null),
                assessment.unavailable().map(Enum::name).orElse(null),
                assessment.challengeSatisfied().orElse(null),
                assessment.velocity().name(),
                assessment.cutAt().map(Instant::toString).orElse(null),
                com.finapp.risk.CanonicalEvaluation.FORMAT,
                assessment.inputsCanonical(),
                assessment.inputsSha256(),
                assessment.verdictSha256(),
                assessment.sealHolds(),
                assessment.hits().stream().map(RiskAssessmentDesk::hit).toList(),
                assessment.reasons().stream().map(ReasonCode::name).toList(),
                assessment.latencyMicros(),
                assessment.correlationId(),
                assessment.assessedAt().map(Instant::toString).orElse(null));
    }

    private static RiskRuleHitView hit(RuleHit hit) {
        return new RiskRuleHitView(hit.ordinal(), hit.kind().name(), hit.outcome().name(), hit.contribution(),
                hit.hardDecision().map(RiskDecision::name).orElse(null), hit.judged());
    }

    private static RiskAssessmentId assessmentId(String raw) {
        try {
            return RiskAssessmentId.of(UUID.fromString(Objects.requireNonNull(raw, "raw")));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(RiskErrorCode.NOT_FOUND, "No risk assessment answers the request", "no such record.");
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a risk assessment read runs inside a correlation scope"))
                .correlationId();
    }
}
