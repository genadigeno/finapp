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
import com.finapp.reconciliation.BreakCaseFile;
import com.finapp.reconciliation.BreakCaseStore;
import com.finapp.reconciliation.BreakInquiries;
import com.finapp.reconciliation.BreakStatus;
import com.finapp.reconciliation.BreakTrace;
import com.finapp.reconciliation.BreakTraces;
import com.finapp.reconciliation.BreakType;
import com.finapp.reconciliation.EvidenceTargetKind;
import com.finapp.reconciliation.ExpectationInquiries;
import com.finapp.reconciliation.ExpectationKind;
import com.finapp.reconciliation.ExpectationStatus;
import com.finapp.reconciliation.ReconciliationErrorCode;
import com.finapp.reconciliation.SettlementStatuses;
import com.finapp.reconciliation.Severity;
import com.finapp.sharedkernel.correlation.Correlation;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import javax.sql.DataSource;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The investigator's desk (`P8-TSK-014`, ADR-0069 §7): break reads, the case file, the
 * trace, expectation reads and an operation's settlement status — all under
 * {@code RECONCILIATION_INVESTIGATE} at the door.
 *
 * <p>Commands are ONE transaction each, in reconciliation's {@link BreakCaseFile}. Notes and
 * evidence links are keyed per principal from birth ({@code reconciliation.break-note:} and
 * {@code reconciliation.break-evidence-link:<actorType>:<actorId>}, the `X-TSK-003`
 * disposition): the shape screen runs BEFORE the claim (a refused body stores nothing, not
 * even a claim), the state checks inside it (a refusal rolls the claim back; a replay of a
 * success replays byte for byte). <strong>The stored response never carries the note
 * body</strong> — the idempotency record would otherwise be a second, unscreened home for
 * CONFIDENTIAL text. Assignment and reclassification are idempotent by state.
 *
 * <p>Reads run in one {@code REPEATABLE READ}, read-only snapshot, so a trace, a case file or
 * a status and its trail are one moment of the books. Unknown and malformed identifiers are
 * one named {@code 404} each, recording nothing.
 */
@RequiredArgsConstructor
public class BreakInvestigation {

    static final int BOUND = 100;

    static final String NOTE_SCOPE = "reconciliation.break-note:";
    static final String LINK_SCOPE = "reconciliation.break-evidence-link:";

    @NonNull private final BreakCaseFile caseFile;
    @NonNull private final BreakInquiries breaks;
    @NonNull private final BreakTraces traces;
    @NonNull private final ExpectationInquiries expectations;
    @NonNull private final SettlementStatuses statuses;
    @NonNull private final IdempotentExecutor executor;
    @NonNull private final TransactionTemplate reconciliationTransactions;
    @NonNull private final TransactionTemplate reconciliationSnapshotReads;
    @NonNull private final DataSource dataSource;

    // ================================================================== views

    public record BreakView(
            String id,
            String type,
            String cause,
            String status,
            String severity,
            String sourceId,
            String subjectKind,
            String subjectId,
            String valueAtIssue,
            String currency,
            String assignee,
            long residualVersion,
            String followsBreakId,
            String raisedAt,
            String statusChangedAt,
            String resolvedAt) {}

    public record BreakList(List<BreakView> breaks, boolean truncated) {}

    public record HistoryView(
            long seq,
            String eventType,
            String actor,
            String actorType,
            String reason,
            String detail,
            String occurredAt) {}

    public record NoteView(
            String id, String body, String author, String authorType, String addedAt) {}

    public record LinkView(
            String id,
            String targetKind,
            String targetRef,
            String addedBy,
            String addedByType,
            String addedAt) {}

    public record ResolutionView(
            String id,
            String kind,
            String status,
            String reasonCode,
            String narrative,
            String amount,
            String currency,
            String decisionId,
            String parkId,
            String adjustmentProposalId,
            String journalEntryId,
            String proposedBy,
            String proposedByType,
            String proposedAt,
            String decidedBy,
            String decidedAt) {}

