package com.finapp.bnpl;

import com.finapp.platform.audit.AuditId;
import com.finapp.platform.audit.AuditOutcome;
import com.finapp.platform.audit.AuditRecord;
import com.finapp.platform.audit.AuditWriter;
import com.finapp.platform.outbox.OutboxWriter;
import com.finapp.platform.security.Actor;
import com.finapp.sharedkernel.correlation.CorrelationId;
import com.finapp.sharedkernel.id.IdGenerator;
import java.sql.Connection;
import java.time.Clock;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * A customer's BNPL offers (`P12-TSK-011`; {@code BNPL_LIFECYCLES.md} section 4.3, {@code PHASE_12_PLAN.md} section 9;
 * lending's {@code LoanOffers}): the owner's read - the plan, the illustrative schedule, the totals, the expiry and the
 * document - and the decline. Each on the caller's unit of work; the door's idempotency claim wraps the decline.
 *
 * <p><strong>The read re-derives before it shows.</strong> The illustration is re-projected from the PINNED terms
 * version and the stored amount and start, the canonical terms re-rendered and held to the stored SHA-256, and the
 * document re-rendered and held to its stored SHA-256 - so what the customer reads is, byte for byte, what was hashed at
 * the offer's birth; a disagreement is a defect raised loudly ({@link IllegalStateException}), never a silently
 * different document.
 *
 * <p><strong>The decline</strong> locks the offer (B2), acts only while {@code OFFERED}, and moves it {@code DECLINED}
 * only while {@code expires_at > clock_timestamp()} - the complement of the sweep's expiry, so the decline racing the
 * expiry at the boundary leaves exactly one of {@code DECLINED} and {@code EXPIRED}, whatever the instances' clocks
 * read. Nothing is released: credit's unconsumed approval lapses at its own validity, and the session stays
 * {@code OPEN} for another instrument - an {@code OFFERED} application holds its session for good, so no second BNPL
 * application opens on it (the owner judgement `P12-TSK-010` left to this task: one decision per purchase, B5).
 */
@RequiredArgsConstructor
public final class BnplOffers {

    public static final String DECLINED_REASON = "CUSTOMER_DECLINED";

    @NonNull private final BnplOfferStore store;
    @NonNull private final BnplTermsStore terms;
    @NonNull private final OutboxWriter<Connection> outbox;
    @NonNull private final AuditWriter<Connection> audit;
    @NonNull private final IdGenerator ids;
    @NonNull private final Clock clock;

    /**
     * An offer as its owner reads it: the row, its terms, the re-derived illustration, the pinned agreement template and
     * the document.
     */
    public record Read(BnplOffer offer, BnplOfferStore.StoredTerms terms, BnplIllustration illustration,
            BnplAgreementTemplate template, String document) {

        public Read {
            Objects.requireNonNull(offer, "offer must not be null");
            Objects.requireNonNull(template, "template must not be null");
            Objects.requireNonNull(terms, "terms must not be null");
            Objects.requireNonNull(illustration, "illustration must not be null");
            Objects.requireNonNull(document, "document must not be null");
        }
    }

    /** {@code party}'s offer {@code id}, re-derived and verified; another party's is the same empty answer. */
    public Optional<Read> read(Connection unitOfWork, BnplOfferId id, UUID party) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        return store.ownedBy(unitOfWork, Objects.requireNonNull(id, "id"), Objects.requireNonNull(party, "party"))
                .map(offer -> verified(unitOfWork, offer));
    }

    /**
     * Declines {@code party}'s offer {@code id} - the customer's act.
     *
     * @throws OfferNotFound when the party has no offer with this id (another party's included)
     * @throws OfferNotAcceptable when it is no longer {@code OFFERED}, or its expiry has come on the database clock
     */
    public BnplOffer decline(Connection unitOfWork, BnplOfferId id, UUID party, Actor actor, CorrelationId correlation) {
        Objects.requireNonNull(unitOfWork, "unitOfWork");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(party, "party");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(correlation, "correlation");
        // B2 first; every judgement below is on the row as this lock found it.
        BnplOffer offer = store.lockOwnedBy(unitOfWork, id, party).orElseThrow(OfferNotFound::new);
        if (offer.status() != BnplOfferStatus.OFFERED || !store.decline(unitOfWork, id)) {
            throw new OfferNotAcceptable(offer.product());
        }
        store.appendEvent(unitOfWork, new BnplOfferStore.HistoryRow(ids.next(), id, Optional.of(BnplOfferStatus.OFFERED),
                BnplOfferStatus.DECLINED, DECLINED_REASON, actor, correlation));
        BnplOrigination.publishClosed(unitOfWork, outbox, ids, clock, offer, BnplOfferStatus.DECLINED, DECLINED_REASON,
                correlation);
        audit.append(unitOfWork, new AuditRecord(AuditId.next(ids), actor, clock.instant(), BnplAuditAction.OFFER_DECLINED,
                BnplOrigination.OFFER_AGGREGATE_TYPE, id.value().toString(), Optional.empty(), AuditOutcome.SUCCEEDED,
                correlation, Optional.of("offer " + id.value() + " declined, application " + offer.application().value())));
        return store.ownedBy(unitOfWork, id, party)
                .orElseThrow(() -> new IllegalStateException("a BNPL offer is readable once written"));
    }

    // ------------------------------------------------------------------ the read's verification

    private Read verified(Connection unitOfWork, BnplOffer offer) {
        BnplOfferStore.StoredTerms stored = store.terms(unitOfWork, offer.id())
                .orElseThrow(() -> new IllegalStateException("an offer commits with its terms (bnpl V004)"));
        BnplTermsVersion version = terms.version(unitOfWork, offer.termsVersionId())
                .orElseThrow(() -> new IllegalStateException("an offer's pinned terms version exists"));
        BnplIllustration illustration = BnplIllustration.of(version.terms(), offer.plan(), stored.amount(),
                stored.illustrativeStart());
        String canonical = BnplOfferTerms.render(new BnplOfferTerms.Content(offer.id(), offer.application(),
                offer.decision(), offer.termsVersionId(), version.row().version(), version.terms(), offer.plan(),
                illustration));
        if (!canonical.equals(stored.canonicalTerms()) || !Arrays.equals(BnplOfferTerms.sha256(canonical),
                stored.termsSha256())) {
            throw new IllegalStateException("an offer's canonical terms no longer re-derive from its pinned version");
        }
        String document = BnplOfferDocument.render(offer.id(), version.terms(), offer.plan(), illustration,
                HexFormat.of().formatHex(stored.termsSha256()));
        if (!Arrays.equals(BnplOfferTerms.sha256(document), stored.documentSha256())) {
            throw new IllegalStateException("an offer's document no longer renders the bytes hashed at its birth");
        }
        return new Read(offer, stored, illustration, version.terms().agreementTemplate(), document);
    }

    // ------------------------------------------------------------------ the refusals, each writing nothing

    /** No offer of the caller's has this id - another party's included. */
    public static final class OfferNotFound extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        OfferNotFound() {
            super("no such BNPL offer");
        }
    }

    /** The offer is no longer open to the act - declined, expired on the database clock, or accepted. */
    public static final class OfferNotAcceptable extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final BnplProduct product;

        OfferNotAcceptable(BnplProduct product) {
            super("the BNPL offer is no longer open: it was declined, expired or accepted");
            this.product = product;
        }

        /** The offer's product - for the meter. */
        public BnplProduct product() {
            return product;
        }
    }
}
