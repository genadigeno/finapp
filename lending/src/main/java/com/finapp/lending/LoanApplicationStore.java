package com.finapp.lending;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the loan applications (`P11-TSK-010`, {@code lending V004}): the machine row
 * ({@code loan_application}, its status the one column that moves) and its history ({@code loan_application_event},
 * append-only). Every read a customer reaches is owner-scoped: the party is a predicate of the statement, so another
 * party's application is the same empty answer as an absent one.
 */
public interface LoanApplicationStore {

    /**
     * Births the application {@code SUBMITTED}; {@code false} - nothing written - when the party already holds an open
     * application for the product (the {@code loan_application_one_open} partial unique, the arbiter's backstop).
     */
    boolean insert(Connection unitOfWork, NewApplication application);

    /** Moves the application {@code from -> to}, conditional on {@code from}; {@code false} when it was not there. */
    boolean transition(
            Connection unitOfWork, LoanApplicationId id, LoanApplicationStatus from, LoanApplicationStatus to);

    /** Appends one history row; the database stamps the time. */
    void appendEvent(Connection unitOfWork, HistoryRow row);

    /** {@code party}'s application {@code id}, unlocked. */
    Optional<LoanApplication> ownedBy(Connection unitOfWork, LoanApplicationId id, UUID party);

    /** L1: {@code party}'s application {@code id}, {@code FOR UPDATE}. */
    Optional<LoanApplication> lockOwnedBy(Connection unitOfWork, LoanApplicationId id, UUID party);

    /**
     * The origination sweep's claim (`P11-TSK-011`): up to {@code limit} applications {@code AWAITING_DECISION} whose
     * permit is due, oldest first, in ONE statement - rows another sweeper holds skipped, the page's permit re-stamped
     * {@code permit} ahead, so an application with nothing to do yet lets its permit lapse rather than holding the page.
     */
    List<LoanApplicationId> claimAwaiting(Connection unitOfWork, int limit, Duration permit);

    /** L1 for the system (`P11-TSK-011`): the application {@code id} - one the caller's own claim answered - {@code FOR UPDATE}. */
    Optional<LoanApplication> lockClaimed(Connection unitOfWork, LoanApplicationId id);

    /**
     * A credit event's hint (`P11-TSK-011`): the application awaiting credit's request {@code decisionRequest} made due
     * now - its permit and nothing else; {@code false} when no such application awaits a decision.
     */
    boolean hint(Connection unitOfWork, UUID decisionRequest);

    /**
     * How long the oldest application {@code AWAITING_DECISION} has waited since its submission, on the database clock -
     * the {@code finapp.lending.application.awaiting.age} gauge's read (the Phase 11 gate's A5-03); empty when none waits.
     */
    Optional<Duration> oldestAwaitingDecisionAge(Connection unitOfWork);

    // ------------------------------------------------------------------ rows

    /** An application at birth - the choices, never a figure. */
    record NewApplication(
            LoanApplicationId id,
            UUID party,
            LoanProduct product,
            UUID decisionRequest,
            LoanTermsVersionId termsVersion,
            int repaymentDay,
            RepaymentMethod repaymentMethod,
            PayoutPreference payoutPreference,
            Actor submittedBy,
            CorrelationId correlation) {

        public NewApplication {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(party, "party must not be null");
            Objects.requireNonNull(product, "product must not be null");
            Objects.requireNonNull(decisionRequest, "decisionRequest must not be null");
            Objects.requireNonNull(termsVersion, "termsVersion must not be null");
            Objects.requireNonNull(repaymentMethod, "repaymentMethod must not be null");
            Objects.requireNonNull(payoutPreference, "payoutPreference must not be null");
            Objects.requireNonNull(submittedBy, "submittedBy must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }

        @Override
        public String toString() {
            return "NewApplication[" + id.value() + ", " + product + "]";
        }
    }

    /** One edge of the history; {@code reason} a code, never free text. */
    record HistoryRow(
            UUID eventId,
            LoanApplicationId application,
            Optional<LoanApplicationStatus> from,
            LoanApplicationStatus to,
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
