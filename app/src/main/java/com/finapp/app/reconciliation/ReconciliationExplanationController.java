package com.finapp.app.reconciliation;

import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.ApiException;
import com.finapp.reconciliation.DecisionOutcome;
import com.finapp.reconciliation.MatchingStore;
import com.finapp.reconciliation.ReconciliationErrorCode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The matcher's explanation doors (`P8-TSK-011`, ADR-0068 §7): a decision explains itself
 * from STORED rows alone — the snapshot, its candidates and its allocations — never by
 * re-running anything (`reconciliation-domain.md`: deterministic and explainable). Under
 * {@link PermissionName#RECONCILIATION_INVESTIGATE}; read-only, and like the `P8-TSK-008`
 * batch read not audited per serving (the backlog's closed audit list — the rows carry
 * amounts an investigator already holds through the audited positions report's door).
 *
 * <p>Unknown and malformed ids are one named {@code 404} each (the
 * {@code settlement.FileNotFound} departure's reasoning), recording nothing. Handler names
 * are deliberately distinctive (the springdoc {@code operationId} rule).
 */
@RestController
@RequestMapping(
        path = "/operator/reconciliation",
        produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ReconciliationExplanationController {

    /** The runs listing's bound — the platform's listing bound. */
    static final int BOUND = 100;

    @NonNull private final MatchingStore store;
    @NonNull private final TransactionTemplate reconciliationTransactions;
    @NonNull private final DataSource dataSource;

    // ------------------------------------------------------------------ views

    public record RunView(
            String id,
            String sourceId,
            String batchId,
            String status,
            String ruleSetId,
            long sourceSequence,
            int itemCount,
            long cursor,
            int failures,
            String businessDate,
            Map<String, Long> outcomes) {}

    public record RunList(List<RunView> runs, boolean truncated) {}

    public record CandidateView(
            String expectationId,
            String keyKind,
            String amount,
            String currency,
            String direction,
            String remainderBefore,
            String openedAt) {}

    public record AllocationView(
            String id,
            String decisionId,
            String externalItemId,
            String expectationId,
            String amount,
            String currency,
            String reversesAllocationId,
            String createdAt) {}

    public record DecisionView(
            String id,
            String externalItemId,
            String runId,
            String origin,
            String ruleSetId,
            Integer rulePriority,
            String strategy,
            String matchedKeyKind,
            String outcome,
            Integer claimantRank,
            Integer claimantCount,
            Integer dateDeviationDays,
            Integer timingToleranceDays,
            String decidedAt,
            String decidedOn,
            List<CandidateView> candidates,
            List<AllocationView> allocations) {}

    // ------------------------------------------------------------------ doors

    /** The newest runs — identity, progress and per-outcome counts, bounded at 100. */
    @GetMapping("/runs")
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public RunList listReconciliationRuns() {
        return inOneTransaction(
                unitOfWork -> {
                    List<MatchingStore.RunRow> rows = store.runs(unitOfWork, BOUND + 1);
                    return new RunList(
                            rows.stream()
                                    .limit(BOUND)
                                    .map(row -> runView(unitOfWork, row))
                                    .toList(),
                            rows.size() > BOUND);
                });
    }

    /** One run: identity, progress, and its decisions counted per outcome. */
    @GetMapping("/runs/{id}")
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public RunView viewReconciliationRun(@PathVariable("id") String runId) {
        UUID id = parsed(runId, ReconciliationErrorCode.RUN_NOT_FOUND);
        return inOneTransaction(
                unitOfWork ->
                        store.run(unitOfWork, id)
                                .map(row -> runView(unitOfWork, row))
                                .orElseThrow(
                                        () ->
                                                notFound(
                                                        ReconciliationErrorCode
                                                                .RUN_NOT_FOUND)));
    }

    /** One decision, whole: the snapshot, its candidates and its allocations. */
    @GetMapping("/decisions/{id}")
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public DecisionView viewMatchDecision(@PathVariable("id") String decisionId) {
        UUID id = parsed(decisionId, ReconciliationErrorCode.DECISION_NOT_FOUND);
        return inOneTransaction(
                unitOfWork -> {
                    MatchingStore.DecisionRow decision =
                            store.decision(unitOfWork, id)
                                    .orElseThrow(
                                            () ->
                                                    notFound(
                                                            ReconciliationErrorCode
                                                                    .DECISION_NOT_FOUND));
                    return decisionView(
                            decision,
                            store.candidatesOf(unitOfWork, id),
                            store.allocationsOfDecision(unitOfWork, id));
                });
    }

    /** One allocation — the row a break's or an entry's investigation names. */
    @GetMapping("/allocations/{id}")
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public AllocationView viewAllocation(@PathVariable("id") String allocationId) {
        UUID id = parsed(allocationId, ReconciliationErrorCode.ALLOCATION_NOT_FOUND);
        return inOneTransaction(
                unitOfWork ->
                        store.allocation(unitOfWork, id)
                                .map(ReconciliationExplanationController::allocationView)
                                .orElseThrow(
                                        () ->
                                                notFound(
                                                        ReconciliationErrorCode
                                                                .ALLOCATION_NOT_FOUND)));
    }

    // ------------------------------------------------------------------ rendering

    private RunView runView(Connection unitOfWork, MatchingStore.RunRow row) {
        Map<DecisionOutcome, Long> counts = store.outcomeCounts(unitOfWork, row.id());
        return new RunView(
                row.id().toString(),
                row.sourceId().toString(),
                row.batchId() == null ? null : row.batchId().toString(),
                row.status().name(),
                row.ruleSetId().toString(),
                row.sourceSequence(),
                row.itemCount(),
                row.cursor(),
                row.failures(),
                row.businessDate().toString(),
                counts.entrySet().stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        entry -> entry.getKey().name(),
                                        Map.Entry::getValue)));
    }

    private static DecisionView decisionView(
            MatchingStore.DecisionRow decision,
            List<MatchingStore.CandidateRow> candidates,
            List<MatchingStore.AllocationRow> allocations) {
        return new DecisionView(
                decision.id().toString(),
                decision.externalItemId().toString(),
                decision.runId().toString(),
                decision.origin(),
                decision.ruleSetId().toString(),
                decision.rulePriority().orElse(null),
                decision.strategy().orElse(null),
                decision.matchedKeyKind().orElse(null),
                decision.outcome(),
                decision.claimantRank().orElse(null),
                decision.claimantCount().orElse(null),
                decision.dateDeviationDays().orElse(null),
                decision.timingToleranceDays().orElse(null),
                decision.decidedAt().toString(),
                decision.decidedOn().toString(),
                candidates.stream()
                        .map(
                                candidate ->
                                        new CandidateView(
                                                candidate.expectationId().toString(),
                                                candidate.keyKind(),
                                                plain(
                                                        candidate.amount().minorUnits(),
                                                        candidate.amount().scale()),
                                                candidate.amount().currency().code(),
                                                candidate.direction(),
                                                plain(
                                                        candidate.remainderBeforeMinor(),
                                                        candidate.amount().scale()),
                                                candidate.openedAt().toString()))
                        .toList(),
                allocations.stream()
                        .map(ReconciliationExplanationController::allocationView)
                        .toList());
    }

    private static AllocationView allocationView(MatchingStore.AllocationRow row) {
        return new AllocationView(
                row.id().toString(),
                row.decisionId().toString(),
                row.externalItemId().toString(),
                row.expectationId().toString(),
                plain(row.amount().minorUnits(), row.amount().scale()),
                row.amount().currency().code(),
                row.reversesAllocationId().map(UUID::toString).orElse(null),
                row.createdAt().toString());
    }

    private static String plain(long minor, int scale) {
        return BigDecimal.valueOf(minor, scale).toPlainString();
    }

    // ------------------------------------------------------------------ plumbing

    /** Unknown and malformed ids are ONE answer, recording nothing. */
    private static UUID parsed(String id, ReconciliationErrorCode absent) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException malformed) {
            throw notFound(absent);
        }
    }

    private static ApiException notFound(ReconciliationErrorCode code) {
        return new ApiException(
                code,
                "No reconciliation record matches the requested identifier",
                "no such record.");
    }

    private <R> R inOneTransaction(java.util.function.Function<Connection, R> work) {
        return reconciliationTransactions.execute(
                status -> {
                    Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
                    try {
                        return work.apply(unitOfWork);
                    } finally {
                        DataSourceUtils.releaseConnection(unitOfWork, dataSource);
                    }
                });
    }
}
