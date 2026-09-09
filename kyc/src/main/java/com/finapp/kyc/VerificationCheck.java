package com.finapp.kyc;

import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * One question to one provider (`P2-TSK-009`, ADR-0038, {@code INV-KYC-01}).
 *
 * <p>A check is <strong>evidence, never the decision</strong>: its raw answer is retained
 * verbatim beside it ({@code INV-HIST-02}, {@code kyc.verification_evidence}) and its outcome is
 * normalised into our vocabulary. The case moves only by the platform's own assessment of the
 * whole ({@link ChecksAssessment}) — no code path maps a provider state onto a case status.
 *
 * <p>Transitions go through the machine ({@code INV-LIFE-02}, the {@link KycCase} idiom): every
 * aggregate is eventually driven by a second caller, and an API-layer check protects none of
 * them. The store's conditional writes are the concurrency protocol; these methods are the rule.
 */
public final class VerificationCheck {

    private final CheckId id;
    private final KycCaseId caseId;
    private final CheckType type;
    private final CheckStatus status;
    private final Instant requestedAt;
    private final Instant statusChangedAt;

    private VerificationCheck(
            CheckId id,
            KycCaseId caseId,
            CheckType type,
            CheckStatus status,
            Instant requestedAt,
            Instant statusChangedAt) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.caseId = Objects.requireNonNull(caseId, "caseId must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        this.statusChangedAt =
                Objects.requireNonNull(statusChangedAt, "statusChangedAt must not be null");
    }

    /** Requests a check: the question exists, nobody has asked the provider yet. */
    public static VerificationCheck request(
            IdGenerator ids, Clock clock, KycCaseId caseId, CheckType type) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Instant now = Instant.now(clock);
        return new VerificationCheck(
                CheckId.next(ids), caseId, type, CheckStatus.REQUESTED, now, now);
    }

    /** Reconstitutes from storage. Applies no transition rules: the row was already valid. */
    public static VerificationCheck rehydrate(
            CheckId id,
            KycCaseId caseId,
            CheckType type,
            CheckStatus status,
            Instant requestedAt,
            Instant statusChangedAt) {
        return new VerificationCheck(id, caseId, type, status, requestedAt, statusChangedAt);
    }

    /** {@code REQUESTED → DISPATCHED}: the dispatch is about to be made durable. */
    public VerificationCheck dispatch(Clock clock) {
        return transitionTo(CheckStatus.DISPATCHED, clock);
    }

    /** {@code DISPATCHED → } the outcome's terminal state. */
    public VerificationCheck complete(CheckOutcome outcome, Clock clock) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        return transitionTo(outcome.toStatus(), clock);
    }

    private VerificationCheck transitionTo(CheckStatus target, Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        if (!status.canTransitionTo(target)) {
            throw new IllegalCheckTransitionException(id, status, target);
        }
        return new VerificationCheck(id, caseId, type, target, requestedAt, Instant.now(clock));
    }

    public CheckId id() {
        return id;
    }

    public KycCaseId caseId() {
        return caseId;
    }

    public CheckType type() {
        return type;
    }

    public CheckStatus status() {
        return status;
    }

    public Instant requestedAt() {
        return requestedAt;
    }

    public Instant statusChangedAt() {
        return statusChangedAt;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VerificationCheck check && id.equals(check.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    /** Identifiers, a type and a status — nothing about a person. */
    @Override
    public String toString() {
        return "VerificationCheck[" + id + ", case=" + caseId + ", " + type + ", " + status + "]";
    }
}
