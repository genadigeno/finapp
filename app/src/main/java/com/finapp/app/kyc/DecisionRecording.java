package com.finapp.app.kyc;

import com.finapp.identity.IdentityId;
import com.finapp.kyc.CheckStore;
import com.finapp.kyc.DecisionOutcome;
import com.finapp.kyc.KycAuditAction;
import com.finapp.kyc.KycCase;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.KycCaseStore;
import com.finapp.kyc.KycDecision;
import com.finapp.kyc.KycDecisionStore;
import com.finapp.kyc.VerificationCheck;
import com.finapp.party.CustomerId;
import com.finapp.party.CustomerStatus;
import com.finapp.party.PartyStore;
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
import org.springframework.beans.factory.ObjectProvider;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records the one decision a case ever gets (`P2-TSK-013`, {@code INV-KYC-02}, ADR-0038) —
 * one routine, two doors, exactly as {@code CaseAssessment} is one routine for the run and the
 * callback: a second copy of this logic would drift on the branch that matters.
 *
 * <h2>The conditional case move is the arbiter, and the decision rides its transaction</h2>
 *
 * <p>{@code moveStatus(READY_FOR_DECISION → terminal)} decides who records: only the winner
 * inserts the decision, its evidence references and the {@code kyc.DecisionRecorded} record,
 * all in one transaction — so no crash can leave a terminal case without its decision or a
 * decision on an undecided case, and N concurrent deciders produce one decision, one record,
 * and N−1 losers told the truth. The total {@code UNIQUE (case_id)} index is defence in depth
 * behind this conditional, not the mechanism.
 *
 * <h2>The automatic path runs inside the assessment's own transaction, deliberately</h2>
 *
 * <p>Unlike the assessment itself (`P2-TSK-009`: assessed after the outcome commits, because it
 * must see <em>other</em> transactions' writes), the automatic decision reads only what this
 * transaction already read — so atomicity costs nothing and buys the absence of a stranded
 * {@code READY_FOR_DECISION} window on the clean path: a case is all-clear-and-undecided for
 * no observable instant. The reviewer path keeps that state durable, which is what awaiting a
 * person means.
 *
 * <h2>The platform is the automatic actor — the sixth enumerated {@code enterSystem()} site</h2>
 *
 * <p>Nobody is present when the platform applies its own stated policy, and attributing the
 * approval to whichever customer's callback happened to complete the last check would record
 * them as having approved themselves (`P2-TSK-009`'s reasoning, at the decision). The reviewer
 * path takes its actor from the established request scope — the person, never the platform.
 * Justification in {@code SECURITY_ARCHITECTURE.md}.
 */
@SuppressWarnings("try") // the security scope is used for its close side effect
public class DecisionRecording {

    /** What a reviewer's recording attempt came to. The controller maps these. */
    public enum Recording {
        RECORDED,
        ALREADY_DECIDED,
        NOT_READY,
        NOT_FOUND
    }

    private final KycCaseStore<Connection> cases;
    private final CheckStore<Connection> checks;
    private final KycDecisionStore<Connection> decisions;
    private final PartyStore<Connection> parties;
    private final AuditWriter<Connection> auditWriter;
    private final IdGenerator ids;
    private final Clock clock;
    private final KycUnitOfWork units;
    private final ObjectProvider<CaseAssessment> assessments;

    public DecisionRecording(
            KycCaseStore<Connection> kycCaseStore,
            CheckStore<Connection> checkStore,
            KycDecisionStore<Connection> kycDecisionStore,
            PartyStore<Connection> partyStore,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate kycTransactions,
            DataSource dataSource,
            ObjectProvider<CaseAssessment> caseAssessment) {
        this.cases = Objects.requireNonNull(kycCaseStore, "kycCaseStore must not be null");
        this.checks = Objects.requireNonNull(checkStore, "checkStore must not be null");
        this.decisions =
                Objects.requireNonNull(kycDecisionStore, "kycDecisionStore must not be null");
        this.parties = Objects.requireNonNull(partyStore, "partyStore must not be null");
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
        this.ids = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.units =
                new KycUnitOfWork(
                        Objects.requireNonNull(kycTransactions, "kycTransactions must not be null"),
                        Objects.requireNonNull(dataSource, "dataSource must not be null"));
        // An ObjectProvider, because the assessment exists only where a provider endpoint is
        // configured while recording a reviewer's decision is unconditional - and in a
        // deployment with no providers no check ever runs, so no KYB parent can be waiting
        // on a decision made here (P2-TSK-015).
        this.assessments = Objects.requireNonNull(caseAssessment, "caseAssessment must not be null");
    }

    /**
     * A reviewer's decision on a case awaiting one.
     *
     * <p>The policy version recorded is the <em>case's own</em>, copied by the factory — never
     * {@code CURRENT} re-read at decision time ({@code INV-HIST-04}).
     */
    public Recording byReviewer(
            KycCaseId caseId, IdentityId reviewer, DecisionOutcome outcome, String reason) {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(reviewer, "reviewer must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        Recording recorded = units.inTransaction(
                unitOfWork -> {
                    Optional<KycCase> kycCase = cases.findById(unitOfWork, caseId);
                    if (kycCase.isEmpty()) {
                        return Recording.NOT_FOUND;
                    }
                    boolean won =
                            cases.moveStatus(
                                    unitOfWork,
                                    caseId,
                                    KycCaseStatus.READY_FOR_DECISION,
                                    outcome.caseStatus(),
                                    Instant.now(clock));
                    if (!won) {
                        // Disambiguate the lost conditional by what is checked, not by the
                        // commonest cause (the NOT_ACTIVE lesson): a terminal case is already
                        // decided; anything else is not ready for a decision yet.
                        return cases.findById(unitOfWork, caseId)
                                        .map(current -> current.status().isTerminal())
                                        .orElse(false)
                                ? Recording.ALREADY_DECIDED
                                : Recording.NOT_READY;
                    }
                    KycDecision decision =
                            KycDecision.byReviewer(
                                    ids,
                                    clock,
                                    kycCase.get(),
                                    checks.forCase(unitOfWork, caseId),
                                    reviewer.value(),
                                    outcome,
                                    reason);
                    decisions.record(unitOfWork, decision);
                    // The actor is the person the interceptor proved - never the platform
                    // (INV-KYC-02: the record names who decided).
                    audit(unitOfWork, decision, SecurityContext.require());
                    project(unitOfWork, kycCase.get(), outcome);
                    return Recording.RECORDED;
                });
        if (recorded == Recording.RECORDED || recorded == Recording.ALREADY_DECIDED) {
            // The decided case may be some KYB case's pinned owner verification (P2-TSK-015):
            // re-route the parents waiting on the answer, AFTER the commit (the exit-attempt
            // discipline throughout this phase). On ALREADY_DECIDED too, deliberately - the
            // retried request is what heals a crash that landed between the original
            // decision's commit and its re-route (the P2-TSK-012 409-path-heals shape). The
            // hook lives on the RECORDING rather than the reviewer controller, because
            // byReviewer already has a second caller - and a consequence that lives only on
            // the HTTP boundary is one a second caller silently loses.
            assessments.ifAvailable(assessment -> assessment.reRouteParentsOf(caseId));
        }
        return recorded;
    }

    /**
     * The platform's own decision on an all-clear case, inside the caller's transaction.
     *
     * <p>Call it on the all-clear branch only: {@link KycDecision#automatic} refuses a case
     * with any non-{@code CLEAR} check — loudly, rolling the caller back, because the silent
     * alternative is an automatic approval of a case that owed a person a judgement
     * ({@code INV-KYC-04}). A lost conditional writes nothing and is every harmless cause at
     * once: another assessor already decided, or the case was never
     * {@code READY_FOR_DECISION}.
     */
    public void automatically(
            Connection unitOfWork, KycCaseId caseId, List<VerificationCheck> caseChecks) {
        Objects.requireNonNull(unitOfWork, "unitOfWork must not be null");
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(caseChecks, "caseChecks must not be null");
        KycCase kycCase =
                cases.findById(unitOfWork, caseId)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "an assessed case must exist: " + caseId));
        // Construct BEFORE the move: the policy refusal must fire even for a case whose
        // status would make the conditional a no-op, or the refusal becomes unreachable
        // exactly where it is defence (INV-LIFE-02's principle).
        KycDecision decision = KycDecision.automatic(ids, clock, kycCase, caseChecks);
        boolean won =
                cases.moveStatus(
                        unitOfWork,
                        caseId,
                        KycCaseStatus.READY_FOR_DECISION,
                        KycCaseStatus.APPROVED,
                        Instant.now(clock));
        if (!won) {
            return;
        }
        decisions.record(unitOfWork, decision);
        try (SecurityContext.Scope platform = SecurityContext.enterSystem()) {
            audit(unitOfWork, decision, SecurityContext.require());
        }
        project(unitOfWork, kycCase, DecisionOutcome.APPROVED);
    }

    /**
     * The projection: the customer's status learns the decision, in the same transaction
     * (`P2-TSK-014`, ADR-0035, {@code INV-KYC-05}).
     *
     * <p>The last write of the recording, deliberately — the atomicity test injects its
     * failure here, and a crash between the decision and the projection is impossible because
     * there is no between: one transaction commits both or neither.
     *
     * <p>The outcome-to-status mapping lives in {@code app} because {@code kyc} cannot see
     * {@code party} (module isolation): the projection is precisely the cross-context fact the
     * orchestration exists to carry. A lost conditional is a <strong>loud failure of the whole
     * transaction</strong> — the only reachable cause is a customer no longer {@code PENDING}
     * (closed mid-verification), and recording a decision beside an unmoved projection would
     * be the silent drift {@code INV-KYC-05} forbids; refusing keeps the case decidable once
     * the contradiction is resolved.
     */
    private void project(Connection unitOfWork, KycCase kycCase, DecisionOutcome outcome) {
        CustomerStatus target =
                switch (outcome) {
                    case APPROVED -> CustomerStatus.ACTIVE;
                    case REJECTED -> CustomerStatus.REJECTED;
                };
        boolean moved =
                parties.moveCustomerStatus(
                        unitOfWork,
                        CustomerId.of(kycCase.customerId()),
                        CustomerStatus.PENDING,
                        target,
                        Instant.now(clock));
        if (!moved) {
            throw new IllegalStateException(
                    "customer " + kycCase.customerId() + " of case " + kycCase.id()
                            + " was not PENDING when its decision was recorded: the projection"
                            + " cannot follow the decision, so neither is recorded"
                            + " (INV-KYC-05)");
        }
    }

    private void audit(Connection unitOfWork, KycDecision decision,
            com.finapp.platform.security.Actor actor) {
        auditWriter.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        Instant.now(clock),
                        KycAuditAction.KYC_DECISION_RECORDED,
                        "KycCase",
                        decision.caseId().value().toString(),
                        Optional.of(decision.reason()),
                        AuditOutcome.SUCCEEDED,
                        correlation(),
                        // Which decision, which way, under which regime - identifiers and
                        // enumerated names only (INV-AUD-02), so an investigator reads one row.
                        Optional.of(
                                "decision=" + decision.id()
                                        + " outcome=" + decision.outcome()
                                        + " basis=" + decision.basis()
                                        + " policy=" + decision.policyVersion().value())));
    }

    private static com.finapp.sharedkernel.correlation.CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "a decision must be recorded inside a correlation scope:"
                                                + " the record carries the flow's identifier"))
                .correlationId();
    }
}
