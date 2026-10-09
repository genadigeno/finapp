package com.finapp.credit;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import com.finapp.sharedkernel.money.Money;
import java.io.Serial;
import java.sql.Connection;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The manual review (`P10-TSK-018`; ADR-0089, CREDIT_DECISIONING_LIFECYCLES.md section 3.3; {@code INV-CRD-11},
 * {@code INV-CRD-09}, {@code INV-CRD-02}, {@code INV-CRD-06}, {@code INV-AUD-04}): the underwriter's acts on a referral's
 * case, each on the caller's unit of work (one keyed act, one transaction), each taking the lock order's subsequence -
 * <strong>never the case alone</strong>:
 *
 * <ul>
 *   <li><strong>assign</strong> ({@code OPEN -> ASSIGNED}) and <strong>release</strong> ({@code ASSIGNED -> OPEN}): the
 *       request (element (2)) then the case (element (3)), conditional on an {@code IN_REVIEW} request and the case's state
 *       - so an assignment and the request's expiry, which takes the same two locks in the same order, leave exactly one of
 *       {@code ASSIGNED}, {@code EXPIRED}; of two underwriters, one is assigned and the other {@link CaseTaken};
 *   <li><strong>decide</strong>: a decline, or an approval at or below the product's four-eyes threshold, runs the
 *       deciding transaction ({@link DecisionMaking}) profile-first - profile, request, case - conditional on the case
 *       held by the person, <em>not</em> on the request's validity, so a taken case is never stuck; an approval above the
 *       threshold records the first decision on the case ({@code AWAITING_SECOND}) under the request and case locks;
 *   <li><strong>the second approval</strong>: a different underwriter approves (the deciding transaction, recording the
 *       first decision's content) or refuses with a reason ({@code AWAITING_SECOND -> ASSIGNED}, back to the first
 *       underwriter, the refused decision kept in the case's history).
 * </ul>
 *
 * <p><strong>What a person may not do</strong>: decide without a reason code and a reason; approve a request whose
 * evaluation triggered a hard decline; approve more than the evaluation allows - the referral's {@link #ceiling} - or
 * beyond the party's exposure limit re-read under the profile lock ({@link ExposureLimitExceeded}: nothing recorded, the
 * case unchanged, the person decides again); second-approve their own decision. A party whose standing or consent was
 * lost is found in the deciding transaction: the request is {@code ABANDONED} and the case {@code CLOSED} with it,
 * nothing decided.
 */
@RequiredArgsConstructor
public final class UnderwritingCases {

    static final String CASE_TARGET = "underwriting_case";
    static final String QUEUE_TARGET = "review_queue";
    /**
     * A person's decision's {@code decided_by_type} - the decision table's word for a person, whatever type the acting
     * session's actor carries (a session's actor is typed by the platform, not by the role that admits it).
     */
    static final String PERSON = com.finapp.platform.security.ActorType.EMPLOYEE.name();
    /** The most cases one queue read serves. */
    public static final int QUEUE_LIMIT = 50;

    @NonNull private final UnderwritingCaseStore cases;
    @NonNull private final DecisionRequestStore requests;
    @NonNull private final CreditProfiles<Connection> profiles;
    @NonNull private final DecisionMaking deciding;
    @NonNull private final DecisionSnapshotStore snapshots;
    @NonNull private final PolicyEvaluationStore evaluations;
    @NonNull private final CreditPolicyStore policies;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    // ------------------------------------------------------------------ the vocabulary

    /** A person's decision as asked: the outcome, an approval's amount, the catalogued reason codes and a reason. */
    public record Judgement(DecisionOutcome outcome, Optional<Money> approved, List<ReasonCode> reasons, String reason) {

        public Judgement {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(approved, "approved");
            Objects.requireNonNull(reasons, "reasons");
            reasons = List.copyOf(reasons);
        }

        @Override
        public String toString() {
            return "Judgement[" + outcome + "]";
        }
    }

    /** What an act left: the case as it now stands, and the decision it recorded with its after-commit observation. */
    public record Acted(
            UnderwritingCase reviewCase, Optional<CreditDecision> decision, Optional<DecisionMaking.Recorded> recorded) {}

    /** Tells the decision observer of an act's recorded decision - the caller's step once its transaction commits. */
    public void observe(Acted acted) {
        acted.recorded().ifPresent(deciding::observe);
    }

