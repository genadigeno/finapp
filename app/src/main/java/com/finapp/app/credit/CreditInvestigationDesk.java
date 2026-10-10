package com.finapp.app.credit;

import com.finapp.credit.AttributeProvenance;
import com.finapp.credit.AttributeValue;
import com.finapp.credit.CreditDecisionId;
import com.finapp.credit.CreditErrorCode;
import com.finapp.credit.CreditReasons;
import com.finapp.credit.CreditInvestigations;
import com.finapp.credit.CreditPolicy;
import com.finapp.credit.CreditRecordId;
import com.finapp.credit.DecisionReplayer;
import com.finapp.credit.ReasonCode;
import com.finapp.credit.TransactionRunner;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The investigator's doors (`P10-TSK-017`; behind {@code CREDIT_INVESTIGATE}): a decision's full explanation, from rows
 * alone, and a credit record's raw evidence with a reason. Each read runs in one transaction with its audit row, and
 * that transaction commits before the answer - so no serving goes unrecorded, and an unreadable evidence read is
 * recorded {@code FAILED} before its {@code 503}. Reads, so no idempotency key: a repeated read is a second serving,
 * recorded again. A replay (`P10-TSK-019`) reads one read-only {@code REPEATABLE READ} snapshot, then records its audit
 * row in a transaction of its own that commits before the verdict is answered.
 */
@RequiredArgsConstructor
public final class CreditInvestigationDesk {

    @NonNull private final CreditInvestigations investigations;
    @NonNull private final TransactionRunner transactions;
    @NonNull private final DecisionReplayer replayer;
    @NonNull private final CreditReadingSnapshot snapshots;

    /** A decision explained: the decision, the snapshot's attributes, the pinned versions, the rules and which fired. */
    public record CreditExplanationView(
            String decisionId,
            String requestId,
            String product,
            String outcome,
            String approvedAmount,
            String currency,
            Integer approvedTermMonths,
            String decidedAt,
            String decidedBy,
            String decidedByType,
            String validUntil,
            List<String> reasonCodes,
            String snapshotId,
            int snapshotSequence,
            String snapshotSha256,
            String policyVersionId,
            int policyVersion,
            String modelVersionId,
            int modelVersion,
            int engineVersion,
            int score,
            String evaluationOutcome,
            boolean fallbackApplied,
            List<CreditExplainedAttributeView> attributes,
            List<CreditExplainedRuleView> rules) {}

    /** One attribute: its value as frozen, where it came from, and when its provider retrieved it. */
    public record CreditExplainedAttributeView(
            String code, String value, String provenance, String source, String retrievedAt) {}

    /** One rule of the pinned policy: what it reads and does, and what it did. */
    public record CreditExplainedRuleView(
            int ordinal, String ruleCode, String subject, String operator, String operand, String effect, String reasonCode,
            String state) {}

    /** A replay's verdict: what differs, by kind alone - never a value - and the pinned versions it ran under. */
    public record CreditReplayView(
            String decisionId,
            String verdict,
            List<String> differences,
            boolean decidedByPerson,
            String policyVersionId,
            String modelVersionId,
            int engineVersion) {}

    /** A record's evidence, as its provider delivered it. */
    public record CreditEvidenceView(String recordId, String evidenceId, String contentBase64) {}

