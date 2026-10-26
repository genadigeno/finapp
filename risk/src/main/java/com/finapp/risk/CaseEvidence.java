package com.finapp.risk;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The evidence read door (`P13-TSK-023`; the item `-018` (D9), `-020` and `-021` routed here; {@code PHASE_13_PLAN.md}
 * section 11: "evidence is held as references ... resolved through audited reads, never copied"; {@code INV-RSK-08};
 * {@code DESIGN-023} D9) - one reference a case file holds, resolved: a <strong>signal</strong> (its kind, subject, the
 * instant it was observed, its source event's type and its typed attributes - bounded codes by construction, never a
 * description) or a <strong>journal entry</strong> (the monitored transaction born from it: kind, direction, the amount in
 * minor units with its currency and scale, the value date, the posting instant, the counterparty by kind and id). An
 * assessment reference is the assessment door's own read.
 *
 * <p><strong>Scoped by the case.</strong> The case's file is read first ({@link CaseWorklist#read}), so its self-dealing
 * judgement holds here whole - the reader's own party's case, a case whose subject cannot be placed and a reader who
 * cannot be placed are each refused {@link RiskAssessments.SelfDealingRefused} - and only a reference the file names is
 * served: no signal or transaction is readable by its id alone. Anything else is {@link EvidenceNotFound}, the uniform
 * {@code 404}. {@link #record} appends {@code risk.CaseRead} ({@code act=evidence}), which the caller commits in a
 * transaction of its own before the answer.
 */
public final class CaseEvidence {

    /** The reference is not one the case holds, not a kind served here, or names no row ({@code 404 risk.NotFound}). */
    public static final class EvidenceNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        EvidenceNotFound() {
            super("no evidence of this case answers the request");
        }
    }

    /** One resolved reference: the signal, or the journal entry's monitored transaction. */
    public sealed interface Served permits SignalServed, TransactionServed {
        CaseWorklist.EvidenceKind kind();

        UUID id();
    }

    /** A signal the case holds. */
    public record SignalServed(RiskSignal signal) implements Served {
        public SignalServed {
            Objects.requireNonNull(signal, "signal");
        }

        @Override
        public CaseWorklist.EvidenceKind kind() {
            return CaseWorklist.EvidenceKind.SIGNAL;
        }

        @Override
        public UUID id() {
            return signal.id().value();
        }
    }

    /** A journal entry the case holds, as the monitoring record holds it. */
    public record TransactionServed(MonitoredTransaction transaction) implements Served {
        public TransactionServed {
            Objects.requireNonNull(transaction, "transaction");
        }

        @Override
        public CaseWorklist.EvidenceKind kind() {
            return CaseWorklist.EvidenceKind.JOURNAL_ENTRY;
        }

        @Override
        public UUID id() {
            return transaction.journalEntryId();
        }
    }

    private static final String ACT = "evidence";

    private final CaseWorklist worklist;
    private final RiskProofReads reads;
    private final AuditWriter<Connection> audit;
    private final IdGenerator ids;
    private final Clock clock;

    public CaseEvidence(CaseWorklist worklist, RiskProofReads reads, AuditWriter<Connection> audit, IdGenerator ids,
            Clock clock) {
        this.worklist = Objects.requireNonNull(worklist, "worklist");
        this.reads = Objects.requireNonNull(reads, "reads");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * {@code caseId}'s reference {@code (kind, id)} resolved for {@code actor} on the caller's read-only snapshot -
     * nothing recorded here.
     *
     * @throws RiskAssessments.SelfDealingRefused the file's own judgement refused the reader (fail closed)
     * @throws Cases.CaseNotFound no such case
     * @throws EvidenceNotFound the case holds no such reference, the kind is the assessment door's, or no row answers
     */
    public Served read(Connection snapshot, RiskCaseId caseId, CaseWorklist.EvidenceKind kind, UUID id, Actor actor) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        CaseWorklist.CaseFile file = worklist.read(snapshot, caseId, actor);
        if (kind == CaseWorklist.EvidenceKind.ASSESSMENT
                || !file.evidence().contains(new CaseWorklist.EvidenceRef(kind, id))) {
            throw new EvidenceNotFound();
        }
        return switch (kind) {
            case SIGNAL -> new SignalServed(reads.signal(snapshot, RiskSignalId.of(id))
                    .orElseThrow(EvidenceNotFound::new));
            case JOURNAL_ENTRY -> new TransactionServed(reads.monitored(snapshot, id).orElseThrow(EvidenceNotFound::new));
            case ASSESSMENT -> throw new EvidenceNotFound();
        };
    }

    /** Records {@code served} read by {@code actor} - {@code risk.CaseRead}, its target the case, the reference named. */
    public void record(Connection unitOfWork, RiskCaseId caseId, Served served, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(served, "served");
        Objects.requireNonNull(correlation, "correlation");
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), RiskAuditAction.CASE_READ,
                Cases.TARGET_TYPE, caseId.value().toString(), Optional.empty(), AuditOutcome.SUCCEEDED, correlation,
                Optional.of("act=" + ACT + ", kind=" + served.kind().name() + ", evidence=" + served.id())));
    }

    /** Records a refused evidence read {@code FAILED} - the file's refusal, a transaction of the caller's own. */
    public void recordRefusal(Connection unitOfWork, RiskCaseId caseId, RiskAssessments.SelfDealingRefused refused,
            Actor actor, CorrelationId correlation) {
        worklist.recordRefusal(unitOfWork, caseId, refused, actor, correlation);
    }
}
