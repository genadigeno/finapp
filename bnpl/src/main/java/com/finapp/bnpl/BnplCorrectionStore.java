package com.finapp.bnpl;

import com.finapp.ledger.JournalEntryId;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Persistence for the servicing corrections under four eyes (`P12-TSK-024`, {@code bnpl V016}; lending's
 * {@code RepaymentReversalStore} and {@code WaiverStore}): the operator's addressing of an agreement, the repayment
 * reversal's machine row and its negating rows, and the fee waiver and its born-once decision. Every read of an
 * obligation's rows is of one the caller holds under B4 ({@link BnplServicingStore#lock}) - an operator acts on any
 * customer's agreement by design ({@code BNPL_SERVICE}), so the agreement is the path's, and every row below it is matched
 * to that agreement's obligation in the statement; every instant the database's, stamped by {@code bnpl V016}'s triggers.
 */
public interface BnplCorrectionStore {

    // ------------------------------------------------------------------ the operator's addressing

    /**
     * The obligation of agreement {@code agreement} and its party (a frozen column), read unlocked - so the party's credit
     * profile (L0) can be taken first and an operator of that party refused before any lock; empty when no such agreement.
     */
    Optional<Addressed> addressed(Connection unitOfWork, BnplAgreementId agreement);

    /** The repayment {@code repayment} of {@code obligation}, its allocation in order - empty when another's or none. */
    Optional<BnplPurchaseStore.Repayment> repayment(Connection unitOfWork, BnplObligationId obligation, UUID repayment);

    // ------------------------------------------------------------------ the repayment reversal

    /** The repayment's live reversal - {@code PROPOSED} or {@code APPROVED} (the partial unique's), if any. */
    Optional<BnplRepaymentReversal> liveReversal(Connection unitOfWork, UUID repayment);

    /** Births a proposal {@code PROPOSED}; the instant the database stamped - empty when the partial unique refused it. */
    Optional<Instant> insertReversal(Connection unitOfWork, NewReversal reversal);

    /** (B6) The reversal {@code FOR UPDATE} - empty when absent. */
    Optional<BnplRepaymentReversal> lockReversal(Connection unitOfWork, UUID reversal);

    /** The reversal, as committed or written in this transaction. */
    Optional<BnplRepaymentReversal> reversal(Connection unitOfWork, UUID reversal);

    /** {@code PROPOSED -> APPROVED | REJECTED}, conditional on {@code PROPOSED} - whether it moved. */
    boolean decideReversal(Connection unitOfWork, ReversalDecision decision);

    /** One negating row per allocation row of the reversed repayment, the same amount - INSERT-only. */
    void insertNegations(Connection unitOfWork, UUID reversal, List<BnplPurchaseStore.Allocation> allocations,
            Supplier<UUID> ids);

    /**
     * The instalments of {@code obligation} a refund cancelled whole - a {@code BILLED_UNPAID} adjustment with
     * {@code whole} (OQ-14): where a late fee a reversal re-opens is released beside it (the Phase 12 transition gate's
     * A7-3). Read under B4.
     */
    Set<UUID> wholeCancelledInstalments(Connection unitOfWork, BnplObligationId obligation);

    /**
     * The reversal's releases of the late fees it re-opened on instalments a refund cancelled whole, one row per instalment,
     * under its one release entry ({@code bnpl V019}'s {@code bnpl_reversal_fee_release}) - INSERT-only.
     */
    void insertFeeReleases(Connection unitOfWork, UUID reversal, BnplObligationId obligation, JournalEntryId entry,
            Map<UUID, Money> released, Supplier<UUID> ids);

    /** The obligation's reversals, newest first, at most {@code limit}. */
    List<BnplRepaymentReversal> reversalsOf(Connection unitOfWork, BnplObligationId obligation, int limit);

    /** Which of {@code repayments} an {@code APPROVED} reversal names - their derived {@code REVERSED} status. */
    Set<UUID> reversedAmong(Connection unitOfWork, Collection<UUID> repayments);

    // ------------------------------------------------------------------ the fee waiver

    /** The fee assessment {@code assessment} of {@code obligation} - empty when it is another's or none. */
    Optional<BnplFeeAssessment> assessment(Connection unitOfWork, BnplObligationId obligation, UUID assessment);

    /** Every waiver of the assessment, decided or not. */
    List<BnplWaiver> waiversOf(Connection unitOfWork, UUID assessment);

