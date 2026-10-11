package com.finapp.lending;

import java.util.Objects;
import java.util.Optional;

/**
 * The agreement templates this build registers (`P11-TSK-005`; ADR-0092 sections 2 and 5, {@code PHASE_11_PLAN.md}
 * section 12.1) - the document a customer is shown and accepts, by id and version. A terms version names one, the offer
 * renders it (`P11-TSK-011`) and the agreement and its acceptance evidence record it (`P11-TSK-012`).
 *
 * <p><strong>Code-registered, never edited.</strong> A template's wording is versioned with the code: changed wording
 * is a new member (a new version of the same id) beside the old one, which stays for as long as an agreement names it.
 * Each member belongs to one product and is checked as the class loads - named for its id and version, one id and
 * version once - so a terms version can never name a document the platform cannot render. The neutral EUR reference
 * jurisdiction (A1) is the templates' own: a jurisdiction is a document, not a terms field.
 */
public enum AgreementTemplate {

    /** The amortising instalment loan's agreement, version 1 - the neutral EUR reference jurisdiction (A1). */
    PERSONAL_LOAN_AGREEMENT_V1(LoanProduct.PERSONAL_LOAN, "PERSONAL_LOAN_AGREEMENT", 1),

    /** The revolving credit line's agreement, version 1 - the neutral EUR reference jurisdiction (A1). */
    CREDIT_LINE_AGREEMENT_V1(LoanProduct.CREDIT_LINE, "CREDIT_LINE_AGREEMENT", 1);

    static {
        for (AgreementTemplate template : values()) {
            if (!template.name().equals(template.id + "_V" + template.version)) {
                throw new IllegalStateException(template.name() + ": a member is named for its id and version");
            }
        }
    }

    private final LoanProduct product;
    private final String id;
    private final int version;

    AgreementTemplate(LoanProduct product, String id, int version) {
        this.product = Objects.requireNonNull(product, "product");
        this.id = Objects.requireNonNull(id, "id");
        if (version < 1) {
            throw new IllegalArgumentException("a template version is numbered from 1");
        }
        this.version = version;
    }

    /** The product whose agreement this is. */
    public LoanProduct product() {
        return product;
    }

    /** The template's id - what a terms version and an agreement record. */
    public String id() {
        return id;
    }

    /** The template's version, from 1. */
    public int version() {
        return version;
    }

    /** The registered template {@code id} at {@code version}, or empty - for the caller to refuse, never to guess. */
    public static Optional<AgreementTemplate> of(String id, int version) {
        for (AgreementTemplate template : values()) {
            if (template.id.equals(id) && template.version == version) {
                return Optional.of(template);
            }
        }
        return Optional.empty();
    }
}
