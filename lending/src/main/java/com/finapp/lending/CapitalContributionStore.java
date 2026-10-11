package com.finapp.lending;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.CurrencyCode;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Persistence for the capital contributions (`P11-TSK-004`, {@code lending V002}): two INSERT-only tables - the
 * born-once proposal and its machine as history. Nothing here is ever updated.
 */
public interface CapitalContributionStore {

    /**
     * Takes the currency's administration lock - advisory namespace {@code 11} on {@code hashtext(currency)}, blocking,
     * transaction-scoped - FIRST in every proposal and decision, so ten proposers leave one proposal and ten approvers
     * one approval (`DISTRIBUTED_EXECUTION.md` section 3).
     */
    void lockCurrency(Connection unitOfWork, CurrencyCode currency);

    /**
     * The transaction's database time - the instant every row this transaction writes is stamped with, so a decision's
     * date is the database's, never an instance's clock.
     */
    Instant transactionTime(Connection unitOfWork);

    /** Whether a contribution of {@code currency} awaits a decision - read under the currency's lock. */
    boolean proposalPending(Connection unitOfWork, CurrencyCode currency);

    /** Whether any contribution - of any currency, in any state - already names {@code reference}. */
    boolean referenceTaken(Connection unitOfWork, String reference);

    /** Births the proposal and its {@code PROPOSED} history row; the database stamps the time. */
    void insertProposal(Connection unitOfWork, NewProposal proposal);

    /** The contribution as it stands: the proposal and its decision, if any. */
    Optional<ContributionRow> find(Connection unitOfWork, CapitalContributionId id);

    /** Every contribution, newest first, at most {@code limit}. */
    List<ContributionRow> recent(Connection unitOfWork, int limit);

    /**
     * Records the one decision - {@code APPROVED} (naming its expectation) or {@code REJECTED}. Returns the database's
     * decision time, or empty when another decision already stands (the {@code capital_contribution_decided_once}
     * arbiter): nothing written.
     */
    Optional<Instant> decide(Connection unitOfWork, Decision decision);

    /** A proposal at birth. */
    record NewProposal(
            CapitalContributionId id,
            Money amount,
            String reference,
            Actor proposer,
            String reason,
            CorrelationId correlation,
            java.util.UUID eventId) {

        public NewProposal {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(proposer, "proposer must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            Objects.requireNonNull(eventId, "eventId must not be null");
        }

        @Override
        public String toString() {
            return "NewProposal[" + id.value() + "]";
        }
    }

    /** A decision. */
    record Decision(
            java.util.UUID eventId,
            CapitalContributionId contribution,
            CapitalContributionStatus status,
            Actor actor,
            String proposedBy,
            String reason,
            Optional<String> expectationRef,
            CorrelationId correlation) {

        public Decision {
            Objects.requireNonNull(eventId, "eventId must not be null");
            Objects.requireNonNull(contribution, "contribution must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(actor, "actor must not be null");
            Objects.requireNonNull(proposedBy, "proposedBy must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(expectationRef, "expectationRef must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            if (status == CapitalContributionStatus.PROPOSED) {
                throw new IllegalArgumentException("a decision approves or rejects");
            }
        }

        @Override
        public String toString() {
            return "Decision[" + contribution.value() + ", " + status + "]";
        }
    }

    /** A contribution as it stands. */
    record ContributionRow(
            CapitalContributionId id,
            Money amount,
            String reference,
            String proposedBy,
            Instant proposedAt,
            CapitalContributionStatus status,
            Optional<String> decidedBy,
            Optional<Instant> decidedAt,
            Optional<String> expectationRef) {

        public ContributionRow {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(reference, "reference must not be null");
            Objects.requireNonNull(proposedBy, "proposedBy must not be null");
            Objects.requireNonNull(proposedAt, "proposedAt must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(decidedBy, "decidedBy must not be null");
            Objects.requireNonNull(decidedAt, "decidedAt must not be null");
            Objects.requireNonNull(expectationRef, "expectationRef must not be null");
        }

        public CurrencyCode currency() {
            return amount.currency();
        }

        /** Identifiers and the state only - never the amount or the reference ({@code INV-AUD-02}). */
        @Override
        public String toString() {
            return "ContributionRow[" + id.value() + ", " + status + "]";
        }
    }
}