    /** One queued case with its basis: the snapshot's normalised attributes and every rule's result, never raw evidence. */
    public record Queued(UnderwritingCase reviewCase, List<CreditAttribute> attributes, List<QueuedRule> rules) {}

    /** A rule of the pinned policy and what it did in the basis evaluation. */
    public record QueuedRule(int ordinal, String ruleCode, PolicyEffect effect, ReasonCode reason,
            EvaluationResult.RuleState state) {}

    /** No such case. */
    public static final class CaseNotFound extends RuntimeException {
        @Serial private static final long serialVersionUID = 1L;

        CaseNotFound() {
            super("no such review case");
        }
    }

    /** The case is held by another underwriter, or has moved on from the state the act needs. */
    public static final class CaseTaken extends RuntimeException {
        @Serial private static final long serialVersionUID = 1L;

        CaseTaken(String detail) {
            super(detail);
        }
    }

    /** The second approver made the first decision ({@code INV-CRD-11}, {@code INV-AUD-04}). */
    public static final class SelfApprovalRefused extends RuntimeException {
        @Serial private static final long serialVersionUID = 1L;

        SelfApprovalRefused() {
            super("the second approval is someone other than the first decider's");
        }
    }

    /** A person's approval of a request whose evaluation triggered a hard decline (ADR-0089 point 2). */
    public static final class HardDeclineNotOverridable extends RuntimeException {
        @Serial private static final long serialVersionUID = 1L;

        HardDeclineNotOverridable() {
            super("the evaluation triggered a hard decline: a person may decline it, never approve it");
        }
    }

    /** Above the evaluation's ceiling or beyond the exposure limit (G7): nothing recorded. */
    public static final class ExposureLimitExceeded extends RuntimeException {
        @Serial private static final long serialVersionUID = 1L;

        ExposureLimitExceeded(String detail) {
            super(detail);
        }
    }

    /** A decision or a refusal without its reason code or its reason ({@code INV-CRD-11}). */
    public static final class ReasonRequired extends RuntimeException {
        @Serial private static final long serialVersionUID = 1L;

        ReasonRequired(String detail) {
            super(detail);
        }
    }

    /** A judgement that is not well formed - an approval without a positive amount in the product's currency, a decline with one. */
    public static final class JudgementInvalid extends RuntimeException {
        @Serial private static final long serialVersionUID = 1L;

        JudgementInvalid(String detail) {
            super(detail);
        }
    }

    // ------------------------------------------------------------------ the queue

    /** At most {@link #QUEUE_LIMIT} cases, oldest first, each with its basis - the serving recorded in {@code unitOfWork}. */
    public List<Queued> queue(
            Connection unitOfWork, Optional<UnderwritingCaseStatus> status, Actor actor, CorrelationId correlation) {
        List<Queued> queued = new ArrayList<>();
        for (UnderwritingCase reviewCase : cases.queue(unitOfWork, status, QUEUE_LIMIT)) {
            SnapshotContent basis = CanonicalSnapshot.parse(snapshots.snapshotById(unitOfWork, reviewCase.basisSnapshot())
                    .orElseThrow(() -> new IllegalStateException("a case's basis names a snapshot that exists"))
                    .canonical());
            PolicyEvaluation evaluation = evaluations.byAssessment(unitOfWork, reviewCase.basisAssessment())
                    .orElseThrow(() -> new IllegalStateException("a case's basis is evaluated"));
            List<CreditPolicy.PolicyRule> frozen = policies.policy(unitOfWork, evaluation.policyVersion())
                    .orElseThrow(() -> new IllegalStateException("a basis pins a policy that exists"))
                    .policy()
                    .rules();
            List<QueuedRule> rules = new ArrayList<>();
            for (EvaluationResult.RuleResult result : evaluation.result().rules()) {
                rules.add(new QueuedRule(result.ordinal(), result.ruleCode(), result.effect(),
                        frozen.get(result.ordinal() - 1).reason(), result.state()));
            }
            queued.add(new Queued(reviewCase, basis.attributes(), rules));
        }
        record(unitOfWork, actor, CreditAuditAction.REVIEW_CASES_READ, QUEUE_TARGET,
                status.map(Enum::name).orElse("ALL"), Optional.empty(), AuditOutcome.SUCCEEDED, correlation,
                "review queue read: " + queued.size() + " case(s), status " + status.map(Enum::name).orElse("any"));
        return queued;
    }

    // ------------------------------------------------------------------ assign and release

