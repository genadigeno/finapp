package com.finapp.app.reconciliation;

import com.finapp.platform.api.ApiException;
import com.finapp.platform.api.PlatformErrorCode;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.idempotency.CommandResult;
import com.finapp.platform.idempotency.IdempotencyKey;
import com.finapp.platform.idempotency.IdempotentExecutor;
import com.finapp.platform.idempotency.RequestFingerprint;
import com.finapp.platform.idempotency.StoredResponse;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.reconciliation.Cardinality;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.ExternalLineType;
import com.finapp.reconciliation.ReconciliationErrorCode;
import com.finapp.reconciliation.RuleSetAdministration;
import com.finapp.reconciliation.RuleSetProposal;
import com.finapp.reconciliation.RuleSetStore;
import com.finapp.reconciliation.RunAdministration;
import com.finapp.reconciliation.RunReplays;
import com.finapp.settlement.SettlementFileStore;
import com.finapp.sharedkernel.correlation.CausationId;
import com.finapp.sharedkernel.correlation.Correlation;
import com.finapp.sharedkernel.money.CurrencyCode;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The reconciliation controller's doors (`P8-TSK-022`, ADR-0068 §§8-9): a rule set version
 * proposed (keyed per principal), activated by a second controller or rejected - each one
 * transaction with its history and reasoned audit record; and the run doors: reprocessing - keyed
 * per principal, one transaction with its claim, the run's birth and its reasoned audit
 * record - the requeue and the replay. The replay is the investigator's: it explains, never
 * repairs, so it takes {@code RECONCILIATION_INVESTIGATE} and no key (each call appends its own
 * verdict). The domain's refusals are answered in the API's words.
 */
@RequiredArgsConstructor
public class ReconciliationAdministrationDesk {

    /** The idempotency scope, per principal: {@code <command>:<actorType>:<actorId>}. */
    static final String REPROCESS_SCOPE = "reconciliation.reprocess:";

    /** The proposal's idempotency scope, per principal. */
    static final String PROPOSE_SCOPE = "reconciliation.rule-set:";

    /** The bound on a version listing; one more is read to know it was truncated. */
    static final int VERSION_BOUND = 20;

    @NonNull private final RuleSetAdministration ruleSets;
    @NonNull private final RunAdministration runs;
    @NonNull private final RunReplays replays;
    @NonNull private final SettlementFileStore<Connection> sourceRows;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final TransactionTemplate transactions;
    @NonNull private final DataSource dataSource;
    @NonNull private final Clock clock;

    /** What a reprocess request opened; a replay of the same key answers it byte for byte. */
    public record ReprocessingReceipt(
            String runId, String sourceCode, String ruleSetId, int itemCount) {}

    /** A requeued run: {@code IN_PROGRESS}, resumed by any instance's next tick. */
    public record RunRequeuedReceipt(String runId, String status) {}

    /** A replay's verdict, as appended to {@code run_replay}. */
    public record RunReplayVerdict(
            String replayId,
            String runId,
            String verdict,
            int replayed,
            int notReplayed,
            int divergences,
            int pendingRematch,
            String firstDivergentDecision) {}

    /** A version's decision as the doors answer it. */
    public record RuleSetReceipt(
            String ruleSetId,
            int version,
            String status,
            String retiredRuleSetId) {}

    /** One version and its whole content - what an approver reviews. */
    public record RuleSetVersion(
            String ruleSetId,
            int version,
            String status,
            String proposedBy,
            String decidedBy,
            Instant decidedAt,
            String reason,
            Instant createdAt,
            String effectiveFrom,
            int fundingLagDays,
            int gainMinAgeDays,
            Map<String, Integer> lagDays,
            List<RuleSetRule> rules,
            List<RuleSetTolerance> tolerances,
            List<RuleSetFeeTerms> feeSchedules,
            Map<String, Long> severityThresholds) {}

    public record RuleSetRule(
            int priority,
            String lineType,
            String keyKind,
            String expectationKind,
            String cardinality,
            boolean operationAnchored,
            int graceHours) {}

    public record RuleSetTolerance(
            String comparison, String currency, Long absoluteMinor, Integer days) {}

    public record RuleSetFeeTerms(
            String lineType,
            String currency,
            String rate,
            long fixedMinor,
            int scale,
            String roundingPolicy) {}

    /** A source's versions, newest first, bounded. */
    public record RuleSetList(List<RuleSetVersion> versions, boolean truncated) {}

    public RuleSetList ruleSets(String sourceCode) {
        List<RuleSetStore.VersionView> rows =
                command(
                        unitOfWork ->
                                ruleSets.versions(
                                        unitOfWork, sourceId(unitOfWork, sourceCode),
                                        VERSION_BOUND + 1));
        return new RuleSetList(
                rows.stream()
                        .limit(VERSION_BOUND)
                        .map(ReconciliationAdministrationDesk::version)
                        .toList(),
                rows.size() > VERSION_BOUND);
    }