    public record BreakDetail(
            BreakView breakRecord,
            String internalClassification,
            String internalOperationRef,
            String internalState,
            List<String> predecessorIds,
            List<HistoryView> history,
            List<NoteView> notes,
            List<LinkView> evidenceLinks,
            List<ResolutionView> resolutions) {}

    public record TraceStepView(
            String fromKind, String fromId, String relation, String toKind, String toId) {}

    public record TraceView(String breakId, List<TraceStepView> steps, boolean truncated) {}

    /** A note's receipt — its identity and length, never its body. */
    public record NoteReceipt(String noteId, String breakId, String addedAt, int length) {}

    public record LinkReceipt(
            String linkId, String breakId, String targetKind, String targetRef, String addedAt) {}

    public record ExpectationView(
            String id,
            String kind,
            String operationRef,
            String sourceId,
            String positionPurpose,
            String direction,
            String amount,
            String currency,
            String allocated,
            String resolved,
            String status,
            String journalEntryId,
            String ledgerAccountId,
            String postingDate,
            String settlementCycle,
            String expectedBy,
            String overdueSince,
            String ruleSetId,
            String openedAt) {}

    public record ExpectationList(List<ExpectationView> expectations, boolean truncated) {}

    public record ExpectationKeyView(String keyKind, String keyValue) {}

    public record ExpectationHistoryView(
            long seq, String eventType, String detail, String actor, String actorType,
            String occurredAt) {}

    public record ExpectationAllocationView(
            String id,
            String decisionId,
            String externalItemId,
            String amount,
            String reversesAllocationId,
            String createdAt) {}

    public record ExpectationBreakView(String id, String type, String status, String severity) {}

    public record ExpectationDetail(
            ExpectationView expectation,
            List<ExpectationKeyView> keys,
            List<ExpectationHistoryView> history,
            List<ExpectationAllocationView> allocations,
            boolean allocationsTruncated,
            List<ExpectationBreakView> breaks) {}

    public record SettlementStatusView(
            String expectationId,
            String kind,
            String operationRef,
            String status,
            List<String> allocationIds,
            List<String> itemIds,
            List<String> batchIds,
            List<String> remittanceIds,
            List<String> bankItemIds,
            boolean truncated) {}

    // ================================================================== break reads

    public BreakList listBreaks(
            String type,
            String status,
            String severity,
            String source,
            String assignee,
            Integer agedOver) {
        BreakInquiries.BreakFilter filter =
                new BreakInquiries.BreakFilter(
                        enumParam("type", type, BreakType.values()),
                        enumParam("status", status, BreakStatus.values()),
                        enumParam("severity", severity, Severity.values()),
                        uuidParam("source", source),
                        Optional.ofNullable(assignee).filter(value -> !value.isBlank()),
                        Optional.ofNullable(agedOver).map(BreakInvestigation::agedOver));
        return snapshot(
                unitOfWork -> {
                    List<BreakCaseStore.BreakRow> rows =
                            breaks.breaks(unitOfWork, filter, BOUND + 1);
                    return new BreakList(
                            rows.stream().limit(BOUND).map(BreakInvestigation::breakView).toList(),
                            rows.size() > BOUND);
                });
    }

