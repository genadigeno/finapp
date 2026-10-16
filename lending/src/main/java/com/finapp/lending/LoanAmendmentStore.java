package com.finapp.lending;

import com.finapp.creditmath.DueItemId;
import com.finapp.ledger.JournalEntryId;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for restructuring (`P11-TSK-027`, {@code lending V023}): the born-once proposal ({@code loan_amendment}), its
 * born-once decision ({@code loan_amendment_decision}, {@code UNIQUE (amendment_id)} - decided once) and an accepted
 * amendment's re-scheduled arrears ({@code loan_amendment_rescheduling}). All INSERT-only; every write is reached under the
 * loan's row lock (L3), which each table's trigger takes again for every writer. The status is derived in the read itself,
 * on the database clock.
 */
public interface LoanAmendmentStore {

    /** The database clock - read by an act after its locks. */
    Instant databaseNow(Connection unitOfWork);

    /** Births {@code proposal} {@code PROPOSED}; the database stamps and answers its instant. */
    Instant insert(Connection unitOfWork, NewAmendment proposal);

    /**
     * Births {@code decision} - whether this call decided the amendment: false when it was already decided
     * ({@code UNIQUE (amendment_id)}, the arbiter beneath L3).
     */
    boolean decide(Connection unitOfWork, Decision decision);

    /** The amendment {@code id} of {@code loan}, its status derived now - empty when it is another loan's or none. */
    Optional<LoanAmendment> find(Connection unitOfWork, LoanId loan, UUID id);

    /** The loan's amendments, newest first, at most {@code limit}, each status derived now. */
    List<LoanAmendment> ofLoan(Connection unitOfWork, LoanId loan, int limit);

    /** Whether an amendment of {@code loan} is open - {@code PROPOSED}, or {@code APPROVED} and unaccepted, unexpired. */
    boolean open(Connection unitOfWork, LoanId loan);

    /** The schedule version projecting agreement version {@code agreementVersion} of {@code loan}, if born. */
    Optional<Integer> scheduleVersionOf(Connection unitOfWork, LoanId loan, int agreementVersion);

    /** Births one re-scheduled item of an accepted amendment, naming rule 13's entry. */
    void insertRescheduling(Connection unitOfWork, Rescheduling rescheduling);

    // ------------------------------------------------------------------ rows

    /** A proposal at birth. */
    record NewAmendment(
            UUID id,
            LoanId loan,
            LoanAgreementId amendsAgreement,
            int amendsVersion,
            BigDecimal nominalAnnualRate,
            int remainingInstalments,
            boolean rescheduleArrears,
            byte[] documentSha256,
            Actor proposer,
            String reason,
            CorrelationId correlation) {

        public NewAmendment {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(loan, "loan must not be null");
            Objects.requireNonNull(amendsAgreement, "amendsAgreement must not be null");
            Objects.requireNonNull(nominalAnnualRate, "nominalAnnualRate must not be null");
            documentSha256 = Objects.requireNonNull(documentSha256, "documentSha256 must not be null").clone();
            Objects.requireNonNull(proposer, "proposer must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }

        @Override
        public byte[] documentSha256() {
            return documentSha256.clone();
        }

        @Override
        public String toString() {
            return "NewAmendment[" + id + ", amends v" + amendsVersion + "]";
        }
    }

    /** A decision: {@code APPROVED} (the database stamps the offer's expiry) or {@code REJECTED}. */
    record Decision(UUID id, UUID amendment, String proposedBy, LoanAmendmentStatus outcome, Actor decider, String reason,
            CorrelationId correlation) {

        public Decision {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(amendment, "amendment must not be null");
            Objects.requireNonNull(proposedBy, "proposedBy must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(decider, "decider must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            if (outcome != LoanAmendmentStatus.APPROVED && outcome != LoanAmendmentStatus.REJECTED) {
                throw new IllegalArgumentException("a decision approves or rejects");
            }
        }

        @Override
        public String toString() {
            return "Decision[" + amendment + ", " + outcome + "]";
        }
    }

    /** One billed item's whole unpaid principal re-scheduled by an accepted amendment, under rule 13's entry. */
    record Rescheduling(UUID id, UUID amendment, LoanId loan, DueItemId billing, Money principal, JournalEntryId entry,
            CorrelationId correlation) {

        public Rescheduling {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(amendment, "amendment must not be null");
            Objects.requireNonNull(loan, "loan must not be null");
            Objects.requireNonNull(billing, "billing must not be null");
            Objects.requireNonNull(principal, "principal must not be null");
            Objects.requireNonNull(entry, "entry must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            if (!principal.isPositive()) {
                throw new IllegalArgumentException("a re-scheduling moves some principal");
            }
        }

        @Override
        public String toString() {
            return "Rescheduling[" + amendment + " on " + billing + ", redacted]";
        }
    }
}
