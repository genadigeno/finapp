package com.finapp.bnpl;

import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A version of a BNPL financing agreement (`P12-TSK-013`, {@code bnpl V006}'s {@code bnpl_agreement};
 * {@code BNPL_LIFECYCLES.md} section 4.6, {@code INV-HIST-04}) - immutable from birth for every role. Version 1 is the
 * accepted offer's canonical terms copied byte for byte (the database holds the copy and the hash to the bytes), with
 * what replay re-derives them by (`P12-TSK-025`): the pinned terms version, the schedule engine (pay-in-4
 * {@code BNPL_SCHEDULE_ENGINE_V1}, pay-in-N {@code SCHEDULE_ENGINE_V1}), pay-in-N's accrual engine, the allocation engine,
 * the refund allocation engine it will be refunded by ({@code REFUND_ALLOCATION_ENGINE_V1}, `P12-TSK-009`) and the
 * merchant's BNPL fee schedule version that priced the purchase. A later version is a refund's (`P12-TSK-021`).
 *
 * @param id the agreement's identity - the same for every version, credit's consumer reference
 *     ({@code ConsumerRef(BNPL_AGREEMENT, id)})
 * @param createdAt the database's instant of the version's birth
 */
public record BnplAgreement(
        BnplAgreementId id,
        int version,
        BnplObligationId obligation,
        BnplOfferId offer,
        UUID decision,
        BnplTermsVersionId termsVersionId,
        BnplProduct product,
        BnplPlan plan,
        String scheduleEngine,
        Optional<String> accrualEngine,
        String allocationEngine,
        String refundAllocationEngine,
        UUID feeScheduleVersion,
        BnplAgreementTemplate template,
        int canonicalFormat,
        String canonicalTerms,
        byte[] termsSha256,
        byte[] documentSha256,
        Instant createdAt) {

    public BnplAgreement {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(obligation, "obligation must not be null");
        Objects.requireNonNull(offer, "offer must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(termsVersionId, "termsVersionId must not be null");
        Objects.requireNonNull(product, "product must not be null");
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(scheduleEngine, "scheduleEngine must not be null");
        Objects.requireNonNull(accrualEngine, "accrualEngine must not be null");
        Objects.requireNonNull(allocationEngine, "allocationEngine must not be null");
        Objects.requireNonNull(refundAllocationEngine, "refundAllocationEngine must not be null");
        Objects.requireNonNull(feeScheduleVersion, "feeScheduleVersion must not be null");
        Objects.requireNonNull(template, "template must not be null");
        Objects.requireNonNull(canonicalTerms, "canonicalTerms must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("an agreement version is numbered from 1");
        }
        if (plan.product() != product || template.product() != product) {
            throw new IllegalArgumentException("an agreement's plan and template are its product's");
        }
        termsSha256 = Objects.requireNonNull(termsSha256, "termsSha256 must not be null").clone();
        documentSha256 = Objects.requireNonNull(documentSha256, "documentSha256 must not be null").clone();
    }

    /**
     * Version 1 of the agreement {@code id} for {@code obligation}: the accepted {@code offer}'s stored terms copied, the
     * engines and template of its pinned {@code terms}, and the fee schedule version {@code feeScheduleVersion} the
     * purchase was priced by.
     */
    public static BnplAgreement versionOne(BnplAgreementId id, BnplObligationId obligation, BnplOffer offer,
            BnplOfferStore.StoredTerms stored, BnplTerms terms, UUID feeScheduleVersion, Instant createdAt) {
        Objects.requireNonNull(offer, "offer");
        Objects.requireNonNull(stored, "stored");
        Objects.requireNonNull(terms, "terms");
        if (terms.product() != offer.product()) {
            throw new IllegalArgumentException("an agreement pins its offer's own product's terms");
        }
        return new BnplAgreement(id, 1, obligation, offer.id(), offer.decision(), offer.termsVersionId(),
                offer.product(), offer.plan(), stored.scheduleEngine(),
                terms.payInN().map(own -> own.accrualEngine().name()), terms.allocationEngine().name(),
                terms.refundAllocationEngine().name(), feeScheduleVersion, terms.agreementTemplate(),
                stored.canonicalFormat(), stored.canonicalTerms(), stored.termsSha256(), stored.documentSha256(),
                createdAt);
    }

    /** The same version, stamped with the database's instant. */
    public BnplAgreement stampedAt(Instant at) {
        return new BnplAgreement(id, version, obligation, offer, decision, termsVersionId, product, plan, scheduleEngine,
                accrualEngine, allocationEngine, refundAllocationEngine, feeScheduleVersion, template, canonicalFormat,
                canonicalTerms, termsSha256, documentSha256, at);
    }

    @Override
    public byte[] termsSha256() {
        return termsSha256.clone();
    }

    @Override
    public byte[] documentSha256() {
        return documentSha256.clone();
    }

    /** The terms hash, hexadecimal - what the customer echoed. */
    public String termsSha256Hex() {
        return HexFormat.of().formatHex(termsSha256);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof BnplAgreement that && id.equals(that.id) && version == that.version
                && canonicalTerms.equals(that.canonicalTerms) && java.util.Arrays.equals(termsSha256, that.termsSha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, version);
    }

    /** The identity, version and product only. */
    @Override
    public String toString() {
        return "BnplAgreement[" + id.value() + " v" + version + ", " + product + "]";
    }
}
