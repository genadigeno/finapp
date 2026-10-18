package com.finapp.bnpl;

import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence for the BNPL offers (`P12-TSK-011`, {@code bnpl V004}): the money-free machine row ({@code bnpl_offer} -
 * its status and permit the only columns that move), its INSERT-only terms ({@code bnpl_offer_terms} - the money, the
 * canonical terms and their hash, held to the bytes by {@code CHECK}) and its append-only history
 * ({@code bnpl_offer_event}). Every instant is the database's: the offering instant, the expiry and the permit are
 * stamped by {@code bnpl V004}'s trigger after the act's locks, never written by an instance. Every read a customer reaches
 * is owner-scoped in the statement.
 */
public interface BnplOfferStore {

    /**
     * Births the offer {@code OFFERED} - under its application's B1, beside its {@code AWAITING_DECISION -> OFFERED} edge
     * in the same transaction - and answers the instants the database stamped.
     */
    Offered insert(Connection unitOfWork, NewOffer offer);

    /** Births the offer's terms - once, beside its {@code OFFERED} row. */
    void insertTerms(Connection unitOfWork, BnplOfferId offer, StoredTerms terms);

    /** Appends one history row; the database stamps the time. */
    void appendEvent(Connection unitOfWork, HistoryRow row);

    /** The database clock, read as its own statement after the caller's locks - what the step judges a lapse by. */
    Instant databaseNow(Connection unitOfWork);

    /**
     * The expiry sweep's claim: up to {@code limit} offers {@code OFFERED} whose permit has come, in ONE statement - each
     * permit re-stamped {@code permit} ahead on the database clock, rows another sweeper holds skipped.
     */
    List<BnplOfferId> claimDue(Connection unitOfWork, int limit, Duration permit);

    /** B2 for the system's step: the offer {@code id}, {@code FOR UPDATE}. */
    Optional<BnplOffer> lockClaimed(Connection unitOfWork, BnplOfferId id);

    /** B2 for the customer's act: {@code party}'s offer {@code id}, {@code FOR UPDATE}. */
    Optional<BnplOffer> lockOwnedBy(Connection unitOfWork, BnplOfferId id, UUID party);

    /** {@code party}'s offer {@code id}, unlocked; another party's is the same empty answer as an absent one. */
    Optional<BnplOffer> ownedBy(Connection unitOfWork, BnplOfferId id, UUID party);

    /** The offer born for {@code application}, unlocked - for its owner's application read. */
    Optional<BnplOffer> ofApplication(Connection unitOfWork, BnplApplicationId application);

    /** The offer's terms. */
    Optional<StoredTerms> terms(Connection unitOfWork, BnplOfferId id);

    /**
     * {@code OFFERED -> EXPIRED}, conditional on {@code OFFERED} and {@code expires_at <= clock_timestamp()} - the
     * complement of the decline's (and the acceptance's) condition; {@code false} when not there or not yet due.
     */
    boolean expire(Connection unitOfWork, BnplOfferId id);

    /**
     * {@code OFFERED -> DECLINED}, conditional on {@code OFFERED} and {@code expires_at > clock_timestamp()}; {@code false}
     * when not there or already past its expiry - the sweep's to expire.
     */
    boolean decline(Connection unitOfWork, BnplOfferId id);

    /** An offer claimed before its expiry has its permit set back to the expiry. */
    void permitAtExpiry(Connection unitOfWork, BnplOfferId id);

    // ------------------------------------------------------------------ rows

    /**
     * An offer at birth: its references by value and the two bounds its expiry is stamped from - never written by the
     * step: the trigger stamps {@code LEAST(decisionValidUntil, sessionExpiresAt, offered_at + validity)}.
     */
    record NewOffer(
            BnplOfferId id,
            BnplApplicationId application,
            UUID party,
            BnplProduct product,
            BnplPlan plan,
            UUID decision,
            BnplTermsVersionId termsVersion,
            UUID checkoutSession,
            Instant decisionValidUntil,
            Instant sessionExpiresAt,
            CorrelationId correlation) {

        public NewOffer {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(application, "application must not be null");
            Objects.requireNonNull(party, "party must not be null");
            Objects.requireNonNull(product, "product must not be null");
            Objects.requireNonNull(plan, "plan must not be null");
            Objects.requireNonNull(decision, "decision must not be null");
            Objects.requireNonNull(termsVersion, "termsVersion must not be null");
            Objects.requireNonNull(checkoutSession, "checkoutSession must not be null");
            Objects.requireNonNull(decisionValidUntil, "decisionValidUntil must not be null");
            Objects.requireNonNull(sessionExpiresAt, "sessionExpiresAt must not be null");
            Objects.requireNonNull(correlation, "correlation must not be null");
        }
    }

