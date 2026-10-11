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
import java.util.UUID;

/**
 * Persistence for the loan accounts (`P11-TSK-012`, {@code lending V006}): the machine row ({@code loan}), its history
 * ({@code loan_event}, append-only), its agreement versions ({@code loan_agreement}), its acceptance evidence
 * ({@code loan_acceptance}) and a loan's disbursement ({@code loan_disbursement}) - every table INSERT-only here. Every
 * read a customer reaches is owner-scoped in the statement; the committed principal is the capital headroom's.
 */
public interface LoanStore {

    /** Births the loan; the database stamps and answers the opening instant. */
    Instant insert(Connection unitOfWork, NewLoan loan);

    /** Appends one history row; the database stamps the time. */
    void appendEvent(Connection unitOfWork, HistoryRow row);

    /** Births an agreement version - the database holds version 1 to its offer's terms and answers its effective instant. */
    Instant insertAgreement(Connection unitOfWork, LoanAgreement agreement);

    /** Births the acceptance evidence for {@code agreement}; the database stamps and answers the instant. */
    Instant insertAcceptance(
            Connection unitOfWork,
            UUID id,
            LoanAgreement agreement,
            byte[] documentSha256,
            AcceptanceEvidence evidence,
            CorrelationId correlation);

    /** Births a loan's disbursement {@code PENDING}, with its path X payout method when it has one. */
    void insertDisbursement(
            Connection unitOfWork, UUID id, LoanId loan, Optional<UUID> payoutMethod, CorrelationId correlation);

    /** {@code party}'s loan {@code id} with its current agreement version, unlocked. */
    Optional<Owned> ownedBy(Connection unitOfWork, LoanId id, UUID party);

    /** {@code party}'s loans with their current agreement versions, newest first, at most {@code limit}. */
    List<Owned> listOwnedBy(Connection unitOfWork, UUID party, int limit);

    /**
     * The principal committed in {@code currency} and not yet disbursed - Σ agreement principal of the loans
     * {@code PENDING_DISBURSEMENT} (ADR-0096 section 5) - folded through {@link Money} at the agreements' scale; zero
     * when there are none.
     */
    Money committedPrincipal(Connection unitOfWork, CurrencyCode currency);

    // ------------------------------------------------------------------ rows

    /** A loan at birth - its status the kind's (a loan {@code PENDING_DISBURSEMENT}, a line {@code ACTIVE}). */
    record NewLoan(
            LoanId id,
            UUID party,
            LoanProduct product,
            CurrencyCode currency,
            LoanOfferId offer,
            UUID decision,
            CorrelationId correlation) {

        public NewLoan {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(party, "party must not be null");
            Objects.requireNonNull(product, "product must not be null");
            Objects.requireNonNull(currency, "currency must not be null");
            Objects.requireNonNull(offer, "offer must not be null");
            Objects.requireNonNull(decision, "decision must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }

        /** The state the kind is born in (LENDING_LIFECYCLES.md section 4.5). */
        public LoanStatus bornStatus() {
            return product.kind() == LoanKind.INSTALMENT ? LoanStatus.PENDING_DISBURSEMENT : LoanStatus.ACTIVE;
        }

        @Override
        public String toString() {
            return "NewLoan[" + id.value() + ", " + product + "]";
        }
    }

    /** One edge of the history; {@code reason} a code, never free text. */
    record HistoryRow(
            UUID eventId,
            LoanId loan,
            Optional<LoanStatus> from,
            LoanStatus to,
            String reason,
            Actor actor,
            CorrelationId correlation) {

        public HistoryRow {
            Objects.requireNonNull(eventId, "eventId must not be null");
            Objects.requireNonNull(loan, "loan must not be null");
            Objects.requireNonNull(from, "from must not be null");
            Objects.requireNonNull(to, "to must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(actor, "actor must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }

        @Override
        public String toString() {
            return "HistoryRow[" + loan.value() + ", " + from.map(Enum::name).orElse("-") + " -> " + to + "]";
        }
    }

    /** A loan with the agreement version that governs it now. */
    record Owned(Loan loan, LoanAgreement agreement) {

        public Owned {
            Objects.requireNonNull(loan, "loan must not be null");
            Objects.requireNonNull(agreement, "agreement must not be null");
        }

        @Override
        public String toString() {
            return "Owned[" + loan + "]";
        }
    }
}
