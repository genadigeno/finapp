package com.finapp.app.kyc;

import com.finapp.kyc.CheckOutcome;
import com.finapp.kyc.CheckStore;
import com.finapp.kyc.CheckType;
import com.finapp.kyc.ChecksAssessment;
import com.finapp.kyc.EvidenceId;
import com.finapp.kyc.KycAuditAction;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.KycCaseStore;
import com.finapp.kyc.ReviewTask;
import com.finapp.kyc.ReviewTaskStore;
import com.finapp.kyc.VerificationCheck;
import com.finapp.kyc.VerificationProvider;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a case's verification checks against the registered providers (`P2-TSK-009`, ADR-0038).
 *
 * <h2>The choreography is the deliverable: dispatch durable, call connectionless, outcome atomic</h2>
 *
 * <p>Per check, <strong>two transactions with the provider call between them</strong>:
 *
 * <ol>
 *   <li><strong>Dispatch</strong>: claim the check (one in-flight per type, the partial unique
 *       index) and move it {@code REQUESTED → DISPATCHED} conditionally — <em>then commit</em>.
 *       The dispatch is durable before the provider is asked, so a crash mid-call leaves a
 *       visible {@code DISPATCHED} fact to reconcile, never an unknown ({@code INV-LIFE-03}).
 *       Only the instance whose conditional update returned a row calls the provider: one
 *       dispatch, one provider effect.
 *   <li><strong>The call</strong>, holding <em>no database connection</em> — an HTTP round-trip
 *       on one of eight pooled connections is the `P1-TSK-026` failure shape.
 *   <li><strong>Outcome</strong>: conditional {@code DISPATCHED → terminal}; only the winner
 *       writes the evidence, the audit record and the counter — all in one transaction, so a
 *       recorded outcome always has its evidence and its trail.
 * </ol>
 *
 * <h2>Assessment happens after the outcome commits, and that ordering is load-bearing</h2>
 *
 * <p>Two instances completing a case's last two checks simultaneously would each assess
 * <em>inside</em> its own outcome transaction and each see the other's check still
 * {@code DISPATCHED} — and nobody would move the case. Assessing in a <strong>separate
 * transaction after the commit</strong> means whichever assessment runs last sees every
 * committed outcome; both may then attempt the transition, and the conditional
 * {@code moveStatus} lets exactly one win. A run over already-answered checks re-assesses too,
 * which is what makes a crash between outcome and transition self-healing on the next run.
 *
 * <h2>The case moves only by our assessment — and a blocked case moves to a person</h2>
 *
 * <p>No branch here maps a provider verdict onto the case ({@code INV-KYC-01}):
 * {@link ChecksAssessment} reads the whole. {@code CLEAR_TO_PROCEED} moves the case toward its
 * decision; {@code BLOCKED} — a {@code HIT}, or a required type {@code INDETERMINATE} past its
 * retry budget — routes it to {@code IN_REVIEW} <strong>with its review tasks, atomically</strong>
 * (`P2-TSK-010`, {@code INV-KYC-04}): the tasks are inserted and the conditional move is made in
 * one transaction, so a case is never {@code IN_REVIEW} with nothing to resolve and
 * `P2-TSK-012`'s exit condition ("every task resolved") can never be vacuously true on arrival.
 * Task creation is unconditional on the case's status, deliberately — a late {@code HIT}
 * completing against an already-in-review case still gets its task ({@code ON CONFLICT} absorbs
 * re-runs), and the move's lost race is the ordinary outcome, not an error.
 */
public class VerificationRunService {

    private final KycCaseStore<Connection> cases;
    private final CheckStore<Connection> checks;
    private final ReviewTaskStore<Connection> reviewTasks;
    private final List<VerificationProvider> providers;
    private final Set<CheckType> requiredTypes;
    private final AuditWriter<Connection> auditWriter;
    private final IdGenerator ids;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final DataSource dataSource;
    private final Map<CheckOutcome, Counter> outcomes;
    private final Timer providerLatency;

