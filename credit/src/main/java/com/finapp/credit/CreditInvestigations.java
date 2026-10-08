package com.finapp.credit;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The investigator's reads (`P10-TSK-017`; ADR-0087, ADR-0085 point 7; {@code INV-CRD-02}, {@code INV-CRD-07},
 * {@code INV-AUD-01}): a decision's full explanation, built from rows alone - the decision, the snapshot it was made
 * from with every attribute's provenance and retrieval time, the PINNED policy's frozen rules beside which of them
 * fired, the reasons, the outcome, when and by whom - and a credit record's raw evidence, read with a reason through
 * the definer and decrypted here, where the key is.
 *
 * <p><strong>No serving without its record.</strong> Each read writes its audit row in the read's own transaction
 * ({@code credit.ExplanationRead}, {@code credit.EvidenceRead}); evidence that cannot be decrypted is recorded
 * {@code FAILED} and nothing is returned.
 */
@RequiredArgsConstructor
public final class CreditInvestigations {

    static final String DECISION_TARGET = "credit_decision";
    static final String RECORD_TARGET = "credit_record";

    @NonNull private final CreditDecisions<Connection> decisions;
    @NonNull private final DecisionSnapshotStore snapshots;
    @NonNull private final CreditAssessmentStore assessments;
    @NonNull private final PolicyEvaluationStore evaluations;
    @NonNull private final CreditPolicyStore policies;
    @NonNull private final ScorecardStore scorecards;
    @NonNull private final JdbcCreditReads reads;
    @NonNull private final CreditEvidenceCipher cipher;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /** One attribute as the snapshot froze it, with where it came from and when the provider retrieved it. */
    public record ExplainedAttribute(
            CreditAttributeCode code, AttributeValue value, AttributeProvenance provenance, Optional<Instant> retrievedAt) {}

    /** One rule of the pinned policy and what it did. */
    public record ExplainedRule(int ordinal, CreditPolicy.PolicyRule rule, EvaluationResult.RuleState state) {}

    /** A decision explained from its rows. */
    public record Explanation(
            CreditDecision decision,
            int snapshotSequence,
            List<ExplainedAttribute> attributes,
            int policyVersion,
            int modelVersion,
            int score,
            EvaluationResult evaluation,
            List<ExplainedRule> rules) {}

    /** What an evidence read did. */
    public sealed interface EvidenceRead permits EvidenceRead.Served, EvidenceRead.NotFound, EvidenceRead.Unreadable {

        /** The plaintext, served and recorded. */
        record Served(CreditEvidenceId evidence, byte[] content) implements EvidenceRead {}

        /** No record, or a record with no readable evidence. */
        record NotFound() implements EvidenceRead {}

        /** The key could not decrypt it: recorded {@code FAILED}, nothing served. */
        record Unreadable() implements EvidenceRead {}
    }

    /** A blank reason for an evidence read ({@code credit.ReasonRequired}). */
    public static final class ReasonRequired extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ReasonRequired() {
            super("reading credit evidence requires a reason");
        }
    }

    /** Decision {@code id} explained, and the read recorded in {@code unitOfWork} - empty when no such decision. */
    public Optional<Explanation> explain(Connection unitOfWork, CreditDecisionId id, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        Optional<CreditDecision> found = decisions.byId(unitOfWork, id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        CreditDecision decision = found.get();
        DecisionSnapshotStore.StoredSnapshot stored = snapshots.snapshotById(unitOfWork, decision.snapshot())
                .orElseThrow(() -> new IllegalStateException("a decision names a snapshot that exists"));
        SnapshotContent content = CanonicalSnapshot.parse(stored.canonical());
        List<ExplainedAttribute> attributes = new ArrayList<>();
        for (CreditAttribute attribute : content.attributes()) {
            Optional<Instant> retrieved = attribute.provenance() instanceof AttributeProvenance.Record record
                    ? reads.retrievedAt(unitOfWork, record.record())
                    : Optional.empty();
            attributes.add(new ExplainedAttribute(attribute.code(), attribute.value(), attribute.provenance(), retrieved));
        }
        CreditAssessment assessment = assessments.bySnapshot(unitOfWork, decision.snapshot())
                .orElseThrow(() -> new IllegalStateException("a decision's snapshot is assessed"));
        EvaluationResult evaluation = evaluations.byAssessment(unitOfWork, assessment.id())
                .orElseThrow(() -> new IllegalStateException("a decision's assessment is evaluated"))
                .result();
        CreditPolicyStore.PolicyVersion policy = policies.policy(unitOfWork,
                        CreditPolicyVersionId.of(decision.versions().policyVersion()))
                .orElseThrow(() -> new IllegalStateException("a decision pins a policy that exists"));
        int modelVersion = scorecards.model(unitOfWork, ScorecardModelVersionId.of(decision.versions().modelVersion()))
                .orElseThrow(() -> new IllegalStateException("a decision pins a model that exists"))
                .row()
                .version();
        List<CreditPolicy.PolicyRule> frozen = policy.policy().rules();
        List<ExplainedRule> rules = new ArrayList<>();
        for (EvaluationResult.RuleResult result : evaluation.rules()) {
            rules.add(new ExplainedRule(result.ordinal(), frozen.get(result.ordinal() - 1), result.state()));
        }
        audit.append(unitOfWork, new AuditRecord(
                AuditId.next(ids),
                actor,
                clock.instant(),
                CreditAuditAction.EXPLANATION_READ,
                DECISION_TARGET,
                id.value().toString(),
                Optional.empty(),
                AuditOutcome.SUCCEEDED,
                correlation,
                Optional.of("explanation of decision " + id.value() + " for request " + decision.decisionRequest())));
        return Optional.of(new Explanation(decision, stored.sequence(), attributes, policy.row().version(), modelVersion,
                assessment.score(), evaluation, rules));
    }

    /**
     * Record {@code record}'s raw evidence, read with {@code reason} and recorded in {@code unitOfWork} - served, not
     * found, or unreadable (recorded {@code FAILED}). The caller commits before answering, whatever the outcome.
     */
    public EvidenceRead readEvidence(
            Connection unitOfWork, CreditRecordId record, String reason, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        if (reason == null || reason.isBlank()) {
            throw new ReasonRequired();
        }
        Optional<CreditEvidenceId> evidence = reads.evidenceOf(unitOfWork, record);
        Optional<CreditEvidenceCipher.Encrypted> encrypted = evidence.flatMap(id -> reads.readEvidence(unitOfWork, id, reason));
        if (encrypted.isEmpty()) {
            return new EvidenceRead.NotFound();
        }
        byte[] content;
        AuditOutcome outcome;
        try {
            content = cipher.decrypt(evidence.get(), encrypted.get());
            outcome = AuditOutcome.SUCCEEDED;
        } catch (IllegalStateException unreadable) {
            content = null;
            outcome = AuditOutcome.FAILED;
        }
        audit.append(unitOfWork, new AuditRecord(
                AuditId.next(ids),
                actor,
                clock.instant(),
                CreditAuditAction.EVIDENCE_READ,
                RECORD_TARGET,
                record.value().toString(),
                Optional.of(reason),
                outcome,
                correlation,
                Optional.of("evidence " + evidence.get().value() + " of record " + record.value()
                        + (content == null ? " - could not be decrypted" : ""))));
        return content == null ? new EvidenceRead.Unreadable() : new EvidenceRead.Served(evidence.get(), content);
    }
}