    public BreakDetail viewBreak(String rawBreakId) {
        UUID breakId = parsed(rawBreakId, ReconciliationErrorCode.BREAK_NOT_FOUND);
        return snapshot(
                unitOfWork -> {
                    BreakCaseStore.BreakRow row =
                            breaks.breakById(unitOfWork, breakId)
                                    .orElseThrow(
                                            () -> notFound(ReconciliationErrorCode.BREAK_NOT_FOUND));
                    return new BreakDetail(
                            breakView(row),
                            row.internalClassification().orElse(null),
                            row.internalOperationRef().orElse(null),
                            row.internalState().orElse(null),
                            breaks.predecessors(unitOfWork, breakId).stream()
                                    .map(predecessor -> predecessor.id().toString())
                                    .toList(),
                            breaks.history(unitOfWork, breakId).stream()
                                    .map(
                                            event ->
                                                    new HistoryView(
                                                            event.seq(),
                                                            event.eventType(),
                                                            event.actor(),
                                                            event.actorType(),
                                                            event.reason().orElse(null),
                                                            event.detail().orElse(null),
                                                            event.occurredAt().toString()))
                                    .toList(),
                            breaks.notes(unitOfWork, breakId).stream()
                                    .map(
                                            note ->
                                                    new NoteView(
                                                            note.id().toString(),
                                                            note.body(),
                                                            note.author(),
                                                            note.authorType(),
                                                            note.addedAt().toString()))
                                    .toList(),
                            breaks.links(unitOfWork, breakId).stream()
                                    .map(
                                            link ->
                                                    new LinkView(
                                                            link.id().toString(),
                                                            link.targetKind(),
                                                            link.targetRef(),
                                                            link.addedBy(),
                                                            link.addedByType(),
                                                            link.addedAt().toString()))
                                    .toList(),
                            breaks.resolutions(unitOfWork, breakId).stream()
                                    .map(BreakInvestigation::resolutionView)
                                    .toList());
                });
    }

    public TraceView traceBreak(String rawBreakId) {
        UUID breakId = parsed(rawBreakId, ReconciliationErrorCode.BREAK_NOT_FOUND);
        BreakTrace trace =
                snapshot(unitOfWork -> traces.trace(unitOfWork, breakId))
                        .orElseThrow(() -> notFound(ReconciliationErrorCode.BREAK_NOT_FOUND));
        return new TraceView(
                trace.breakId().toString(),
                trace.steps().stream()
                        .map(
                                step ->
                                        new TraceStepView(
                                                step.fromKind().name(),
                                                step.fromId(),
                                                step.relation().name(),
                                                step.toKind().name(),
                                                step.toId()))
                        .toList(),
                trace.truncated());
    }

    // ================================================================== the case file

    public BreakView assign(String rawBreakId, String assigneeId) {
        UUID breakId = parsed(rawBreakId, ReconciliationErrorCode.BREAK_NOT_FOUND);
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        return guarded(
                () ->
                        breakView(
                                command(
                                                unitOfWork ->
                                                        caseFile.assign(
                                                                unitOfWork, breakId,
                                                                assigneeId == null
                                                                        ? ""
                                                                        : assigneeId,
                                                                actor,
                                                                correlation.correlationId()))
                                        .row()));
    }

    public BreakView reclassify(String rawBreakId, String type, String reason) {
        UUID breakId = parsed(rawBreakId, ReconciliationErrorCode.BREAK_NOT_FOUND);
        BreakType to =
                enumParam("type", type, BreakType.values())
                        .orElseThrow(() -> invalid("type is required"));
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        return guarded(
                () ->
                        breakView(
                                command(
                                                unitOfWork ->
                                                        caseFile.reclassify(
                                                                unitOfWork, breakId, to,
                                                                reason == null ? "" : reason,
                                                                actor,
                                                                correlation.correlationId()))
                                        .row()));
    }

