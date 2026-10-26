package com.finapp.app.risk;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.AssessmentReplay;
import com.finapp.risk.DailyReplay;
import com.finapp.risk.Explanation;
import com.finapp.risk.ReasonCode;
import com.finapp.risk.RiskAssessment;
import com.finapp.risk.RiskAssessmentId;
import com.finapp.risk.RiskAssessments;
import com.finapp.risk.RiskDay;
import com.finapp.risk.RiskDecision;
import com.finapp.risk.RiskErrorCode;
import com.finapp.risk.RiskProofs;
import com.finapp.risk.RiskReasons;
import com.finapp.risk.RiskSpans;
import com.finapp.risk.RuleHit;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The analyst's risk assessment doors (`P13-TSK-004`; {@code PHASE_13_PLAN.md} sections 9, 11; behind
 * {@code RISK_INVESTIGATE}; bnpl's {@code BnplInvestigationDesk} and `-007`'s {@code RiskProfileDesk} shape).
 *
 * <ul>
 *   <li><strong>The read</strong> - one transaction: the self-dealing judgement, the assessment whole, its explanation
 *       (`P13-TSK-023`: from the sealed rows and the pinned version's band list, never the engine) and
 *       {@code risk.AssessmentRead} committed with it before the answer, so nothing is served unrecorded. A read, so no
 *       idempotency key: a repeated read is a second serving, recorded again.</li>
 *   <li><strong>The replay</strong> - a blank or instrument-shaped reason refused ({@code 422 risk.ReasonRequired}) before
 *       anything is read; the replay on one read-only {@code REPEATABLE READ} snapshot; then {@code risk.Replayed}
 *       committed in a transaction of its own - only then is the verdict answered. It writes nothing else.</li>
 *   <li><strong>The day's replay</strong> (`P13-TSK-023`) - a date that is no calendar date, a cursor that is not one of
 *       the day's assessments or a limit below one {@code 422 api.ValidationFailed}, and a blank reason {@code 422
 *       risk.ReasonRequired}, before anything is read; a bounded page of the UTC day's assessments, each replayed under
 *       its own pinned pair on one snapshot whose every statement is bounded ({@link DailyReplay},
 *       {@link RiskProofs#bound}); then {@code risk.Replayed} for the page committed in a transaction of its own; a
 *       reader the platform cannot place {@code 403 risk.SelfDealingRefused}, recorded {@code FAILED}. A retry repeats
 *       one bounded page - no lock, nothing to stack across instances.</li>
 * </ul>
 *
 * <p>An analyst of the assessment's own party - or one the platform cannot place in a party - is {@code 403
 * risk.SelfDealingRefused}, the attempt recorded {@code FAILED} in a transaction of its own; an unknown assessment, or a
 * malformed id, is the uniform {@code 404 risk.NotFound}. The answer carries the sealed inputs, the hits, the score and
 * the reasons - an operator's surface only, never a customer's ({@code INV-RSK-08}); none of it is logged.
 */
public final class RiskAssessmentDesk {

    private final RiskAssessments assessments;
    private final DailyReplay daily;
    private final RiskTransactions transactions;
    private final RiskFlowScope flow;
    private final Duration statementTimeout;

    /** The doors, a day replay's snapshot bounded by {@link RiskProofs#STATEMENT_TIMEOUT}. */
    public RiskAssessmentDesk(RiskAssessments assessments, DailyReplay daily, RiskTransactions transactions,
            RiskFlowScope flow) {
        this(assessments, daily, transactions, flow, RiskProofs.STATEMENT_TIMEOUT);
    }

    /** The doors, a day replay's snapshot armed with {@code statementTimeout} ({@link RiskProofs#bound}). */
    public RiskAssessmentDesk(RiskAssessments assessments, DailyReplay daily, RiskTransactions transactions,
            RiskFlowScope flow, Duration statementTimeout) {
        this.assessments = Objects.requireNonNull(assessments, "assessments");
        this.daily = Objects.requireNonNull(daily, "daily");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.flow = Objects.requireNonNull(flow, "flow");
        this.statementTimeout = Objects.requireNonNull(statementTimeout, "statementTimeout");
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
            String assessedAt, RiskExplanationView explanation) {}

    /** One rule of the pinned version as the assessment judged it - the reading it judged, never a threshold. */
    public record RiskRuleHitView(int ordinal, String kind, String outcome, int contribution, String hardDecision,
            String judged) {}

    /**
     * Why the sealed decision is what it is (`P13-TSK-023`; {@link Explanation}): the step that decided
     * ({@code FAIL_SAFE}, {@code HARD_RULE}, {@code SCORE_BAND}), the rules that carried a hard decision, the band
     * reached by position and decision (never its bounds), each rule in order with its reading and contribution, the
     * fail-safe when it answered, the challenge's answer and whether the movement was permitted - from the sealed rows,
     * never a re-evaluation. Absent on the override desk's read of the same assessment.
     */
    public record RiskExplanationView(String step, String decision, int score, RiskExplainedBandView band,
            List<Integer> decidingRules, List<RiskExplainedRuleView> rules, RiskFailSafeView failSafe,
            Boolean challengeSatisfied, boolean permits, String velocityOutcome) {}

    /** A band by its position among its version's bands and its decision. */
    public record RiskExplainedBandView(int position, int of, String decision) {}

    /** One rule as it was judged. */
    public record RiskExplainedRuleView(int ordinal, String kind, String outcome, String judged, int contribution,
            String hardDecision) {}

    /** The fail-safe that answered: its cause, its reason code and its band when a version was pinned. */
    public record RiskFailSafeView(String cause, String reasonCode, RiskExplainedBandView band) {}

    /** A replay's verdict: what diverged, by kind, and the first rule that did - never a figure. */
    public record RiskReplayView(String assessmentId, String verdict, List<String> diverged, Integer firstDivergedRule) {}

    /**
     * A page of a day's replay (`P13-TSK-023`): the date, whether the platform had it, the page's verdict, the counts
     * per operation, the reader's own party's divergent assessments counted and withheld, the first divergent of the
     * page the reader may be shown - an id and kinds, never a figure - the cursor it continued from ({@code after}) and
     * the one to continue with ({@code next}, null on the day's last page). The day is {@code IDENTICAL} when every page
     * is.
     */
    public record RiskDayReplayView(String date, boolean known, String verdict, long replayed, long identical,
            long diverged, long withheld, List<RiskDayReplayOperationView> operations,
            RiskDayReplayFirstView firstDiverged, String after, String next) {}

    /** One operation's replays of the day. */
    public record RiskDayReplayOperationView(String operation, long replayed, long identical, long diverged) {}

    /** The first divergent assessment of the day: its id, its operation, what diverged and the first rule that did. */
    public record RiskDayReplayFirstView(String assessmentId, String operation, List<String> diverged,
            Integer firstDivergedRule) {}

    /** {@code rawId}'s assessment, read, explained and recorded - one transaction. */
    public RiskAssessmentView read(String rawId) {
        RiskAssessmentId id = assessmentId(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        Explained explained;
        try {
            explained = flow.door(RiskSpans.EXPLAIN, () -> transactions.inTransaction(unitOfWork -> {
                RiskAssessment assessment = assessments.read(unitOfWork, id, actor, correlation);
                return new Explained(assessment, assessments.explanation(unitOfWork, assessment));
            }));
        } catch (RiskAssessments.SelfDealingRefused refused) {
            throw refused(id, refused, actor, correlation);
        } catch (RiskAssessments.AssessmentNotFound none) {
            throw notFound();
        }
        return view(explained.assessment(), explanation(explained.explanation()));
    }

    /** The day's first page, {@link DailyReplay#DEFAULT_LIMIT} assessments at most. */
    public RiskDayReplayView replayDay(String rawDate, String reason) {
        return replayDay(rawDate, null, null, reason);
    }

    /**
     * At most {@code limit} assessments of {@code rawDate} (UTC) after the assessment {@code rawAfter}, replayed under
     * their own pinned pairs with {@code reason}, on one bounded snapshot, the page answered once recorded
     * ({@code risk.Replayed}, the day its target). A cursor that is no assessment id of the day, or a limit below one, is
     * {@code 422 api.ValidationFailed} before anything is read; a limit above {@link DailyReplay#MAX_LIMIT} is answered
     * with that many.
     */
    public RiskDayReplayView replayDay(String rawDate, String rawAfter, String rawLimit, String reason) {
        RiskDay day = RiskReportDates.day(rawDate, "A risk day replay");
        Optional<RiskAssessmentId> after = cursor(rawAfter, day);
        int bounded = limit(rawLimit);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        String reasoned;
        try {
            reasoned = RiskAssessments.reasoned(reason);
        } catch (RiskReasons.ReasonRequired missing) {
            throw new ApiException(RiskErrorCode.REASON_REQUIRED, "A risk day replay without a usable reason",
                    "a replay is reasoned: 1 to " + RiskReasons.BOUND + " characters, no card or account number.");
        }
        DailyReplay.Result result;
        try {
            result = flow.door(RiskSpans.EXPLAIN, () -> transactions.inSnapshot(snapshot -> {
                RiskProofs.bound(snapshot, statementTimeout);
                return daily.replay(snapshot, day, actor, after, bounded);
            }));
        } catch (RiskAssessments.SelfDealingRefused refused) {
            transactions.inTransaction(unitOfWork -> {
                daily.recordRefusal(unitOfWork, day, refused, actor, correlation);
                return null;
            });
            throw new ApiException(RiskErrorCode.SELF_DEALING_REFUSED, "A risk day replay was refused",
                    "a holder the platform cannot place in a party replays nothing; nothing was served.");
        }
        transactions.inTransaction(unitOfWork -> {
            daily.record(unitOfWork, result, reasoned, actor, correlation);
            return null;
        });
        return new RiskDayReplayView(result.day().toString(), result.known(), result.verdict().name(),
                result.replayed(), result.identical(), result.diverged(), result.withheld(),
                result.operations().stream().map(operation -> new RiskDayReplayOperationView(
                        operation.operation().name(), operation.replayed(), operation.identical(),
                        operation.diverged())).toList(),
                result.first().map(first -> new RiskDayReplayFirstView(first.assessment().value().toString(),
                        first.operation().name(), first.diverged().stream().map(Enum::name).toList(),
                        first.firstDivergedRule().isPresent() ? first.firstDivergedRule().getAsInt() : null))
                        .orElse(null),
                result.after().map(id -> id.value().toString()).orElse(null),
                result.next().map(id -> id.value().toString()).orElse(null));
    }

    /** {@code raw} as a page's limit - {@link DailyReplay#DEFAULT_LIMIT} when none is given; not a count 422. */
    private static int limit(String raw) {
        if (raw == null) {
            return DailyReplay.DEFAULT_LIMIT;
        }
        if (raw.matches("^[0-9]{1,9}$") && Integer.parseInt(raw) >= 1) {
            return Integer.parseInt(raw);
        }
        throw new ApiException(PlatformErrorCode.VALIDATION_FAILED, "A risk day replay was asked for a page that is"
                + " not a count", "limit is 1 to " + DailyReplay.MAX_LIMIT + "; a larger one is answered with "
                + DailyReplay.MAX_LIMIT + ".");
    }

    /** {@code raw} as a cursor of {@code day} - absent when none is given; anything else 422 before any read. */
    private static Optional<RiskAssessmentId> cursor(String raw, RiskDay day) {
        if (raw == null) {
            return Optional.empty();
        }
        try {
            UUID id = UUID.fromString(raw);
            if (raw.length() == 36 && day.holds(id)) {
                return Optional.of(RiskAssessmentId.of(id));
            }
        } catch (IllegalArgumentException malformed) {
            // Falls through to the refusal.
        }
        throw new ApiException(PlatformErrorCode.VALIDATION_FAILED, "A risk day replay was asked to continue from a"
                + " cursor that is not one of the day's", "after must be the next of the day's previous page.");
    }

    private record Explained(RiskAssessment assessment, Explanation explanation) {}

    static RiskExplanationView explanation(Explanation explanation) {
        return new RiskExplanationView(
                explanation.step().name(),
                explanation.decision().name(),
                explanation.score(),
                explanation.band().map(RiskAssessmentDesk::band).orElse(null),
                explanation.decidingRules(),
                explanation.rules().stream().map(rule -> new RiskExplainedRuleView(rule.ordinal(), rule.kind().name(),
                        rule.outcome().name(), rule.judged(), rule.contribution(),
                        rule.hardDecision().map(Enum::name).orElse(null))).toList(),
                explanation.failSafe().map(failSafe -> new RiskFailSafeView(failSafe.cause().name(),
                        failSafe.reason().name(), failSafe.band().map(RiskAssessmentDesk::band).orElse(null)))
                        .orElse(null),
                explanation.challengeSatisfied().orElse(null),
                explanation.permits(),
                explanation.velocity().name());
    }

    private static RiskExplainedBandView band(Explanation.Band band) {
        return new RiskExplainedBandView(band.position(), band.of(), band.decision().name());
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

    /** {@code assessment} as served without its explanation - the override desk's read. */
    static RiskAssessmentView view(RiskAssessment assessment) {
        return view(assessment, null);
    }

    static RiskAssessmentView view(RiskAssessment assessment, RiskExplanationView explanation) {
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
                assessment.assessedAt().map(Instant::toString).orElse(null),
                explanation);
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
