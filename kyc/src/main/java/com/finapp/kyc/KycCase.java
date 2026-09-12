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
        return transitionTo(KycCaseStatus.CHECKS_IN_PROGRESS, clock);
    }

    /**
     * {@code CHECKS_IN_PROGRESS → IN_REVIEW}: a check {@code HIT}, or went indeterminate past
     * its retry budget. A person resolves it ({@code INV-KYC-04}); silence resolves nothing.
     */
    public KycCase requireReview(Clock clock) {
        return transitionTo(KycCaseStatus.IN_REVIEW, clock);
    }

    /**
     * {@code → READY_FOR_DECISION}, from checks all clear or from every review task resolved.
     * Being ready is not being decided: the decision is a separate recorded act
     * ({@code P2-TSK-013}, {@code INV-KYC-02}).
     */
    public KycCase readyForDecision(Clock clock) {
        return transitionTo(KycCaseStatus.READY_FOR_DECISION, clock);
    }

    /** {@code READY_FOR_DECISION → APPROVED}. Terminal ({@code INV-LIFE-04}). */
    public KycCase approve(Clock clock) {
        return transitionTo(KycCaseStatus.APPROVED, clock);
    }

    /** {@code READY_FOR_DECISION → REJECTED}. Terminal ({@code INV-LIFE-04}). */
    public KycCase reject(Clock clock) {
        return transitionTo(KycCaseStatus.REJECTED, clock);
    }

    private KycCase transitionTo(KycCaseStatus target, Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        if (!status.canTransitionTo(target)) {
            throw new IllegalKycCaseTransitionException(id, status, target);
        }
        return new KycCase(
                id, customerId, kind, target, policyVersion, openedAt, Instant.now(clock));
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
