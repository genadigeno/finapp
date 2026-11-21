package com.finapp.app.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.finapp.platform.security.Actor;
import com.finapp.platform.security.SecurityContext;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The one pending-decision shape (`P17-TSK-040`; finapp-admin-panel ADR-C133 decision 3, gap G-140). Every four-eyes domain
 * serves its proposals awaiting a decision as a section 7 collection of {@link PendingDecision}s, under that domain's approve
 * permission, so the console's approvals inbox reads every queue through one function and marks the operator's own proposals.
 * There is no generic approval service (decision 4): each queue is read from its own domain's store, and each decision is still
 * taken at that domain's own approve and reject doors.
 *
 * <p>The rules every queue keeps, so they are stated once:
 * <ul>
 *   <li><strong>Order</strong>: proposed at ascending, the proposal's id ascending - the oldest waiting first, the id the
 *       tiebreak. The keyset is {@code (proposed_at, id) > (?, ?)}.
 *   <li><strong>Limit</strong>: {@value #DEFAULT_LIMIT} unless named, {@value #MAX_LIMIT} at most, refused above
 *       ({@link OperatorCollection#limit}).
 *   <li><strong>Status</strong>: the queue is the pending status alone. A {@code status} parameter may name it, in the domain's
 *       own word or as {@value #UNIFORM_STATUS} - the word every queue accepts (`P17-TSK-127`), so a client reading every queue
 *       sends one word; any other value is refused {@code 422 api.ValidationFailed} ({@link #pendingOnly}), never served as a
 *       different list under the queue's name.
 *   <li><strong>Reason</strong>: {@code reasonSummary} is the proposer's reason cut to {@value #SUMMARY_LENGTH} characters, or
 *       null where the domain records none - never a free-text field beyond the proposer's own reason.
 *   <li><strong>Kind</strong>: one of {@link PendingDecisionKind}, by constant, never by string (`P17-TSK-127`); the subject's
 *       kind from the same list.
 *   <li><strong>The caller</strong>: {@code proposedByCaller} says whether the reader is the proposer - judged against the
 *       request's own actor, so the console disables the proposer's own approve and reject without comparing identities
 *       itself; the decision doors' self-approval refusals stay the control.
 * </ul>
 *
 * <p>A proposal decided between the list and the click answers {@code 409} at its decision door - the existing conditional
 * transitions - so a stale page is harmless on any instance.
 */
public final class PendingDecisions {

    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 200;
    public static final int SUMMARY_LENGTH = 120;

    /** The status word every queue accepts beside its domain's own (`P17-TSK-127`): {@code PROPOSED}, 43 queues' own word. */
    public static final String UNIFORM_STATUS = "PROPOSED";

    private PendingDecisions() {}

    /**
     * One proposal awaiting a decision.
     *
     * @param id            the proposal's id - the one its approve and reject doors name
     * @param kind          {@code <domain>.<ProposalKind>}, e.g. {@code fx.EnableRequest}
     * @param proposedBy    the proposer's identity id; the console marks the operator's own
     * @param proposedAt    when it was proposed, an ISO instant
     * @param reasonSummary the proposer's reason, cut to {@value #SUMMARY_LENGTH} characters; null where none is recorded
     * @param subjectId     what the proposal acts on (an account, a period, a version, ...); null where it acts on nothing
     *                      but itself
     * @param relatedId     a second identifier the kind's decision doors name, where the subject alone is not enough to reach
     *                      them - a repayment reversal's repayment, under {@code .../{subject}/repayments/{relatedId}/...};
     *                      null for every other kind. The one addition to ADR-C133 decision 3's six fields (`P17-TSK-040`).
     * @param subjectKind   what {@code subjectId} names, in the domain's own word ({@code ACCOUNT}, {@code OUTBOX_EVENT}, ...),
     *                      read with {@code kind}; null exactly where {@code subjectId} is (`P17-TSK-127`)
     * @param proposedByCaller whether the reader is the proposer (`P17-TSK-127`); false where the reader is no actor
     */
    public record PendingDecision(String id, String kind, String proposedBy, String proposedAt, String reasonSummary,
            String subjectId, String relatedId, String subjectKind, boolean proposedByCaller) {

        public PendingDecision {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(proposedAt, "proposedAt");
        }
    }

    /** A page of a queue: {@code nextCursor} absent, not null, on the last one. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PendingDecisionPage(List<PendingDecision> items, String nextCursor) {

        public PendingDecisionPage {
            items = List.copyOf(items);
        }
    }

    /** An item from a domain row: the ids and the proposer as stored, the instant as stored, the subject's kind the kind's one. */
    public static PendingDecision of(Object id, PendingDecisionKind kind, Object proposedBy, Instant proposedAt, String reason,
            Object subjectId) {
        return of(id, kind, proposedBy, proposedAt, reason, subjectId, null);
    }

    /** An item whose decision doors also name a second identifier ({@code relatedId}). */
    public static PendingDecision of(Object id, PendingDecisionKind kind, Object proposedBy, Instant proposedAt, String reason,
            Object subjectId, Object relatedId) {
        return item(id, kind, proposedBy, proposedAt, reason, kind.subjectKind(subjectId), subjectId, relatedId);
    }

    /** An item of a kind whose subject's kind differs row by row ({@code fx.EnableRequest}, {@code deadletter.Discard}). */
    public static PendingDecision ofSubject(Object id, PendingDecisionKind kind, Object proposedBy, Instant proposedAt,
            String reason, String subjectKind, Object subjectId) {
        return item(id, kind, proposedBy, proposedAt, reason, kind.subjectKind(subjectKind, subjectId), subjectId, null);
    }

    private static PendingDecision item(Object id, PendingDecisionKind kind, Object proposedBy, Instant proposedAt, String reason,
            String subjectKind, Object subjectId, Object relatedId) {
        String proposer = proposedBy == null ? null : String.valueOf(proposedBy);
        return new PendingDecision(String.valueOf(id), kind.wire(), proposer, proposedAt.toString(), summary(reason),
                subjectId == null ? null : String.valueOf(subjectId), relatedId == null ? null : String.valueOf(relatedId),
                subjectKind, proposedByCaller(proposer));
    }

    /**
     * Whether the request's own actor is {@code proposer} (`P17-TSK-127`): false where there is none, a system read. The actor
     * is the request's, held for the request alone ({@link SecurityContext}), so the answer is the same on every instance.
     */
    public static boolean proposedByCaller(String proposer) {
        return proposer != null && SecurityContext.current().map(Actor::id).filter(proposer::equals).isPresent();
    }

    /** The proposer's reason cut to {@value #SUMMARY_LENGTH} characters, or null where there is none. */
    public static String summary(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String trimmed = reason.strip();
        return trimmed.length() <= SUMMARY_LENGTH ? trimmed : trimmed.substring(0, SUMMARY_LENGTH - 1) + "…";
    }

    /** The page's limit under the shared rules. */
    public static int limit(String requested) {
        return OperatorCollection.limit(requested, DEFAULT_LIMIT, MAX_LIMIT);
    }

    /**
     * The queue's status parameter: absent, the domain's own pending word ({@code PROPOSED}, {@code VALIDATED}, ...), or
     * {@value #UNIFORM_STATUS}, the word every queue accepts (`P17-TSK-127`). Anything else is refused, so the queue never
     * serves another status under its name. No domain whose own word differs has a {@code PROPOSED} state of its own, so the
     * uniform word never names a different status there.
     */
    public static void pendingOnly(String requested, String pendingWord) {
        if (requested != null && !requested.equals(pendingWord) && !requested.equals(UNIFORM_STATUS)) {
            throw OperatorCollection.invalid("status: this queue serves " + pendingWord + " alone (" + UNIFORM_STATUS
                    + " names it on every queue)");
        }
    }

    /** A keyset position: strictly after this (proposed at, id). */
    public record Position(Instant proposedAt, UUID id) {}

    /** A cursor read back: the (proposed at, id) position it names; empty for the first page; refused if it is not this queue's. */
    public static java.util.Optional<Position> position(String collection, String cursor) {
        if (cursor == null) {
            return java.util.Optional.empty();
        }
        List<String> key = OperatorCollection.key(collection, cursor, 2).orElseThrow(OperatorCollection::invalidCursor);
        try {
            return java.util.Optional.of(new Position(Instant.parse(key.get(0)), UUID.fromString(key.get(1))));
        } catch (RuntimeException malformed) {
            throw OperatorCollection.invalidCursor();
        }
    }

    /**
     * The page from a look-ahead read of {@code limit + 1} rows already mapped to items: the extra row only decides whether a
     * {@code nextCursor} is named, the last served item's (proposed at, id).
     */
    public static PendingDecisionPage page(String collection, List<PendingDecision> readLimitPlusOne, int limit) {
        if (readLimitPlusOne.size() <= limit) {
            return new PendingDecisionPage(readLimitPlusOne, null);
        }
        List<PendingDecision> served = readLimitPlusOne.subList(0, limit);
        PendingDecision last = served.get(served.size() - 1);
        return new PendingDecisionPage(served, OperatorCollection.cursor(collection, last.proposedAt(), last.id()));
    }
}
