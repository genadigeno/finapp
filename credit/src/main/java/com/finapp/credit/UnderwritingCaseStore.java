package com.finapp.credit;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The underwriting case's persistence (`P10-TSK-018`, {@code credit V013}) - on the caller's unit of work. Every edge is
 * conditional on the state it leaves and writes its history row; the trigger judges the edge for every writer.
 */
public interface UnderwritingCaseStore {

    /** What a referral opens: the case, its basis, the referral's ceiling and the product's threshold. */
    record NewCase(
            UnderwritingCaseId id,
            DecisionRequest request,
            PolicyEvaluationId basisEvaluation,
            Money approvable,
            Money fourEyesThreshold) {

        public NewCase {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(basisEvaluation, "basisEvaluation");
            Objects.requireNonNull(approvable, "approvable");
            Objects.requireNonNull(fourEyesThreshold, "fourEyesThreshold");
        }
    }

    /**
     * One edge: where it goes and the columns it writes. {@code first} is the first decision the edge carries (written at
     * the decision, carried to the second approval and a closure, absent after a refused second approval).
     */
    record Edge(
            UnderwritingCaseStatus to,
            Optional<String> assignee,
            Optional<FirstWrite> first,
            Optional<String> secondDecidedBy,
            Optional<String> closureReason,
            Optional<String> reason) {

        public Edge {
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(assignee, "assignee");
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(secondDecidedBy, "secondDecidedBy");
            Objects.requireNonNull(closureReason, "closureReason");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** A first decision as written. */
    record FirstWrite(DecisionOutcome outcome, Optional<Money> approved, List<ReasonCode> reasons, String reason,
            String decidedBy) {

        public FirstWrite {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(approved, "approved");
            Objects.requireNonNull(reasons, "reasons");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(decidedBy, "decidedBy");
            reasons = List.copyOf(reasons);
        }

        static FirstWrite of(UnderwritingCase.FirstDecision first) {
            return new FirstWrite(first.outcome(), first.approved(), first.reasons(), first.reason(), first.decidedBy());
        }
    }

    /**
     * Opens the case {@code OPEN} with its birth history row, unless the request already has one ({@code ON CONFLICT
     * (decision_request_id) DO NOTHING}); true when this call wrote it.
     */
    boolean open(Connection unitOfWork, NewCase opening, Actor actor);

    /** The case, read without a lock. */
    Optional<UnderwritingCase> byId(Connection unitOfWork, UnderwritingCaseId id);

    /** The case {@code FOR UPDATE} - lock-order element (3), taken after its request's row. */
    Optional<UnderwritingCase> lock(Connection unitOfWork, UnderwritingCaseId id);

    /** The request's case {@code FOR UPDATE} - element (3), for the progress step that already holds the request. */
    Optional<UnderwritingCase> lockByRequest(Connection unitOfWork, DecisionRequestId request);

    /**
     * Moves the case from {@code from} along {@code edge}, with the edge's history row; true when this call moved it. The
     * history row names the first decider a second person's act answered.
     */
    boolean move(Connection unitOfWork, UnderwritingCase from, Edge edge, Actor actor);

    /** At most {@code limit} cases, oldest first - every status, or only {@code status}. */
    List<UnderwritingCase> queue(Connection unitOfWork, Optional<UnderwritingCaseStatus> status, int limit);

    /** How long the oldest {@code OPEN} case has waited, on the database's clock; empty when none waits. */
    Optional<Duration> oldestOpenAge(Connection unitOfWork);
}