    public VerificationRunService(
            KycCaseStore<Connection> kycCaseStore,
            CheckStore<Connection> checkStore,
            ReviewTaskStore<Connection> reviewTaskStore,
            List<VerificationProvider> providers,
            AuditWriter<Connection> auditWriter,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate kycTransactions,
            DataSource dataSource,
            MeterRegistry meterRegistry) {
        this.cases = Objects.requireNonNull(kycCaseStore, "kycCaseStore must not be null");
        this.checks = Objects.requireNonNull(checkStore, "checkStore must not be null");
        this.reviewTasks =
                Objects.requireNonNull(reviewTaskStore, "reviewTaskStore must not be null");
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers must not be null"));
        if (this.providers.isEmpty()) {
            throw new IllegalArgumentException(
                    "a verification run needs at least one provider: a run with none would"
                            + " assess every case as complete by absence of questions");
        }
        this.requiredTypes = EnumSet.noneOf(CheckType.class);
        for (VerificationProvider provider : this.providers) {
            if (!requiredTypes.add(provider.checkType())) {
                throw new IllegalArgumentException(
                        "two providers answer " + provider.checkType() + "; a run must know"
                                + " which one a check of that type means");
            }
        }
        this.auditWriter = Objects.requireNonNull(auditWriter, "auditWriter must not be null");
        this.ids = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.transactions =
                Objects.requireNonNull(kycTransactions, "kycTransactions must not be null");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");

        // Eager, one per outcome (P1-TSK-029): a counter that starts existing when the thing it
        // counts happens is a delayed notification, not monitoring - and a rising
        // `indeterminate` is a provider degrading, which is exactly the moment the series must
        // already exist.
        Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.outcomes = new EnumMap<>(CheckOutcome.class);
        for (CheckOutcome outcome : CheckOutcome.values()) {
            outcomes.put(
                    outcome,
                    Counter.builder("finapp.kyc.check")
                            .tag("outcome", outcome.name().toLowerCase(java.util.Locale.ROOT))
                            .description("Verification check outcomes, as normalised by us")
                            .register(meterRegistry));
        }
        this.providerLatency =
                Timer.builder("finapp.kyc.provider.latency")
                        .description("Verification provider round-trip time, refusals included")
                        .register(meterRegistry);
    }

    /**
     * Dispatches and completes this case's checks against every registered provider, then
     * assesses the whole.
     *
     * <p>Idempotent at every layer: re-running converges on existing checks, cannot re-call a
     * provider for a dispatched or answered check, cannot duplicate an outcome, and re-assesses
     * — which is what heals a crash that landed between an outcome and the case transition.
     */
    public RunReport runChecks(KycCaseId caseId, UUID customerId) {
        Objects.requireNonNull(caseId, "caseId must not be null");
        Objects.requireNonNull(customerId, "customerId must not be null");
        VerificationProvider.VerificationSubject subject =
                new VerificationProvider.VerificationSubject(caseId, customerId);

        for (VerificationProvider provider : providers) {
            Optional<VerificationCheck> dispatched = dispatch(caseId, provider.checkType());
            if (dispatched.isEmpty()) {
                continue;
            }
            // No connection is held here: the dispatch transaction has committed and the
            // outcome transaction has not begun.
            VerificationProvider.ProviderResult result =
                    providerLatency.record(() -> provider.verify(subject));
            recordOutcome(dispatched.get(), result);
        }

        return assess(caseId);
    }

    /** Transaction one: the claim and the durable dispatch. */
    private Optional<VerificationCheck> dispatch(KycCaseId caseId, CheckType type) {
        return inOneTransaction(
                unitOfWork -> {
                    CheckStore.Requested requested =
                            checks.requestOrConverge(
                                    unitOfWork,
                                    VerificationCheck.request(ids, clock, caseId, type));
                    VerificationCheck check = requested.check();
                    if (!checks.dispatch(unitOfWork, check.id(), Instant.now(clock))) {
                        // Somebody else dispatched it, or it is already answered. Either way the
                        // provider must not be asked twice for one check.
                        return Optional.empty();
                    }
                    // The first dispatch moves the case; later ones find it already moved and
                    // the conditional returns false, which is fine - the row count is the
                    // outcome, not an error.
                    cases.moveStatus(
                            unitOfWork,
                            caseId,
                            KycCaseStatus.OPEN,
                            KycCaseStatus.CHECKS_IN_PROGRESS,
                            Instant.now(clock));
                    return Optional.of(check.dispatch(clock));
                });
    }

