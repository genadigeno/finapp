package com.finapp.reconciliation;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The case file's persistence (`P8-TSK-014`, ADR-0069 §7): the break row locked in the Phase 8
 * lock order (the source's namespace-4 advisory FIRST, then the row — the `P8-TSK-012` register
 * fact for every writer that locks a committed break row), the two moving columns a person
 * moves ({@code assignee}, {@code type}) by expected-value updates, and the append-only case
 * file: {@code break_event}, {@code break_note}, {@code break_evidence_link}.
 */
public interface BreakCaseStore {

    /** One break row as stored — the subject, the frozen facts and the moving columns. */
    record BreakRow(
            UUID id,
            BreakType type,
            BreakCause cause,
            BreakStatus status,
            Severity severity,
            UUID sourceId,
            UUID ruleSetId,
            Optional<UUID> expectationId,
            Optional<UUID> externalItemId,
            Optional<UUID> suspenseItemId,
            Optional<UUID> runId,
            Optional<UUID> decisionId,
            long valueAtIssueMinor,
            String currency,
            int scale,
            Optional<String> internalClassification,
            Optional<String> internalOperationRef,
            Optional<String> internalState,
            Optional<String> assignee,
            long residualVersion,
            Optional<UUID> followsBreakId,
            Instant raisedAt,
            Optional<Instant> resolvedAt,
            Instant statusChangedAt) {

        public BreakRow {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(status, "status must not be null");
        }

        /**
         * The subject the raise set — in the partial uniques' own order, so a break carrying
         * several subject columns (legal, ADR-0069 §1) is judged on the one its seat is.
         */
        public BreakSubjectKind subjectKind() {
            if (expectationId.isPresent()) {
                return BreakSubjectKind.EXPECTATION;
            }
            if (externalItemId.isPresent()) {
                return BreakSubjectKind.EXTERNAL_ITEM;
            }
            if (suspenseItemId.isPresent()) {
                return BreakSubjectKind.SUSPENSE_ITEM;
            }
            if (runId.isPresent()) {
                return BreakSubjectKind.RUN;
            }
            return BreakSubjectKind.DECISION;
        }

        public UUID subjectId() {
            return switch (subjectKind()) {
                case EXPECTATION -> expectationId.orElseThrow();
                case EXTERNAL_ITEM -> externalItemId.orElseThrow();
                case SUSPENSE_ITEM -> suspenseItemId.orElseThrow();
                case RUN -> runId.orElseThrow();
                case DECISION -> decisionId.orElseThrow();
            };
        }

        public Money valueAtIssue() {
            return Money.ofPersisted(valueAtIssueMinor, CurrencyCode.of(currency), scale);
        }
    }

    /** The break's source — frozen at raise, so reading it before any lock is safe. */
    Optional<UUID> sourceOf(Connection unitOfWork, UUID breakId);

    /** The source's namespace-4 advisory, the BLOCKING form — taken before any break row. */
    void lockSource(Connection unitOfWork, UUID sourceId);

    Optional<BreakRow> lockForUpdate(Connection unitOfWork, UUID breakId);

    Optional<BreakRow> lockForShare(Connection unitOfWork, UUID breakId);

    /** Sets the assignee and moves the status, conditional on the status read under lock. */
    boolean assign(
            Connection unitOfWork,
            UUID breakId,
            BreakStatus from,
            BreakStatus to,
            String assignee,
            Instant at);

    /**
     * Moves the type, raises the grade to {@code severity} and bumps {@code residual_version}
     * (ADR-0071's staleness counter), conditional on the type read under lock.
     *
     * @throws OneOpenSeatTaken when a break of the new type already stands open on the
     *     subject — the partial unique refused the move for this writer as for any
     */
    boolean reclassify(
            Connection unitOfWork,
            UUID breakId,
            BreakType from,
            BreakType to,
            Severity severity,
            Instant at);

    void appendEvent(
            Connection unitOfWork,
            UUID breakId,
            BreakEventType eventType,
            Actor actor,
            Optional<String> reason,
            String detail,
            Instant at,
            CorrelationId correlation);

    void insertNote(
            Connection unitOfWork,
            UUID noteId,
            UUID breakId,
            String body,
            Actor actor,
            Instant at,
            CorrelationId correlation);

    void insertLink(
            Connection unitOfWork,
            UUID linkId,
            UUID breakId,
            EvidenceTargetKind kind,
            String targetRef,
            Actor actor,
            Instant at,
            CorrelationId correlation);

    /** Whether the break owns suspense value not yet released ({@code INV-REC-09}'s side). */
    boolean holdsParkedValue(Connection unitOfWork, UUID breakId);

    /** Whether another break of {@code type} stands open on the subject. */
    boolean openBreakOfTypeStands(
            Connection unitOfWork,
            BreakType type,
            BreakSubjectKind subjectKind,
            UUID subjectId,
            UUID exceptBreakId);

    /** The subject's direction, where it has one — the severity refinement's input. */
    Optional<ExpectationDirection> subjectDirection(Connection unitOfWork, BreakRow row);

    /** The subject expectation's kind, where the subject is one. */
    Optional<ExpectationKind> subjectExpectationKind(Connection unitOfWork, BreakRow row);

    /** The pinned rule set's {@code high_value_minor} for the currency, where it names one. */
    Optional<Long> highValueMinor(Connection unitOfWork, UUID ruleSetId, String currency);

    boolean runExists(Connection unitOfWork, UUID runId);

    boolean decisionExists(Connection unitOfWork, UUID decisionId);

    /** Whether an expectation tracks (kind, operation) — {@code UNIQUE (kind, operation_ref)}. */
    boolean expectationTracks(Connection unitOfWork, ExpectationKind kind, String operationRef);

    /** The partial unique refused a reclassification onto an occupied (type, subject). */
    final class OneOpenSeatTaken extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public OneOpenSeatTaken(Throwable cause) {
            super("a break of this type already stands open on the subject", cause);
        }
    }
}