    /** {@code OPEN -> ASSIGNED} to {@code actor}, under the request then the case. */
    public Acted assign(Connection unitOfWork, UnderwritingCaseId id, Actor actor, CorrelationId correlation) {
        UnderwritingCase found = find(unitOfWork, id);
        DecisionRequest request = lockRequest(unitOfWork, found);
        UnderwritingCase locked = lockCase(unitOfWork, id);
        if (locked.status() != UnderwritingCaseStatus.OPEN || request.status() != DecisionRequestStatus.IN_REVIEW) {
            throw new CaseTaken("the case is not open to take - it is " + locked.status());
        }
        move(unitOfWork, locked, new UnderwritingCaseStore.Edge(UnderwritingCaseStatus.ASSIGNED, Optional.of(actor.id()),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), actor);
        record(unitOfWork, actor, CreditAuditAction.REVIEW_CASE_ASSIGNED, CASE_TARGET, id.value().toString(),
                Optional.empty(), AuditOutcome.SUCCEEDED, correlation, "review case " + id.value() + " taken");
        return new Acted(find(unitOfWork, id), Optional.empty(), Optional.empty());
    }

    /** {@code ASSIGNED -> OPEN}, by the underwriter who holds it. */
    public Acted release(Connection unitOfWork, UnderwritingCaseId id, Actor actor, CorrelationId correlation) {
        UnderwritingCase found = find(unitOfWork, id);
        lockRequest(unitOfWork, found);
        UnderwritingCase locked = lockCase(unitOfWork, id);
        if (locked.status() != UnderwritingCaseStatus.ASSIGNED || !locked.heldBy(actor.id())) {
            throw new CaseTaken("only the underwriter holding an assigned case releases it");
        }
        move(unitOfWork, locked, new UnderwritingCaseStore.Edge(UnderwritingCaseStatus.OPEN, Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()), actor);
        record(unitOfWork, actor, CreditAuditAction.REVIEW_CASE_RELEASED, CASE_TARGET, id.value().toString(),
                Optional.empty(), AuditOutcome.SUCCEEDED, correlation, "review case " + id.value() + " released");
        return new Acted(find(unitOfWork, id), Optional.empty(), Optional.empty());
    }

    // ------------------------------------------------------------------ decide

    /**
     * The holder's decision: a decline, or an approval at or below the threshold, recorded by the deciding transaction;
     * an approval above it recorded on the case, {@code AWAITING_SECOND}.
     */
    public Acted decide(
            Connection unitOfWork, UnderwritingCaseId id, Judgement judgement, Actor actor, CorrelationId correlation) {
        reasoned(judgement.reasons(), judgement.reason());
        UnderwritingCase found = find(unitOfWork, id);
        wellFormed(judgement, found);
        boolean fourEyes = judgement.outcome() == DecisionOutcome.APPROVED
                && judgement.approved().orElseThrow().compareTo(found.fourEyesThreshold()) > 0;
        if (fourEyes) {
            DecisionRequest request = lockRequest(unitOfWork, found);
            UnderwritingCase locked = held(lockCase(unitOfWork, id), request, actor);
            if (hardDeclined(basisOf(unitOfWork, locked))) {
                throw new HardDeclineNotOverridable();
            }
            if (judgement.approved().orElseThrow().compareTo(locked.approvable()) > 0) {
                throw new ExposureLimitExceeded("the approval is above the referral's ceiling");
            }
            move(unitOfWork, locked, new UnderwritingCaseStore.Edge(UnderwritingCaseStatus.AWAITING_SECOND,
                    Optional.of(actor.id()), Optional.of(firstWrite(judgement, actor.id())), Optional.empty(),
                    Optional.empty(), Optional.empty()), actor);
            record(unitOfWork, actor, CreditAuditAction.REVIEW_DECIDED, CASE_TARGET, id.value().toString(),
                    Optional.of(judgement.reason()), AuditOutcome.SUCCEEDED, correlation,
                    "review case " + id.value() + " approved above the four-eyes threshold: awaiting a second underwriter");
            return new Acted(find(unitOfWork, id), Optional.empty(), Optional.empty());
        }
        lockProfile(unitOfWork, found);
        DecisionRequest request = lockRequest(unitOfWork, found);
        UnderwritingCase locked = held(lockCase(unitOfWork, id), request, actor);
        return decideWithin(unitOfWork, request, locked, firstWrite(judgement, actor.id()), false, actor, correlation,
                CreditAuditAction.REVIEW_DECIDED, Optional.of(judgement.reason()));
    }

    // ------------------------------------------------------------------ the second approval

