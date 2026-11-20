package com.finapp.payments;

import com.finapp.ledger.LedgerAccountId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The operator's payment collections (`P17-TSK-032`, finapp-admin-panel B7.2; `API_CONVENTIONS.md` section 7): keyset pages
 * in one declared total order each, read under the caller's unit of work. Every caller holds the permission of the door that
 * serves it - {@code PAYMENT_READ}, {@code DISPUTE_ADMINISTER}, {@code PAYMENT_ROUTING_ADMINISTER} or {@code RISK_INVESTIGATE} -
 * and reads across parties by it: administered reads, never a customer's own.
 *
 * <p>{@code payments} still never learns what a merchant is (ADR-0061 section 4): a merchant filter arrives as the credited
 * accounts the caller resolved through the ledger's owner-scoped read.
 */
public interface PaymentCollections {

    // ------------------------------------------------------------------ the payment search (G-40)

    /**
     * The search's filter: a window of creation instants {@code [from, to)}, and at most one of each of the payer's party,
     * the credited accounts (a merchant's payables), the intent's status and the attempt's rail.
     */
    record PaymentFilter(
            Instant from,
            Instant to,
            Optional<UUID> party,
            Optional<Set<LedgerAccountId>> creditAccounts,
            Optional<PaymentIntentStatus> status,
            Optional<RailId> rail) {
        public PaymentFilter {
            Objects.requireNonNull(from, "from must not be null");
            Objects.requireNonNull(to, "to must not be null");
            Objects.requireNonNull(party, "party must not be null");
            Objects.requireNonNull(creditAccounts, "creditAccounts must not be null");
            Objects.requireNonNull(status, "status must not be null");
            Objects.requireNonNull(rail, "rail must not be null");
            if (!from.isBefore(to)) {
                throw new IllegalArgumentException("a window's start is before its end");
            }
        }
    }

    /** Where a search page resumes: after the intent created at {@code createdAt} with id {@code id}. */
    record PaymentCursor(Instant createdAt, UUID id) {
        public PaymentCursor {
            Objects.requireNonNull(createdAt, "createdAt must not be null");
            Objects.requireNonNull(id, "id must not be null");
        }
    }

    /** One payment as the search shows it: the intent's head and its attempt's rail and status, when it has one. */
    record PaymentHead(
            PaymentIntentId id,
            UUID party,
            PaymentIntentStatus status,
            Money amount,
            Instant createdAt,
            Optional<RailId> rail,
            Optional<PaymentAttemptStatus> attemptStatus) {}

    /** At most {@code limit} payments matching {@code filter}, created at descending and id descending, after {@code after}. */
    List<PaymentHead> payments(Connection unitOfWork, PaymentFilter filter, Optional<PaymentCursor> after, int limit);

    // ------------------------------------------------------------------ the dispute queue (G-42)

    /**
     * Where a queue page resumes: after the dispute with {@code respondBy} - empty for one whose deadline the network has not
     * stated, which sort last - and id {@code id}.
     */
    record DisputeCursor(Optional<Instant> respondBy, UUID id) {
        public DisputeCursor {
            Objects.requireNonNull(respondBy, "respondBy must not be null");
            Objects.requireNonNull(id, "id must not be null");
        }
    }

    /**
     * At most {@code limit} disputes of {@code stage} when named, with a deadline before {@code deadlineBefore} when named,
     * ordered by the network's deadline - earliest first, an unstated one last - and id, after {@code after}.
     */
    List<DisputeStore.Found> disputes(
            Connection unitOfWork,
            Optional<DisputeStage> stage,
            Optional<Instant> deadlineBefore,
            Optional<DisputeCursor> after,
            int limit);

    // ------------------------------------------------------------------ the routing policy versions (G-46)

    /** At most {@code limit} versions, newest number first, below {@code before} when named - each with its rules. */
    List<RoutingPolicyVersion> routingVersions(Connection unitOfWork, Optional<Integer> before, int limit);

    /** The version numbered {@code version}, with its rules. */
    Optional<RoutingPolicyVersion> routingVersion(Connection unitOfWork, int version);

    // ------------------------------------------------------------------ the held card payments (G-43)

    /** Where a held page resumes: after the hold due at {@code heldUntil} on intent {@code intent}. */
    record HeldCursor(Instant heldUntil, UUID intent) {
        public HeldCursor {
            Objects.requireNonNull(heldUntil, "heldUntil must not be null");
            Objects.requireNonNull(intent, "intent must not be null");
        }
    }

    /** One held card payment: its open capture hold, the payment's payer and amount, and its attempt's rail. */
    record HeldPayment(
            PaymentIntentId intent,
            PaymentAttemptId attempt,
            UUID party,
            Money amount,
            RailId rail,
            Instant heldAt,
            Instant heldUntil) {}

    /**
     * At most {@code limit} open capture holds - unresolved, whatever their deadline says, since the expiry leg resolves a lapsed
     * one - of payments whose payer is not {@code withheldParty}, earliest deadline first and the intent breaking the tie,
     * after {@code after}.
     */
    List<HeldPayment> heldPayments(Connection unitOfWork, UUID withheldParty, Optional<HeldCursor> after, int limit);

    // ------------------------------------------------------------------ the payments report (G-45)

    /**
     * How many payments created in {@code [from, to)} reached one combination: the attempt's rail and status - both empty for
     * an intent that never had an attempt - and the intent's status.
     */
    record OutcomeCount(
            Optional<RailId> rail,
            Optional<PaymentAttemptStatus> attemptStatus,
            PaymentIntentStatus intentStatus,
            long count) {}

    /** The window's payments, counted by rail, attempt status and intent status. */
    List<OutcomeCount> outcomes(Connection unitOfWork, Instant from, Instant to);

    /** What is waiting on a provider's answer now: attempts and refunds in an unknown state, by kind and status. */
    record UnknownCount(String kind, String status, long count, Instant oldestCreatedAt) {}

    /** The unknown-state backlog as it stands: every attempt in an {@code *_UNKNOWN} status and every {@code UNKNOWN} refund. */
    List<UnknownCount> unknownBacklog(Connection unitOfWork);

    /** Where a queue of proposals awaiting a decision resumes (`P17-TSK-040`): the last row served, oldest first. */
    record PendingPosition(java.time.Instant proposedAt, java.util.UUID id) {
        public PendingPosition {
            java.util.Objects.requireNonNull(proposedAt, "proposedAt must not be null");
            java.util.Objects.requireNonNull(id, "id must not be null");
        }
    }

    /** A proposal awaiting a decision (`P17-TSK-040`): its id, its proposer and their reason, when, and what it acts on. */
    record PendingProposal(
            java.util.UUID id, String proposedBy, java.time.Instant proposedAt, String reason, String subject) {
        public PendingProposal {
            java.util.Objects.requireNonNull(id, "id must not be null");
            java.util.Objects.requireNonNull(proposedAt, "proposedAt must not be null");
        }
    }

    /**
     * The refunds above the four-eyes threshold awaiting a decision, each naming its payment: {@code PROPOSED} alone, oldest first, the id the tiebreak (`P17-TSK-040`).
     */
    java.util.List<PendingProposal> pendingRefundProposals(Connection unitOfWork, java.util.Optional<PendingPosition> after, int limit);
}