    /** Transaction two: outcome, evidence, audit record and counter — together or not at all. */
    private void recordOutcome(
            VerificationCheck check, VerificationProvider.ProviderResult result) {
        inOneTransaction(
                unitOfWork -> {
                    if (!checks.complete(
                            unitOfWork, check.id(), result.outcome(), Instant.now(clock))) {
                        // A duplicate completion - another instance answered first. One outcome,
                        // one evidence set, one record.
                        return null;
                    }
                    result.evidence()
                            .ifPresent(
                                    payload ->
                                            checks.appendEvidence(
                                                    unitOfWork,
                                                    EvidenceId.next(ids),
                                                    check.id(),
                                                    payload,
                                                    Instant.now(clock)));
                    audit(unitOfWork, check, result.outcome());
                    outcomes.get(result.outcome()).increment();
                    return null;
                });
    }

    /** The separate assessment transaction — see the class note on why it must be separate. */
    private RunReport assess(KycCaseId caseId) {
        return inOneTransaction(
                unitOfWork -> {
                    List<VerificationCheck> all = checks.forCase(unitOfWork, caseId);
                    ChecksAssessment assessment = ChecksAssessment.of(requiredTypes, all);
                    if (assessment == ChecksAssessment.CLEAR_TO_PROCEED) {
                        cases.moveStatus(
                                unitOfWork,
                                caseId,
                                KycCaseStatus.CHECKS_IN_PROGRESS,
                                KycCaseStatus.READY_FOR_DECISION,
                                Instant.now(clock));
                    } else if (assessment == ChecksAssessment.BLOCKED) {
                        // Tasks first, move second, ONE transaction: the state and its work item
                        // commit together, so IN_REVIEW always has something to resolve
                        // (INV-KYC-04 - the exit P2-TSK-012 builds must never be vacuously open).
                        // Creation is unconditional on the case's status: a late HIT joining an
                        // already-in-review case is real work, and its move simply loses.
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
                    return new RunReport(assessment, all);
                });
    }

    /**
     * The record ties a provider's answer into the decision trail ({@code INV-KYC-02} will
     * reference these checks). The actor is the platform — {@code enterSystem()}, the fifth
     * enumerated site: nobody is present when a machine records what a machine answered, and the
     * correlation is what ties the record to the run.
     */
    @SuppressWarnings("try") // the actor scope is used for its close side effect
    private void audit(Connection unitOfWork, VerificationCheck check, CheckOutcome outcome) {
        try (SecurityContext.Scope actor = SecurityContext.enterSystem()) {
            auditWriter.append(
                    unitOfWork,
                    new AuditRecord(
                            AuditId.next(ids),
                            SecurityContext.require(),
                            Instant.now(clock),
                            KycAuditAction.KYC_CHECK_COMPLETED,
                            "VerificationCheck",
                            check.id().value().toString(),
                            Optional.empty(),
                            AuditOutcome.SUCCEEDED,
                            CorrelationContext.current()
                                    .orElseThrow(
                                            () ->
                                                    new IllegalStateException(
                                                            "a verification run must execute"
                                                                + " inside a correlation scope:"
                                                                + " the record carries the flow's"
                                                                + " identifier"))
                                    .correlationId(),
                            // Identifiers and enum names only (INV-AUD-02) - which case, which
                            // question, what it normalised to.
                            Optional.of(
                                    "case=" + check.caseId()
                                            + " type=" + check.type()
                                            + " outcome=" + outcome)));
        }
    }

    private <T> T inOneTransaction(Function<Connection, T> work) {
        return transactions.execute(
                status -> {
                    Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                    try {
                        return work.apply(unitOfWork);
                    } finally {
                        DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                    }
                });
    }

    /** What a run came to: the platform's assessment, and the checks it read. */
    public record RunReport(ChecksAssessment assessment, List<VerificationCheck> checks) {
        public RunReport {
            Objects.requireNonNull(assessment, "assessment must not be null");
            checks = List.copyOf(Objects.requireNonNull(checks, "checks must not be null"));
        }
    }
}
