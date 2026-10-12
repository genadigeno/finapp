package com.finapp.lending;

import com.finapp.sharedkernel.money.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One version of a loan agreement (`P11-TSK-012`, {@code lending V006}; ADR-0092 point 5, {@code INV-LND-05}): version 1
 * is its offer's terms, copied byte for byte at the acceptance - the canonical terms ({@link CanonicalOfferTerms}, in
 * the offer's own format) and their SHA-256 - with the figures a servicing step needs beside them. Never changed by any
 * role; a change of terms is version n+1 (`P11-TSK-027`).
 *
 * @param offer the offer it was accepted from - version 1's; an amendment's version names its amendment instead
 * @param decision credit's decision the acceptance consumed, by value
 * @param termsVersionId the terms version the offer pinned
 * @param amount the principal (a loan) or the limit (a line)
 * @param termMonths a loan's term
 * @param effectiveFrom the database's instant from which the version governs - the acceptance's, for version 1
 */
public record LoanAgreement(
        LoanAgreementId id,
        LoanId loan,
        int version,
        Optional<LoanOfferId> offer,
        UUID decision,
        LoanTermsVersionId termsVersionId,
        LoanProduct product,
        Money amount,
        Optional<Integer> termMonths,
        BigDecimal nominalAnnualRate,
        int repaymentDay,
        Optional<EngineVersion> scheduleEngine,
        EngineVersion accrualEngine,
        EngineVersion allocationEngine,
        Optional<EngineVersion> statementEngine,
        AgreementTemplate template,
        String canonicalTerms,
        byte[] termsSha256,
        Instant effectiveFrom) {

    public LoanAgreement {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(loan, "loan must not be null");
        Objects.requireNonNull(offer, "offer must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(termsVersionId, "termsVersionId must not be null");
        Objects.requireNonNull(product, "product must not be null");
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(termMonths, "termMonths must not be null");
        Objects.requireNonNull(nominalAnnualRate, "nominalAnnualRate must not be null");
        Objects.requireNonNull(scheduleEngine, "scheduleEngine must not be null");
        Objects.requireNonNull(accrualEngine, "accrualEngine must not be null");
        Objects.requireNonNull(allocationEngine, "allocationEngine must not be null");
        Objects.requireNonNull(statementEngine, "statementEngine must not be null");
        Objects.requireNonNull(template, "template must not be null");
        Objects.requireNonNull(canonicalTerms, "canonicalTerms must not be null");
        termsSha256 = Objects.requireNonNull(termsSha256, "termsSha256 must not be null").clone();
        Objects.requireNonNull(effectiveFrom, "effectiveFrom must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("an agreement version is numbered from 1");
        }
        if (termsSha256.length != 32) {
            throw new IllegalArgumentException("the terms hash is SHA-256");
        }
    }

    /**
     * Version 1 as the acceptance copies it from {@code offer}'s terms - the effective instant is the database's, read
     * back after the insert.
     */
    static LoanAgreement versionOne(LoanAgreementId id, LoanId loan, LoanOffer offer, Instant effectiveFrom) {
        LoanOffer.Terms terms = offer.terms();
        return new LoanAgreement(id, loan, 1, Optional.of(offer.id()), offer.decision(), offer.termsVersionId(),
                offer.product(), terms.amount(), terms.termMonths(), terms.nominalAnnualRate(), terms.repaymentDay(),
                terms.scheduleEngine(), terms.accrualEngine(), terms.allocationEngine(), terms.statementEngine(),
                terms.template(), terms.canonicalTerms(), terms.termsSha256(), effectiveFrom);
    }

    @Override
    public byte[] termsSha256() {
        return termsSha256.clone();
    }

    /** The terms hash in lowercase hexadecimal. */
    public String termsSha256Hex() {
        return HexFormat.of().formatHex(termsSha256);
    }

    /** Never a figure or the rate ({@code INV-AUD-02}). */
    @Override
    public String toString() {
        return "LoanAgreement[" + id.value() + ", v" + version + ", redacted]";
    }
}