    /**
     * Σ the late fees every refund released on {@code instalment} (OQ-14), and every reversal that re-opened one there (the
     * Phase 12 transition gate's A7-3) - {@code zero} when none.
     */
    Money releasedOn(Connection unitOfWork, UUID instalment, Money zero);

    /** Births a waiver's proposal; the instant the database stamped. */
    Instant insertWaiver(Connection unitOfWork, NewWaiver waiver);

    /** The waiver {@code waiver} of {@code obligation}, with its decision if any - empty when another's or none. */
    Optional<BnplWaiver> waiver(Connection unitOfWork, BnplObligationId obligation, UUID waiver);

    /** Births the waiver's decision - once ({@code UNIQUE (waiver_id)}); whether it was born. */
    boolean decideWaiver(Connection unitOfWork, WaiverDecision decision);

    /** The obligation's waivers, newest first, at most {@code limit}. */
    List<BnplWaiver> waiversOfObligation(Connection unitOfWork, BnplObligationId obligation, int limit);

    // ------------------------------------------------------------------ rows

    /** An agreement as an operator addresses it: its obligation and the obligation's party. */
    record Addressed(BnplObligationId obligation, UUID party) {

        public Addressed {
            Objects.requireNonNull(obligation, "obligation must not be null");
            Objects.requireNonNull(party, "party must not be null");
        }

        @Override
        public String toString() {
            return "Addressed[" + obligation.value() + "]";
        }
    }

    /** A reversal at birth; its instant is the database's. */
    record NewReversal(UUID id, BnplObligationId obligation, UUID repayment, BnplAgreementId agreement,
            int agreementVersion, Actor proposer, String reason, CorrelationId correlation) {

        public NewReversal {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(obligation, "obligation must not be null");
            Objects.requireNonNull(repayment, "repayment must not be null");
            Objects.requireNonNull(agreement, "agreement must not be null");
            Objects.requireNonNull(proposer, "proposer must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }

        @Override
        public String toString() {
            return "NewReversal[" + id + "]";
        }
    }

    /** A reversal's decision; an approval names its entry and the date it is valued. */
    record ReversalDecision(UUID reversal, BnplCorrectionStatus outcome, Actor decider, String reason,
            Optional<JournalEntryId> entry, Optional<LocalDate> valueDate) {

        public ReversalDecision {
            Objects.requireNonNull(reversal, "reversal must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(decider, "decider must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(entry, "entry must not be null");
            Objects.requireNonNull(valueDate, "valueDate must not be null");
            if (outcome == BnplCorrectionStatus.PROPOSED
                    || (outcome == BnplCorrectionStatus.APPROVED) != (entry.isPresent() && valueDate.isPresent())) {
                throw new IllegalArgumentException("a decision approves with its entry and value date, or rejects with"
                        + " neither");
            }
        }

        @Override
        public String toString() {
            return "ReversalDecision[" + reversal + ", " + outcome + "]";
        }
    }

    /** A waiver at birth; its instant is the database's. */
    record NewWaiver(UUID id, BnplObligationId obligation, BnplWaiverKind kind, UUID assessment, UUID instalment,
            Money amount, BnplAgreementId agreement, int agreementVersion, Actor proposer, String reason,
            CorrelationId correlation) {

        public NewWaiver {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(obligation, "obligation must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
            Objects.requireNonNull(assessment, "assessment must not be null");
            Objects.requireNonNull(instalment, "instalment must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(agreement, "agreement must not be null");
            Objects.requireNonNull(proposer, "proposer must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }

        @Override
        public String toString() {
            return "NewWaiver[" + id + "]";
        }
    }

    /** A waiver's decision, carrying its proposer's copy for the four-eyes {@code CHECK}; an approval names its entry. */
    record WaiverDecision(UUID id, UUID waiver, String proposedBy, BnplCorrectionStatus outcome, Actor decider,
            String reason, Optional<JournalEntryId> entry, CorrelationId correlation) {

        public WaiverDecision {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(waiver, "waiver must not be null");
            Objects.requireNonNull(proposedBy, "proposedBy must not be null");
            Objects.requireNonNull(outcome, "outcome must not be null");
            Objects.requireNonNull(decider, "decider must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(entry, "entry must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
            if (outcome == BnplCorrectionStatus.PROPOSED
                    || (outcome == BnplCorrectionStatus.APPROVED) != entry.isPresent()) {
                throw new IllegalArgumentException("a decision approves with its entry, or rejects with none");
            }
        }

        @Override
        public String toString() {
            return "WaiverDecision[" + waiver + ", " + outcome + "]";
        }
    }
}
