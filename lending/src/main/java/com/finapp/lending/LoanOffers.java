package com.finapp.lending;

import com.finapp.creditmath.ScheduleOutcome;
import com.finapp.creditmath.SchedulePeriod;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A customer's loan offers (`P11-TSK-011`; {@code LENDING_LIFECYCLES.md} section 4.4, {@code PHASE_11_PLAN.md} section
 * 9): the owner's read and the decline, each on the caller's unit of work - the door's idempotency claim wraps the
 * decline, so a retried key replays and a refusal, which writes nothing, rolls the claim back with it. The acceptance is
 * {@link LoanAcceptances}' (`P11-TSK-012`).
 *
 * <p><strong>The read shows the bytes that were hashed.</strong> The offer document is re-rendered from the stored terms
 * and shown only if its SHA-256 is the one stored at birth; a loan's illustrative periods are re-derived by the pinned
 * schedule engine from the stored inputs and shown only if they reproduce the stored figures - so what the customer sees
 * is exactly what the offer recorded, and a code change that would show anything else fails loudly instead.
 *
 * <p><strong>The decline</strong> locks the offer (L2) under its owner and moves it {@code OFFERED -> DECLINED} only
 * while {@code expires_at > statement_timestamp()} - the complement of the expiry's condition, so a decline racing the
 * expiry sweep leaves exactly one. A declined offer releases nothing: the unconsumed decision lapses at its own
 * validity (section 5.2).
 */
@RequiredArgsConstructor
public final class LoanOffers {

    public static final String DECLINED_REASON = "CUSTOMER_DECLINED";

    @NonNull private final LoanOfferStore store;
    @NonNull private final LoanTermsStore terms;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * An offer as its owner reads it: the row and its terms, the document shown (its hash the stored one), and a loan's
     * illustrative periods (empty for a line).
     */
    public record Read(LoanOffer offer, String document, List<SchedulePeriod> illustrativePeriods) {

        public Read {
            Objects.requireNonNull(offer, "offer must not be null");
            Objects.requireNonNull(document, "document must not be null");
            illustrativePeriods = List.copyOf(illustrativePeriods);
        }

        @Override
        public String toString() {
            return "Read[" + offer + "]";
        }
    }

    // ------------------------------------------------------------------ read

    /** {@code party}'s offer {@code id}; another party's is the same empty answer as an absent one. */
    public Optional<Read> read(Connection unitOfWork, LoanOfferId id, UUID party) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return store.ownedBy(unitOfWork, id, party).map(offer -> new Read(offer, document(offer),
                periods(unitOfWork, offer)));
    }

    /** {@code party}'s offer for its application {@code application}, if one was born. */
    public Optional<LoanOfferId> offerFor(Connection unitOfWork, LoanApplicationId application, UUID party) {
        return store.offerFor(Objects.requireNonNull(unitOfWork, "unitOfWork"), application, party);
    }

    // ------------------------------------------------------------------ decline

    /**
     * Declines {@code party}'s offer {@code id} - the customer's act; the offer, {@code DECLINED}.
     *
     * @throws OfferNotFound when the party has no offer with this id (another party's included)
     * @throws OfferNotAcceptable when it is no longer {@code OFFERED}, or has expired on the database clock
     */
    public LoanOffer decline(Connection unitOfWork, LoanOfferId id, UUID party, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        LoanOffer offer = store.lockOwnedBy(unitOfWork, id, party).orElseThrow(OfferNotFound::new);
        if (offer.status() != LoanOfferStatus.OFFERED || !store.decline(unitOfWork, id)) {
            throw new OfferNotAcceptable(offer.status(), offer.product());
        }
        store.appendEvent(unitOfWork, new LoanOfferStore.HistoryRow(ids.next(), id,
                Optional.of(LoanOfferStatus.OFFERED), LoanOfferStatus.DECLINED, DECLINED_REASON, actor, correlation));
        LoanOrigination.publishClosed(unitOfWork, outbox, ids, clock, offer, LoanOfferStatus.DECLINED, DECLINED_REASON,
                correlation);
        return store.ownedBy(unitOfWork, id, party)
                .orElseThrow(() -> new IllegalStateException("a loan offer is readable once written"));
    }

    // ------------------------------------------------------------------ what is shown

    /** The document re-rendered from the stored terms - shown only if it is the one hashed at birth. */
    static String document(LoanOffer offer) {
        String document = OfferDocument.render(offer.id(), offer.product(), offer.terms());
        if (!MessageDigest.isEqual(CanonicalOfferTerms.sha256(document), offer.terms().documentSha256())) {
            throw new IllegalStateException("the offer document no longer renders the bytes hashed at its birth: a"
                    + " template's wording changed without a new template version");
        }
        return document;
    }

    /** A loan's illustrative periods, re-derived by the pinned engine - shown only if they reproduce the stored figures. */
    private List<SchedulePeriod> periods(Connection unitOfWork, LoanOffer offer) {
        Optional<LoanOffer.Illustration> stored = offer.terms().illustration();
        if (stored.isEmpty()) {
            return List.of();
        }
        LoanTerms pinned = terms.version(unitOfWork, offer.termsVersionId())
                .orElseThrow(() -> new IllegalStateException("an offer's pinned terms version is never deleted"))
                .terms();
        ScheduleOutcome outcome = LoanEngines.project(pinned, offer.terms().amount(),
                offer.terms().termMonths().orElseThrow(), offer.terms().repaymentDay(), stored.get().start());
        if (!(outcome instanceof ScheduleOutcome.Projected projected)
                || !LoanOffer.Illustration.of(projected.schedule()).equals(stored.get())) {
            throw new IllegalStateException("the pinned schedule engine no longer reproduces the offer's illustration");
        }
        return projected.schedule().periods();
    }

    // ------------------------------------------------------------------ the refusals, each writing nothing

    /** No offer of the caller's has this id - another party's included. */
    public static final class OfferNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        OfferNotFound() {
            super("no such loan offer");
        }
    }

    /**
     * The offer is past acting on - declined, expired (on the database clock) or accepted - or, at an acceptance, credit
     * would not consume its decision (`P11-TSK-012`: credit's reason, by name, in the message).
     */
    public static final class OfferNotAcceptable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final LoanProduct product;

        OfferNotAcceptable(LoanOfferStatus status, LoanProduct product) {
            super(status == LoanOfferStatus.OFFERED ? "the offer has expired"
                    : "a " + status + " offer can no longer be acted on");
            this.product = product;
        }

        private OfferNotAcceptable(String message, LoanProduct product) {
            super(message);
            this.product = product;
        }

        /** {@code offer} as it stands - moved, or past its expiry on the database clock. */
        static OfferNotAcceptable of(LoanOffer offer) {
            return new OfferNotAcceptable(offer.status(), offer.product());
        }

        /** Credit would not consume the offer's decision - its reason by name; never a figure. */
        static OfferNotAcceptable unconsumable(LoanProduct product, LoanCreditDecisions.Consumption.Refusal refusal) {
            return new OfferNotAcceptable("credit's decision cannot be taken up: " + refusal, product);
        }

        /** The offer's product - for the meter. */
        public LoanProduct product() {
            return product;
        }
    }
}
