package com.finapp.app.reconciliation;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.app.api.OperatorCollection;
import com.finapp.app.session.RecordsRefusals;
import com.finapp.app.session.RequiresPermission;
import com.finapp.identity.PermissionName;
import com.finapp.platform.api.ApiException;
import com.finapp.reconciliation.DecisionOutcome;
import com.finapp.reconciliation.MatchingStore;
import com.finapp.reconciliation.ReconciliationErrorCode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.web.bind.annotation.RequestParam;
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
@RecordsRefusals
@RequestMapping(
        path = "/operator/reconciliation",
        produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
public class ReconciliationExplanationController {

    /** The run list's collection name, inside its cursors (`P17-TSK-122`). */
    static final String RUNS = "reconciliation.runs";

    static final int RUN_PAGE_DEFAULT = 50;
    static final int RUN_PAGE_MAXIMUM = 200;

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

    /** A page of the run list (`API_CONVENTIONS.md` section 7): {@code nextCursor} absent on the last page. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RunPage(List<RunView> items, String nextCursor) {}

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
            List<AllocationView> allocations,
            // The fee comparison, where a CHECK decision stored one (P8-TSK-012);
            // added at the end, additive under ADR-0015.
            String feeExpected,
            String feeReported,
            String feeTolerance) {}

    // ------------------------------------------------------------------ doors

    /**
     * The runs - identity, progress and per-outcome counts - as a section 7 collection (`P17-TSK-122`; read "the newest 100
     * with {@code truncated}", ordered by birth alone, until then): born newest first, the id breaking the tie;
     * {@code limit} {@value #RUN_PAGE_DEFAULT} unless named, {@value #RUN_PAGE_MAXIMUM} at most and refused above, never
     * reduced; the opaque {@code cursor} of the page before.
     */
    @GetMapping("/runs")
    @RequiresPermission(PermissionName.RECONCILIATION_INVESTIGATE)
    public RunPage listReconciliationRuns(
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "limit", required = false) String limit) {
        int size = OperatorCollection.limit(limit, RUN_PAGE_DEFAULT, RUN_PAGE_MAXIMUM);
        Optional<MatchingStore.RunCursor> after = runCursor(cursor);
        return inOneTransaction(
                unitOfWork -> {
                    List<MatchingStore.ListedRun> rows = store.runs(unitOfWork, after, size + 1);
                    List<MatchingStore.ListedRun> served = rows.subList(0, Math.min(size, rows.size()));
                    String next = null;
                    if (rows.size() > size) {
                        MatchingStore.ListedRun tail = served.get(served.size() - 1);
                        next = OperatorCollection.cursor(
                                RUNS, tail.createdAt().toString(), tail.run().id().toString());
                    }
                    return new RunPage(
                            served.stream().map(listed -> runView(unitOfWork, listed.run())).toList(), next);
                });
    }

    /** The position {@code cursor} names in the run list, or none for the first page; anything else refused. */
    static Optional<MatchingStore.RunCursor> runCursor(String cursor) {
        return OperatorCollection.key(RUNS, cursor, 2)
                .map(key -> {
                    try {
                        return new MatchingStore.RunCursor(Instant.parse(key.get(0)), UUID.fromString(key.get(1)));
                    } catch (RuntimeException unreadable) {
                        throw OperatorCollection.invalidCursor();
                    }
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
                            store.allocationsOfDecision(unitOfWork, id),
                            itemScale(unitOfWork, decision.externalItemId()));
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

    /** The fee triple's scale is the decided item's own (INV-MON-05). */
    private int itemScale(Connection unitOfWork, UUID itemId) {
        try (java.sql.PreparedStatement read =
                unitOfWork.prepareStatement(
                        "SELECT scale FROM reconciliation.external_item WHERE id = ?")) {
            read.setObject(1, itemId);
            try (java.sql.ResultSet row = read.executeQuery()) {
                row.next();
                return row.getInt("scale");
            }
        } catch (java.sql.SQLException failure) {
            throw new IllegalStateException("the decided item carries its scale", failure);
        }
    }

    private static DecisionView decisionView(
            MatchingStore.DecisionRow decision,
            List<MatchingStore.CandidateRow> candidates,
            List<MatchingStore.AllocationRow> allocations,
            int scale) {
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
                        .toList(),
                decision.feeExpectedMinor().map(fee -> plain(fee, scale)).orElse(null),
                decision.feeReportedMinor().map(fee -> plain(fee, scale)).orElse(null),
                decision.feeToleranceMinor().map(fee -> plain(fee, scale)).orElse(null));
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