    public NoteReceipt addNote(String rawBreakId, String idempotencyKey, String body) {
        UUID breakId = parsed(rawBreakId, ReconciliationErrorCode.BREAK_NOT_FOUND);
        String text = body == null ? "" : body;
        // The shape screen BEFORE any claim: a refused body stores nothing at all.
        guarded(() -> {
            BreakCaseFile.refuseNote(text);
            return null;
        });
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        IdempotencyKey key =
                new IdempotencyKey(
                        NOTE_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(
                        (breakId + "|" + text).getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome =
                guarded(
                        () ->
                                command(
                                        unitOfWork ->
                                                executor.execute(
                                                        unitOfWork,
                                                        key,
                                                        fingerprint,
                                                        uow -> {
                                                            BreakCaseFile.NoteAdded added =
                                                                    caseFile.addNote(
                                                                            uow, breakId, text,
                                                                            actor,
                                                                            correlation
                                                                                    .correlationId());
                                                            return storedResult(
                                                                    added.noteId() + "|"
                                                                            + added.breakId()
                                                                            + "|"
                                                                            + added.addedAt()
                                                                            + "|"
                                                                            + added.length());
                                                        })));
        String[] fields = recorded(outcome, 4);
        return new NoteReceipt(fields[0], fields[1], fields[2], Integer.parseInt(fields[3]));
    }

    public LinkReceipt linkEvidence(
            String rawBreakId, String idempotencyKey, String targetKind, String targetRef) {
        UUID breakId = parsed(rawBreakId, ReconciliationErrorCode.BREAK_NOT_FOUND);
        EvidenceTargetKind kind =
                enumParam("targetKind", targetKind, EvidenceTargetKind.values())
                        .orElseThrow(() -> invalid("targetKind is required"));
        String ref = targetRef == null ? "" : targetRef;
        guarded(() -> {
            BreakCaseFile.refuseLink(kind, ref);
            return null;
        });
        Actor actor = SecurityContext.require();
        Correlation correlation = resolvedCorrelation();
        IdempotencyKey key =
                new IdempotencyKey(
                        LINK_SCOPE + actor.type().name() + ":" + actor.id(), idempotencyKey);
        RequestFingerprint fingerprint =
                RequestFingerprint.sha256(
                        (breakId + "|" + kind.name() + "|" + ref)
                                .getBytes(StandardCharsets.UTF_8));
        IdempotentExecutor.ExecutionOutcome outcome =
                guarded(
                        () ->
                                command(
                                        unitOfWork ->
                                                executor.execute(
                                                        unitOfWork,
                                                        key,
                                                        fingerprint,
                                                        uow -> {
                                                            BreakCaseFile.EvidenceLinked linked =
                                                                    caseFile.link(
                                                                            uow, breakId, kind,
                                                                            ref, actor,
                                                                            correlation
                                                                                    .correlationId());
                                                            return storedResult(
                                                                    linked.linkId() + "|"
                                                                            + linked.breakId()
                                                                            + "|"
                                                                            + linked.kind().name()
                                                                            + "|"
                                                                            + linked.addedAt()
                                                                            + "|"
                                                                            + linked.targetRef());
                                                        })));
        String[] fields = recorded(outcome, 5);
        return new LinkReceipt(fields[0], fields[1], fields[2], fields[4], fields[3]);
    }

    // ================================================================== expectations

    public ExpectationList listExpectations(String status, Boolean overdue, String source) {
        ExpectationInquiries.ExpectationFilter filter =
                new ExpectationInquiries.ExpectationFilter(
                        enumParam("status", status, ExpectationStatus.values()),
                        Optional.ofNullable(overdue),
                        uuidParam("source", source));
        return snapshot(
                unitOfWork -> {
                    List<ExpectationInquiries.ExpectationRow> rows =
                            expectations.expectations(unitOfWork, filter, BOUND + 1);
                    return new ExpectationList(
                            rows.stream()
                                    .limit(BOUND)
                                    .map(BreakInvestigation::expectationView)
                                    .toList(),
                            rows.size() > BOUND);
                });
    }

    public ExpectationDetail viewExpectation(String rawExpectationId) {
        UUID expectationId =
                parsed(rawExpectationId, ReconciliationErrorCode.EXPECTATION_NOT_FOUND);
        return snapshot(
                unitOfWork -> {
                    ExpectationInquiries.ExpectationRow row =
                            expectations.expectationById(unitOfWork, expectationId)
                                    .orElseThrow(
                                            () ->
                                                    notFound(
                                                            ReconciliationErrorCode
                                                                    .EXPECTATION_NOT_FOUND));
                    List<ExpectationInquiries.AllocationRow> allocations =
                            expectations.allocations(unitOfWork, expectationId, BOUND + 1);
                    return new ExpectationDetail(
                            expectationView(row),
                            expectations.keys(unitOfWork, expectationId).stream()
                                    .map(key -> new ExpectationKeyView(key.keyKind(), key.keyValue()))
                                    .toList(),
                            expectations.events(unitOfWork, expectationId).stream()
                                    .map(
                                            event ->
                                                    new ExpectationHistoryView(
                                                            event.seq(),
                                                            event.eventType(),
                                                            event.detail().orElse(null),
                                                            event.actor(),
                                                            event.actorType(),
                                                            event.occurredAt().toString()))
                                    .toList(),
                            allocations.stream()
                                    .limit(BOUND)
                                    .map(
                                            allocation ->
                                                    new ExpectationAllocationView(
                                                            allocation.id().toString(),
                                                            allocation.decisionId().toString(),
                                                            allocation.externalItemId().toString(),
                                                            plain(allocation.amountMinor(),
                                                                    row.scale()),
                                                            allocation.reversesAllocationId()
                                                                    .map(UUID::toString)
                                                                    .orElse(null),
                                                            allocation.createdAt().toString()))
                                    .toList(),
                            allocations.size() > BOUND,
                            expectations.breaksOn(unitOfWork, expectationId).stream()
                                    .map(
                                            ref ->
                                                    new ExpectationBreakView(
                                                            ref.id().toString(), ref.type(),
                                                            ref.status(), ref.severity()))
                                    .toList());
                });
    }

    public SettlementStatusView settlementStatus(String kind, String operationRef) {
        ExpectationKind expectationKind =
                enumParam("kind", kind, ExpectationKind.values())
                        .orElseThrow(() -> invalid("kind is required"));
        if (expectationKind == ExpectationKind.REMITTANCE) {
            throw invalid(
                    "kind REMITTANCE is a report's promise, not an operation; read it as an"
                            + " expectation");
        }
        if (operationRef == null || operationRef.isBlank() || operationRef.length() > 200) {
            throw invalid("operationRef is required, at most 200 characters");
        }
        SettlementStatuses.Trail trail =
                snapshot(unitOfWork -> statuses.of(unitOfWork, expectationKind, operationRef))
                        .orElseThrow(
                                () -> notFound(ReconciliationErrorCode.EXPECTATION_NOT_FOUND));
        return new SettlementStatusView(
                trail.expectationId().toString(),
                trail.kind().name(),
                trail.operationRef(),
                trail.status().name(),
                strings(trail.allocationIds()),
                strings(trail.itemIds()),
                strings(trail.batchIds()),
                strings(trail.remittanceIds()),
                strings(trail.bankItemIds()),
                trail.truncated());
    }

    // ================================================================== rendering

    static BreakView breakView(BreakCaseStore.BreakRow row) {
        return new BreakView(
                row.id().toString(),
                row.type().name(),
                row.cause().name(),
                row.status().name(),
                row.severity().name(),
                row.sourceId().toString(),
                row.subjectKind().name(),
                row.subjectId().toString(),
                plain(row.valueAtIssueMinor(), row.scale()),
                row.currency(),
                row.assignee().orElse(null),
                row.residualVersion(),
                row.followsBreakId().map(UUID::toString).orElse(null),
                row.raisedAt().toString(),
                row.statusChangedAt().toString(),
                row.resolvedAt().map(Object::toString).orElse(null));
    }

    private static ResolutionView resolutionView(BreakInquiries.ResolutionRow row) {
        return new ResolutionView(
                row.id().toString(),
                row.kind(),
                row.status(),
                row.reasonCode(),
                row.narrative(),
                plain(row.proposedAmountMinor(), row.scale()),
                row.currency(),
                row.decisionId().map(UUID::toString).orElse(null),
                row.parkId().map(UUID::toString).orElse(null),
                row.adjustmentProposalId().map(UUID::toString).orElse(null),
                row.journalEntryId().map(UUID::toString).orElse(null),
                row.proposedBy(),
                row.proposedByType(),
                row.proposedAt().toString(),
                row.decidedBy().orElse(null),
                row.decidedAt().map(Object::toString).orElse(null));
    }

    private static ExpectationView expectationView(ExpectationInquiries.ExpectationRow row) {
        return new ExpectationView(
                row.id().toString(),
                row.kind().name(),
                row.operationRef(),
                row.sourceId().toString(),
                row.positionPurpose(),
                row.direction().name(),
                plain(row.amountMinor(), row.scale()),
                row.currency(),
                plain(row.allocatedMinor(), row.scale()),
                plain(row.resolvedMinor(), row.scale()),
                row.status().name(),
                row.journalEntryId().map(UUID::toString).orElse(null),
                row.ledgerAccountId().toString(),
                row.postingDate().toString(),
                row.settlementCycle().orElse(null),
                row.expectedBy().toString(),
                row.overdueSince().map(Object::toString).orElse(null),
                row.ruleSetId().toString(),
                row.openedAt().toString());
    }

    private static String plain(long minor, int scale) {
        return BigDecimal.valueOf(minor, scale).toPlainString();
    }

    private static List<String> strings(List<UUID> ids) {
        return ids.stream().map(UUID::toString).toList();
    }

    // ================================================================== plumbing

    /** The domain's refusals, in the API's words — never a note body in either. */
    private static <R> R guarded(java.util.function.Supplier<R> work) {
        try {
            return work.get();
        } catch (BreakCaseFile.BreakNotFound unknown) {
            throw notFound(ReconciliationErrorCode.BREAK_NOT_FOUND);
        } catch (BreakCaseFile.BreakTerminal terminal) {
            throw new ApiException(
                    ReconciliationErrorCode.BREAK_TERMINAL,
                    "A case-file write reached a resolved break",
                    "this break is resolved; its case continues on its successor.");
        } catch (BreakCaseFile.CaseFileRefused refused) {
            throw invalid(refused.getMessage());
        } catch (BreakCaseFile.CaseFileConflict conflict) {
            throw new ApiException(
                    PlatformErrorCode.CONFLICT,
                    "A case-file command conflicted with the break's state",
                    conflict.getMessage());
        }
    }

    private static StoredResponse stored(String joined) {
        return StoredResponse.of(joined.getBytes(StandardCharsets.UTF_8), "text/plain");
    }

    private static CommandResult storedResult(String joined) {
        return CommandResult.succeeded(stored(joined));
    }

    private static String[] recorded(IdempotentExecutor.ExecutionOutcome outcome, int fields) {
        byte[] body =
                outcome.body()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "a recorded case-file outcome always carries its"
                                                        + " receipt"));
        String[] split = new String(body, StandardCharsets.UTF_8).split("\\|", fields);
        if (split.length != fields) {
            throw new IllegalStateException("a stored case-file receipt is malformed");
        }
        return split;
    }

