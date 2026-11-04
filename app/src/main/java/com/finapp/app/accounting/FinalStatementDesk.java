package com.finapp.app.accounting;

import com.finapp.accounting.AccountingAuditAction;
import com.finapp.accounting.AccountingErrorCode;
import com.finapp.accounting.GlPins;
import com.finapp.accounting.ReportRunId;
import com.finapp.accounting.ReportRunStore;
import com.finapp.accounting.ReportRuns;
import com.finapp.accounting.RunStatus;
import com.finapp.accounting.reporting.BalanceSheetDefinition;
import com.finapp.accounting.reporting.IncomeStatementDefinition;
import com.finapp.platform.api.ApiException;
import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.correlation.CorrelationContext;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A FINAL statement as a controller reads it (`P14-TSK-022`, the owner's ruling (v); ADR-0115; {@code INV-ACC-04}, {@code INV-ACC-05}). Admits a {@code BALANCE_SHEET} or an {@code INCOME_STATEMENT}, plain or translated, whose run is {@code FINAL}, and nothing else: another definition, another run status, an unknown
 * id and a malformed one are one {@code 404}, the same code, title and detail, so the door tells a caller nothing about what a run is.
 *
 * <p><strong>A superseded statement is not read here</strong>: the ruling says FINAL, and the accountant's door is where history is read. <strong>A month after a reopen</strong>: its FINAL statements go {@code SUPERSEDED} in the reopen's own transaction, so the old id answers the 404 at once, and a controller reads
 * nothing of that month until it is closed again and a statement generated, a new run with a new id. A reopen only <em>proposed</em> moves nothing: the statement stands and is served.
 *
 * <p><strong>What is withheld, and why it is not the issued bytes.</strong> The issued statement is written for an accountant: its {@code drillDown} and each note's {@code route} and {@code reasons} name doors under {@code ACCOUNTING_READ} and {@code RECONCILIATION_INVESTIGATE}, none of which opens under this
 * permission, and {@code untraced}'s route says "under this role" meaning the accountant's. Served whole, a controller would read directions to doors it cannot open. So the view is the issued JSON with those members removed at any depth, <strong>and nothing else changed</strong>; {@code withheld} names each
 * path removed; {@link #DOORS} says, once, what does and does not open here and what {@code untraced} means. Its figures are the issued figures: this reads no journal, and {@code issuedSha256} is the hash of the issued bytes, named so nobody takes it for this projection's. The stored bytes do not move: nothing here writes.
 *
 * <p><strong>The real status of the month</strong>, as {@link ReportRunLinesDesk} serves it: {@code periodStatus} is read from the period's own row in the same {@code REPEATABLE READ READ ONLY} snapshot as the run ({@code NONE} when no row holds it), never inferred from the run. The read is recorded
 * {@code accounting.FinalStatementRead} in a transaction of its own <strong>before it is served</strong>, a failed record failing the read, as {@code accounting.CloseProposalRead} is. A refusal records nothing, since it served nothing.
 *
 * <p>How a controller comes by the id: the accountant who generated the run hands it over (the owner's (w)). The view's {@code toString} holds no content.
 */
@RequiredArgsConstructor
public final class FinalStatementDesk {

    static final String SPAN = "accounting.report.final-statement";

    /** The audit record's target type. */
    static final String TARGET = "report_run";

    /** What this door admits: the two statements. */
    static final Set<String> STATEMENTS = Set.of(BalanceSheetDefinition.CODE, IncomeStatementDefinition.CODE);

    /** The members a controller's view withholds, wherever they stand: what names a door written for another role. */
    static final Set<String> WITHHELD_MEMBERS = Set.of("drillDown", "route", "reasons");

    /** What opens here and what does not, in the words a controller reads: the one place this view names a door. */
    static final String DOORS = "This is the issued statement with its routes withheld: drillDown and each note's route and reasons name doors written for an accountant (ACCOUNTING_READ) and a reconciliation operator (RECONCILIATION_INVESTIGATE), and none of them opens under"
            + " ACCOUNTING_CLOSE_APPROVE. No journal line comes through this read: a figure's lines are an accountant's to read, so hand over this run's id. What does open under this permission: a DISCLOSED attempt's reason, and the adjustments posted against the month, at GET"
            + " /v1/operator/accounting/periods/{periodId}/close-proposal. notes.priorPeriodCorrections.untraced counts entries of the ACCOUNTING origin with no posted adjustment beside them: zero is the one expected answer, and any other means a writer bypassed the door, so escalate.";

    /** The issued form holds no JSON number at all ({@code CanonicalJson}), so a parse rewrites no figure. Immutable and thread-safe: configuration, never state. */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @NonNull private final ReportRuns runs;
    @NonNull private final GlPins.PeriodPins periods;
    @NonNull private final ReportSnapshots snapshots;
    @NonNull private final AccountingTransactions transactions;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final AccountingFlowScope flow;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * A FINAL statement as a controller reads it - no person, no journal line, no door it cannot open.
     *
     * @param periodStatus the month's own status, read with the run ({@code NONE} when no period row holds it)
     * @param status the run's: always {@code FINAL} here, since no other is served
     * @param issuedSha256 the hash of the issued bytes, not of {@code statement}
     * @param statement the issued canonical JSON with {@code withheld} removed
     * @param withheld each path removed from the issued content, sorted
     * @param doors what opens under this permission and what does not
     */
    public record FinalStatementView(
            String runId,
            String definition,
            int definitionVersion,
            String periodId,
            String period,
            int periodRevision,
            String periodStatus,
            int revision,
            String status,
            Integer mappingVersion,
            String valuationRateSetId,
            Map<String, String> parameters,
            String issuedSha256,
            String completedAt,
            String statement,
            List<String> withheld,
            String doors) {

        /** Served to the controller and never logged: {@code CONFIDENTIAL} aggregates would otherwise reach a log line in a record's generated {@code toString}. */
        @Override
        public String toString() {
            return "FinalStatementView[" + runId + ", " + definition + ", " + period + "]";
        }
    }

    /** The FINAL statement {@code rawRunId} names, recorded before it is served; anything else is the one 404. */
    public FinalStatementView read(String rawRunId) {
        ReportRunId id = runId(rawRunId);
        Actor actor = SecurityContext.require();
        CorrelationId correlation = correlation();
        return flow.door(SPAN, () -> {
            Served served = snapshots.read(snapshot -> {
                ReportRunStore.RunRow run = runs.find(snapshot, id).filter(FinalStatementDesk::admitted).orElseThrow(FinalStatementDesk::notFound);
                String issued = runs.content(snapshot, id).map(ReportRunStore.Content::text).orElseThrow(() -> new IllegalStateException("a FINAL run holds its content"));
                return new Served(run, issued, periods.of(snapshot, run.period()).map(pin -> pin.status().name()).orElse("NONE"));
            });
            Projection projection = project(served.issued());
            ReportRunStore.RunRow run = served.run();
            transactions.inTransaction(unitOfWork -> {
                audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, Instant.now(clock), AccountingAuditAction.FINAL_STATEMENT_READ, TARGET, id.value().toString(), Optional.empty(), AuditOutcome.SUCCEEDED, correlation,
                        Optional.of("definition=" + run.definition() + ", period=" + run.period() + ", periodRevision=" + run.periodRevision() + ", periodStatus=" + served.periodStatus() + ", revision=" + run.revision() + ", issuedSha256=" + run.contentSha256().orElse(""))));
                return null;
            });
            return new FinalStatementView(id.value().toString(), run.definition(), run.definitionVersion(), run.periodId().value().toString(), run.period().toString(), run.periodRevision(), served.periodStatus(), run.revision(), run.status().name(),
                    run.mappingVersion().orElse(null), run.valuationRateSetId().map(UUID::toString).orElse(null), run.parameters().values(), run.contentSha256().orElse(null), run.completedAt().map(Instant::toString).orElse(null), projection.statement(), projection.withheld(), DOORS);
        });
    }

    /** What one snapshot read. */
    private record Served(ReportRunStore.RunRow run, String issued, String periodStatus) {}

    /** The issued content less what names a door, and each path removed. */
    record Projection(String statement, List<String> withheld) {

        /** Nothing here is logged. */
        @Override
        public String toString() {
            return "Projection[" + withheld + "]";
        }
    }

    /** {@code BALANCE_SHEET} or {@code INCOME_STATEMENT}, and {@code FINAL}: the whole of what this door admits. */
    static boolean admitted(ReportRunStore.RunRow run) {
        return STATEMENTS.contains(run.definition()) && run.status() == RunStatus.FINAL;
    }

    /** Removes {@link #WITHHELD_MEMBERS} at any depth and changes nothing else, member order and numbers included. */
    static Projection project(String issued) {
        JsonNode root = JSON.readTree(issued);
        Set<String> withheld = new TreeSet<>();
        strip(root, "", withheld);
        return new Projection(JSON.writeValueAsString(root), List.copyOf(withheld));
    }

    private static void strip(JsonNode node, String path, Set<String> withheld) {
        if (node instanceof ObjectNode object) {
            for (String member : new ArrayList<>(object.propertyNames())) {
                String at = path.isEmpty() ? member : path + "." + member;
                if (WITHHELD_MEMBERS.contains(member)) {
                    object.remove(member);
                    withheld.add(at);
                } else {
                    strip(object.get(member), at, withheld);
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(element -> strip(element, path, withheld));
        }
    }

    /** One answer for everything this door does not serve: a distinct one would make it an oracle over what a run is. */
    private static ApiException notFound() {
        return new ApiException(AccountingErrorCode.NOT_FOUND, "No report run or accounting period answers the request", "no such record.");
    }

    private static ReportRunId runId(String raw) {
        try {
            return ReportRunId.of(UUID.fromString(raw));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            throw notFound();
        }
    }

    private static CorrelationId correlation() {
        return CorrelationContext.current()
                .orElseThrow(() -> new IllegalStateException("a report run read runs inside a correlation scope"))
                .correlationId();
    }
}
