package com.finapp.bnpl;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the BNPL applications (`P12-TSK-010`, {@code bnpl V003}): the machine row ({@code bnpl_application},
 * its status, closure reason and permit the only columns that move) and its history ({@code bnpl_application_event},
 * append-only). Every read a customer reaches is owner-scoped: the party is a predicate of the statement, so another
 * party's application is the same empty answer as an absent one. The origination sweep's claim, its system lock, the
 * closures with their reason and the hints are `P12-TSK-011`'s.
 */
public interface BnplApplicationStore {

    /**
     * Births the application {@code SUBMITTED}; {@code false} - nothing written - when the checkout session already has
     * an open application ({@code bnpl_application_one_open_per_session}, over {@code SUBMITTED}, {@code AWAITING_DECISION}
     * and {@code OFFERED}), or the party one for the product ({@code bnpl_application_one_open_per_party_product}, the
     * backstop beneath credit's one-open-request partial unique). A conflict is nothing written, never an aborted
     * transaction.
     */
    boolean insert(Connection unitOfWork, NewApplication application);

    /** Moves the application {@code from -> to}, conditional on {@code from}; {@code false} when it was not there. */
    boolean transition(
            Connection unitOfWork, BnplApplicationId id, BnplApplicationStatus from, BnplApplicationStatus to);

    /** Appends one history row; the database stamps the time. */
    void appendEvent(Connection unitOfWork, HistoryRow row);

    /** {@code party}'s application {@code id}, unlocked. */
    Optional<BnplApplication> ownedBy(Connection unitOfWork, BnplApplicationId id, UUID party);

    /** B1: {@code party}'s application {@code id}, {@code FOR UPDATE}. */
    Optional<BnplApplication> lockOwnedBy(Connection unitOfWork, BnplApplicationId id, UUID party);

    // ------------------------------------------------------------------ the origination step (P12-TSK-011)

    /**
     * Moves the application {@code AWAITING_DECISION -> to}, conditional on {@code AWAITING_DECISION}, with
     * {@code reason} as its closure reason - present exactly for {@code CLOSED_UNDECIDED}, by {@code bnpl V003}'s
     * {@code CHECK}; {@code false} when it was not there.
     */
    boolean close(Connection unitOfWork, BnplApplicationId id, BnplApplicationStatus to,
            Optional<BnplApplicationClosureReason> reason);

    /**
     * The origination sweep's claim: up to {@code limit} applications {@code AWAITING_DECISION} whose permit has come,
     * oldest first, in ONE statement - each one's permit re-stamped {@code permit} ahead on the database clock, rows
     * another sweeper holds skipped ({@code FOR UPDATE SKIP LOCKED}) - so concurrent sweepers take disjoint pages and an
     * application whose step cannot act (credit undecided, or a failure) lets its permit lapse and never holds a page.
     * The claim binds nothing: every step re-judges under B1.
     */
    List<BnplApplicationId> claimAwaiting(Connection unitOfWork, int limit, Duration permit);

    /** B1 for the system's step: the application {@code id}, {@code FOR UPDATE} - no owner predicate. */
    Optional<BnplApplication> lockClaimed(Connection unitOfWork, BnplApplicationId id);

    /**
     * A credit event's hint: the application awaiting {@code decisionRequest} made due now - its permit and nothing
     * else; {@code false} when none awaits it.
     */
    boolean hintRequest(Connection unitOfWork, UUID decisionRequest);

    /**
     * A checkout event's hint: the application awaiting a decision in {@code checkoutSession} made due now; {@code false}
     * when none awaits one.
     */
    boolean hintSession(Connection unitOfWork, UUID checkoutSession);

    /**
     * How long the oldest application {@code AWAITING_DECISION} has waited since its submission, on the database clock -
     * empty when none waits (`P12-TSK-026`; the Phase 11 gate's A5-03 applied forward, lending's
     * {@code LoanApplicationStore#oldestAwaitingDecisionAge}). An age, never an application or a party.
     */
    Optional<Duration> oldestAwaitingDecisionAge(Connection unitOfWork);

    // ------------------------------------------------------------------ rows

    /** An application at birth - its choices and references, never a figure. */
    record NewApplication(
            BnplApplicationId id,
            UUID party,
            BnplProduct product,
            BnplPlan plan,
            UUID checkoutSession,
            UUID merchant,
            UUID decisionRequest,
            BnplTermsVersionId termsVersion,
            Actor submittedBy,
            CorrelationId correlation) {

        public NewApplication {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(party, "party must not be null");
            Objects.requireNonNull(product, "product must not be null");
            Objects.requireNonNull(plan, "plan must not be null");
            Objects.requireNonNull(checkoutSession, "checkoutSession must not be null");
            Objects.requireNonNull(merchant, "merchant must not be null");
            Objects.requireNonNull(decisionRequest, "decisionRequest must not be null");
            Objects.requireNonNull(termsVersion, "termsVersion must not be null");
            Objects.requireNonNull(submittedBy, "submittedBy must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }

        @Override
        public String toString() {
            return "NewApplication[" + id.value() + ", " + product + ", " + plan + "]";
        }
    }

    /** One edge of the history; {@code reason} a code, never free text. */
    record HistoryRow(
            UUID eventId,
            BnplApplicationId application,
            Optional<BnplApplicationStatus> from,
            BnplApplicationStatus to,
            String reason,
            Actor actor,
            CorrelationId correlation) {

        public HistoryRow {
            Objects.requireNonNull(eventId, "eventId must not be null");
            Objects.requireNonNull(application, "application must not be null");
            Objects.requireNonNull(from, "from must not be null");
            Objects.requireNonNull(to, "to must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(actor, "actor must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }

        @Override
        public String toString() {
            return "HistoryRow[" + application.value() + ", " + from.map(Enum::name).orElse("-") + " -> " + to + "]";
        }
    }
}
