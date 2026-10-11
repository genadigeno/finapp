package com.finapp.lending;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the loan offers (`P11-TSK-011`, {@code lending V005}): the machine row ({@code loan_offer}, its status
 * and permit the columns that move), its immutable terms ({@code loan_offer_terms}) and its history
 * ({@code loan_offer_event}, append-only). Every read a customer reaches is owner-scoped in the statement; the system's
 * reads take ids only from its own claims.
 */
public interface LoanOfferStore {

    /** The database's clock now - {@code statement_timestamp()}, read after the caller's locks. */
    Instant databaseNow(Connection unitOfWork);

    /** Births the offer {@code OFFERED}; the database stamps and answers its expiry. */
    Instant insert(Connection unitOfWork, NewOffer offer);

    /** Births the offer's terms - once. */
    void insertTerms(Connection unitOfWork, LoanOfferId offer, LoanProduct product, LoanOffer.Terms terms);

    /** Appends one history row; the database stamps the time. */
    void appendEvent(Connection unitOfWork, HistoryRow row);

    /** {@code party}'s offer {@code id} with its terms, unlocked. */
    Optional<LoanOffer> ownedBy(Connection unitOfWork, LoanOfferId id, UUID party);

    /** L2: {@code party}'s offer {@code id}, {@code FOR UPDATE}. */
    Optional<LoanOffer> lockOwnedBy(Connection unitOfWork, LoanOfferId id, UUID party);

    /** {@code party}'s offer for its application {@code application}, if one was born. */
    Optional<LoanOfferId> offerFor(Connection unitOfWork, LoanApplicationId application, UUID party);

    /**
     * {@code OFFERED -> DECLINED}, conditional on {@code OFFERED} and {@code expires_at > statement_timestamp()};
     * {@code false} when either no longer holds.
     */
    boolean decline(Connection unitOfWork, LoanOfferId id);

    /**
     * {@code OFFERED -> ACCEPTED} (`P11-TSK-012`), conditional on {@code OFFERED} and {@code expires_at >
     * statement_timestamp()} - the expiry's complement; {@code false} when either no longer holds. {@code lending V006}
     * admits the edge only beside the offer's loan, and commits it only with its agreement and evidence.
     */
    boolean accept(Connection unitOfWork, LoanOfferId id);

    /**
     * The expiry sweep's claim: up to {@code limit} {@code OFFERED} offers whose permit is due, oldest first, in ONE
     * statement - rows another sweeper holds skipped, the page's permit re-stamped {@code permit} ahead.
     */
    List<LoanOfferId> claimDue(Connection unitOfWork, int limit, Duration permit);

    /** L2 for the system: the offer {@code id} - one the caller's own claim answered - {@code FOR UPDATE}. */
    Optional<LoanOffer> lockClaimed(Connection unitOfWork, LoanOfferId id);

    /**
     * {@code OFFERED -> EXPIRED}, conditional on {@code OFFERED} and {@code expires_at <= statement_timestamp()};
     * {@code false} when either does not hold.
     */
    boolean expire(Connection unitOfWork, LoanOfferId id);

    /** An open offer claimed before its expiry: its permit set back to the expiry itself. */
    void permitAtExpiry(Connection unitOfWork, LoanOfferId id);

    // ------------------------------------------------------------------ rows

    /** An offer at birth - identity, decision, pin and the offering instant; the expiry is the database's. */
    record NewOffer(
            LoanOfferId id,
            LoanApplicationId application,
            UUID party,
            LoanProduct product,
            UUID decision,
            LoanTermsVersionId termsVersion,
            Instant offeredAt,
            Instant decisionValidUntil,
            CorrelationId correlation) {

        public NewOffer {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(application, "application must not be null");
            Objects.requireNonNull(party, "party must not be null");
            Objects.requireNonNull(product, "product must not be null");
            Objects.requireNonNull(decision, "decision must not be null");
            Objects.requireNonNull(termsVersion, "termsVersion must not be null");
            Objects.requireNonNull(offeredAt, "offeredAt must not be null");
            Objects.requireNonNull(decisionValidUntil, "decisionValidUntil must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }

        @Override
        public String toString() {
            return "NewOffer[" + id.value() + ", " + product + "]";
        }
    }

    /** One edge of the history; {@code reason} a code, never free text. */
    record HistoryRow(
            UUID eventId,
            LoanOfferId offer,
            Optional<LoanOfferStatus> from,
            LoanOfferStatus to,
            String reason,
            Actor actor,
            CorrelationId correlation) {

        public HistoryRow {
            Objects.requireNonNull(eventId, "eventId must not be null");
            Objects.requireNonNull(offer, "offer must not be null");
            Objects.requireNonNull(from, "from must not be null");
            Objects.requireNonNull(to, "to must not be null");
            Objects.requireNonNull(reason, "reason must not be null");
            Objects.requireNonNull(actor, "actor must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }

        @Override
        public String toString() {
            return "HistoryRow[" + offer.value() + ", " + from.map(Enum::name).orElse("-") + " -> " + to + "]";
        }
    }
}
