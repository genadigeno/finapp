package com.finapp.credit;

import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Credit's operator collections (`P17-TSK-035`, gaps G-80 and G-81; finapp-admin-panel ADR-C133): the decision requests, the
 * credit policy versions and the scorecard model versions as an operator browses them - keyset pages, each strictly after the
 * cursor in its declared total order, one beyond the page read so the caller knows whether more remain. Reads only, on the
 * caller's unit of work; recording what was served is the caller's (ADR-C132 decision 5).
 */
public interface CreditCollections {

    /** The last row a page served: its order's stamp and its id, the tie-breaker. */
    record Cursor(Instant at, UUID id) {

        public Cursor {
            Objects.requireNonNull(at, "at");
            Objects.requireNonNull(id, "id");
        }
    }

    /**
     * A decision request's head: whose, which product and through which door, where its machine stands, what was asked, and
     * the decision once recorded - never a declared income or expenditure, a score or an attribute.
     */
    record DecisionRequestHead(
            DecisionRequestId id,
            UUID party,
            CreditProduct product,
            RequestOrigin origin,
            DecisionRequestStatus status,
            Optional<ClosureReason> closureReason,
            Money requested,
            Optional<Integer> termMonths,
            Instant submittedAt,
            Instant expiresAt,
            Optional<CreditPolicyVersionId> pinnedPolicy,
            Optional<ScorecardModelVersionId> pinnedModel,
            Optional<CreditDecisionId> decision,
            Optional<DecisionOutcome> outcome) {}

    /**
     * A policy or scorecard version's head: what it is a version of (the product, or the scorecard's family), its number and
     * status, who proposed it, when and why, the decision's stamp and reason once decided - never who decided - and the period
     * it was in force.
     */
    record VersionHead(
            UUID id,
            String subject,
            int version,
            String status,
            String proposedBy,
            Instant proposedAt,
            String reason,
            Optional<Instant> decidedAt,
            Optional<String> decisionReason,
            Optional<Instant> effectiveFrom,
            Optional<Instant> effectiveTo) {}

    /** The decision requests, submitted newest first and the id breaking the tie, of one status and one product when named. */
    List<DecisionRequestHead> decisionRequests(Connection unitOfWork, Optional<DecisionRequestStatus> status,
            Optional<CreditProduct> product, Optional<Cursor> after, int limit);

    /** The credit policy versions, proposed newest first and the id breaking the tie, of one product and status when named. */
    List<VersionHead> policyVersions(Connection unitOfWork, Optional<CreditProduct> product, Optional<CreditPolicyStatus> status,
            Optional<Cursor> after, int limit);

    /** One credit policy version's head, whatever its status. */
    Optional<VersionHead> policyVersion(Connection unitOfWork, CreditPolicyVersionId id);

    /** The scorecard model versions, proposed newest first and the id breaking the tie, of one status when named. */
    List<VersionHead> scorecardVersions(Connection unitOfWork, Optional<ScorecardStatus> status, Optional<Cursor> after,
            int limit);

    /** One scorecard model version's head, whatever its status. */
    Optional<VersionHead> scorecardVersion(Connection unitOfWork, ScorecardModelVersionId id);

    /**
     * The credit policy versions awaiting a decision ({@code PROPOSED}), proposed oldest first and the id breaking the tie,
     * strictly after {@code after}: the approver's pending-decision queue (`P17-TSK-040`).
     */
    List<VersionHead> pendingPolicyVersions(Connection unitOfWork, Optional<Cursor> after, int limit);

    /**
     * The scorecard model versions awaiting a decision ({@code PROPOSED}), proposed oldest first and the id breaking the tie,
     * strictly after {@code after}: the approver's pending-decision queue (`P17-TSK-040`).
     */
    List<VersionHead> pendingScorecardVersions(Connection unitOfWork, Optional<Cursor> after, int limit);

    /**
     * A review case's first decision awaiting a different underwriter's second approval ({@code AWAITING_SECOND}): the case, the
     * first underwriter, when and why they decided, and the decision request it decides.
     */
    record PendingSecondApproval(UUID caseId, String firstDecidedBy, Instant firstDecidedAt, String firstReason,
            UUID decisionRequest) {}

    /**
     * The review cases awaiting a second approval, first decided oldest first and the case's id breaking the tie, strictly
     * after {@code after}: the second approver's pending-decision queue (`P17-TSK-040`).
     */
    List<PendingSecondApproval> pendingSecondApprovals(Connection unitOfWork, Optional<Cursor> after, int limit);
}
