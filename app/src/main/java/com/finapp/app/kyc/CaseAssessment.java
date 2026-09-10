package com.finapp.app.kyc;

import com.finapp.kyc.CheckType;
import com.finapp.kyc.ChecksAssessment;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.KycCaseStore;
import com.finapp.kyc.CheckStore;
import com.finapp.kyc.ReviewTask;
import com.finapp.kyc.ReviewTaskStore;
import com.finapp.kyc.VerificationCheck;
import com.finapp.kyc.VerificationProvider;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The platform's assessment of a case, and the routing it decides (`P2-TSK-009`/`-010`,
 * extracted by `P2-TSK-011` the moment a second caller arrived — the callback door).
 *
 * <p>Extraction rather than a second copy, because this is the phase's most safety-critical
 * routine and two copies of it would drift on exactly the branch that matters: tasks inserted
 * <em>before</em> the conditional move, in <strong>one transaction</strong>, so a case is never
 * {@code IN_REVIEW} with nothing to resolve ({@code INV-KYC-04}); task creation unconditional
 * on case status, so a late {@code HIT} joining an in-review case still gets its task; the
 * conditional {@code moveStatus} letting exactly one of N assessors win.
 *
 * <h2>Assessed in its own transaction, after the outcome commits — still load-bearing</h2>
 *
 * <p>The `P2-TSK-009` argument, unchanged by the extraction: two instances completing a case's
 * last two checks simultaneously would each assess inside their own outcome transaction and
 * each see the other's check still {@code DISPATCHED} — nobody would move the case. Assessed
 * afterwards, the last assessor sees every committed outcome. The same separation is what lets
 * a <em>duplicate</em> callback delivery re-assess and heal a crash that landed between an
 * outcome and the transition.
 */
public class CaseAssessment {

    private final KycCaseStore<Connection> cases;
    private final CheckStore<Connection> checks;
    private final ReviewTaskStore<Connection> reviewTasks;
    private final DecisionRecording decisions;
    private final Set<CheckType> requiredTypes;
    private final IdGenerator ids;
    private final Clock clock;
    private final KycUnitOfWork units;

    public CaseAssessment(
            KycCaseStore<Connection> kycCaseStore,
            CheckStore<Connection> checkStore,
            ReviewTaskStore<Connection> reviewTaskStore,
            DecisionRecording decisionRecording,
            List<VerificationProvider> providers,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate kycTransactions,
            DataSource dataSource) {
        this.cases = Objects.requireNonNull(kycCaseStore, "kycCaseStore must not be null");
        this.checks = Objects.requireNonNull(checkStore, "checkStore must not be null");
        this.reviewTasks =
                Objects.requireNonNull(reviewTaskStore, "reviewTaskStore must not be null");
        this.decisions =
                Objects.requireNonNull(decisionRecording, "decisionRecording must not be null");
        // The required types ARE the registered providers' set (P2-TSK-009's decision): an
        // empty set would assess every case complete by absence of questions, and two
        // providers answering one type would leave a run not knowing which one a check means.
        // Validated here because this class is the set's one owner; the run service iterates
        // the same injected list.
        Objects.requireNonNull(providers, "providers must not be null");
        if (providers.isEmpty()) {
            throw new IllegalArgumentException(
                    "an assessment needs at least one required type: a run with none would"
                            + " assess every case as complete by absence of questions");
        }
        this.requiredTypes = EnumSet.noneOf(CheckType.class);
        for (VerificationProvider provider : providers) {
            if (!requiredTypes.add(provider.checkType())) {
                throw new IllegalArgumentException(
                        "two providers answer " + provider.checkType() + "; a run must know"
                                + " which one a check of that type means");
            }
        }
        this.ids = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.units =
                new KycUnitOfWork(
                        Objects.requireNonNull(kycTransactions, "kycTransactions must not be null"),
                        Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    /** What an assessment read and decided. */
    public record Result(ChecksAssessment assessment, List<VerificationCheck> checks) {
        public Result {
            Objects.requireNonNull(assessment, "assessment must not be null");
            checks = List.copyOf(Objects.requireNonNull(checks, "checks must not be null"));
        }
    }

    /** Assesses the case and applies the one transition the assessment permits. */
    public Result assess(KycCaseId caseId) {
        Objects.requireNonNull(caseId, "caseId must not be null");
        return units.inTransaction(
                unitOfWork -> {
                    List<VerificationCheck> all = checks.forCase(unitOfWork, caseId);
                    ChecksAssessment assessment = ChecksAssessment.of(requiredTypes, all);
                    if (assessment == ChecksAssessment.CLEAR_TO_PROCEED) {
                        // The move's result is deliberately not the gate on the decision: a
                        // re-assessment (a duplicate callback, a re-run) loses this conditional
                        // against a case already READY_FOR_DECISION, and the decision's OWN
                        // conditional is what arbitrates - which is also what heals a case an
                        // older build's crash left awaiting its automatic decision.
                        cases.moveStatus(
                                unitOfWork,
                                caseId,
                                KycCaseStatus.CHECKS_IN_PROGRESS,
                                KycCaseStatus.READY_FOR_DECISION,
                                Instant.now(clock));
                        // Same transaction, deliberately (P2-TSK-013): the decision reads only
                        // what this transaction already read, so atomicity costs nothing and
                        // removes the stranded all-clear-and-undecided window entirely.
                        decisions.automatically(unitOfWork, caseId, all);
                    } else if (assessment == ChecksAssessment.BLOCKED) {
                        // Tasks first, move second, ONE transaction: the state and its work item
                        // commit together, so IN_REVIEW always has something to resolve
                        // (INV-KYC-04 - the exit P2-TSK-012 builds must never be vacuously open).
                        for (VerificationCheck raising :
                                ChecksAssessment.needingReview(requiredTypes, all)) {
                            reviewTasks.openForCheck(
                                    unitOfWork,
                                    ReviewTask.open(ids, clock, caseId, raising.id()));
                        }
                        cases.moveStatus(
                                unitOfWork,
                                caseId,
                                KycCaseStatus.CHECKS_IN_PROGRESS,
                                KycCaseStatus.IN_REVIEW,
                                Instant.now(clock));
                    }
                    return new Result(assessment, all);
                });
    }
}