    public CreditExplanationView explain(String rawId) {
        CreditDecisionId id;
        try {
            id = CreditDecisionId.of(UUID.fromString(rawId));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return transactions.inTransaction(uow -> investigations.explain(uow, id, actor, correlation))
                .map(CreditInvestigationDesk::view)
                .orElseThrow(CreditInvestigationDesk::notFound);
    }

    /**
     * Replays decision {@code rawId} with {@code reason}: a blank reason refused before anything is read, the replay on one
     * read-only snapshot, then {@code credit.DecisionReplayed} committed - only then is the verdict answered.
     */
    public CreditReplayView replay(String rawId, String reason) {
        CreditDecisionId id;
        try {
            id = CreditDecisionId.of(UUID.fromString(rawId));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
        if (reason == null || reason.isBlank()) {
            throw new ApiException(CreditErrorCode.REASON_REQUIRED, "A decision replay without a reason",
                    "a reason is required.");
        }
        // Never a card-number or bank-account shape - it becomes the audit record's reason (CreditReasons).
        CreditReasons.defect(reason).ifPresent(defect -> {
            throw new ApiException(CreditErrorCode.REASON_REQUIRED, "A decision replay's reason was refused", defect + ".");
        });
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        DecisionReplayer.Replay replay = snapshots.read(uow -> replayer.replay(uow, id))
                .orElseThrow(CreditInvestigationDesk::notFound);
        transactions.inTransaction(uow -> {
            investigations.recordReplay(uow, replay, reason, actor, correlation);
            return null;
        });
        return new CreditReplayView(
                replay.decision().value().toString(),
                replay.verdict().name(),
                replay.divergences().stream().map(Enum::name).sorted().toList(),
                replay.byPerson(),
                replay.versions().policyVersion().toString(),
                replay.versions().modelVersion().toString(),
                replay.versions().engineVersion());
    }

    public CreditEvidenceView readEvidence(String rawId, String reason) {
        CreditRecordId id;
        try {
            id = CreditRecordId.of(UUID.fromString(rawId));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        CreditInvestigations.EvidenceRead read;
        try {
            read = transactions.inTransaction(uow -> investigations.readEvidence(uow, id, reason, actor, correlation));
        } catch (CreditInvestigations.ReasonRequired refused) {
            throw new ApiException(CreditErrorCode.REASON_REQUIRED, "An evidence read's reason was refused",
                    refused.getMessage() + ".");
        }
        return switch (read) {
            case CreditInvestigations.EvidenceRead.Served served -> new CreditEvidenceView(rawId,
                    served.evidence().value().toString(), Base64.getEncoder().encodeToString(served.content()));
            case CreditInvestigations.EvidenceRead.NotFound absent -> throw notFound();
            case CreditInvestigations.EvidenceRead.Unreadable unreadable -> throw new ApiException(
                    CreditErrorCode.EVIDENCE_UNREADABLE, "Credit evidence could not be decrypted",
                    "the evidence cannot be read now.");
        };
    }

    private static CreditExplanationView view(CreditInvestigations.Explanation explanation) {
        var decision = explanation.decision();
        return new CreditExplanationView(
                decision.id().value().toString(),
                decision.decisionRequest().toString(),
                decision.product().name(),
                decision.outcome().name(),
                decision.approved().map(amount -> amount.toBigDecimal().toPlainString()).orElse(null),
                decision.requested().currency().code(),
                decision.termMonths().orElse(null),
                decision.decidedAt().toString(),
                decision.decidedBy(),
                decision.decidedByType(),
                decision.validUntil().toString(),
                decision.reasons().stream().map(ReasonCode::code).toList(),
                decision.snapshot().value().toString(),
                explanation.snapshotSequence(),
                decision.snapshotSha256Hex(),
                decision.versions().policyVersion().toString(),
                explanation.policyVersion(),
                decision.versions().modelVersion().toString(),
                explanation.modelVersion(),
                decision.versions().engineVersion(),
                explanation.score(),
                explanation.evaluation().outcome().name(),
                explanation.evaluation().fallbackApplied(),
                explanation.attributes().stream().map(attribute -> new CreditExplainedAttributeView(
                        attribute.code().name(), value(attribute.value()), provenance(attribute.provenance()),
                        source(attribute.provenance()), attribute.retrievedAt().map(Object::toString).orElse(null)))
                        .toList(),
                explanation.rules().stream().map(rule -> new CreditExplainedRuleView(rule.ordinal(), rule.rule().ruleCode(),
                        rule.rule().subject().name(), rule.rule().operator().name(), operand(rule.rule()),
                        rule.rule().effect().name(), rule.rule().reason().code(), rule.state().name()))
                        .toList());
    }

    /** The investigator's rendering of a frozen value - this surface is CREDIT_INVESTIGATE's, never a customer's. */
    static String value(AttributeValue value) {
        return switch (value) {
            case AttributeValue.IntegerValue integer -> Long.toString(integer.value());
            case AttributeValue.MoneyValue money ->
                    money.value().toBigDecimal().toPlainString() + " " + money.value().currency().code();
            case AttributeValue.BooleanValue bool -> Boolean.toString(bool.value());
            case AttributeValue.CodeValue code -> code.value();
            case AttributeValue.Absent absent -> "ABSENT";
        };
    }

    static String provenance(AttributeProvenance provenance) {
        return switch (provenance) {
            case AttributeProvenance.Provider provider -> "PROVIDER";
            case AttributeProvenance.Record record -> "RECORD";
            case AttributeProvenance.Unavailable unavailable -> "UNAVAILABLE";
            case AttributeProvenance.NotRead notRead -> "NOT_READ";
            case AttributeProvenance.Declared declared -> "DECLARED";
            case AttributeProvenance.Port port -> "PORT";
        };
    }

    /** Where it came from, identified: the record, the data request, the provider and normaliser, or the port. */
    private static String source(AttributeProvenance provenance) {
        return switch (provenance) {
            case AttributeProvenance.Provider provider ->
                    provider.kind() + " " + provider.providerCode() + " v" + provider.normaliserVersion();
            case AttributeProvenance.Record record -> record.kind() + " " + record.providerCode() + " v"
                    + record.normaliserVersion() + " record " + record.record().value();
            case AttributeProvenance.Unavailable unavailable ->
                    unavailable.kind() + " data request " + unavailable.dataRequest().value();
            case AttributeProvenance.NotRead notRead -> notRead.kind().name();
            case AttributeProvenance.Declared declared -> "the applicant";
            case AttributeProvenance.Port port -> port.port() + " v" + port.version();
        };
    }

    private static String operand(CreditPolicy.PolicyRule rule) {
        String operand = switch (rule.operand()) {
            case CreditPolicy.Operand.None none -> null;
            case CreditPolicy.Operand.IntegerOperand integer -> Long.toString(integer.value());
            case CreditPolicy.Operand.MoneyOperand money ->
                    money.value().toBigDecimal().toPlainString() + " " + money.value().currency().code();
            case CreditPolicy.Operand.BooleanOperand bool -> Boolean.toString(bool.value());
            case CreditPolicy.Operand.CodesOperand codes -> String.join(",", codes.codes());
        };
        return rule.cap().map(cap -> (operand == null ? "" : operand + "; ") + "cap " + cap.toBigDecimal().toPlainString())
                .orElse(operand);
    }

    private static ApiException notFound() {
        return new ApiException(CreditErrorCode.NOT_FOUND, "No credit record matches the requested identifier",
                "no such credit record.");
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a credit read runs inside a correlation scope"))
                .correlationId();
    }
}
