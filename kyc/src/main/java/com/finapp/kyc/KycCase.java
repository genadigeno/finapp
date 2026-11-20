package com.finapp.kyc;

import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One verification of one customer, from opening to a terminal decision state (`P2-TSK-005`).
 *
 * <p>The phase's spine, and the one authority for the question every financial phase will gate
 * on: {@code INV-KYC-05} makes the verification outcome this module's alone, with
 * {@code party.customer.status} a projection updated in <em>reaction</em> to the decision
 * ({@code P2-TSK-014}) and never computed independently.
 *
 * <h2>The transition rules live here</h2>
 *
 * <p>{@code INV-LIFE-02}: an invalid transition is rejected <em>by the aggregate</em>, not
 * merely unreachable through an API — because every aggregate is eventually driven by a second
 * caller (a consumer, a provider callback, an operator tool), and this one is <em>designed</em>
 * for second callers from birth. The {@code Customer} idiom throughout: no setter, no method
 * taking a target state, every state change a new instance.
 *
 * <h2>The customer reference is a value, not a foreign key</h2>
 *
 * <p>{@link #customerId()} is a raw {@code UUID} — the {@code Identity.partyId} pattern. No
 * database foreign key to {@code party.customer}, no compile-time dependency on the {@code party}
 * module; {@code KycModuleIsolationTest} asserts the second and the migration states the first.
 *
 * <h2>The policy version is pinned at open</h2>
 *
 * <p>{@code INV-HIST-04}'s rule at the moment it is free: which regime a case was assessed under
 * is a fact about the case, unrecoverable if not recorded when the case is born. The decision
 * ({@code P2-TSK-013}) pins the same version again on its own record.
 *
 * <h2>The kind is fixed at open, like the policy version</h2>
 *
 * <p>{@code KYC} or {@code KYB} (`P2-TSK-015`): one machine, two kinds — see
 * {@link KycCaseKind} for why the kind is a column rather than a second aggregate, and why it
 * is immutable at {@code DB-PRIVILEGE} (a flip would disarm the ownership gate).
 */
public final class KycCase {

    private final KycCaseId id;
    private final UUID customerId;
    private final KycCaseKind kind;
    private final KycCaseStatus status;
    private final KycPolicyVersion policyVersion;
    private final Instant openedAt;
    private final Instant statusChangedAt;

    private KycCase(
            KycCaseId id,
            UUID customerId,
            KycCaseKind kind,
            KycCaseStatus status,
            KycPolicyVersion policyVersion,
            Instant openedAt,
            Instant statusChangedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.customerId = Objects.requireNonNull(customerId, "customerId must not be null");
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.policyVersion =
                Objects.requireNonNull(policyVersion, "policyVersion must not be null");
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt must not be null");
        this.statusChangedAt =
                Objects.requireNonNull(statusChangedAt, "statusChangedAt must not be null");
    }

    /** Opens a case for a customer, {@code OPEN}, under the current policy regime. */
    public static KycCase open(IdGenerator ids, Clock clock, UUID customerId, KycCaseKind kind) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Instant now = Instant.now(clock);
        return new KycCase(
                KycCaseId.next(ids),
                customerId,
                kind,
                KycCaseStatus.OPEN,
                KycPolicyVersion.CURRENT,
                now,
                now);
    }

    /** Reconstitutes from storage. Applies no transition rules: the row was already valid. */
    public static KycCase rehydrate(
            KycCaseId id,
            UUID customerId,
            KycCaseKind kind,
            KycCaseStatus status,
            KycPolicyVersion policyVersion,
            Instant openedAt,
            Instant statusChangedAt) {
        return new KycCase(id, customerId, kind, status, policyVersion, openedAt, statusChangedAt);
    }

    /** {@code OPEN → CHECKS_IN_PROGRESS}: the first check was dispatched. */
    public KycCase beginChecks(Clock clock) {
        return apply(KycCaseTransition.BEGIN_CHECKS, clock);
    }

    /**
     * {@code CHECKS_IN_PROGRESS → IN_REVIEW}: a check {@code HIT}, or went indeterminate past
     * its retry budget. A person resolves it ({@code INV-KYC-04}); silence resolves nothing.
     * This edge alone: a case awaiting its customer returns to review by {@link #resumeReview}.
     */
    public KycCase requireReview(Clock clock) {
        return apply(KycCaseTransition.CHECKS_RAISE_REVIEW, clock);
    }

    /**
     * {@code IN_REVIEW → AWAITING_CUSTOMER}: a reviewer asked the customer for more information
     * (`P17-TSK-126`, ADR-0155). The request itself is an {@link InformationRequest}, written in
     * the same transaction as this move.
     */
    public KycCase awaitCustomer(Clock clock) {
        return apply(KycCaseTransition.INFORMATION_REQUESTED, clock);
    }

    /**
     * {@code AWAITING_CUSTOMER → IN_REVIEW}, by {@code why}: the customer answered, or the
     * request's deadline passed. Either way the reviewer has the case back; nothing is decided.
     *
     * @throws IllegalArgumentException {@code why} is not one of the two returns
     */
    public KycCase resumeReview(KycCaseTransition why, Clock clock) {
        Objects.requireNonNull(why, "why must not be null");
        if (why != KycCaseTransition.CUSTOMER_ANSWERED && why != KycCaseTransition.REQUEST_EXPIRED) {
            throw new IllegalArgumentException("a case returns to review by an answer or a lapse, not " + why);
        }
        return apply(why, clock);
    }

    /**
     * {@code → READY_FOR_DECISION}, from checks all clear or from every review task resolved.
     * Being ready is not being decided: the decision is a separate recorded act
     * ({@code P2-TSK-013}, {@code INV-KYC-02}). Never from {@code AWAITING_CUSTOMER}.
     */
    public KycCase readyForDecision(Clock clock) {
        return apply(
                status == KycCaseStatus.IN_REVIEW
                        ? KycCaseTransition.REVIEW_COMPLETE
                        : KycCaseTransition.CHECKS_CLEAR,
                clock);
    }

    /** {@code READY_FOR_DECISION → APPROVED}. Terminal ({@code INV-LIFE-04}). */
    public KycCase approve(Clock clock) {
        return apply(KycCaseTransition.APPROVE, clock);
    }

    /** {@code READY_FOR_DECISION → REJECTED}. Terminal ({@code INV-LIFE-04}). */
    public KycCase reject(Clock clock) {
        return apply(KycCaseTransition.REJECT, clock);
    }

    /**
     * Applies one named edge of the table ({@link KycCaseTransition}): refused unless the case
     * stands exactly at the edge's source ({@code INV-LIFE-02}), so no method reaches a status by
     * another edge's row.
     */
    private KycCase apply(KycCaseTransition transition, Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        KycCaseStatus target = transition.to();
        if (status != transition.from() || !status.canTransitionTo(target)) {
            throw new IllegalKycCaseTransitionException(id, status, target);
        }
        // max(now, latest) - ADR-0063 decision 2 (X-TSK-006): the database orders the case's
        // facts, and a stamp never contradicts that order. A callback, a reviewer or a decision
        // moves the case on whichever instance serves it, whose clock may read behind the one
        // that stamped the previous move (ADR-0014: skew is bounded, never zero), so the later
        // fact is stamped no earlier than statusChangedAt - never before openedAt. The store's
        // two statements clamp the same way.
        Instant now = Instant.now(clock);
        return new KycCase(
                id,
                customerId,
                kind,
                target,
                policyVersion,
                openedAt,
                now.isBefore(statusChangedAt) ? statusChangedAt : now);
    }

    public KycCaseId id() {
        return id;
    }

    /** The customer under verification. Fixed for the life of the case; a value, never an FK. */
    public UUID customerId() {
        return customerId;
    }

    /** Which variant of verification this is. Fixed for the life of the case. */
    public KycCaseKind kind() {
        return kind;
    }

    public KycCaseStatus status() {
        return status;
    }

    /** The policy regime this case is assessed under. Fixed at open ({@code INV-HIST-04}). */
    public KycPolicyVersion policyVersion() {
        return policyVersion;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public Instant statusChangedAt() {
        return statusChangedAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KycCase kycCase && id.equals(kycCase.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** Carries no personal data: identifiers, a status and a policy label. */
    @Override
    public String toString() {
        return "KycCase[" + id + ", customer=" + customerId + ", " + kind + ", " + status + "]";
    }
}
