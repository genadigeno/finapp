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

    /**
     * Births the loan; the database stamps and answers the opening instant.
     *
     * @throws OfferExpired the offer's {@code expires_at} has passed on the database clock at this statement - the one
     *     refusal of the loan's birth that is the customer's, not a breach (`P11-TSK-034`, {@code lending V026})
     */
    Instant insert(Connection unitOfWork, NewLoan loan);

    /** Appends one history row; the database stamps the time. */
    void appendEvent(Connection unitOfWork, HistoryRow row);

    /**
     * Births an agreement version - the database holds version 1 to its offer's terms and answers its effective instant.
     *
     * @throws AmendmentExpired an amendment's version whose offer's {@code expires_at} has passed on the database clock at
     *     this statement - the one refusal of the birth that is the customer's, not a breach (`P11-TSK-035`,
     *     {@code lending V027}); only the amendment's acceptance can meet it - version 1 and a prepayment's version judge
     *     no expiry
     */
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

    /**
     * The party a loan belongs to, unlocked (`P11-TSK-014`) - a frozen column, read so the disbursement can take the
     * party's profile lock (L0) BEFORE the loan's row (L3); empty when there is no such loan.
     */
    Optional<UUID> partyOf(Connection unitOfWork, LoanId id);

    /** The loan {@code FOR UPDATE} (L3) with its current agreement version (`P11-TSK-014`). */
    Optional<Owned> lock(Connection unitOfWork, LoanId id);

    /** {@code PENDING_DISBURSEMENT -> ACTIVE}, conditional (`P11-TSK-014`); whether this call moved it. */
    boolean activate(Connection unitOfWork, LoanId id);

    /** {@code PENDING_DISBURSEMENT -> CANCELLED} for {@code reason}, conditional (`P11-TSK-014`); whether it moved. */
    boolean cancel(Connection unitOfWork, LoanId id, CancellationReason reason);

    /**
     * {@code party}'s loan {@code id} {@code FOR UPDATE} (L3) with its current agreement version (`P11-TSK-021`) - the
     * draw's lock, owner-scoped in the statement: another party's loan is the same empty answer as an absent one, and is
     * never locked.
     */
    Optional<Owned> lockOwnedBy(Connection unitOfWork, LoanId id, UUID party);

    /**
     * Closes a loan (`P11-TSK-018`; a credit line's, `P11-TSK-023`): births {@code closure}'s {@code loan_closure} row, then
     * moves the loan {@code from -> CLOSED} for its reason, conditional on {@code from} - in this order, which the edge
     * function requires. Whether the loan moved; called only under the loan's row lock (L3), in the transaction that zeroed
     * its last receivable and refunded its credit balance - an instalment loan from {@code ACTIVE}, a line from
     * {@code ACTIVE} (at its closure request) or {@code CLOSING}.
     */
    boolean close(Connection unitOfWork, LoanClosure closure, LoanStatus from, CorrelationId correlation);

    /**
     * A credit line {@code ACTIVE -> CLOSING} (`P11-TSK-023`), conditional - beside the customer's closure request born in
     * this transaction, which the edge function requires. Whether it moved; called only under the line's row lock (L3).
     */
    boolean beginClosing(Connection unitOfWork, LoanId id);

    /**
     * How many loan accounts are in each (product, status) - the {@code finapp.lending.loans} gauge's read (`P11-TSK-023`);
     * a pair with none is absent.
     */
    java.util.Map<LoanProduct, java.util.Map<LoanStatus, Long>> countsByProductAndStatus(Connection unitOfWork);

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

    /**
     * The loan's birth refused because its offer expired on the database clock between the acceptance's own judgement
     * and the insert ({@code lending V026}'s SQLSTATE {@code LNEXP}, `P11-TSK-034`) - the acceptance answers it as the
     * offer no longer acceptable. Every other refusal of the birth is a {@link LendingStorageException}.
     */
    final class OfferExpired extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public OfferExpired() {
            super("the offer expired before the loan was born");
        }
    }

    /**
     * An amendment's agreement version refused because the amendment's offer expired on the database clock between the
     * acceptance's own judgement (its L5 read) and the insert ({@code lending V027}'s SQLSTATE {@code LNAEX},
     * `P11-TSK-035`) - the amendment's acceptance answers it as the amendment lapsed. Every other refusal of the birth is a
     * {@link LendingStorageException}; any other caller meeting this one has no mapping for it, so it stays a failure.
     */
    final class AmendmentExpired extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public AmendmentExpired() {
            super("the amendment's offer expired before its agreement version was born");
        }
    }
}