    /** A different underwriter approves the first decision: the deciding transaction records it. */
    public Acted approveSecond(
            Connection unitOfWork, UnderwritingCaseId id, Optional<String> reason, Actor actor, CorrelationId correlation) {
        UnderwritingCase found = find(unitOfWork, id);
        lockProfile(unitOfWork, found);
        DecisionRequest request = lockRequest(unitOfWork, found);
        UnderwritingCase locked = awaitingSecond(lockCase(unitOfWork, id), request, actor);
        UnderwritingCase.FirstDecision first = locked.first().orElseThrow();
        return decideWithin(unitOfWork, request, locked, UnderwritingCaseStore.FirstWrite.of(first), true, actor,
                correlation, CreditAuditAction.REVIEW_SECOND_APPROVAL, reason.filter(text -> !text.isBlank()));
    }

    /** A different underwriter refuses the first decision, with a reason: back to the first underwriter. */
    public Acted refuseSecond(
            Connection unitOfWork, UnderwritingCaseId id, String reason, Actor actor, CorrelationId correlation) {
        if (reason == null || reason.isBlank()) {
            throw new ReasonRequired("refusing a second approval requires a reason");
        }
        UnderwritingCase found = find(unitOfWork, id);
        DecisionRequest request = lockRequest(unitOfWork, found);
        UnderwritingCase locked = awaitingSecond(lockCase(unitOfWork, id), request, actor);
        move(unitOfWork, locked, new UnderwritingCaseStore.Edge(UnderwritingCaseStatus.ASSIGNED, locked.assignee(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(reason)), actor);
        record(unitOfWork, actor, CreditAuditAction.REVIEW_SECOND_APPROVAL_REFUSED, CASE_TARGET, id.value().toString(),
                Optional.of(reason), AuditOutcome.SUCCEEDED, correlation,
                "review case " + id.value() + ": the second approval refused - back to its first underwriter");
        return new Acted(find(unitOfWork, id), Optional.empty(), Optional.empty());
    }

    // ------------------------------------------------------------------ the person's deciding transaction

    /**
     * Under the profile, request and case locks: the deciding transaction's basis (standing and consent re-read, the
     * pinned versions, the reservation re-read), the person's bounds, then the decision, the request {@code DECIDED} and
     * the case {@code DECIDED} - or, a party's standing or consent lost, the request {@code ABANDONED} and the case
     * {@code CLOSED}, nothing decided.
     */
    private Acted decideWithin(
            Connection unitOfWork,
            DecisionRequest request,
            UnderwritingCase locked,
            UnderwritingCaseStore.FirstWrite first,
            boolean second,
            Actor actor,
            CorrelationId correlation,
            CreditAuditAction act,
            Optional<String> reason) {
        DecisionMaking.Basis basis = deciding.basisWithin(unitOfWork, request, actor, correlation);
        if (basis instanceof DecisionMaking.Basis.Abandoned abandoned) {
            move(unitOfWork, locked, closure(abandoned.reason().name(), locked), actor);
            record(unitOfWork, actor, act, CASE_TARGET, locked.id().value().toString(), reason, AuditOutcome.FAILED,
                    correlation, "review case " + locked.id().value() + ": nothing decided - the request was abandoned ("
                            + abandoned.reason().name() + ") and the case closed");
            return new Acted(find(unitOfWork, locked.id()), Optional.empty(), Optional.empty());
        }
        DecisionMaking.Basis.Ready ready = (DecisionMaking.Basis.Ready) basis;
        if (first.outcome() == DecisionOutcome.APPROVED) {
            bounded(unitOfWork, locked, ready, first.approved().orElseThrow());
        }
        DecisionMaking.Recorded recorded = deciding.recordWithin(unitOfWork, request, DecisionRequestStatus.IN_REVIEW, ready,
                        first.outcome(), first.approved(), first.reasons(), first.decidedBy(), PERSON, actor, correlation)
                .orElseThrow(() -> new CaseTaken("the request was decided first"));
        move(unitOfWork, locked, new UnderwritingCaseStore.Edge(UnderwritingCaseStatus.DECIDED, locked.assignee(),
                Optional.of(first), second ? Optional.of(actor.id()) : Optional.empty(), Optional.empty(), reason), actor);
        record(unitOfWork, actor, act, CASE_TARGET, locked.id().value().toString(), reason, AuditOutcome.SUCCEEDED,
                correlation, "review case " + locked.id().value() + " decided: " + first.outcome().name() + " (decision "
                        + recorded.decision().id().value() + ")");
        return new Acted(find(unitOfWork, locked.id()), Optional.of(recorded.decision()), Optional.of(recorded));
    }

    /**
     * The person's bounds (ADR-0089 point 2, G7, {@code INV-CRD-09}): never a hard decline - in the case's basis or in a
     * successor's evaluation; never above the evaluation's ceiling; never beyond the exposure limit, judged on the
     * deciding snapshot (its reservation re-read under the profile lock) with the person's amount.
     */
    private void bounded(Connection unitOfWork, UnderwritingCase locked, DecisionMaking.Basis.Ready ready, Money approved) {
        EvaluationResult deciding = ready.evaluation().result();
        if (hardDeclined(basisOf(unitOfWork, locked)) || hardDeclined(deciding)) {
            throw new HardDeclineNotOverridable();
        }
        Money ceiling = lesser(locked.approvable(), ceiling(deciding, ready.policy().policy()));
        if (approved.compareTo(ceiling) > 0) {
            throw new ExposureLimitExceeded("the approval is above the referral's ceiling");
        }
        if (exposure(ready.snapshot().content(), approved).compareTo(ready.policy().policy().maximumExposure()) > 0) {
            throw new ExposureLimitExceeded("the approval is beyond the party's exposure limit");
        }
    }

    /**
     * The exposure a person's approval of {@code approved} would make (ADR-0088 section 2 with the person's amount): the
     * platform's outstanding credit and reserved exposure - always present - plus the bureau's total balance when it was
     * read. An absent bureau balance is the referral's question, which the person answers; the platform's own terms are
     * still bound by the limit.
     */
    static Money exposure(SnapshotContent snapshot, Money approved) {
        Money exposure = money(snapshot, CreditAttributeCode.PLATFORM_OUTSTANDING_CREDIT).orElseThrow()
                .plus(money(snapshot, CreditAttributeCode.PLATFORM_RESERVED_EXPOSURE).orElseThrow())
                .plus(approved);
        Optional<Money> bureau = money(snapshot, CreditAttributeCode.BUREAU_TOTAL_BALANCE);
        return bureau.isPresent() ? exposure.plus(bureau.get()) : exposure;
    }

    /**
     * The referral's ceiling (G7, as built): the requested amount capped by every {@code CAP_AMOUNT} rule the evaluation
     * triggered - the most the evaluation's rules let through. The auto-approval ceiling is not one: it bounds an
     * automated approval, and a person's approval above the product's four-eyes threshold meets a second person instead.
     */
    static Money ceiling(EvaluationResult evaluation, CreditPolicy policy) {
        Money ceiling = evaluation.requested();
        for (EvaluationResult.RuleResult result : evaluation.rules()) {
            if (result.effect() == PolicyEffect.CAP_AMOUNT && result.state().triggered()) {
                ceiling = lesser(ceiling, policy.rules().get(result.ordinal() - 1).cap().orElseThrow());
            }
        }
        return ceiling;
    }

    /** The edge that closes {@code locked} with its request's reason, its first decision carried. */
    static UnderwritingCaseStore.Edge closure(String reason, UnderwritingCase locked) {
        return new UnderwritingCaseStore.Edge(UnderwritingCaseStatus.CLOSED, locked.assignee(),
                locked.first().map(UnderwritingCaseStore.FirstWrite::of), Optional.empty(), Optional.of(reason),
                Optional.empty());
    }

    // ------------------------------------------------------------------ plumbing

    private static boolean hardDeclined(EvaluationResult evaluation) {
        return evaluation.outcome() == EvaluationOutcome.HARD_DECLINE || evaluation.rules().stream()
                .anyMatch(rule -> rule.effect() == PolicyEffect.HARD_DECLINE && rule.state().triggered());
    }

    private EvaluationResult basisOf(Connection unitOfWork, UnderwritingCase reviewCase) {
        return evaluations.byAssessment(unitOfWork, reviewCase.basisAssessment())
                .orElseThrow(() -> new IllegalStateException("a case's basis is evaluated"))
                .result();
    }

    private static void reasoned(List<ReasonCode> reasons, String reason) {
        if (reasons.isEmpty()) {
            throw new ReasonRequired("a person's decision carries at least one reason code");
        }
        if (reason == null || reason.isBlank()) {
            throw new ReasonRequired("a person's decision carries a reason");
        }
        if (new HashSet<>(reasons).size() != reasons.size()) {
            throw new ReasonRequired("a reason code is given once");
        }
        if (reasons.stream().anyMatch(code -> !code.adverse())) {
            // CRD-AUTO-APPROVAL-CEILING speaks of an automated approval, never of a person's judgement.
            throw new ReasonRequired("a person's reason codes are the catalogue's codes about the applicant");
        }
    }

    private static void wellFormed(Judgement judgement, UnderwritingCase reviewCase) {
        if (judgement.outcome() == DecisionOutcome.DECLINED) {
            if (judgement.approved().isPresent()) {
                throw new JudgementInvalid("a decline approves no amount");
            }
            return;
        }
        Money amount = judgement.approved().orElseThrow(() -> new JudgementInvalid("an approval approves an amount"));
        if (!amount.currency().equals(reviewCase.requested().currency()) || !amount.isPositive()) {
            throw new JudgementInvalid("an approval approves a positive amount in the product's currency");
        }
        if (amount.compareTo(reviewCase.product().minimumAmount()) < 0) {
            // The product's published bounds (ADR-0084 section 6): an approval below its minimum is no offer of it.
            throw new JudgementInvalid("an approval is at least the product's minimum amount");
        }
    }

    private static UnderwritingCaseStore.FirstWrite firstWrite(Judgement judgement, String by) {
        return new UnderwritingCaseStore.FirstWrite(judgement.outcome(), judgement.approved(), judgement.reasons(),
                judgement.reason(), by);
    }

    /** The case held by {@code actor} under an {@code IN_REVIEW} request - else taken. */
    private static UnderwritingCase held(UnderwritingCase locked, DecisionRequest request, Actor actor) {
        if (locked.status() != UnderwritingCaseStatus.ASSIGNED || !locked.heldBy(actor.id())
                || request.status() != DecisionRequestStatus.IN_REVIEW) {
            throw new CaseTaken("only the underwriter holding an assigned case decides it");
        }
        return locked;
    }

    /** The case awaiting a second underwriter who is not its first decider. */
    private static UnderwritingCase awaitingSecond(UnderwritingCase locked, DecisionRequest request, Actor actor) {
        if (locked.status() != UnderwritingCaseStatus.AWAITING_SECOND || request.status() != DecisionRequestStatus.IN_REVIEW) {
            throw new CaseTaken("the case is not awaiting a second approval - it is " + locked.status());
        }
        if (locked.first().orElseThrow().decidedBy().equals(actor.id())) {
            throw new SelfApprovalRefused();
        }
        return locked;
    }

    private UnderwritingCase find(Connection unitOfWork, UnderwritingCaseId id) {
        return cases.byId(unitOfWork, id).orElseThrow(CaseNotFound::new);
    }

    /** Element (1), first - a deciding act only. */
    private void lockProfile(Connection unitOfWork, UnderwritingCase reviewCase) {
        if (profiles.lockForDecision(unitOfWork, reviewCase.party()).isEmpty()) {
            throw new IllegalStateException("a case's party has a profile");
        }
    }

    /** Element (2). */
    private DecisionRequest lockRequest(Connection unitOfWork, UnderwritingCase reviewCase) {
        return requests.lock(unitOfWork, reviewCase.decisionRequest())
                .orElseThrow(() -> new IllegalStateException("a case's request exists"))
                .request();
    }

    /** Element (3). */
    private UnderwritingCase lockCase(Connection unitOfWork, UnderwritingCaseId id) {
        return cases.lock(unitOfWork, id).orElseThrow(CaseNotFound::new);
    }

    private void move(Connection unitOfWork, UnderwritingCase from, UnderwritingCaseStore.Edge edge, Actor actor) {
        if (!cases.move(unitOfWork, from, edge, actor)) {
            throw new IllegalStateException("the locked case moved under its own lock");
        }
    }

    private void record(
            Connection unitOfWork,
            Actor actor,
            CreditAuditAction action,
            String targetType,
            String targetId,
            Optional<String> reason,
            AuditOutcome outcome,
            CorrelationId correlation,
            String summary) {
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), action, targetType, targetId,
                reason, outcome, correlation, Optional.of(summary)));
    }

    private static Optional<Money> money(SnapshotContent snapshot, CreditAttributeCode code) {
        return snapshot.attribute(code).value() instanceof AttributeValue.MoneyValue money
                ? Optional.of(money.value())
                : Optional.empty();
    }

    private static Money lesser(Money a, Money b) {
        return b.compareTo(a) < 0 ? b : a;
    }
}
