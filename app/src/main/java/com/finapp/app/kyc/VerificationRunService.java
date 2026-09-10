package com.finapp.app.kyc;

import com.finapp.kyc.CheckStore;
import com.finapp.kyc.CheckType;
import com.finapp.kyc.ChecksAssessment;
import com.finapp.kyc.EvidenceId;
import com.finapp.kyc.KycCaseId;
import com.finapp.kyc.KycCaseStatus;
import com.finapp.kyc.KycCaseStore;
import com.finapp.kyc.VerificationCheck;
import com.finapp.kyc.VerificationProvider;
import com.finapp.sharedkernel.id.IdGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
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
 *       visible {@code DISPATCHED} fact to reconcile, never an unknown ({@code INV-LIFE-03}) —
 *       and, since `P2-TSK-011`, one the provider's own callback can complete. Only the
 *       instance whose conditional update returned a row calls the provider: one dispatch, one
 *       provider effect.
 *   <li><strong>The call</strong>, holding <em>no database connection</em> — an HTTP round-trip
 *       on one of eight pooled connections is the `P1-TSK-026` failure shape.
 *   <li><strong>Outcome</strong>: conditional {@code DISPATCHED → terminal}; only the winner
 *       writes the evidence and the {@link CheckOutcomeTrail} — all in one transaction, so a
 *       recorded outcome always has its evidence and its trail.
 * </ol>
 *
 * <h2>Assessment and routing live in {@link CaseAssessment}</h2>
 *
 * <p>Extracted by `P2-TSK-011` when the callback door became the second caller; the
 * separate-transaction-after-commit argument and the atomic tasks-then-move routing are that
 * class's javadoc now, unchanged. No branch here maps a provider verdict onto the case
 * ({@code INV-KYC-01}).
 */
public class VerificationRunService {

    private final KycCaseStore<Connection> cases;
    private final CheckStore<Connection> checks;
    private final CaseAssessment assessment;
    private final CheckOutcomeTrail trail;
    private final List<VerificationProvider> providers;
    private final IdGenerator ids;
    private final Clock clock;
    private final KycUnitOfWork units;
    private final Timer providerLatency;

    public VerificationRunService(
            KycCaseStore<Connection> kycCaseStore,
            CheckStore<Connection> checkStore,
            CaseAssessment caseAssessment,
            CheckOutcomeTrail checkOutcomeTrail,
            List<VerificationProvider> providers,
            IdGenerator idGenerator,
            Clock clock,
            TransactionTemplate kycTransactions,
            DataSource dataSource,
            MeterRegistry meterRegistry) {
        this.cases = Objects.requireNonNull(kycCaseStore, "kycCaseStore must not be null");
        this.checks = Objects.requireNonNull(checkStore, "checkStore must not be null");
        this.assessment =
                Objects.requireNonNull(caseAssessment, "caseAssessment must not be null");
        this.trail = Objects.requireNonNull(checkOutcomeTrail, "checkOutcomeTrail must not be null");
        this.providers = List.copyOf(Objects.requireNonNull(providers, "providers must not be null"));
        if (this.providers.isEmpty()) {
            // CaseAssessment owns the full required-types validation (duplicates included);
            // this guard keeps the dispatch loop's own precondition stated where the loop is.
            throw new IllegalArgumentException(
                    "a verification run needs at least one provider: a run with none would"
                            + " assess every case as complete by absence of questions");
        }
        this.ids = Objects.requireNonNull(idGenerator, "idGenerator must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.units =
                new KycUnitOfWork(
                        Objects.requireNonNull(kycTransactions, "kycTransactions must not be null"),
                        Objects.requireNonNull(dataSource, "dataSource must not be null"));

        Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
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

        CaseAssessment.Result assessed = assessment.assess(caseId);
        return new RunReport(assessed.assessment(), assessed.checks());
    }

    /** Transaction one: the claim and the durable dispatch. */
    private Optional<VerificationCheck> dispatch(KycCaseId caseId, CheckType type) {
        return units.inTransaction(
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

    /** Transaction two: outcome, evidence and the trail — together or not at all. */
    private void recordOutcome(
            VerificationCheck check, VerificationProvider.ProviderResult result) {
        units.inTransaction(
                unitOfWork -> {
                    if (!checks.complete(
                            unitOfWork, check.id(), result.outcome(), Instant.now(clock))) {
                        // A duplicate completion - another instance or a callback answered
                        // first. One outcome, one evidence set, one record.
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
                    trail.record(unitOfWork, check, result.outcome());
                    return null;
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
