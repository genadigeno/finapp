package com.finapp.app.risk;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.risk.CaseEvidence;
import com.finapp.risk.CaseWorklist;
import com.finapp.risk.Cases;
import com.finapp.risk.MonitoredTransaction;
import com.finapp.risk.RiskAssessments;
import com.finapp.risk.RiskCaseId;
import com.finapp.risk.RiskErrorCode;
import com.finapp.risk.RiskSignal;
import com.finapp.risk.RiskSpans;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The evidence read door (`P13-TSK-023`; the item `-018` (D9), `-020` and `-021` routed; {@code DESIGN-023} D9;
 * {@code RiskQueueDesk}'s shape): one reference a case's file holds, resolved on one read-only snapshot after the file's
 * own self-dealing judgement ({@link CaseEvidence}), then {@code risk.CaseRead} ({@code act=evidence}) committed in a
 * transaction of its own before the answer. {@code signals} and {@code journal-entries} are served here; an assessment
 * has its own door; anything else - a kind, a malformed id, a reference the case does not hold, a row not found - is the
 * uniform {@code 404 risk.NotFound}; the reader's own party's case, or one the platform cannot place, {@code 403
 * risk.SelfDealingRefused}, recorded {@code FAILED}. Ids, codes and the amount in minor units with its currency - an
 * operator surface only ({@code INV-RSK-08}); none of it is logged.
 */
public final class RiskEvidenceDesk {

    private final CaseEvidence evidence;
    private final RiskTransactions transactions;
    private final RiskFlowScope flow;

    public RiskEvidenceDesk(CaseEvidence evidence, RiskTransactions transactions, RiskFlowScope flow) {
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.flow = Objects.requireNonNull(flow, "flow");
    }

    /** One resolved reference: its kind and id, and the signal or the monitored transaction - one of the two. */
    public record RiskEvidenceItemView(String kind, String id, RiskSignalEvidenceView signal,
            RiskTransactionEvidenceView transaction) {}

    /** A signal: its kind, subject, the instant observed, its source event's type and its typed attributes (codes). */
    public record RiskSignalEvidenceView(String signalId, String kind, String subjectKind, String subjectRef,
            String observedAt, String sourceEventType, int sourceEventVersion, String producer,
            Map<String, String> attributes) {}

    /** A journal entry as the monitoring record holds it - the amount in minor units of its currency at its scale. */
    public record RiskTransactionEvidenceView(String journalEntryId, long journalSequence, String entryType,
            String postingNamespace, String kind, String subjectKind, String subjectRef, String direction,
            String counterpartyKind, String counterpartyRef, String counterpartyPurpose, long amountMinor,
            String currency, int scale, String valueDate, String postedAt, String reversesJournalEntryId) {}

    /** {@code rawCaseId}'s reference {@code (rawKind, rawId)}, resolved and recorded. */
    public RiskEvidenceItemView read(String rawCaseId, String rawKind, String rawId) {
        RiskCaseId caseId = caseId(rawCaseId);
        CaseWorklist.EvidenceKind kind = kind(rawKind);
        UUID id = uuid(rawId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a risk read runs inside a correlation scope"))
                .correlationId();
        CaseEvidence.Served served;
        try {
            served = flow.door(RiskSpans.CASE_READ, () -> transactions.inSnapshot(
                    snapshot -> evidence.read(snapshot, caseId, kind, id, actor)));
        } catch (RiskAssessments.SelfDealingRefused refused) {
            transactions.inTransaction(uow -> {
                evidence.recordRefusal(uow, caseId, refused, actor, correlation);
                return null;
            });
            throw new ApiException(RiskErrorCode.SELF_DEALING_REFUSED, "A risk read was refused",
                    "a holder never reads a case of their own party, nor one whose subject the platform cannot place;"
                            + " nothing was served.");
        } catch (Cases.CaseNotFound | CaseEvidence.EvidenceNotFound none) {
            throw notFound();
        }
        transactions.inTransaction(uow -> {
            evidence.record(uow, caseId, served, actor, correlation);
            return null;
        });
        return view(served);
    }

    static RiskEvidenceItemView view(CaseEvidence.Served served) {
        return switch (served) {
            case CaseEvidence.SignalServed signal -> new RiskEvidenceItemView(served.kind().name(),
                    served.id().toString(), signal(signal.signal()), null);
            case CaseEvidence.TransactionServed transaction -> new RiskEvidenceItemView(served.kind().name(),
                    served.id().toString(), null, transaction(transaction.transaction()));
        };
    }

    private static RiskSignalEvidenceView signal(RiskSignal signal) {
        return new RiskSignalEvidenceView(signal.id().value().toString(), signal.kind().name(),
                signal.subject().kind().name(), signal.subject().ref(), signal.observedAt().toString(),
                signal.source().type(), signal.source().version(), signal.source().producer(),
                signal.attributes().asMap());
    }

    private static RiskTransactionEvidenceView transaction(MonitoredTransaction row) {
        return new RiskTransactionEvidenceView(row.journalEntryId().toString(), row.journalSequence(),
                row.entryType().name(), row.postingNamespace(), row.kind().name(),
                row.subjectKind().map(Enum::name).orElse(null), row.subjectRef().map(UUID::toString).orElse(null),
                row.direction().map(Enum::name).orElse(null), row.counterpartyKind().name(),
                row.counterpartyRef().map(UUID::toString).orElse(null), row.counterpartyPurpose().orElse(null),
                row.amountMinor(), row.currency().code(), row.scale(), row.valueDate().toString(),
                row.postedAt().toString(), row.reversesJournalEntryId().map(UUID::toString).orElse(null));
    }

    /** The served kinds by their path segment; an assessment is the assessment door's. */
    static CaseWorklist.EvidenceKind kind(String raw) {
        return switch (Objects.requireNonNullElse(raw, "")) {
            case "signals" -> CaseWorklist.EvidenceKind.SIGNAL;
            case "journal-entries" -> CaseWorklist.EvidenceKind.JOURNAL_ENTRY;
            default -> throw notFound();
        };
    }

    static RiskCaseId caseId(String raw) {
        try {
            return RiskCaseId.of(UUID.fromString(Objects.requireNonNull(raw, "raw")));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    static UUID uuid(String raw) {
        try {
            return UUID.fromString(Objects.requireNonNull(raw, "raw"));
        } catch (IllegalArgumentException malformed) {
            throw notFound();
        }
    }

    private static ApiException notFound() {
        return new ApiException(RiskErrorCode.NOT_FOUND, "No risk record answers the request", "no such record.");
    }
}
