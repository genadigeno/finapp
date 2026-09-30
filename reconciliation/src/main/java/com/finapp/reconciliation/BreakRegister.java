package com.finapp.reconciliation;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Raises breaks (`P8-TSK-010`, ADR-0069 §3): a break is born only from a stored fact the
 * platform detected, in the transaction that detects it — no person raises one, and there
 * is no route for it.
 *
 * <p>A raise CONVERGES: the insert yields to the one-open-per-(type, subject) partial
 * uniques ({@code ON CONFLICT DO NOTHING}), and a loser returns the standing open break,
 * writing no event, no audit and no history — ten sweepers raising one discrepancy write
 * one row. Severity is computed inside the raise ({@link BreakSeverity}, the one seat),
 * from the pinned rule set's own {@code severity_threshold} — a raiser cannot pass a
 * quieter grade.
 */
public interface BreakRegister {

    /** The raise's outcome: the row this transaction created, or the one already standing. */
    record Raised(boolean created, UUID breakId, Severity severity) {}

    Raised raise(Connection unitOfWork, NewBreak newBreak);

    /**
     * Exactly one subject column set is the usual shape; several are legal (ADR-0069 §1
     * names at least one). The subject rows are reconciliation's own.
     */
    record Subject(
            Optional<UUID> expectationId,
            Optional<UUID> externalItemId,
            Optional<UUID> suspenseItemId,
            Optional<UUID> runId,
            Optional<UUID> decisionId) {

        public Subject {
            Objects.requireNonNull(expectationId, "expectationId must not be null");
            Objects.requireNonNull(externalItemId, "externalItemId must not be null");
            Objects.requireNonNull(suspenseItemId, "suspenseItemId must not be null");
            Objects.requireNonNull(runId, "runId must not be null");
            Objects.requireNonNull(decisionId, "decisionId must not be null");
            if (expectationId.isEmpty()
                    && externalItemId.isEmpty()
                    && suspenseItemId.isEmpty()
                    && runId.isEmpty()
                    && decisionId.isEmpty()) {
                throw new IllegalArgumentException(
                        "a break needs a subject that holds value (ADR-0069)");
            }
        }

        public static Subject expectation(UUID id) {
            return new Subject(
                    Optional.of(id),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
        }

        public static Subject externalItem(UUID id) {
            return new Subject(
                    Optional.empty(),
                    Optional.of(id),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
        }

        /**
         * A suspense item's owner standing on the item itself - a parking's (`P8-TSK-020`),
         * whose value entered suspense with no external item behind it.
         */
        public static Subject suspenseItem(UUID id) {
            return new Subject(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(id),
                    Optional.empty(),
                    Optional.empty());
        }

        public static Subject run(UUID id) {
            return new Subject(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(id),
                    Optional.empty());
        }

        /** The one decision-subject type: {@code TIMING_DIFFERENCE} (`P8-TSK-011`). */
        public static Subject decision(UUID id) {
            return new Subject(
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(id));
        }
    }

    /**
     * One detection's facts, all frozen at raise (ADR-0069 §1). {@code direction} and
     * {@code expectationKind} feed only the severity refinement; the internal fields are
     * what {@link InternalReferenceLookup} answered, kept because the live state moves on.
     */
    record NewBreak(
            UUID breakId,
            BreakType type,
            BreakCause cause,
            Subject subject,
            UUID sourceId,
            UUID ruleSetId,
            Money valueAtIssue,
            Optional<ExpectationDirection> direction,
            Optional<ExpectationKind> expectationKind,
            Optional<InternalClassification> internalClassification,
            Optional<String> internalOperationRef,
            Optional<String> internalState,
            Optional<UUID> followsBreakId,
            Actor actor,
            Instant raisedAt,
            CorrelationId correlation) {

        public NewBreak {
            Objects.requireNonNull(breakId, "breakId must not be null");
            Objects.requireNonNull(type, "type must not be null");
            Objects.requireNonNull(cause, "cause must not be null");
            Objects.requireNonNull(subject, "subject must not be null");
            Objects.requireNonNull(sourceId, "sourceId must not be null");
            Objects.requireNonNull(ruleSetId, "ruleSetId must not be null");
            Objects.requireNonNull(valueAtIssue, "valueAtIssue must not be null");
            Objects.requireNonNull(direction, "direction must not be null");
            Objects.requireNonNull(expectationKind, "expectationKind must not be null");
            Objects.requireNonNull(
                    internalClassification, "internalClassification must not be null");
            Objects.requireNonNull(internalOperationRef, "internalOperationRef must not be null");
            Objects.requireNonNull(internalState, "internalState must not be null");
            Objects.requireNonNull(followsBreakId, "followsBreakId must not be null");
            Objects.requireNonNull(actor, "actor must not be null");
            Objects.requireNonNull(raisedAt, "raisedAt must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            if (!cause.raisesAs().contains(type)) {
                throw new IllegalArgumentException(
                        "a break is raised only by its own detector: " + cause
                                + " never raises " + type + " (ADR-0069)");
            }
            if (valueAtIssue.minorUnits() < 0) {
                throw new IllegalArgumentException(
                        "the value at issue is an absolute fact of the raise (ADR-0069)");
            }
        }
    }
}
