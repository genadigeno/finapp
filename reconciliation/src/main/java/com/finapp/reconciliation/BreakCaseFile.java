package com.finapp.reconciliation;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The investigation is the break's case file (`P8-TSK-014`, ADR-0069 §7) — never a second
 * machine: assignment, notes, evidence links and reclassification, each ONE transaction
 * (row, history, audit, outbox), each on the break row locked in the Phase 8 lock order —
 * the source's namespace-4 advisory FIRST, then the row ({@code FOR UPDATE} for the two
 * commands that move it, {@code FOR SHARE} for the two that only append beside it), the
 * `P8-TSK-012` register fact every writer of a committed break row honours. A resolved break
 * takes none of them ({@link BreakTerminal}); its case continues on its successor.
 *
 * <p>Assignment and reclassification are idempotent BY STATE: a repeat of what already
 * stands converges with no write. Notes and links are keyed by the caller (per principal,
 * `app`'s {@code IdempotentExecutor}); this service is the command inside the claim.
 */
@RequiredArgsConstructor
public final class BreakCaseFile {

    static final String TARGET_TYPE = "reconciliation_break";

    /**
     * An identity's identifier - the actor id every record names, a UUID - before the
     * {@link Investigators} port judges who it is. *(Corrected 2026-10-02 by the Phase 8 -> 9
     * transition, SEC-06: this admitted any 1..100 letters, digits, '-' or '_' - a card number,
     * a mistyped id, a customer's - and the value reached the row and the published event.)*
     */
    public static final Pattern ASSIGNEE_SHAPE =
            Pattern.compile(
                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public static final int MAX_REASON_LENGTH = 1000;

    public static final int MAX_TARGET_REF_LENGTH = 200;

    @NonNull private final BreakCaseStore store;
    @NonNull private final EvidenceTargets targets;
    @NonNull private final Investigators investigators;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    // ------------------------------------------------------------------ outcomes

    /** The assignment's outcome: {@code changed} false when it converged. */
    public record Assigned(BreakCaseStore.BreakRow row, boolean changed, boolean started) {}

    public record NoteAdded(UUID noteId, UUID breakId, Instant addedAt, int length) {}

    public record EvidenceLinked(
            UUID linkId, UUID breakId, EvidenceTargetKind kind, String targetRef, Instant addedAt) {}

    /** The reclassification's outcome: {@code changed} false when it converged. */
    public record Reclassified(BreakCaseStore.BreakRow row, boolean changed) {}

    // ------------------------------------------------------------------ refusals

    /** No break has this id. */
    public static final class BreakNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        BreakNotFound() {
            super("no break has this identifier");
        }
    }

    /** The break is {@code RESOLVED}: every write is refused. */
    public static final class BreakTerminal extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        BreakTerminal() {
            super("the break is resolved; its case continues on its successor");
        }
    }

    /** The request is shaped wrongly or asks for what the taxonomy refuses — a 422. */
    public static final class CaseFileRefused extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public CaseFileRefused(String detail) {
            super(detail);
        }
    }

    /** The request conflicts with the break's current state — a 409. */
    public static final class CaseFileConflict extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public CaseFileConflict(String detail) {
            super(detail);
        }
    }

    // ------------------------------------------------------------------ assignment

    /**
     * Sets the assignee; the first assignment moves {@code OPEN → INVESTIGATING} and publishes
     * {@code reconciliation.BreakInvestigationStarted} — once, whoever races: the break row
     * lock serialises the racers, and the loser reads {@code INVESTIGATING}. The assignee is an
     * identity identifier naming an active investigator ({@link Investigators}), stored in its
     * canonical form; anything else is refused before any lock, nothing written and nothing
     * published (SEC-06), and the refusal never echoes the value.
     */
    public Assigned assign(
            Connection unitOfWork,
            UUID breakId,
            String requested,
            Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(requested, "assignee must not be null");
        if (!ASSIGNEE_SHAPE.matcher(requested).matches()) {
            throw new CaseFileRefused("assigneeId must be an identity identifier (a UUID)");
        }
        UUID principal = UUID.fromString(requested);
        if (!investigators.investigates(unitOfWork, principal)) {
            throw new CaseFileRefused(
                    "assigneeId must name an active identity holding"
                            + " RECONCILIATION_INVESTIGATE");
        }
        String assignee = principal.toString();
        BreakCaseStore.BreakRow row = locked(unitOfWork, breakId, true);
        if (row.assignee().filter(assignee::equals).isPresent()) {
            return new Assigned(row, false, false);
        }
        Instant now = Instant.now(clock);
        BreakStatus from = row.status();
        BreakStatus to = from == BreakStatus.OPEN ? BreakStatus.INVESTIGATING : from;
        if (!store.assign(unitOfWork, breakId, from, to, assignee, now)) {
            throw new IllegalStateException(
                    "the break moved under its own row lock (ADR-0069 section 11)");
        }
        String detail =
                "assignee=" + assignee
                        + ", previous=" + row.assignee().orElse("none")
                        + ", status=" + from.name() + "->" + to.name();
        store.appendEvent(
                unitOfWork, breakId, BreakEventType.ASSIGNED, actor, Optional.empty(), detail,
                now, correlation);
        audit(unitOfWork, actor, now, ReconciliationAuditAction.BREAK_ASSIGNED, breakId,
                Optional.empty(), detail, correlation);
        boolean started = from == BreakStatus.OPEN;
        if (started) {
            ReconciliationEvents.investigationStarted(
                    outbox, unitOfWork, ids, breakId, assignee, now, correlation);
        }
        return new Assigned(
                store.lockForUpdate(unitOfWork, breakId).orElseThrow(), true, started);
    }

    // ------------------------------------------------------------------ notes

    /**
     * Appends a note. The screen runs FIRST — nothing is stored for a refused body, and
     * `V004`'s {@code CHECK}s refuse the same shapes for every writer. The body is CONFIDENTIAL:
     * it reaches the note row and nothing else — never the audit record, an event or a log.
     */
    public NoteAdded addNote(
            Connection unitOfWork,
            UUID breakId,
            String body,
            Actor actor,
            CorrelationId correlation) {
        refuseNote(body);
        locked(unitOfWork, breakId, false);
        Instant now = Instant.now(clock);
        UUID noteId = ids.next();
        store.insertNote(unitOfWork, noteId, breakId, body, actor, now, correlation);
        audit(unitOfWork, actor, now, ReconciliationAuditAction.BREAK_NOTE_ADDED, breakId,
                Optional.empty(), "noteId=" + noteId + ", length=" + body.length(),
                correlation);
        return new NoteAdded(noteId, breakId, now, body.length());
    }

    /** The domain rank of the note screen — callable before any claim is taken. */
    public static void refuseNote(String body) {
        Objects.requireNonNull(body, "body must not be null");
        NoteScreen.screenNote(body)
                .ifPresent(
                        refusal -> {
                            throw new CaseFileRefused(noteRefusalDetail(refusal));
                        });
    }

    private static String noteRefusalDetail(NoteScreen.Refusal refusal) {
        return switch (refusal) {
            case EMPTY -> "body must not be empty";
            case TOO_LONG -> "body must be at most " + NoteScreen.MAX_NOTE_LENGTH + " characters";
            case CARD_NUMBER_SHAPE ->
                    "body must not hold a card-number shape (INV-PAY-02); link evidence by"
                            + " identifier instead";
            case ACCOUNT_SHAPE ->
                    "body must not hold a bank-account shape (INV-RAIL-03); link evidence by"
                            + " identifier instead";
        };
    }

    // ------------------------------------------------------------------ evidence links

    /**
     * Links stored evidence by identifier — verified to EXIST in the same transaction, never
     * stored dangling. An {@code OPERATION} reference is {@code <ExpectationKind>:<operationRef>},
     * the operation as the register tracks it ({@code UNIQUE (kind, operation_ref)}).
     */
    public EvidenceLinked link(
            Connection unitOfWork,
            UUID breakId,
            EvidenceTargetKind kind,
            String targetRef,
            Actor actor,
            CorrelationId correlation) {
        refuseLink(kind, targetRef);
        locked(unitOfWork, breakId, false);
        if (!targetExists(unitOfWork, kind, targetRef)) {
            throw new CaseFileRefused(
                    "targetRef names no stored " + kind.name() + " (a link is never stored"
                            + " dangling)");
        }
        Instant now = Instant.now(clock);
        UUID linkId = ids.next();
        store.insertLink(unitOfWork, linkId, breakId, kind, targetRef, actor, now, correlation);
        audit(unitOfWork, actor, now, ReconciliationAuditAction.BREAK_EVIDENCE_LINKED, breakId,
                Optional.empty(),
                "linkId=" + linkId + ", targetKind=" + kind.name() + ", targetRef=" + targetRef,
                correlation);
        return new EvidenceLinked(linkId, breakId, kind, targetRef, now);
    }

    /** The link's shape, judged before any claim: bounds, screen, and the reference's form. */
    public static void refuseLink(EvidenceTargetKind kind, String targetRef) {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(targetRef, "targetRef must not be null");
        if (targetRef.isEmpty() || targetRef.length() > MAX_TARGET_REF_LENGTH) {
            throw new CaseFileRefused(
                    "targetRef must be 1.." + MAX_TARGET_REF_LENGTH + " characters");
        }
        if (NoteScreen.screenShapes(targetRef).isPresent()) {
            throw new CaseFileRefused(
                    "targetRef must be an identifier, never a card-number or bank-account"
                            + " shape");
        }
        if (kind == EvidenceTargetKind.OPERATION) {
            operationOf(targetRef);
        } else {
            uuidOf(targetRef);
        }
    }

    private boolean targetExists(
            Connection unitOfWork, EvidenceTargetKind kind, String targetRef) {
        return switch (kind) {
            case RUN -> store.runExists(unitOfWork, uuidOf(targetRef));
            case DECISION -> store.decisionExists(unitOfWork, uuidOf(targetRef));
            case OPERATION -> {
                Operation operation = operationOf(targetRef);
                yield store.expectationTracks(unitOfWork, operation.kind(), operation.ref());
            }
            case SETTLEMENT_FILE, SETTLEMENT_BATCH, SETTLEMENT_LINE, JOURNAL_ENTRY,
                            PROVIDER_EVIDENCE ->
                    targets.exists(unitOfWork, kind, uuidOf(targetRef));
        };
    }

    private record Operation(ExpectationKind kind, String ref) {}

    private static Operation operationOf(String targetRef) {
        int colon = targetRef.indexOf(':');
        if (colon <= 0 || colon == targetRef.length() - 1) {
            throw new CaseFileRefused(
                    "an OPERATION targetRef is <ExpectationKind>:<operationRef>");
        }
        try {
            return new Operation(
                    ExpectationKind.valueOf(targetRef.substring(0, colon)),
                    targetRef.substring(colon + 1));
        } catch (IllegalArgumentException unknownKind) {
            throw new CaseFileRefused(
                    "an OPERATION targetRef names an unknown expectation kind");
        }
    }

    private static UUID uuidOf(String targetRef) {
        try {
            return UUID.fromString(targetRef);
        } catch (IllegalArgumentException malformed) {
            throw new CaseFileRefused("targetRef must be the target's identifier (a UUID)");
        }
    }

    // ------------------------------------------------------------------ reclassification

    /**
     * Moves the break's type (ADR-0069 §7) — in {@code OPEN} and {@code INVESTIGATING} only,
     * because a proposal's frozen lines depend on the type; onto a type that stands on the
     * break's own subject kind and parks exactly when the subject holds parked value
     * ({@code INV-REC-09}); only where the frozen cause keeps an exit
     * ({@link ResolutionTemplates#reclassificationStrands} - a kind the subject takes, the
     * cause's own evidence, or an acknowledgement back on its raise type); never onto an
     * occupied (type, subject) seat. The cause, subject and value at issue never change; the
     * severity is the higher of the stored and the recomputed grade (a relabel cannot quiet an
     * alert); {@code residual_version} moves. *(Corrected 2026-10-02 by the Phase 8 -> 9
     * transition: the exit rule is new - before it, one investigator's reclassification could
     * make a break permanently unclosable.)*
     */
    public Reclassified reclassify(
            Connection unitOfWork,
            UUID breakId,
            BreakType to,
            String reason,
            Actor actor,
            CorrelationId correlation) {
        Objects.requireNonNull(to, "to must not be null");
        Objects.requireNonNull(reason, "reason must not be null");
        if (reason.isBlank() || reason.length() > MAX_REASON_LENGTH) {
            throw new CaseFileRefused(
                    "a reclassification is reasoned: reason must be 1.." + MAX_REASON_LENGTH
                            + " characters");
        }
        // The reason reaches the history and the audit record: free text there is held to
        // the note's screen (INV-PAY-02, INV-RAIL-03).
        if (NoteScreen.screenShapes(reason).isPresent()) {
            throw new CaseFileRefused(
                    "reason must not hold a card-number or bank-account shape");
        }
        BreakCaseStore.BreakRow row = locked(unitOfWork, breakId, true);
        if (row.status() == BreakStatus.RESOLUTION_PROPOSED) {
            throw new CaseFileConflict(
                    "a break with a resolution proposed cannot be reclassified: the proposal's"
                            + " frozen lines depend on the type");
        }
        if (row.type() == to) {
            return new Reclassified(row, false);
        }
        BreakSubjectKind subject = row.subjectKind();
        if (!to.admits(subject)) {
            throw new CaseFileRefused(
                    to.name() + " does not stand on a " + subject.name() + " subject");
        }
        boolean parked = store.holdsParkedValue(unitOfWork, breakId);
        if (to.parksOn(subject) != parked) {
            throw new CaseFileRefused(
                    parked
                            ? to.name() + " owns no suspense, and this break's subject holds"
                                    + " parked value (INV-REC-09)"
                            : to.name() + " parks its value, and this break's subject holds"
                                    + " none");
        }
        // The frozen cause must keep an exit on the target type (the Phase 8 -> 9 transition's
        // correction): a type no kind and no evidence would ever close strands the break for
        // good - a permanent false break, or one a type-filtered closer later discards.
        ResolutionTemplates.reclassificationStrands(
                        to,
                        row.cause(),
                        subject,
                        store.holding(unitOfWork, row),
                        store.subjectExpectationKind(unitOfWork, row))
                .ifPresent(
                        stranded -> {
                            throw new CaseFileRefused(stranded);
                        });
        if (store.openBreakOfTypeStands(
                unitOfWork, to, subject, row.subjectId(), breakId)) {
            throw seatTaken(to);
        }
        Severity recomputed =
                BreakSeverity.assess(
                        to,
                        row.cause(),
                        store.subjectDirection(unitOfWork, row),
                        store.subjectExpectationKind(unitOfWork, row),
                        row.valueAtIssue(),
                        store.highValueMinor(unitOfWork, row.ruleSetId(), row.currency()));
        Severity severity =
                recomputed.ordinal() > row.severity().ordinal() ? recomputed : row.severity();
        Instant now = Instant.now(clock);
        boolean moved;
        try {
            moved = store.reclassify(unitOfWork, breakId, row.type(), to, severity, now);
        } catch (BreakCaseStore.OneOpenSeatTaken taken) {
            throw seatTaken(to);
        }
        if (!moved) {
            throw new IllegalStateException(
                    "the break moved under its own row lock (ADR-0069 section 11)");
        }
        String detail =
                "from=" + row.type().name() + ", to=" + to.name()
                        + ", severity=" + row.severity().name() + "->" + severity.name();
        store.appendEvent(
                unitOfWork, breakId, BreakEventType.RECLASSIFIED, actor, Optional.of(reason),
                detail, now, correlation);
        audit(unitOfWork, actor, now, ReconciliationAuditAction.BREAK_RECLASSIFIED, breakId,
                Optional.of(reason), detail, correlation);
        return new Reclassified(store.lockForUpdate(unitOfWork, breakId).orElseThrow(), true);
    }

    private static CaseFileConflict seatTaken(BreakType to) {
        return new CaseFileConflict(
                "a " + to.name() + " break already stands open on this subject; link the two"
                        + " instead");
    }

    // ------------------------------------------------------------------ plumbing

    /** The Phase 8 lock order: the source's advisory, then the row — never the reverse. */
    private BreakCaseStore.BreakRow locked(
            Connection unitOfWork, UUID breakId, boolean exclusive) {
        UUID source = store.sourceOf(unitOfWork, breakId).orElseThrow(BreakNotFound::new);
        store.lockSource(unitOfWork, source);
        BreakCaseStore.BreakRow row =
                (exclusive
                                ? store.lockForUpdate(unitOfWork, breakId)
                                : store.lockForShare(unitOfWork, breakId))
                        .orElseThrow(BreakNotFound::new);
        if (row.status() == BreakStatus.RESOLVED) {
            throw new BreakTerminal();
        }
        return row;
    }

    private void audit(
            Connection unitOfWork,
            Actor actor,
            Instant at,
            ReconciliationAuditAction action,
            UUID breakId,
            Optional<String> reason,
            String summary,
            CorrelationId correlation) {
        audit.append(
                unitOfWork,
                new AuditRecord(
                        AuditId.next(ids),
                        actor,
                        at,
                        action,
                        TARGET_TYPE,
                        breakId.toString(),
                        reason,
                        AuditOutcome.SUCCEEDED,
                        correlation,
                        Optional.of(summary)));
    }
}