    /** What the database stamped at the offer's birth. */
    record Offered(Instant offeredAt, Instant expiresAt) {

        public Offered {
            Objects.requireNonNull(offeredAt, "offeredAt must not be null");
            Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        }
    }

    /**
     * The offer's terms as stored: exactly the purchase amount, the pinned rate and engine, the illustration's figures
     * and dates, the canonical terms (format {@link BnplOfferTerms#FORMAT}) and their SHA-256, and the SHA-256 of the
     * document shown. RESTRICTED-FINANCIAL: {@link #toString()} names the product and plan only.
     */
    record StoredTerms(
            BnplProduct product,
            BnplPlan plan,
            Money amount,
            BigDecimal nominalAnnualRate,
            String scheduleEngine,
            LocalDate illustrativeStart,
            int instalmentCount,
            LocalDate firstDueDate,
            LocalDate lastDueDate,
            Money firstInstalment,
            Money regularInstalment,
            Money finalInstalment,
            Money totalInterest,
            Money totalPayable,
            int canonicalFormat,
            String canonicalTerms,
            byte[] termsSha256,
            byte[] documentSha256) {

        public StoredTerms {
            Objects.requireNonNull(product, "product must not be null");
            Objects.requireNonNull(plan, "plan must not be null");
            Objects.requireNonNull(amount, "amount must not be null");
            Objects.requireNonNull(nominalAnnualRate, "nominalAnnualRate must not be null");
            Objects.requireNonNull(scheduleEngine, "scheduleEngine must not be null");
            Objects.requireNonNull(illustrativeStart, "illustrativeStart must not be null");
            Objects.requireNonNull(firstDueDate, "firstDueDate must not be null");
            Objects.requireNonNull(lastDueDate, "lastDueDate must not be null");
            Objects.requireNonNull(firstInstalment, "firstInstalment must not be null");
            Objects.requireNonNull(regularInstalment, "regularInstalment must not be null");
            Objects.requireNonNull(finalInstalment, "finalInstalment must not be null");
            Objects.requireNonNull(totalInterest, "totalInterest must not be null");
            Objects.requireNonNull(totalPayable, "totalPayable must not be null");
            Objects.requireNonNull(canonicalTerms, "canonicalTerms must not be null");
            termsSha256 = Objects.requireNonNull(termsSha256, "termsSha256 must not be null").clone();
            documentSha256 = Objects.requireNonNull(documentSha256, "documentSha256 must not be null").clone();
        }

        /** The terms of {@code illustration} with their canonical form and the two hashes. */
        public static StoredTerms of(BnplPlan plan, BigDecimal rate, BnplIllustration illustration, String canonical,
                byte[] documentSha256) {
            return new StoredTerms(plan.product(), plan, illustration.amount(), rate, illustration.engine(),
                    illustration.start(), illustration.instalmentCount(), illustration.firstDueDate(),
                    illustration.lastDueDate(), illustration.firstInstalment(), illustration.regularInstalment(),
                    illustration.finalInstalment(), illustration.totalInterest(), illustration.totalPayable(),
                    BnplOfferTerms.FORMAT, canonical, BnplOfferTerms.sha256(canonical), documentSha256);
        }

        @Override
        public byte[] termsSha256() {
            return termsSha256.clone();
        }

        @Override
        public byte[] documentSha256() {
            return documentSha256.clone();
        }

        /** The terms hash, hexadecimal - what the acceptance echoes (`P12-TSK-013`). */
        public String termsSha256Hex() {
            return HexFormat.of().formatHex(termsSha256);
        }

        /** The document hash, hexadecimal. */
        public String documentSha256Hex() {
            return HexFormat.of().formatHex(documentSha256);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof StoredTerms that && product == that.product && plan == that.plan
                    && amount.equals(that.amount) && nominalAnnualRate.compareTo(that.nominalAnnualRate) == 0
                    && scheduleEngine.equals(that.scheduleEngine) && illustrativeStart.equals(that.illustrativeStart)
                    && instalmentCount == that.instalmentCount && canonicalTerms.equals(that.canonicalTerms)
                    && java.util.Arrays.equals(termsSha256, that.termsSha256)
                    && java.util.Arrays.equals(documentSha256, that.documentSha256);
        }

        @Override
        public int hashCode() {
            return Objects.hash(product, plan, canonicalTerms);
        }

        @Override
        public String toString() {
            return "StoredTerms[" + product + ", " + plan + ", redacted]";
        }
    }

    /** One edge of the offer's history; {@code reason} a code, never free text. */
    record HistoryRow(
            UUID eventId,
            BnplOfferId offer,
            Optional<BnplOfferStatus> from,
            BnplOfferStatus to,
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