    private static int agedOver(int days) {
        if (days < 0 || days > 36_500) {
            throw invalid("agedOver must be 0..36500 days");
        }
        return days;
    }

    /** A closed-vocabulary parameter: the enum's own constants, named when refused. */
    private static <E> Optional<E> enumParam(String name, String value, E[] allowed) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        for (E candidate : allowed) {
            if (candidate.toString().equals(value)) {
                return Optional.of(candidate);
            }
        }
        throw invalid(name + " must be one of " + java.util.Arrays.toString(allowed));
    }

    private static Optional<UUID> uuidParam(String name, String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(value));
        } catch (IllegalArgumentException malformed) {
            throw invalid(name + " must be a source identifier (a UUID)");
        }
    }

    private static ApiException invalid(String detail) {
        return new ApiException(
                PlatformErrorCode.VALIDATION_FAILED,
                "A reconciliation investigation request was refused",
                detail);
    }

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

    private static Correlation resolvedCorrelation() {
        return CorrelationContext.current()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "a reconciliation command must run inside a correlation"
                                                + " scope"));
    }

    private <R> R command(Function<Connection, R> work) {
        return reconciliationTransactions.execute(status -> onConnection(work));
    }

    private <R> R snapshot(Function<Connection, R> work) {
        return reconciliationSnapshotReads.execute(status -> onConnection(work));
    }

    private <R> R onConnection(Function<Connection, R> work) {
        Connection unitOfWork = DataSourceUtils.getConnection(dataSource);
        try {
            return work.apply(unitOfWork);
        } finally {
            DataSourceUtils.releaseConnection(unitOfWork, dataSource);
        }
    }
}
