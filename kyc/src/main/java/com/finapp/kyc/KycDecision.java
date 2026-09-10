package com.finapp.kyc;

import com.finapp.platform.audit.AuditRecord;
import com.finapp.sharedkernel.id.IdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The platform's own recorded act on a case (`P2-TSK-013`, {@code INV-KYC-02}, ADR-0038):
 * immutable, attributable, reason-carrying, policy-pinned, evidence-referencing.
 *
 * <h2>Born terminal — a fact, not a machine</h2>
 *
 * <p>A decision has no lifecycle: it is an immutable fact the moment it exists (the
 * {@code ConsentRecord} shape). What moves is the <em>case</em> —
 * {@code READY_FOR_DECISION → APPROVED | REJECTED}, edges the machine has carried since
 * `P2-TSK-005` and which gain their first caller here. A wrong decision is corrected by a
 * <em>new case</em>, never an edit ({@code INV-LIFE-04}, {@code INV-HIST-01}'s shape).
 *
 * <h2>The automatic policy lives on the factory, so refusing is the domain's act</h2>
 *
 * <p>{@link #automatic} refuses a case with any non-{@code CLEAR} check — loudly, because
 * reaching it with one is a caller defect, and the alternative failure is a silent automatic
 * approval of a case that owed a person a judgement ({@code INV-KYC-04}). Reachability through
 * the assessment is not the control; the component's own refusal is ({@code INV-LIFE-02}'s
 * principle: rejected by the domain, not merely unreachable).
 *
 * <h2>The policy version is the case's, never re-read from {@code CURRENT}</h2>
 *
 * <p>The case pinned its regime at open ({@code INV-HIST-04}, `P2-TSK-005` — <em>"which pins
 * the same version on the decision record"</em>). A decision stamped with whatever
 * {@code CURRENT} happens to be at decision time would claim a case opened under one regime was
 * decided under another, which is exactly the irreproducibility the pin exists to prevent —
 * so both factories take the {@link KycCase} and copy its version, and there is no argument a
 * caller could get wrong.
 */
public final class KycDecision {

    /**
     * The stated automatic policy's own justification, recorded as the decision's reason.
     *
     * <p>A constant deliberately: the automatic path has exactly one ground ({@code INV-KYC-02}
     * names it — <em>"the platform under a stated automatic policy"</em>), and a free-text
     * parameter here would invite per-caller variation in a record whose value is that it says
     * the same thing every time.
     */
    public static final String AUTOMATIC_APPROVAL_REASON =
            "Every required check answered CLEAR; approved under the stated automatic policy.";

    private final KycDecisionId id;
    private final KycCaseId caseId;
    private final DecisionOutcome outcome;
    private final DecisionBasis basis;
    private final Optional<UUID> decidedBy;
    private final String reason;
    private final KycPolicyVersion policyVersion;
    private final Instant decidedAt;
    private final List<CheckId> evidence;

    private KycDecision(
            KycDecisionId id,
            KycCaseId caseId,
            DecisionOutcome outcome,
            DecisionBasis basis,
            Optional<UUID> decidedBy,
            String reason,
            KycPolicyVersion policyVersion,
            Instant decidedAt,
            List<CheckId> evidence) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.caseId = Objects.requireNonNull(caseId, "caseId must not be null");
        this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        this.basis = Objects.requireNonNull(basis, "basis must not be null");
        this.decidedBy = Objects.requireNonNull(decidedBy, "decidedBy must not be null");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.policyVersion =
                Objects.requireNonNull(policyVersion, "policyVersion must not be null");
        this.decidedAt = Objects.requireNonNull(decidedAt, "decidedAt must not be null");
        this.evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence must not be null"));

        // A REVIEWER decision names its person; an AUTOMATIC one cannot (INV-KYC-02's two
        // actor cases). Mirrored by V007's kyc_decision_actor_is_coherent CHECK, so the pair
        // cannot disagree between the domain and the schema.
        if ((basis == DecisionBasis.REVIEWER) != decidedBy.isPresent()) {
            throw new IllegalArgumentException(
                    "a " + basis + " decision " + (decidedBy.isPresent() ? "cannot name" : "must name")
                            + " a deciding person");
        }
        if (reason.isBlank() || reason.length() > AuditRecord.MAX_REASON_LENGTH) {
            throw new IllegalArgumentException(
                    "a decision's reason must be 1.." + AuditRecord.MAX_REASON_LENGTH
                            + " characters: a decision nobody can explain is a decision nobody"
                            + " can defend (INV-KYC-02)");
        }
        if (this.evidence.isEmpty()) {
            // The half of "NOT NULL evidence refs" the schema cannot express: a decision that
            // rested on nothing is not a decision, it is an assertion.
            throw new IllegalArgumentException(
                    "a decision must reference the checks it rested on (INV-KYC-02)");
        }
    }

    /**
     * The platform's own act under the stated automatic policy.
     *
     * <p>Refuses — loudly, nothing constructed — unless every check answered {@code CLEAR}:
     * any {@code HIT} or {@code INDETERMINATE} means the case owed a person a judgement
     * ({@code INV-KYC-04}), and the required-type <em>coverage</em> half of all-clear is the
     * assessment's ({@code ChecksAssessment.CLEAR_TO_PROCEED}), which is the only production
     * path that reaches this factory.
     */
    public static KycDecision automatic(
            IdGenerator ids, Clock clock, KycCase kycCase, List<VerificationCheck> checks) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(kycCase, "kycCase must not be null");
        Objects.requireNonNull(checks, "checks must not be null");
        for (VerificationCheck check : checks) {
            if (check.status() != CheckStatus.CLEAR) {
                throw new IllegalArgumentException(
                        "the automatic policy refuses a case with a non-CLEAR check: check "
                                + check.id() + " is " + check.status()
                                + ", and a non-clean case is decided by a person (INV-KYC-04)");
            }
        }
        return new KycDecision(
                KycDecisionId.next(ids),
                kycCase.id(),
                DecisionOutcome.APPROVED,
                DecisionBasis.AUTOMATIC,
                Optional.empty(),
                AUTOMATIC_APPROVAL_REASON,
                kycCase.policyVersion(),
                Instant.now(clock),
                checkIds(checks));
    }

    /** A reviewer's judgement on a case review put in front of a person. */
    public static KycDecision byReviewer(
            IdGenerator ids,
            Clock clock,
            KycCase kycCase,
            List<VerificationCheck> checks,
            UUID reviewer,
            DecisionOutcome outcome,
            String reason) {
        Objects.requireNonNull(ids, "ids must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(kycCase, "kycCase must not be null");
        Objects.requireNonNull(checks, "checks must not be null");
        Objects.requireNonNull(reviewer, "reviewer must not be null");
        return new KycDecision(
                KycDecisionId.next(ids),
                kycCase.id(),
                outcome,
                DecisionBasis.REVIEWER,
                Optional.of(reviewer),
                reason,
                kycCase.policyVersion(),
                Instant.now(clock),
                checkIds(checks));
    }

    private static List<CheckId> checkIds(List<VerificationCheck> checks) {
        return checks.stream().map(VerificationCheck::id).toList();
    }

    public KycDecisionId id() {
        return id;
    }

    public KycCaseId caseId() {
        return caseId;
    }

    public DecisionOutcome outcome() {
        return outcome;
    }

    public DecisionBasis basis() {
        return basis;
    }

    public Optional<UUID> decidedBy() {
        return decidedBy;
    }

    public String reason() {
        return reason;
    }

    public KycPolicyVersion policyVersion() {
        return policyVersion;
    }

    public Instant decidedAt() {
        return decidedAt;
    }

    /** The checks this decision rested on. Evidence appended later is visibly not among them. */
    public List<CheckId> evidence() {
        return evidence;
    }
}