    public RuleSetReceipt proposeRuleSet(
            String idempotencyKey,
            ReconciliationAdministrationController.RuleSetProposalRequest request) {
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        IdempotencyKey key =
                new IdempotencyKey(
                        PROPOSE_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(
                        String.valueOf(request).getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome =
                guarded(
                        () ->
                                command(
                                        unitOfWork ->
                                                executor.execute(
                                                        unitOfWork,
                                                        key,
                                                        fingerprint,
                                                        uow ->
                                                                proposed(
                                                                        uow, request, actor,
                                                                        correlation))));
        String[] stored =
                new String(
                                outcome.body()
                                        .orElseThrow(
                                                () ->
                                                        new IllegalStateException(
                                                                "a proposal stores its"
                                                                        + " receipt")),
                                StandardCharsets.UTF_8)
                        .split("\\|", -1);
        return new RuleSetReceipt(stored[0], Integer.parseInt(stored[1]), "PROPOSED", null);
    }

    public RuleSetReceipt approveRuleSet(String ruleSetId, String reason) {
        UUID id = ruleSetIdOf(ruleSetId);
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        return receipt(
                guarded(
                        () ->
                                command(
                                        unitOfWork ->
                                                ruleSets.approve(
                                                        unitOfWork, id, actor, reason, now(),
                                                        correlation.correlationId()))));
    }

    public RuleSetReceipt rejectRuleSet(String ruleSetId, String reason) {
        UUID id = ruleSetIdOf(ruleSetId);
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        return receipt(
                guarded(
                        () ->
                                command(
                                        unitOfWork ->
                                                ruleSets.reject(
                                                        unitOfWork, id, actor, reason, now(),
                                                        correlation.correlationId()))));
    }

    /** The claimed proposal: the version, its content, history and audit, the receipt stored. */
    private CommandResult proposed(
            Connection unitOfWork,
            ReconciliationAdministrationController.RuleSetProposalRequest request,
            Actor actor,
            Correlation correlation) {
        RuleSetAdministration.Proposed proposed =
                ruleSets.propose(
                        unitOfWork,
                        proposal(sourceId(unitOfWork, request.sourceCode()), request),
                        actor,
                        now(),
                        correlation.correlationId());
        String receipt = proposed.ruleSetId() + "|" + proposed.version();
        return CommandResult.succeeded(
                StoredResponse.of(receipt.getBytes(StandardCharsets.UTF_8), "text/plain"));
    }

    /**
     * The request in the domain's words. A name outside a vocabulary is the proposal's defect
     * ({@code RuleSetInvalid}); a tolerance's comparison is passed through untouched, so an
     * amount tolerance meets {@code ToleranceNotPermitted} (INV-REC-08) at the domain.
     */
    private static RuleSetProposal proposal(
            UUID sourceId, ReconciliationAdministrationController.RuleSetProposalRequest request) {
        try {
            Map<ExpectationKind, Integer> lags = new LinkedHashMap<>();
            request.lagDays().forEach((kind, days) -> lags.put(ExpectationKind.valueOf(kind), days));
            Map<CurrencyCode, Long> severities = new LinkedHashMap<>();
            request.severityThresholds()
                    .forEach((currency, minor) -> severities.put(CurrencyCode.of(currency), minor));
            return new RuleSetProposal(
                    sourceId,
                    request.fundingLagDays(),
                    request.gainMinAgeDays(),
                    lags,
                    request.rules().stream()
                            .map(rule ->
                                    new RuleSetProposal.Rule(
                                            rule.priority(),
                                            ExternalLineType.valueOf(rule.lineType()),
                                            Optional.ofNullable(rule.keyKind()),
                                            Optional.ofNullable(rule.expectationKind())
                                                    .map(ExpectationKind::valueOf),
                                            Cardinality.valueOf(rule.cardinality()),
                                            rule.operationAnchored(),
                                            rule.graceHours()))
                            .toList(),
                    request.tolerances().stream()
                            .map(tolerance ->
                                    new RuleSetProposal.Tolerance(
                                            tolerance.comparison(),
                                            Optional.ofNullable(tolerance.currency())
                                                    .map(CurrencyCode::of),
                                            Optional.ofNullable(tolerance.absoluteMinor()),
                                            Optional.ofNullable(tolerance.days())))
                            .toList(),
                    request.feeSchedules().stream()
                            .map(terms ->
                                    new RuleSetProposal.FeeTerms(
                                            ExternalLineType.valueOf(terms.lineType()),
                                            CurrencyCode.of(terms.currency()),
                                            new BigDecimal(terms.rate()),
                                            terms.fixedMinor(),
                                            terms.scale(),
                                            terms.roundingPolicy()))
                            .toList(),
                    severities,
                    request.reason());
        } catch (IllegalArgumentException outsideAVocabulary) {
            throw new ApiException(
                    ReconciliationErrorCode.RULE_SET_INVALID,
                    "A rule set proposal was refused",
                    "the proposal names a value outside its vocabulary.");
        }
    }

    private static RuleSetReceipt receipt(RuleSetAdministration.Decided decided) {
        return new RuleSetReceipt(
                decided.ruleSetId().toString(),
                decided.version(),
                decided.status().name(),
                decided.retiredRuleSetId().map(UUID::toString).orElse(null));
    }

    private static RuleSetVersion version(RuleSetStore.VersionView view) {
        RuleSetStore.VersionRow row = view.row();
        Map<String, Integer> lags = new LinkedHashMap<>();
        view.lagDays().forEach((kind, days) -> lags.put(kind.name(), days));
        Map<String, Long> severities = new LinkedHashMap<>();
        view.severityThresholds().forEach((currency, minor) -> severities.put(currency.code(), minor));
        return new RuleSetVersion(
                row.id().toString(),
                row.version(),
                row.status().name(),
                row.proposedBy(),
                row.decidedBy().orElse(null),
                row.decidedAt().orElse(null),
                row.reason(),
                row.createdAt(),
                row.effectiveFrom().toString(),
                row.fundingLagDays(),
                row.gainMinAgeDays(),
                lags,
                view.rules().stream()
                        .map(rule ->
                                new RuleSetRule(
                                        rule.priority(),
                                        rule.lineType().name(),
                                        rule.keyKind().orElse(null),
                                        rule.expectationKind().map(Enum::name).orElse(null),
                                        rule.cardinality().name(),
                                        rule.operationAnchored(),
                                        rule.graceHours()))
                        .toList(),
                view.tolerances().stream()
                        .map(tolerance ->
                                new RuleSetTolerance(
                                        tolerance.comparison(),
                                        tolerance.currency().map(CurrencyCode::code).orElse(null),
                                        tolerance.absoluteMinor().orElse(null),
                                        tolerance.days().orElse(null)))
                        .toList(),
                view.feeSchedules().stream()
                        .map(terms ->
                                new RuleSetFeeTerms(
                                        terms.lineType().name(),
                                        terms.currency().code(),
                                        terms.rate().toPlainString(),
                                        terms.fixedMinor(),
                                        terms.scale(),
                                        terms.roundingPolicy()))
                        .toList(),
                severities);
    }

    private static UUID ruleSetIdOf(String ruleSetId) {
        try {
            return UUID.fromString(ruleSetId);
        } catch (IllegalArgumentException malformed) {
            throw notFound(ReconciliationErrorCode.RULE_SET_NOT_FOUND);
        }
    }

    public ReprocessingReceipt requestReprocessing(
            String idempotencyKey, String sourceCode, String reason) {
        // The reason's screen runs before the claim: a refused reason leaves not even a claim
        // (INV-PAY-02, INV-RAIL-03 - the Phase 8 -> 9 transition's SEC-04 correction).
        guarded(() -> RunAdministration.refuseReason(reason));
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        IdempotencyKey key =
                new IdempotencyKey(
                        REPROCESS_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(
                        (sourceCode + "|" + reason).getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome =
                guarded(
                        () ->
                                command(
                                        unitOfWork ->
                                                executor.execute(
                                                        unitOfWork,
                                                        key,
                                                        fingerprint,
                                                        uow ->
                                                                reprocessed(
                                                                        uow, sourceCode, actor,
                                                                        reason, correlation))));
        String[] stored =
                new String(
                                outcome.body()
                                        .orElseThrow(
                                                () ->
                                                        new IllegalStateException(
                                                                "a reprocess request stores its"
                                                                        + " receipt")),
                                StandardCharsets.UTF_8)
                        .split("\\|", -1);
        return new ReprocessingReceipt(
                stored[0], sourceCode, stored[1], Integer.parseInt(stored[2]));
    }

    /** The claimed command: the run's birth and its audit record, the receipt stored. */
    private CommandResult reprocessed(
            Connection unitOfWork,
            String sourceCode,
            Actor actor,
            String reason,
            Correlation correlation) {
        RunAdministration.Reprocessing opened =
                runs.requestReprocessing(
                        unitOfWork,
                        sourceId(unitOfWork, sourceCode),
                        actor,
                        reason,
                        now(),
                        correlation.correlationId());
        String receipt = opened.runId() + "|" + opened.ruleSetId() + "|" + opened.itemCount();
        return CommandResult.succeeded(
                StoredResponse.of(receipt.getBytes(StandardCharsets.UTF_8), "text/plain"));
    }

    public RunRequeuedReceipt requeue(String runId, String reason) {
        UUID id = runIdOf(runId);
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        RunAdministration.Requeued requeued =
                guarded(
                        () ->
                                command(
                                        unitOfWork ->
                                                runs.requeue(
                                                        unitOfWork, id, actor, reason, now(),
                                                        correlation.correlationId())));
        return new RunRequeuedReceipt(requeued.runId().toString(), requeued.status().name());
    }

    public RunReplayVerdict replay(String runId) {
        UUID id = runIdOf(runId);
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        RunReplays.Replay replay =
                guarded(() -> replays.replay(id, actor, correlation.correlationId()));
        return new RunReplayVerdict(
                replay.replayId().toString(),
                replay.runId().toString(),
                replay.verdict(),
                replay.replayed(),
                replay.notReplayed(),
                replay.divergences(),
                replay.pendingRematch(),
                replay.firstDivergentDecision().map(UUID::toString).orElse(null));
    }

    // ================================================================== plumbing

    private UUID sourceId(Connection unitOfWork, String sourceCode) {
        return sourceRows
                .sourceByCode(unitOfWork, sourceCode)
                .map(SettlementFileStore.SourceRow::id)
                .orElseThrow(
                        () ->
                                new ApiException(
                                        ReconciliationErrorCode.SOURCE_NOT_FOUND,
                                        "No declared settlement source matches the code",
                                        "no such source."));
    }

    private static UUID runIdOf(String runId) {
        try {
            return UUID.fromString(runId);
        } catch (IllegalArgumentException malformed) {
            // Malformed and unknown are ONE answer (the settlement.FileNotFound departure).
            throw notFound(ReconciliationErrorCode.RUN_NOT_FOUND);
        }
    }

    private Instant now() {
        return Instant.now(clock);
    }

    /** The domain's refusals, in the API's words. */
    private static <R> R guarded(Supplier<R> work) {
        try {
            return work.get();
        } catch (RunAdministration.RunNotFound | RunReplays.RunNotFound unknown) {
            throw notFound(ReconciliationErrorCode.RUN_NOT_FOUND);
        } catch (RunAdministration.RunNotBlocked notBlocked) {
            throw refused(ReconciliationErrorCode.RUN_NOT_BLOCKED, notBlocked);
        } catch (RunAdministration.ReprocessingInProgress open) {
            throw refused(ReconciliationErrorCode.REPROCESSING_IN_PROGRESS, open);
        } catch (RuleSetAdministration.RuleSetNotFound unknown) {
            throw notFound(ReconciliationErrorCode.RULE_SET_NOT_FOUND);
        } catch (RuleSetAdministration.RuleSetNotPending decided) {
            throw refused(ReconciliationErrorCode.RULE_SET_NOT_PENDING, decided);
        } catch (RuleSetAdministration.RuleSetActivationBySameActor self) {
            throw refused(ReconciliationErrorCode.RULE_SET_ACTIVATION_BY_SAME_ACTOR, self);
        } catch (RuleSetAdministration.RuleSetProposalPending pending) {
            throw refused(ReconciliationErrorCode.RULE_SET_PROPOSAL_PENDING, pending);
        } catch (RuleSetAdministration.ToleranceNotPermitted amount) {
            throw refused(ReconciliationErrorCode.TOLERANCE_NOT_PERMITTED, amount);
        } catch (RuleSetAdministration.RuleSetInvalid invalid) {
            throw refused(ReconciliationErrorCode.RULE_SET_INVALID, invalid);
        } catch (RunAdministration.ReasonRequired reason) {
            throw new ApiException(
                    PlatformErrorCode.VALIDATION_FAILED,
                    "A reconciliation run command was refused",
                    reason.getMessage());
        }
    }

    private static ApiException refused(ReconciliationErrorCode code, RuntimeException cause) {
        return new ApiException(
                code, "A reconciliation controller's command was refused", cause.getMessage());
    }

    private static ApiException notFound(ReconciliationErrorCode code) {
        return new ApiException(
                code,
                "No reconciliation record matches the requested identifier",
                "no such record.");
    }

    private <R> R command(Function<Connection, R> work) {
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

    private static Correlation resolvedCorrelation() {
        Correlation current =
                CorrelationContext.current()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a reconciliation command runs inside a"
                                                        + " correlation scope"));
        return current.cause().isPresent()
                ? current
                : current.causing(CausationId.of(current.correlationId().value()));
    }
}
