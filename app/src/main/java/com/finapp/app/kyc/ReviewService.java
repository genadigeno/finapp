package com.finapp.app.kyc;

import com.finapp.identity.IdentityId;
import com.finapp.kyc.CheckStore;
import com.finapp.kyc.KycAuditAction;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.KycCaseStore;
import com.finapp.kyc.ReviewTask;
import com.finapp.kyc.ReviewTaskId;
import com.finapp.kyc.ReviewTaskStore;
import com.finapp.kyc.VerificationCheck;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The reviewer surface's two acts: reading a case, and resolving what a check raised
 * (`P2-TSK-012`, {@code INV-KYC-04}).
 *
 * <h2>Resolution: one conditional write, one record, then the exit — after the commit</h2>
 *
 * <p>The resolve is a conditional {@code UPDATE} whose three predicates are each load-bearing
 * ({@link ReviewTaskStore#resolve}), and the {@code kyc.ReviewResolved} record rides the same
 * transaction — so N reviewers racing one task produce <strong>one resolution and one audit
 * record</strong>, and the losers are told they lost (409) rather than silently overwriting a
 * judgement a decision may already rest on.
 *
 * <p>The {@code IN_REVIEW → READY_FOR_DECISION} exit runs in a <strong>separate transaction
 * after the resolution commits</strong> — the `P2-TSK-009` assess-after-commit argument: two
 * instances resolving a case's last two tasks inside their own transactions would each see the
 * other's task still {@code OPEN} and nobody would move the case. Post-commit, the last
 * committer sees every resolution and the conditional's {@code status = 'IN_REVIEW'} half lets
 * exactly one exit win. <strong>The already-resolved path re-attempts the exit too</strong> —
 * the `P2-TSK-011` duplicate-re-assesses healing: a crash between a resolve's commit and its
 * exit leaves the case visibly {@code IN_REVIEW} with nothing open, and the reviewer's retried
 * request (409) is what completes it.
 *
 * <h2>The read is audited, because the reviewer is the insider surface</h2>
 *
 * <p>{@code INV-KYC-06}'s shape one level up: the threat a permission wall cannot answer is the
 * <em>legitimate</em> reader browsing cases, so every read writes {@code kyc.CaseRead} naming
 * who looked, in the same unit of work — the record commits with the read or neither happens
 * ({@code DocumentAccess}'s argument). A read of a case that does not exist writes nothing:
 * a trail entry for a guessed identifier would put identifiers that were never real into the
 * permanent record.
 */
public class ReviewService {

    /** What a resolution attempt came to. The controller maps these to 204 / 409 / 404. */
    public enum Resolution {
        RESOLVED,
        ALREADY_RESOLVED,
        NOT_FOUND
    }

    /** The reviewer's view: the case, its checks, its tasks — references, never content. */
    public record CaseFile(
            KycCase kycCase, List<VerificationCheck> checks, List<ReviewTask> tasks) {}

    private final KycCaseStore<Connection> cases;
    private final CheckStore<Connection> checks;
    private final ReviewTaskStore<Connection> tasks;
    private final AuditWriter<Connection> auditWriter;
    private final IdGenerator ids;
    private final Clock clock;
    private final KycUnitOfWork units;

    public ReviewService(
            KycCaseStore<Connection> kycCaseStore,
            CheckStore<Connection> checkStore,
            ReviewTaskStore<Connection> reviewTaskStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate kycTransactions,
            DataSource dataSource) {
        this.cases = Objects.requireNonNull(kycCaseStore, "kycCaseStore must not be null");
        this.checks = Objects.requireNonNull(checkStore, "checkStore must not be null");
        this.tasks = Objects.requireNonNull(reviewTaskStore, "reviewTaskStore must not be null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
        this.ids = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.units =
                new KycUnitOfWork(
                        Objects.requireNonNull(kycTransactions, "kycTransactions must not be null"),
                        Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    /** Reads a case for review, writing {@code kyc.CaseRead} in the same unit of work. */
    public Optional<CaseFile> readCase(KycCaseId caseId) {
        Objects.requireNonNull(caseId, "caseId must not be null");
        return units.inTransaction(
                unitOfWork ->
                        cases.findById(unitOfWork, caseId)
                                .map(
                                        kycCase -> {
                                            CaseFile file =
                                                    new CaseFile(
                                                            kycCase,
                                                            checks.forCase(unitOfWork, caseId),
                                                            tasks.forCase(unitOfWork, caseId));
                                            auditRead(unitOfWork, kycCase);
                                            return file;
                                        }));
    }

    /**
     * Resolves one task, audibly, and gives the case its exit.
     *
     * <p>Deliberately unconditional on the case's own status — the mirror of `P2-TSK-010`'s
     * unconditional creation: a late {@code HIT}'s task on a case that already exited review
     * must still be resolvable, and the exit conditional simply finds nothing to move.
     */
    public Resolution resolve(
            KycCaseId caseId, ReviewTaskId taskId, IdentityId reviewer, String reason) {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(reviewer, "reviewer must not be null");
        Objects.requireNonNull(reason, "reason must not be null");

        Resolution outcome =
                units.inTransaction(
                        unitOfWork -> {
                            boolean won =
                                    tasks.resolve(
                                            unitOfWork,
                                            taskId,
                                            caseId,
                                            reviewer.value(),
                                            reason,
                                            Instant.now(clock));
                            if (won) {
                                auditResolution(unitOfWork, caseId, taskId, reason);
                                return Resolution.RESOLVED;
                            }
                            // Disambiguate the lost conditional: absent (or another case's task
                            // named through this URL) is NOT_FOUND; present can only be RESOLVED,
                            // because a concurrent resolver's row lock makes our UPDATE wait and
                            // re-evaluate, so an OPEN row would have been won.
                            return tasks.findByIdForCase(unitOfWork, taskId, caseId).isPresent()
                                    ? Resolution.ALREADY_RESOLVED
                                    : Resolution.NOT_FOUND;
                        });

        if (outcome != Resolution.NOT_FOUND) {
            // After the commit, never inside it - and on the 409 path too, which is what heals
            // a crash that landed between a resolve's commit and its exit. A false answer is
            // harmless in every cause: a task still open, a case not IN_REVIEW, a lost race -
            // and, since P2-TSK-015, a KYB case whose ownership graph is not yet terminal,
            // whose exit the owner's own decision will re-attempt (CaseAssessment
            // .reRouteParentsOf).
            units.inTransaction(
                    unitOfWork ->
                            cases.moveToReadyForDecision(
                                    unitOfWork,
                                    caseId,
                                    KycCaseStatus.IN_REVIEW,
                                    Instant.now(clock)));
        }
        return outcome;
    }

    private void auditRead(Connection unitOfWork, KycCase kycCase) {
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        SecurityContext.require(),
                        Instant.now(clock),
                        KycAuditAction.KYC_CASE_READ,
                        "KycCase",
                        kycCase.id().value().toString(),
                        Optional.empty(),
                        AuditOutcome.SUCCEEDED,
                        correlation(),
                        // Which customer's case was looked at, so an investigator reads one row
                        // rather than joining. Identifiers only (INV-AUD-02).
                        Optional.of("customer=" + kycCase.customerId())));
    }

    private void auditResolution(
            Connection unitOfWork, KycCaseId caseId, ReviewTaskId taskId, String reason) {
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        // The reviewer, never the platform: resolving is always somebody's
                        // judgement, and the record naming the platform would lose the one fact
                        // INV-KYC-04 exists to hold.
                        SecurityContext.require(),
                        Instant.now(clock),
                        KycAuditAction.REVIEW_RESOLVED,
                        "ReviewTask",
                        taskId.value().toString(),
                        Optional.of(reason),
                        AuditOutcome.SUCCEEDED,
                        correlation(),
                        Optional.of("case=" + caseId)));
    }

    private static com.finapp.sharedkernel.correlation.CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "a review action must run inside a correlation scope:"
                                                + " the record carries the flow's identifier"))
                .correlationId();
    }
}
